package dev.korinetwork.networklock.core;

import dev.korinetwork.networklock.core.config.ConfigException;
import dev.korinetwork.networklock.core.config.ConfigFiles;
import dev.korinetwork.networklock.core.config.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Limiter, replay cache, config files, text conversion and the in-process proxy -> backend path. */
class SupportTest {

    @TempDir
    Path dir;
    final AtomicLong now = new AtomicLong(1_000_000L);
    final Log quiet = new Log() {
        public void info(String m) {}
        public void warn(String m) {}
        public void error(String m) {}
    };

    static ConnectionLimiter.Settings limits(int max, int window, boolean blocks, List<Addresses.Cidr> exempt, int tracked) {
        return new ConnectionLimiter.Settings(true, max, window, blocks, 3, 60, 64, exempt, tracked, 100);
    }

    static InetAddress ip(String s) throws Exception {
        return InetAddress.getByName(s);
    }

    @Test
    void limiterAllowsBurstThenThrottlesThenRefills() throws Exception {
        ConnectionLimiter l = new ConnectionLimiter(limits(3, 3, false, List.of(), 1000));
        InetAddress a = ip("192.0.2.1");
        for (int i = 0; i < 3; i++) assertEquals(ConnectionLimiter.Decision.ALLOWED, l.check(a, 0));
        assertEquals(ConnectionLimiter.Decision.LIMITED, l.check(a, 0));
        assertEquals(ConnectionLimiter.Decision.ALLOWED, l.check(ip("192.0.2.2"), 0), "other address unaffected");
        assertEquals(ConnectionLimiter.Decision.ALLOWED, l.check(a, 1000), "one token per second refilled");
    }

    @Test
    void limiterTemporarilyBlocksRepeatOffenders() throws Exception {
        ConnectionLimiter l = new ConnectionLimiter(limits(1, 10, true, List.of(), 1000));
        InetAddress a = ip("192.0.2.1");
        l.check(a, 0);
        l.check(a, 0);
        l.check(a, 0);
        assertEquals(ConnectionLimiter.Decision.BLOCKED, l.check(a, 0));
        assertEquals(ConnectionLimiter.Decision.BLOCKED, l.check(a, 59_000));
        assertEquals(ConnectionLimiter.Decision.ALLOWED, l.check(a, 61_000));
    }

    @Test
    void limiterGroupsIpv6ByPrefixAndHonoursExemptions() throws Exception {
        ConnectionLimiter l = new ConnectionLimiter(limits(1, 10, false, List.of(Addresses.Cidr.parse("10.0.0.0/8")), 1000));
        assertEquals(ConnectionLimiter.Decision.ALLOWED, l.check(ip("2001:db8:1:2::1"), 0));
        assertEquals(ConnectionLimiter.Decision.LIMITED, l.check(ip("2001:db8:1:2::ffff"), 0), "same /64");
        assertEquals(ConnectionLimiter.Decision.ALLOWED, l.check(ip("2001:db8:1:3::1"), 0), "different /64");
        for (int i = 0; i < 5; i++) assertEquals(ConnectionLimiter.Decision.ALLOWED, l.check(ip("10.1.2.3"), 0));
        assertEquals(l.key(ip("::ffff:192.0.2.9")), l.key(ip("192.0.2.9")), "IPv4-mapped IPv6 is IPv4");
    }

    @Test
    void limiterMemoryIsBounded() throws Exception {
        ConnectionLimiter l = new ConnectionLimiter(limits(5, 1, false, List.of(), 1000));
        for (int i = 0; i < 5000; i++) l.check(ip("10." + (i >> 16 & 255) + "." + (i >> 8 & 255) + "." + (i & 255)), 0);
        assertTrue(l.tracked() <= 1000, "tracked " + l.tracked());
        assertTrue(l.saturated());
        l.check(ip("192.0.2.1"), 10_000); // idle entries expire
        assertTrue(l.tracked() < 10, "tracked after sweep " + l.tracked());
    }

    @Test
    void nonceCacheIsBoundedAndExpires() {
        NonceCache c = new NonceCache(2);
        assertEquals(NonceCache.Result.FRESH, c.use(new byte[16], 100, 0));
        byte[] n2 = new byte[16];
        n2[0] = 1;
        assertEquals(NonceCache.Result.FRESH, c.use(n2, 100, 0));
        byte[] n3 = new byte[16];
        n3[0] = 2;
        assertEquals(NonceCache.Result.FULL, c.use(n3, 100, 0), "fails closed when full");
        assertEquals(NonceCache.Result.FRESH, c.use(n3, 300, 200), "expired entries make room");
    }

