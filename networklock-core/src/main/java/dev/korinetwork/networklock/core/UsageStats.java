package dev.korinetwork.networklock.core;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Anonymous usage counter: once a day it tells the NetworkLock stats API that one more server
 * runs the plugin. It sends an anonymous ID (a one-way hash), the version, the platform and
 * whether this is a proxy or a game server. Nothing else: no IPs, names, server names or keys.
 * Switched off with {@code metrics.enabled: false}.
 */
public final class UsageStats {

    // the official stats endpoint; until the API is live, pings fail silently and nothing else is affected
    static final URI ENDPOINT = URI.create("https://api.kori.my/networklock/v1/ping");

    private final String version;
    private final String platform;
    private final String side;
    private final Supplier<String> id;
    private final BooleanSupplier enabled;
    private ScheduledExecutorService executor;

    /**
     * @param platform e.g. "velocity", "bungeecord", "paper", "sponge"
     * @param side     "proxy" or "server"
     * @param id       the anonymous ID, or null while no config is loaded (no ping then)
     */
    public UsageStats(String version, String platform, String side, Supplier<String> id, BooleanSupplier enabled) {
        this.version = version;
        this.platform = platform;
        this.side = side;
        this.id = id;
        this.enabled = enabled;
    }

    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "NetworkLock-stats");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(this::ping, 5, TimeUnit.DAYS.toMinutes(1), TimeUnit.MINUTES);
    }

    public void stop() {
        if (executor != null) executor.shutdownNow();
    }

    void ping() {
        if (!enabled.getAsBoolean()) return;
        String serverId = id.get();
        if (serverId == null) return;
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest request = HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "NetworkLock/" + version)
                    .POST(HttpRequest.BodyPublishers.ofString(payload(serverId))).build();
            client.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            // offline, or the API is not up yet: try again tomorrow
        } catch (Throwable t) {
            // never let stats affect the server
        }
    }

    String payload(String serverId) {
        return "{\"id\":\"" + json(serverId) + "\",\"version\":\"" + json(version) + "\",\"platform\":\"" + json(platform)
                + "\",\"side\":\"" + json(side) + "\"}";
    }

    private static String json(String s) {
        return s.replaceAll("[^A-Za-z0-9._+-]", "");
    }

    /** A stable anonymous ID: the first 16 bytes of SHA-256 over a fixed label and the given parts. */
    public static String anonymousId(String... parts) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update("NetworkLock/usage-id/v1".getBytes(StandardCharsets.US_ASCII));
            for (String p : parts) {
                sha.update((byte) 0);
                sha.update(p.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(sha.digest(), 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
