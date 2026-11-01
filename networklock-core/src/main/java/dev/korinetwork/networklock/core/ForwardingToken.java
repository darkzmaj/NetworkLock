package dev.korinetwork.networklock.core;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The per-connection credential a proxy attaches to the forwarded game profile as the
 * {@value #PROPERTY} property.
 *
 * <pre>
 * token  = base64url( header || mac )
 * header = version:u8 || len:u8 proxyId || len:u8 target || issuedAtMillis:i64 || nonce:16
 * mac    = HMAC-SHA256(proxyKey, DOMAIN || header || uuid:16 || name || address || sha256(properties))
 * </pre>
 *
 * The MAC binds the token to one proxy identity, one target backend, one login identity
 * (UUID, name, client address and every other forwarded property) and a short validity
 * window; the nonce lets each backend reject reuse.
 */
public final class ForwardingToken {

    public static final String PROPERTY = "networklock";
    public static final int VERSION = 1;
    public static final int NONCE_BYTES = 16;
    public static final int MAC_BYTES = 32;
    /** Encoded tokens are ~250 characters; anything far larger is rejected before decoding. */
    public static final int MAX_ENCODED_LENGTH = 512;
    public static final Pattern PROXY_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private static final byte[] DOMAIN = "NetworkLock/forwarding/v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final SecureRandom RANDOM = new SecureRandom();

    private ForwardingToken() {}

    /** Structurally valid token fields. Nothing here is authenticated until {@link #macMatches} succeeds. */
    public record Parsed(String proxyId, String target, long issuedAtMillis, byte[] nonce, byte[] header, byte[] mac) {}

    public static String mint(String proxyId, byte[] key, String target, PlayerIdentity identity, long nowMillis) {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        byte[] header = header(proxyId, target, nowMillis, nonce);
        byte[] mac = mac(key, header, identity);
        byte[] out = new byte[header.length + mac.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(mac, 0, out, header.length, mac.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
    }

    static byte[] header(String proxyId, String target, long issuedAt, byte[] nonce) {
        byte[] p = checkedId(proxyId, "proxy id");
        byte[] t = checkedTarget(target);
        ByteBuffer buf = ByteBuffer.allocate(1 + 1 + p.length + 1 + t.length + 8 + NONCE_BYTES);
        buf.put((byte) VERSION).put((byte) p.length).put(p).put((byte) t.length).put(t).putLong(issuedAt).put(nonce);
        return buf.array();
    }

    /** @throws IllegalArgumentException if the token is not structurally valid */
    public static Parsed parse(String encoded) {
        if (encoded == null || encoded.isEmpty() || encoded.length() > MAX_ENCODED_LENGTH) {
            throw new IllegalArgumentException("bad token length");
        }
        byte[] raw = Base64.getUrlDecoder().decode(encoded);
        ByteBuffer buf = ByteBuffer.wrap(raw);
        if (buf.get() != VERSION) throw new IllegalArgumentException("unsupported token version");
        String proxyId = readString(buf);
        String target = readString(buf);
        long issuedAt = buf.getLong();
        byte[] nonce = new byte[NONCE_BYTES];
        buf.get(nonce);
        int headerLength = buf.position();
        if (buf.remaining() != MAC_BYTES) throw new IllegalArgumentException("bad token length");
        byte[] mac = new byte[MAC_BYTES];
        buf.get(mac);
        byte[] header = new byte[headerLength];
        System.arraycopy(raw, 0, header, 0, headerLength);
        checkedId(proxyId, "proxy id");
        checkedTarget(target);
        return new Parsed(proxyId, target, issuedAt, nonce, header, mac);
    }

    /** Constant-time comparison against every candidate key; does not stop at the first match. */
    public static boolean macMatches(Parsed token, List<byte[]> keys, PlayerIdentity identity) {
        boolean ok = false;
        for (byte[] key : keys) {
            ok |= MessageDigest.isEqual(mac(key, token.header(), identity), token.mac());
        }
        return ok;
    }

    static byte[] mac(byte[] key, byte[] header, PlayerIdentity id) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(key, "HmacSHA256"));
            hmac.update(DOMAIN);
            hmac.update(header);
            hmac.update(ByteBuffer.allocate(16).putLong(id.uuid().getMostSignificantBits())
                    .putLong(id.uuid().getLeastSignificantBits()).array());
            hmac.update(lengthPrefixed(id.name()));
            hmac.update(lengthPrefixed(id.address()));
            hmac.update(propertiesDigest(id.properties()));
            return hmac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /** Order-independent digest of the forwarded properties, ignoring any NetworkLock token. */
    static byte[] propertiesDigest(List<PlayerIdentity.Property> properties) {
        List<PlayerIdentity.Property> sorted = new ArrayList<>(properties.size());
        for (PlayerIdentity.Property p : properties) {
            if (!PROPERTY.equals(p.name())) sorted.add(p);
        }
        sorted.sort(Comparator.comparing(PlayerIdentity.Property::name)
                .thenComparing(PlayerIdentity.Property::value)
                .thenComparing(PlayerIdentity.Property::signature));
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (PlayerIdentity.Property p : sorted) {
                sha.update(lengthPrefixed(p.name()));
                sha.update(lengthPrefixed(p.value()));
                sha.update(lengthPrefixed(p.signature()));
            }
            return sha.digest();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] lengthPrefixed(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(4 + b.length).putInt(b.length).put(b).array();
    }

    private static String readString(ByteBuffer buf) {
        int len = buf.get() & 0xFF;
        if (len == 0 || len > buf.remaining()) throw new IllegalArgumentException("bad field length");
        byte[] b = new byte[len];
        buf.get(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static byte[] checkedId(String id, String what) {
        if (id == null || !PROXY_ID.matcher(id).matches()) throw new IllegalArgumentException("invalid " + what);
        return id.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] checkedTarget(String target) {
        byte[] b = target == null ? new byte[0] : target.getBytes(StandardCharsets.UTF_8);
        if (b.length == 0 || b.length > 64 || target.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid target server name");
        }
        return b;
    }
}
