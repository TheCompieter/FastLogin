package com.github.games647.fastlogin.core.mojang;

import com.github.games647.fastlogin.core.BalancedSSLFactory;
import com.github.games647.fastlogin.core.CommonUtil;
import com.github.games647.fastlogin.core.shared.LoginSession;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.net.HostAndPort;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Proxy.Type;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

public class MojangApiConnector {

    //http connection, read timeout and user agent for a connection to mojang api servers
    private static final int TIMEOUT = 3 * 1_000;
    private static final String USER_AGENT = "Premium-Checker";

    //only premium (paid account) users have a uuid from here
    private static final String UUID_LINK = "https://api.mojang.com/users/profiles/minecraft/";

    private static final int RATE_LIMIT_CODE = 429;

    //this includes a-zA-Z1-9_
    //compile the pattern only on plugin enable -> and this have to be thread-safe
    private final Pattern validNameMatcher = Pattern.compile("^\\w{2,16}$");

    private final Iterator<Proxy> proxies;
    private final Map<Object, Object> requests = CommonUtil.buildCache(10, -1);
    private final SSLSocketFactory sslFactory;
    private final int rateLimit;
    private long lastRateLimit;

    protected final Gson gson = new GsonBuilder().registerTypeAdapter(UUID.class, new UUIDTypeAdapter()).create();
    protected final Logger logger;

    public MojangApiConnector(Logger logger, Collection<String> localAddresses, int rateLimit
            , List<HostAndPort> proxies) {
        this.logger = logger;
        this.rateLimit = Math.max(rateLimit, 600);
        this.sslFactory = buildAddresses(logger, localAddresses);

        List<Proxy> proxyBuilder = Lists.newArrayList();
        for (HostAndPort proxy : proxies) {
            proxyBuilder.add(new Proxy(Type.HTTP, new InetSocketAddress(proxy.getHostText(), proxy.getPort())));
        }

        this.proxies = Iterables.cycle(proxyBuilder).iterator();
    }

    /**
     * @return null on non-premium
     */
    public UUID getPremiumUUID(String playerName) {
        if (!validNameMatcher.matcher(playerName).matches()) {
            //check if it's a valid player name
            return null;
        }

        try {
            HttpsURLConnection connection;
            if (requests.size() >= rateLimit || System.currentTimeMillis() - lastRateLimit < 1_000 * 60 * 10) {
                synchronized (proxies) {
                    if (proxies.hasNext()) {
                        connection = getConnection(UUID_LINK + playerName, proxies.next());
                    } else {
                        return null;
                    }
                }
            } else {
                requests.put(new Object(), new Object());
                connection = getConnection(UUID_LINK + playerName);
            }

            if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                    String line = reader.readLine();
                    return getUUIDFromJson(line);
                }
            } else if (connection.getResponseCode() == RATE_LIMIT_CODE) {
                logger.info("RATE_LIMIT REACHED");
                lastRateLimit = System.currentTimeMillis();
                if (!connection.usingProxy()) {
                    return getPremiumUUID(playerName);
                }
            }
            //204 - no content for not found
        } catch (Exception ex) {
            logger.log(Level.SEVERE, "Failed to check if player has a paid account", ex);
        }

        return null;
    }

    public boolean hasJoinedServer(LoginSession session, String serverId, InetSocketAddress ip) {
        //only available in Spigot and not in BungeeCord
        return false;
    }

    private UUID getUUIDFromJson(String json) {
        boolean isArray = json.startsWith("[");

        GameProfile mojangPlayer;
        if (isArray) {
            mojangPlayer = gson.fromJson(json, GameProfile[].class)[0];
        } else {
            mojangPlayer = gson.fromJson(json, GameProfile.class);
        }

        return mojangPlayer.getId();
    }

    protected HttpsURLConnection getConnection(String url, Proxy proxy) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(url).openConnection(proxy);
        connection.setConnectTimeout(TIMEOUT);
        connection.setReadTimeout(2 * TIMEOUT);
        //the new Mojang API just uses json as response
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("User-Agent", USER_AGENT);

        connection.setSSLSocketFactory(sslFactory);

        //this connection doesn't need to be closed. So can make use of keep alive in java
        return connection;
    }

    protected HttpsURLConnection getConnection(String url) throws IOException {
        return getConnection(url, Proxy.NO_PROXY);
    }

    private SSLSocketFactory buildAddresses(Logger logger, Collection<String> localAddresses) {
        if (localAddresses.isEmpty()) {
            return HttpsURLConnection.getDefaultSSLSocketFactory();
        } else {
            Set<InetAddress> addresses = Sets.newHashSet();
            for (String localAddress : localAddresses) {
                try {
                    InetAddress address = InetAddress.getByName(localAddress);
                    if (!address.isAnyLocalAddress()) {
                        logger.log(Level.WARNING, "Submitted IP-Address is not local {0}", address);
                        continue;
                    }

                    addresses.add(address);
                } catch (UnknownHostException ex) {
                    logger.log(Level.SEVERE, "IP-Address is unknown to us", ex);
                }
            }

            return new BalancedSSLFactory(HttpsURLConnection.getDefaultSSLSocketFactory(), addresses);
        }
    }
}
