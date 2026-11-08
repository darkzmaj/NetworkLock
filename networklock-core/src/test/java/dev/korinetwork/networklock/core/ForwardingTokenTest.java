package dev.korinetwork.networklock.core;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ForwardingTokenTest {

    static final byte[] KEY = Keys.decode(Keys.generate());
    static final PlayerIdentity ID = new PlayerIdentity(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), "Notch",
            "203.0.113.7", List.of(new PlayerIdentity.Property("textures", "abc", "sig")));

    static boolean valid(String token, PlayerIdentity id, byte[]... keys) {
        return ForwardingToken.macMatches(ForwardingToken.parse(token), List.of(keys), id);
    }

    @Test
    void roundTrip() {
        String token = ForwardingToken.mint("proxy-1", KEY, "survival", ID, 1000L);
        ForwardingToken.Parsed p = ForwardingToken.parse(token);
        assertEquals("proxy-1", p.proxyId());
        assertEquals("survival", p.target());
        assertEquals(1000L, p.issuedAtMillis());
        assertTrue(valid(token, ID, KEY));
        assertTrue(token.length() < ForwardingToken.MAX_ENCODED_LENGTH);
    }

    @Test
    void nonceIsFreshEachTime() {
        assertNotEquals(ForwardingToken.mint("p", KEY, "s", ID, 1), ForwardingToken.mint("p", KEY, "s", ID, 1));
    }

    @Test
    void identityTamperingIsDetected() {
        String token = ForwardingToken.mint("proxy-1", KEY, "survival", ID, 1000L);
        assertFalse(valid(token, new PlayerIdentity(UUID.randomUUID(), ID.name(), ID.address(), ID.properties()), KEY), "uuid");
        assertFalse(valid(token, new PlayerIdentity(ID.uuid(), "notch", ID.address(), ID.properties()), KEY), "name");
        assertFalse(valid(token, new PlayerIdentity(ID.uuid(), ID.name(), "203.0.113.8", ID.properties()), KEY), "address");
        assertFalse(valid(token, new PlayerIdentity(ID.uuid(), ID.name(), ID.address(),
                List.of(new PlayerIdentity.Property("textures", "evil", "sig"))), KEY), "property value");
        assertFalse(valid(token, new PlayerIdentity(ID.uuid(), ID.name(), ID.address(), List.of()), KEY), "property removed");
        assertFalse(valid(token, ID, Keys.decode(Keys.generate())), "wrong key");
    }

    @Test
    void everyHeaderByteIsAuthenticated() {
        String token = ForwardingToken.mint("proxy-1", KEY, "survival", ID, 1000L);
        byte[] raw = Base64.getUrlDecoder().decode(token);
        int headerLength = raw.length - ForwardingToken.MAC_BYTES;
        for (int i = 1; i < raw.length; i++) { // byte 0 is the version, checked by parse
            byte[] copy = raw.clone();
            copy[i] ^= 0x01;
            String tampered = Base64.getUrlEncoder().withoutPadding().encodeToString(copy);
            boolean accepted;
            try {
                accepted = valid(tampered, ID, KEY);
            } catch (RuntimeException structurallyInvalid) {
                accepted = false;
            }
            assertFalse(accepted, "flipped bit at byte " + i + (i < headerLength ? " (header)" : " (mac)"));
        }
    }

    @Test
    void propertyOrderAndTokenPropertyDoNotMatter() {
        var a = new PlayerIdentity.Property("a", "1", "");
        var b = new PlayerIdentity.Property("b", "2", null);
        var tokenProp = new PlayerIdentity.Property(ForwardingToken.PROPERTY, "x", "");
        String token = ForwardingToken.mint("p", KEY, "s", new PlayerIdentity(ID.uuid(), "n", "::1", List.of(a, b)), 5);
        assertTrue(valid(token, new PlayerIdentity(ID.uuid(), "n", "::1", List.of(b, tokenProp, a)), KEY));
    }

    @Test
    void malformedInputIsRejected() {
        assertThrows(RuntimeException.class, () -> ForwardingToken.parse(""));
        assertThrows(RuntimeException.class, () -> ForwardingToken.parse("not base64 !!"));
        assertThrows(RuntimeException.class, () -> ForwardingToken.parse("A".repeat(ForwardingToken.MAX_ENCODED_LENGTH + 1)));
        assertThrows(RuntimeException.class, () -> ForwardingToken.parse("AAAA"));
        String token = ForwardingToken.mint("proxy-1", KEY, "survival", ID, 1000L);
        assertThrows(RuntimeException.class, () -> ForwardingToken.parse(token.substring(0, token.length() - 4)), "truncated");
        assertThrows(RuntimeException.class, () -> ForwardingToken.parse(token + "AAAA"), "trailing data");
        byte[] raw = Base64.getUrlDecoder().decode(token);
        raw[0] = 2;
        assertThrows(RuntimeException.class, () -> ForwardingToken.parse(Base64.getUrlEncoder().withoutPadding().encodeToString(raw)),
                "future version: no downgrade or upgrade fallback");
    }

    @Test
    void mintValidatesIds() {
        assertThrows(IllegalArgumentException.class, () -> ForwardingToken.mint("bad id", KEY, "s", ID, 1));
        assertThrows(IllegalArgumentException.class, () -> ForwardingToken.mint("p", KEY, "", ID, 1));
        assertThrows(IllegalArgumentException.class, () -> ForwardingToken.mint("p", KEY, "x".repeat(65), ID, 1));
    }

    /**
     * The 1.0.1 per-thread Mac reuse must produce exactly the MAC of the 1.0.0 algorithm, or tokens
     * from a 1.0.0 proxy would be refused by a 1.0.1 server (and the other way round).
     */
    @Test
    void optimisedMacIsByteIdenticalToTheOriginalAlgorithm() throws Exception {
        byte[] key2 = Keys.decode(Keys.generate());
        java.util.Random r = new java.util.Random(7);
        for (int i = 0; i < 300; i++) {
            List<PlayerIdentity.Property> props = new java.util.ArrayList<>();
            for (int p = 0; p < i % 4; p++) {
                props.add(new PlayerIdentity.Property("n" + r.nextInt(5), "v" + r.nextInt(1000), r.nextBoolean() ? "" : "s" + r.nextInt()));
            }
            PlayerIdentity id = new PlayerIdentity(new UUID(r.nextLong(), r.nextLong()), "Pé" + i, "198.51.100." + (i % 250), props);
            byte[] key = i % 2 == 0 ? KEY : key2; // alternate keys: exercises the re-keying path
            byte[] header = ForwardingToken.header("proxy-1", "lobby", 1000L + i, new byte[16]);
            assertArrayEquals(reference(key, header, id), ForwardingToken.mac(key, header, id), "iteration " + i);
        }
        // an odd call (Mac.update ignores a null array) must not leave state behind for the next MAC on this thread
        ForwardingToken.mac(KEY, null, ID);
        byte[] header = ForwardingToken.header("proxy-1", "lobby", 5L, new byte[16]);
        assertArrayEquals(reference(KEY, header, ID), ForwardingToken.mac(KEY, header, ID));
    }

    /** The 1.0.0 implementation, kept verbatim as the reference. */
    private static byte[] reference(byte[] key, byte[] header, PlayerIdentity id) throws Exception {
        javax.crypto.Mac hmac = javax.crypto.Mac.getInstance("HmacSHA256");
        hmac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
        hmac.update("NetworkLock/forwarding/v1\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        hmac.update(header);
        hmac.update(java.nio.ByteBuffer.allocate(16).putLong(id.uuid().getMostSignificantBits())
                .putLong(id.uuid().getLeastSignificantBits()).array());
        hmac.update(lp(id.name()));
        hmac.update(lp(id.address()));
        List<PlayerIdentity.Property> sorted = new java.util.ArrayList<>(id.properties());
        sorted.removeIf(p -> p.name().equals(ForwardingToken.PROPERTY));
        sorted.sort(java.util.Comparator.comparing(PlayerIdentity.Property::name)
                .thenComparing(PlayerIdentity.Property::value).thenComparing(PlayerIdentity.Property::signature));
        java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
        for (PlayerIdentity.Property p : sorted) {
            sha.update(lp(p.name()));
            sha.update(lp(p.value()));
            sha.update(lp(p.signature()));
        }
        hmac.update(sha.digest());
        return hmac.doFinal();
    }

    private static byte[] lp(String s) {
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.nio.ByteBuffer.allocate(4 + b.length).putInt(b.length).put(b).array();
    }
}
