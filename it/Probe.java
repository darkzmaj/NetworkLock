import dev.korinetwork.networklock.core.ForwardingToken;
import dev.korinetwork.networklock.core.Keys;
import dev.korinetwork.networklock.core.PlayerIdentity;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

/**
 * Minimal Minecraft login client for integration tests. It plays an attacker connecting
 * straight to a backend (with or without a forged BungeeCord handshake) or a normal player
 * joining through a proxy, and prints ACCEPTED or REJECTED with the server's reason.
 *
 * <pre>
 * java [-Dproto=N] -cp networklock-core/build/classes/java/main it/Probe.java HOST PORT NAME MODE [options]
 *   MODE protocol                print the server's protocol number and exit
 *   MODE plain                  ordinary offline-mode login (use against a proxy)
 *   MODE forged UUID             BungeeCord handshake claiming UUID, no NetworkLock token
 *   MODE garbage UUID            BungeeCord handshake with a junk token property
 *   MODE token UUID PROXY KEY TARGET IP [replay]   valid token minted locally; "replay" sends it twice
 * </pre>
 */
public final class Probe {

    public static void main(String[] a) throws Exception {
        String host = a[0];
        int port = Integer.parseInt(a[1]);
        String name = a[2];
        String mode = a[3];
        if (mode.equals("protocol")) { // prints the server's protocol number, for pinning -Dproto
            System.out.println(statusProtocol(host, port));
            return;
        }
        // -Dproto=N pins the version (a proxy advertises its newest, which an older backend refuses)
        int protocol = Integer.getInteger("proto", -1) >= 0 ? Integer.getInteger("proto") : statusProtocol(host, port);
        try {
            run(a, host, port, name, mode, protocol);
        } catch (IOException e) {
            System.out.println("CLOSED by server (" + e.getClass().getSimpleName() + ")");
        }
    }

    static void run(String[] a, String host, int port, String name, String mode, int protocol) throws Exception {
        switch (mode) {
            case "plain" -> report(login(host, port, protocol, name, host, null));
            case "forged" -> report(login(host, port, protocol, name, bungeeHost(host, "127.0.0.1", UUID.fromString(a[4]), "[]"), UUID.fromString(a[4])));
            case "garbage" -> report(login(host, port, protocol, name,
                    bungeeHost(host, "127.0.0.1", UUID.fromString(a[4]), props("AAAAAAAA")), UUID.fromString(a[4])));
            case "token" -> {
                UUID uuid = UUID.fromString(a[4]);
                PlayerIdentity id = new PlayerIdentity(uuid, name, a[8], List.of());
                String token = ForwardingToken.mint(a[5], Keys.decode(a[6]), a[7], id, System.currentTimeMillis());
                String h = bungeeHost(host, a[8], uuid, props(token));
                report(login(host, port, protocol, name, h, uuid));
                if (a.length > 9 && a[9].equals("replay")) {
                    System.out.print("replay: ");
                    report(login(host, port, protocol, name, h, uuid));
                }
            }
            default -> throw new IllegalArgumentException(mode);
        }
    }

    static String props(String token) {
        return "[{\"name\":\"" + ForwardingToken.PROPERTY + "\",\"value\":\"" + token + "\",\"signature\":\"\"}]";
    }

    static String bungeeHost(String host, String ip, UUID uuid, String propsJson) {
        return host + "\0" + ip + "\0" + uuid.toString().replace("-", "") + "\0" + propsJson;
    }

    static void report(String result) {
        System.out.println(result);
    }

    static int statusProtocol(String host, int port) throws IOException {
        try (Socket s = new Socket(host, port)) {
            s.setSoTimeout(10_000);
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            send(out, packet(0x00, buf -> {
                varInt(buf, -1);
                string(buf, host);
                buf.writeShort(port);
                varInt(buf, 1);
            }), -1);
            send(out, packet(0x00, buf -> {}), -1);
            byte[] p = read(new DataInputStream(s.getInputStream()), -1);
            String json = readString(new DataInputStream(new java.io.ByteArrayInputStream(p, 1, p.length - 1)));
            Matcher m = Pattern.compile("\"protocol\"\\s*:\\s*(-?\\d+)").matcher(json);
            if (!m.find()) throw new IOException("no protocol in status: " + json);
            return Integer.parseInt(m.group(1));
        }
    }