    @Test
    void addressNormalizationAndCidr() throws Exception {
        assertEquals("fe80:0:0:0:0:0:0:1", Addresses.normalize(ip("fe80::1%1")));
        assertEquals(Addresses.normalize(ip("2001:db8::1")), Addresses.normalize(ip("2001:0db8:0000::0001")));
        assertTrue(Addresses.Cidr.parse("2001:db8::/32").contains(ip("2001:db8:ffff::1")));
        assertFalse(Addresses.Cidr.parse("2001:db8::/32").contains(ip("192.0.2.1")));
        assertThrows(IllegalArgumentException.class, () -> Addresses.Cidr.parse("example.com"));
        assertThrows(IllegalArgumentException.class, () -> Addresses.Cidr.parse("10.0.0.0/33"));
    }

    @Test
    void proxyKeyIsGeneratedOnceAndNeverOverwritten() throws Exception {
        ProxyCore core = new ProxyCore(dir, quiet, now::get, Map.of());
        assertTrue(core.start());
        String first = Files.readString(dir.resolve("config.yml"));
        assertTrue(Files.exists(dir.resolve("messages.yml")));
        assertTrue(new ProxyCore(dir, quiet, now::get, Map.of()).start());
        assertEquals(first, Files.readString(dir.resolve("config.yml")));
        assertFalse(first.contains("key: \"\""));
    }

    @Test
    void keysFromEnvironmentAndFiles() throws Exception {
        String key = Keys.generate();
        Path secret = dir.resolve("secret.txt");
        Files.writeString(secret, key + "\n");
        Files.writeString(dir.resolve("config.yml"), "network:\n  server-id: s\nauthentication:\n  trusted-proxies:\n"
                + "    - id: a\n      key: \"env:NL_KEY\"\n    - id: b\n      keys: [\"file:" + secret.toString().replace('\\', '/') + "\"]\n");
        Settings.Backend s = Settings.Backend.load(dir.resolve("config.yml"), Map.of("NL_KEY", key));
        assertEquals(s.fingerprints("a"), s.fingerprints("b"));
        assertFalse(s.warnings().isEmpty(), "shared key across proxies is flagged");
        ConfigException missing = assertThrows(ConfigException.class, () -> Settings.Backend.load(dir.resolve("config.yml"), Map.of()));
        assertTrue(missing.getMessage().contains("NL_KEY"));
    }

    @Test
    void configErrorsAreCollectedAndNeverEchoSecrets() throws Exception {
        Files.writeString(dir.resolve("config.yml"), "network:\n  server-id: s\nauthentication:\n  trusted-proxies:\n"
                + "    - id: \"bad id\"\n      keys: [\"x\"]\n    - id: ok\n      keys: [\"c2VjcmV0LXZhbHVlLXRoYXQtaXMtc2hvcnQ\"]\n"
                + "  max-token-age-seconds: 9999\n");
        ConfigException e = assertThrows(ConfigException.class, () -> Settings.Backend.load(dir.resolve("config.yml"), Map.of()));
        assertEquals(3, e.problems().size(), e.problems().toString());
        assertFalse(e.getMessage().contains("c2VjcmV0"));
        Files.writeString(dir.resolve("config.yml"), "key: \"c2VjcmV0\n  : : [");
        ConfigException yaml = assertThrows(ConfigException.class, () -> Settings.Backend.load(dir.resolve("config.yml"), Map.of()));
        assertFalse(yaml.getMessage().contains("c2VjcmV0"), yaml.getMessage());
    }

    @Test
    void endToEndProxyToBackend() throws Exception {
        Path proxyDir = dir.resolve("proxy");
        ProxyCore proxy = new ProxyCore(proxyDir, quiet, now::get, Map.of());
        assertTrue(proxy.start());
        String key = Settings.Proxy.load(proxyDir.resolve("config.yml"), Map.of()).key() == null ? null
                : Files.readString(proxyDir.resolve("config.yml")).replaceAll("(?s).*\\n\\s*key: \"([^\"]+)\".*", "$1");
        Path backendDir = dir.resolve("backend");
        Files.createDirectories(backendDir);
        Files.writeString(backendDir.resolve("config.yml"), "network:\n  server-id: lobby\nauthentication:\n  trusted-proxies:\n"
                + "    - id: proxy-1\n      keys: [\"" + key + "\"]\n");
        BackendCore backend = new BackendCore(backendDir, quiet, now::get, Map.of());
        assertTrue(backend.start());
        PlayerIdentity id = new PlayerIdentity(UUID.randomUUID(), "Alex", "192.0.2.50", List.of());
        assertEquals(Outcome.OK, backend.verify(id, List.of(proxy.mint(id, "lobby"))));
        assertEquals(Outcome.WRONG_TARGET, backend.verify(id, List.of(proxy.mint(id, "survival"))));
    }

