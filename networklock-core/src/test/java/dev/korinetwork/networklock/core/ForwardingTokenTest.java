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
}
