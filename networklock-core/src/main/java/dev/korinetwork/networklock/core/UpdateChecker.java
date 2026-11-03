package dev.korinetwork.networklock.core;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Looks up the newest release on GitHub at startup and every 12 hours, on its own daemon
 * thread (no server scheduler, so it is safe on Folia). Skips the lookup while
 * {@code updates.check} is off.
 */
public final class UpdateChecker {

    public enum Status { LATEST, OUTDATED, UNKNOWN }

    public static final String DOWNLOAD = "https://github.com/darkzmaj/NetworkLock/releases/latest";
    private static final URI API = URI.create("https://api.github.com/repos/darkzmaj/NetworkLock/releases/latest");
    private static final Pattern TAG = Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"");

    private final String current;
    private final Log log;
    private final BooleanSupplier enabled;
    private volatile String latest;
    private ScheduledExecutorService executor;

    public UpdateChecker(String current, Log log, BooleanSupplier enabled) {
        this.current = current;
        this.log = log;
        this.enabled = enabled;
    }

    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "NetworkLock-update-check");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(this::check, 5, TimeUnit.HOURS.toSeconds(12), TimeUnit.SECONDS);
    }

    public void stop() {
        if (executor != null) executor.shutdownNow();
    }

    void check() {
        if (!enabled.getAsBoolean()) return;
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest request = HttpRequest.newBuilder(API).timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "NetworkLock/" + current).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return;
            String found = parseTag(response.body());
            if (found == null) return;
            latest = found;
            if (isNewer(found, current)) {
                log.warn("A new NetworkLock version is available: " + found + " (you have " + current + "). Download: " + DOWNLOAD);
            }
        } catch (Exception e) {
            // offline or rate-limited: the status just stays unknown
        } catch (Throwable t) {
            // never let the update check affect the server
        }
    }

    public Status status() {
        String l = latest;
        if (!enabled.getAsBoolean() || l == null) return Status.UNKNOWN;
        return isNewer(l, current) ? Status.OUTDATED : Status.LATEST;
    }

    public String current() {
        return current;
    }

    /** The newest released version, or the current one if not known yet. */
    public String latest() {
        String l = latest;
        return l == null ? current : l;
    }

    static String parseTag(String json) {
        Matcher m = TAG.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** True if {@code candidate} is a later version than {@code current}. A release beats its own pre-releases. */
    static boolean isNewer(String candidate, String current) {
        int[] a = numbers(candidate);
        int[] b = numbers(current);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return !candidate.contains("-") && current.contains("-");
    }

    private static int[] numbers(String version) {
        String core = version.split("-", 2)[0];
        String[] parts = core.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].replaceAll("\\D", ""));
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }
}
