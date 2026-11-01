package dev.korinetwork.networklock.core;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-address token-bucket limiter for new proxy logins, with optional temporary blocks.
 * IPv6 clients are grouped by prefix (default /64) because a single host usually controls
 * a whole /64. This is application-level throttling, not volumetric DDoS protection.
 */
public final class ConnectionLimiter {

    public enum Decision { ALLOWED, LIMITED, BLOCKED }

    public record Settings(boolean enabled, int maxAttempts, int windowSeconds, boolean tempBlocks,
                           int violationsBeforeBlock, int blockSeconds, int ipv6Prefix,
                           List<Addresses.Cidr> exempt, int maxTracked, int floodThreshold) {}

    private record Key(long hi, long lo) {}

    private static final class Bucket {
        double tokens;
        long lastRefill;
        int violations;
        long blockedUntil;
        long lastSeen;
    }

    private final Settings s;
    private final double refillPerMilli;
    private final ConcurrentHashMap<Key, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicInteger sinceSweep = new AtomicInteger();
    private final AtomicLong floodSecond = new AtomicLong();
    private final AtomicInteger floodCount = new AtomicInteger();
    private volatile boolean saturated;

    public ConnectionLimiter(Settings settings) {
        this.s = settings;
        this.refillPerMilli = settings.maxAttempts() / (settings.windowSeconds() * 1000.0);
    }

    public Decision check(InetAddress address, long now) {
        if (!s.enabled() || address == null) return Decision.ALLOWED;
        for (Addresses.Cidr c : s.exempt()) {
            if (c.contains(address)) return Decision.ALLOWED;
        }
        Key key = key(address);
        Bucket b = buckets.get(key);
        if (b == null) {
            if ((sinceSweep.incrementAndGet() & 255) == 0 || buckets.size() >= s.maxTracked()) sweep(now);
            if (buckets.size() >= s.maxTracked()) {
                // shortcut: untracked addresses pass when the table is full, which keeps memory bounded
                // at the cost of throttling during a very wide flood; upgrade to an LRU if that matters.
                saturated = true;
                return Decision.ALLOWED;
            }
            b = buckets.computeIfAbsent(key, k -> {
                Bucket n = new Bucket();
                n.tokens = s.maxAttempts();
                n.lastRefill = now;
                return n;
            });
        }
        synchronized (b) {
            b.lastSeen = now;
            if (b.blockedUntil > now) return Decision.BLOCKED;
            b.tokens = Math.min(s.maxAttempts(), b.tokens + (now - b.lastRefill) * refillPerMilli);
            b.lastRefill = now;
            if (b.tokens >= s.maxAttempts()) b.violations = 0;
            if (b.tokens >= 1) {
                b.tokens -= 1;
                return Decision.ALLOWED;
            }
            if (s.tempBlocks() && ++b.violations >= s.violationsBeforeBlock()) {
                b.violations = 0;
                b.blockedUntil = now + s.blockSeconds() * 1000L;
                return Decision.BLOCKED;
            }
            return Decision.LIMITED;
        }
    }

    /** Counts every login attempt network-wide; returns true once per second while above the flood threshold. */
    public boolean recordAttemptAndCheckFlood(long now) {
        long second = now / 1000;
        if (floodSecond.getAndSet(second) != second) floodCount.set(0);
        return floodCount.incrementAndGet() == s.floodThreshold();
    }

    void sweep(long now) {
        long idle = s.windowSeconds() * 1000L; // a bucket idle for a full window has refilled; blocks are kept separately
        buckets.values().removeIf(b -> {
            synchronized (b) {
                return b.blockedUntil <= now && now - b.lastSeen > idle;
            }
        });
        saturated = buckets.size() >= s.maxTracked();
    }

    Key key(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (Addresses.isV4(address)) {
            return new Key(-1L, ByteBuffer.wrap(bytes).getInt() & 0xFFFFFFFFL);
        }
        ByteBuffer b = ByteBuffer.wrap(Addresses.mask(bytes, s.ipv6Prefix()));
        return new Key(b.getLong(), b.getLong());
    }

    public int tracked() {
        return buckets.size();
    }

    public int blocked(long now) {
        int n = 0;
        for (Bucket b : buckets.values()) {
            synchronized (b) {
                if (b.blockedUntil > now) n++;
            }
        }
        return n;
    }

    public boolean saturated() {
        return saturated;
    }
}
