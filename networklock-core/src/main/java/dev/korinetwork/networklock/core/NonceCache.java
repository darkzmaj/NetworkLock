package dev.korinetwork.networklock.core;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Remembers token nonces until their validity window closes, so each token is accepted at
 * most once per backend. Only tokens with a valid MAC reach this cache, so its size is
 * bounded by legitimate login volume times the validity window; {@code maxEntries} is a
 * hard ceiling beyond which logins fail closed.
 */
public final class NonceCache {

    public enum Result { FRESH, REPLAY, FULL }

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

    private final ConcurrentHashMap<Key, Long> seen = new ConcurrentHashMap<>();
    private final AtomicInteger inserts = new AtomicInteger();
    private final int maxEntries;

    public NonceCache(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    /**
     * @param issuedAtMillis  when the token was issued
     * @param retentionMillis how long a token stays acceptable after issue under the CURRENT settings
     *                        (lifetime + clock skew). Entries are kept by issue time and purged with the
     *                        current retention, so raising the token lifetime by reload never lets an
     *                        already-used token be accepted again.
     */
    public Result use(byte[] nonce, long issuedAtMillis, long nowMillis, long retentionMillis) {
        ByteBuffer b = ByteBuffer.wrap(nonce);
        Key key = new Key(b.getLong(), b.getLong());
        if ((inserts.incrementAndGet() & 1023) == 0 || seen.size() >= maxEntries) purge(nowMillis, retentionMillis);
        if (seen.containsKey(key)) return Result.REPLAY;
        if (seen.size() >= maxEntries) return Result.FULL;
        // putIfAbsent is the atomic decision: of two simultaneous uses of one token, only one gets FRESH
        return seen.putIfAbsent(key, issuedAtMillis) == null ? Result.FRESH : Result.REPLAY;
    }

    void purge(long nowMillis, long retentionMillis) {
        seen.values().removeIf(issued -> issued + retentionMillis <= nowMillis);
    }

    public int size() {
        return seen.size();
    }

    public int capacity() {
        return maxEntries;
    }
}
