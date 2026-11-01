package dev.korinetwork.networklock.core;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Security event logging that stays cheap under attack: at most {@value #BURST} lines per
 * {@value #WINDOW_MS} ms, then a single "suppressed" summary. Messages are built lazily so
 * dropped events cost nothing.
 */
public final class SecurityLog {

    static final int BURST = 20;
    static final long WINDOW_MS = 10_000;

    private final Log log;
    private final AtomicLong windowStart = new AtomicLong();
    private final AtomicInteger inWindow = new AtomicInteger();
    private final AtomicInteger suppressed = new AtomicInteger();

    public SecurityLog(Log log) {
        this.log = log;
    }

    public void event(Supplier<String> message, long now) {
        long start = windowStart.get();
        if (now - start >= WINDOW_MS && windowStart.compareAndSet(start, now)) {
            inWindow.set(0);
            int dropped = suppressed.getAndSet(0);
            if (dropped > 0) log.warn("Suppressed " + dropped + " further security events in the last 10s");
        }
        if (inWindow.incrementAndGet() <= BURST) {
            log.warn(message.get());
        } else {
            suppressed.incrementAndGet();
        }
    }

    /** IPv4 keeps the first two octets, IPv6 the first three groups. */
    public static String address(String normalized, boolean redact) {
        if (!redact || normalized.isEmpty()) return normalized;
        if (normalized.indexOf(':') < 0) {
            String[] p = normalized.split("\\.");
            return p.length == 4 ? p[0] + "." + p[1] + ".x.x" : "redacted";
        }
        String[] p = normalized.split(":");
        return p.length >= 3 ? p[0] + ":" + p[1] + ":" + p[2] + "::x" : "redacted";
    }

    public static String address(InetAddress address, boolean redact) {
        return address(Addresses.normalize(address), redact);
    }
}
