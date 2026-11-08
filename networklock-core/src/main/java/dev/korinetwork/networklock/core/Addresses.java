package dev.korinetwork.networklock.core;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;

/** Address helpers shared by both sides of the forwarding boundary. */
public final class Addresses {

    private Addresses() {}

    /**
     * Canonical textual form used when binding a token to the client address. Proxies and
     * backends format addresses differently (host strings, IPv6 zone ids), so both sides
     * reduce them to {@link InetAddress#getHostAddress()} without a zone id.
     */
    public static String normalize(InetAddress address) {
        if (address == null) return "";
        String s = address.getHostAddress();
        int zone = s.indexOf('%');
        return zone >= 0 ? s.substring(0, zone) : s;
    }

    public static String normalize(SocketAddress address) {
        if (address instanceof InetSocketAddress && ((InetSocketAddress) address).getAddress() != null) {
            return normalize(((InetSocketAddress) address).getAddress());
        }
        return ""; // unix domain sockets and unresolved addresses carry no client IP
    }

    /** An address range, e.g. {@code 10.0.0.0/8} or {@code 2001:db8::/32}. A bare address is a single host. */
    public static final class Cidr {

        private final byte[] network;
        private final int prefix;

        public Cidr(byte[] network, int prefix) {
            this.network = network;
            this.prefix = prefix;
        }

        public byte[] network() {
            return network;
        }

        public int prefix() {
            return prefix;
        }

        public static Cidr parse(String text) {
            String s = text.trim();
            int slash = s.indexOf('/');
            String host = slash < 0 ? s : s.substring(0, slash);
            if (!host.matches("[0-9A-Fa-f:.]+")) {
                throw new IllegalArgumentException("not an IP address: " + text); // never resolve hostnames
            }
            InetAddress addr;
            try {
                addr = InetAddress.getByName(host);
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("not an IP address: " + text);
            }
            byte[] bytes = addr.getAddress();
            int max = bytes.length * 8;
            int prefix = slash < 0 ? max : Integer.parseInt(s.substring(slash + 1));
            if (prefix < 0 || prefix > max) throw new IllegalArgumentException("bad prefix length: " + text);
            return new Cidr(mask(bytes, prefix), prefix);
        }

        public boolean contains(InetAddress address) {
            byte[] bytes = address.getAddress();
            return bytes.length == network.length && java.util.Arrays.equals(mask(bytes, prefix), network);
        }
    }

    static byte[] mask(byte[] bytes, int prefix) {
        byte[] out = bytes.clone();
        for (int i = 0; i < out.length; i++) {
            int bits = Math.max(0, Math.min(8, prefix - i * 8));
            out[i] &= (byte) (0xFF << (8 - bits));
        }
        return out;
    }

    /** True for IPv4 addresses (Java already maps {@code ::ffff:a.b.c.d} to IPv4). */
    static boolean isV4(InetAddress address) {
        return address instanceof Inet4Address;
    }
}
