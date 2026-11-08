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

    public static final class Settings {
        private final boolean enabled;
        private final int maxAttempts;
        private final int windowSeconds;
        private final boolean tempBlocks;
        private final int violationsBeforeBlock;
        private final int blockSeconds;
        private final int ipv6Prefix;
        private final List<Addresses.Cidr> exempt;
        private final int maxTracked;
        private final int floodThreshold;

        public Settings(boolean enabled, int maxAttempts, int windowSeconds, boolean tempBlocks,
                        int violationsBeforeBlock, int blockSeconds, int ipv6Prefix,
                        List<Addresses.Cidr> exempt, int maxTracked, int floodThreshold) {
            this.enabled = enabled;
            this.maxAttempts = maxAttempts;
            this.windowSeconds = windowSeconds;
            this.tempBlocks = tempBlocks;
            this.violationsBeforeBlock = violationsBeforeBlock;
            this.blockSeconds = blockSeconds;
            this.ipv6Prefix = ipv6Prefix;
            this.exempt = exempt;
            this.maxTracked = maxTracked;
            this.floodThreshold = floodThreshold;
        }

        public boolean enabled() {
            return enabled;
        }

        public int maxAttempts() {
            return maxAttempts;
        }

        public int windowSeconds() {
            return windowSeconds;
        }

        public boolean tempBlocks() {
            return tempBlocks;
        }

        public int violationsBeforeBlock() {
            return violationsBeforeBlock;
        }

        public int blockSeconds() {
            return blockSeconds;
        }

        public int ipv6Prefix() {
            return ipv6Prefix;
        }

        public List<Addresses.Cidr> exempt() {
            return exempt;
        }

        public int maxTracked() {
            return maxTracked;
        }

        public int floodThreshold() {
            return floodThreshold;
        }
    }

    private static final class Key {
        private final long hi;
        private final long lo;

        Key(long hi, long lo) {
            this.hi = hi;
            this.lo = lo;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key && ((Key) o).hi == hi && ((Key) o).lo == lo;
        }

        @Override
        public int hashCode() {
            return 31 * Long.hashCode(hi) + Long.hashCode(lo);
        }
    }

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
