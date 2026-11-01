package dev.korinetwork.networklock.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Proxy key encoding, generation and display-safe fingerprints. */
public final class Keys {

    public static final int MIN_BYTES = 32;
    public static final int MAX_BYTES = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    private Keys() {}

    /** 32 random bytes, base64url without padding (43 characters). */
    public static String generate() {
        byte[] key = new byte[MIN_BYTES];
        RANDOM.nextBytes(key);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key);
    }

    /**
     * Decodes a base64 or base64url key and enforces the size bounds.
     *
     * @throws IllegalArgumentException with a message that never contains the key material
     */
    public static byte[] decode(String text) {
        String s = text == null ? "" : text.trim();
        if (s.isEmpty()) throw new IllegalArgumentException("key is empty");
        byte[] key;
        try {
            key = Base64.getUrlDecoder().decode(s.replace('+', '-').replace('/', '_').replace("=", ""));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("key is not valid base64 (generate one with a fresh proxy install)");
        }
        if (key.length < MIN_BYTES) {
            throw new IllegalArgumentException("key is too short (" + key.length + " bytes, need " + MIN_BYTES + ")");
        }
        if (key.length > MAX_BYTES) throw new IllegalArgumentException("key is too long");
        return key;
    }

    /** A short, non-reversible identifier for comparing keys between servers without revealing them. */
    public static String fingerprint(byte[] key) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update("NetworkLock/key-fingerprint/v1".getBytes(StandardCharsets.US_ASCII));
            byte[] digest = sha.digest(key);
            String hex = HexFormat.of().formatHex(digest, 0, 8);
            return hex.substring(0, 4) + ":" + hex.substring(4, 8) + ":" + hex.substring(8, 12) + ":" + hex.substring(12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