    @Test
    void proxyTranslatesOnlyGenuineBackendCodes() throws Exception {
        ProxyCore proxy = new ProxyCore(dir, quiet, now::get, Map.of());
        proxy.start();
        assertEquals(proxy.messages().get("authentication-timeout"),
                proxy.translateBackendKick(Outcome.RejectCode.EXPIRED.backendMessage()));
        assertNull(proxy.translateBackendKick("You are banned [NL-01]"));
        assertNull(proxy.translateBackendKick("Server closed"));
        assertEquals(ConfigFiles.CONFIG, "config.yml");
    }

    @Test
    void proxyLockdownOnBrokenConfig() throws Exception {
        Files.writeString(dir.resolve("config.yml"), "network:\n  id: \"proxy 1\"\nsecurity:\n  key: \"short\"\n");
        ProxyCore proxy = new ProxyCore(dir, quiet, now::get, Map.of());
        assertFalse(proxy.start());
        assertEquals(ProxyCore.Admission.LOCKDOWN, proxy.admit(ip("192.0.2.1")));
        assertNull(proxy.mint(new PlayerIdentity(UUID.randomUUID(), "a", "", List.of()), "lobby"));
    }

    @Test
    void miniTextSubset() {
        assertEquals("§cHi §r§lbold", MiniText.toLegacy("<red>Hi <reset><bold>bold"));
        assertEquals("§cA§lB§r§cC", MiniText.toLegacy("<red>A<b>B</b>C"));
        assertEquals("§x§a§b§c§d§e§fX", MiniText.toLegacy("<#abcdef>X"));
        assertEquals("click", MiniText.toLegacy("<click:run_command:/op me>click</click>").replace("§r", ""));
        assertEquals("<literal> cx", MiniText.toLegacy("\\<literal> §cx"), "raw section signs are dropped");
    }

    @Test
    void ampersandCodesAreTheDefaultStyle() {
        assertEquals("§r§8[§r§bNetworkLock§r§8] ", MiniText.anyToLegacy("&8[&bNetworkLock&8] "));
        assertEquals("§r§cA§lB§r§aC", MiniText.anyToLegacy("&cA&lB&aC"), "a colour resets bold, as in legacy text");
        assertEquals("§r§x§f§f§8§8§0§0hex", MiniText.anyToLegacy("&#ff8800hex"));
        assertEquals("§r§cmixed §lok", MiniText.anyToLegacy("&cmixed <bold>ok"), "& codes and MiniMessage tags combine");
        assertEquals("Tom & Jerry &z", MiniText.anyToLegacy("Tom & Jerry &z"), "non-codes are left alone");
        for (String key : dev.korinetwork.networklock.core.config.Messages.DEFAULTS.keySet()) {
            assertFalse(MiniText.anyToLegacy(dev.korinetwork.networklock.core.config.Messages.DEFAULTS.get(key)).contains("&"), key);
        }
    }

    @Test
    void commandPermissions() {
        List<String> seen = new java.util.ArrayList<>();
        Commands.Sender limited = new Commands.Sender() {
            public boolean hasPermission(String n) { return n.equals("networklock.command.status"); }
            public void message(String key) { seen.add("msg:" + key); }
            public void lines(List<String> l) { seen.addAll(l); }
        };
        Commands.Target target = new Commands.Target() {
            public List<String> status() { return List.of("status!"); }
            public List<String> stats() { return List.of(); }
            public List<String> proxies() { return List.of(); }
            public List<String> doctor() { return List.of(); }
            public List<String> reload() { throw new AssertionError("must not reload"); }
            public String version() { return "1"; }
        };
        Commands.dispatch(new String[]{"reload"}, limited, target);
        Commands.dispatch(new String[]{"status"}, limited, target);
        Commands.dispatch(new String[]{"nope"}, limited, target);
        assertEquals(List.of("msg:no-permission", "status!", "msg:invalid-command"), seen);
        assertEquals(List.of("status"), Commands.complete(new String[]{""}, limited));
    }
}