    static String login(String host, int port, int protocol, String name, String handshakeHost, UUID uuid) throws IOException {
        try (Socket s = new Socket(host, port)) {
            s.setSoTimeout(15_000);
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            DataInputStream in = new DataInputStream(s.getInputStream());
            send(out, packet(0x00, buf -> {
                varInt(buf, protocol);
                string(buf, handshakeHost);
                buf.writeShort(port);
                varInt(buf, 2);
            }), -1);
            UUID loginUuid = uuid != null ? uuid : UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            send(out, packet(0x00, buf -> {
                string(buf, name);
                buf.writeLong(loginUuid.getMostSignificantBits());
                buf.writeLong(loginUuid.getLeastSignificantBits());
            }), -1);
            int threshold = -1;
            boolean configuring = false;
            // Packet ids in the configuration phase change between versions; a rejection arrives within
            // a second or two, so surviving this long without a disconnect counts as accepted.
            long acceptAfter = Long.MAX_VALUE;
            while (true) {
                if (System.currentTimeMillis() > acceptAfter) return "ACCEPTED (no disconnect in configuration phase)";
                byte[] p;
                try {
                    p = read(in, threshold);
                } catch (java.net.SocketTimeoutException e) {
                    return configuring ? "ACCEPTED (no disconnect in configuration phase)" : "TIMEOUT";
                } catch (IOException e) {
                    return configuring ? "CLOSED during configuration" : "CLOSED " + e.getMessage();
                }
                DataInputStream pin = new DataInputStream(new java.io.ByteArrayInputStream(p));
                int id = readVarInt(pin);
                if (!configuring) {
                    switch (id) {
                        case 0x00 -> { return "REJECTED " + readString(pin); }
                        case 0x01 -> { return "ONLINE-MODE (encryption requested)"; }
                        case 0x03 -> threshold = readVarInt(pin);
                        case 0x02 -> {
                            configuring = true;
                            acceptAfter = System.currentTimeMillis() + 6_000;
                            s.setSoTimeout(6_500);
                            final int t = threshold;
                            send(out, packet(0x03, buf -> {}), t); // login acknowledged
                        }
                        case 0x04 -> { // login plugin request (Sponge sends these): answer "not understood", like vanilla
                            int messageId = readVarInt(pin);
                            final int t = threshold;
                            send(out, packet(0x02, buf -> {
                                varInt(buf, messageId);
                                buf.writeBoolean(false);
                            }), t);
                        }
                        default -> { /* nothing else is expected during login */ }
                    }
                } else {
                    if (id == 0x02) return "REJECTED (configuration) " + printable(p);
                    if (id == 0x03) return "ACCEPTED (backend reached finish-configuration)";
                    if (id == 0x0E) { // select known packs: answer with none so the backend proceeds
                        final int t = threshold;
                        send(out, packet(0x07, buf -> varInt(buf, 0)), t);
                    }
                    if (id == 0x04) { // keep alive
                        long v = pin.readLong();
                        final int t = threshold;
                        send(out, packet(0x04, buf -> buf.writeLong(v)), t);
                    }
                }
            }
        }
    }

    // --- framing --------------------------------------------------------------------------

    interface Body { void write(DataOutputStream buf) throws IOException; }

    static byte[] packet(int id, Body body) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream buf = new DataOutputStream(bytes);
        varInt(buf, id);
        body.write(buf);
        return bytes.toByteArray();
    }

    static void send(DataOutputStream out, byte[] payload, int threshold) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        DataOutputStream f = new DataOutputStream(frame);
        if (threshold >= 0) varInt(f, 0); // uncompressed
        f.write(payload);
        varInt(out, frame.size());
        out.write(frame.toByteArray());
        out.flush();
    }

    static byte[] read(DataInputStream in, int threshold) throws IOException {
        int len = readVarInt(in);
        byte[] frame = in.readNBytes(len);
        if (frame.length != len) throw new IOException("short read");
        if (threshold < 0) return frame;
        DataInputStream f = new DataInputStream(new java.io.ByteArrayInputStream(frame));
        int dataLen = readVarInt(f);
        byte[] rest = f.readAllBytes();
        if (dataLen == 0) return rest;
        Inflater inflater = new Inflater();
        inflater.setInput(rest);
        byte[] out = new byte[dataLen];
        try {
            inflater.inflate(out);
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException(e);
        }
        return out;
    }

    static void varInt(DataOutputStream out, int v) throws IOException {
        while ((v & ~0x7F) != 0) {
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v);
    }

    static int readVarInt(InputStream in) throws IOException {
        int value = 0, pos = 0, b;
        do {
            b = in.read();
            if (b < 0) throw new IOException("closed");
            value |= (b & 0x7F) << pos;
            pos += 7;
        } while ((b & 0x80) != 0 && pos < 35);
        return value;
    }

    static void string(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        varInt(out, b.length);
        out.write(b);
    }

    static String readString(DataInputStream in) throws IOException {
        return new String(in.readNBytes(readVarInt(in)), StandardCharsets.UTF_8);
    }

    /** NBT text components are awkward to decode; printable runs are enough for a test log. */
    static String printable(byte[] p) {
        StringBuilder sb = new StringBuilder();
        for (byte b : p) sb.append(b >= 32 && b < 127 ? (char) b : ' ');
        return sb.toString().replaceAll(" {2,}", " ").trim();
    }
}
