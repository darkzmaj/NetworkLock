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

    private record Key(long hi, long lo) {}

    private final ConcurrentHashMap<Key, Long> seen = new ConcurrentHashMap<>();
    private final AtomicInteger inserts = new AtomicInteger();
    private final int maxEntries;

    public NonceCache(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    public Result use(byte[] nonce, long expiresAtMillis, long nowMillis) {
        if ((inserts.incrementAndGet() & 1023) == 0 || seen.size() >= maxEntries) purge(nowMillis);
        if (seen.size() >= maxEntries) return Result.FULL;
        ByteBuffer b = ByteBuffer.wrap(nonce);
        return seen.putIfAbsent(new Key(b.getLong(), b.getLong()), expiresAtMillis) == null ? Result.FRESH : Result.REPLAY;
    }

    void purge(long nowMillis) {
        seen.values().removeIf(expiry -> expiry <= nowMillis);
    }

    public int size() {
        return seen.size();
    }

    public int capacity() {
        return maxEntries;
    }
}
