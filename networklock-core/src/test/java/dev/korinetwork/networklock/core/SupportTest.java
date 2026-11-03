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
            public String text(String key) { return key.equals("prefix") ? "" : "msg:" + key; }
            public void send(String formatted) { seen.add(formatted); }
            public void lines(List<String> l) { seen.addAll(l); }
        };
        Commands.Target target = target(new UpdateChecker("1.0.1", quiet, () -> false));
        Commands.dispatch("nlock", new String[]{"reload"}, limited, target);
        Commands.dispatch("nlock", new String[]{"status"}, limited, target);
        Commands.dispatch("nlock", new String[]{"nope"}, limited, target);
        Commands.dispatch("nlock", new String[]{"version"}, limited, target);
        assertEquals(List.of("msg:no-permission", "status!", "msg:invalid-command", "msg:no-permission"), seen);
        assertEquals(List.of("status"), Commands.complete(new String[]{""}, limited));
    }

    Commands.Target target(UpdateChecker updates) {
        return new Commands.Target() {
            public List<String> status() { return List.of("status!"); }
            public List<String> stats() { return List.of(); }
            public List<String> proxies() { return List.of(); }
            public List<String> doctor() { return List.of(); }
            public List<String> reload() { throw new AssertionError("must not reload"); }
            public UpdateChecker updates() { return updates; }
            public boolean notifyEnabled() { return true; }
            public int servers() { return 3; }
        };
    }

    @Test
    void aboutAndUpdateNotice() {
        Commands.Sender proxyLike = new Commands.Sender() {
            public boolean hasPermission(String n) { return true; }
            public String text(String key) { return dev.korinetwork.networklock.core.config.Messages.defaults().get(key); }
            public void send(String formatted) {}
            public void lines(List<String> l) {}
        };
        UpdateChecker unknown = new UpdateChecker("1.0.1-dev", quiet, () -> false);
        String about = Commands.about(proxyLike, target(unknown));
        assertTrue(about.contains("v1.0.1-dev") && about.contains("not checked") && about.contains("&f3") && about.contains("&fon"), about);
        assertFalse(about.contains("%"), "every placeholder filled: " + about);
        assertNull(Commands.updateNotice("x", unknown), "no notice without a known newer version");
    }

    @Test
    void versionComparison() {
        assertTrue(UpdateChecker.isNewer("1.0.1", "1.0.0"));
        assertTrue(UpdateChecker.isNewer("1.1", "1.0.9"));
        assertTrue(UpdateChecker.isNewer("1.0.1", "1.0.1-dev"), "a release beats its dev build");
        assertFalse(UpdateChecker.isNewer("1.0.0", "1.0.1-dev"), "the 1.0.1 dev build is not outdated by 1.0.0");
        assertFalse(UpdateChecker.isNewer("1.0.1", "1.0.1"));
        assertFalse(UpdateChecker.isNewer("1.0.1-dev", "1.0.1"));
        assertEquals("1.0.0", UpdateChecker.parseTag("{\"url\":\"x\",\"tag_name\": \"v1.0.0\",\"name\":\"NetworkLock\"}"));
        assertNull(UpdateChecker.parseTag("{\"message\":\"Not Found\"}"));
    }

    @Test
    void backendKickMessagesAndMultiLineMessages() throws Exception {
        String key = Keys.generate();
        Files.writeString(dir.resolve("config.yml"), "network:\n  server-id: lobby\nauthentication:\n  trusted-proxies:\n"
                + "    - id: p\n      keys: [\"" + key + "\"]\nmessages:\n  authentication-failed:\n    - \"&cLine one\"\n    - \"&7Line two\"\n");
        BackendCore core = new BackendCore(dir, quiet, now::get, Map.of());
        assertTrue(core.start());
        assertEquals("&cLine one\n&7Line two", core.kickText(Outcome.MISSING));
        assertEquals(Outcome.RejectCode.EXPIRED.backendMessage(), core.kickText(Outcome.EXPIRED), "unset keys keep the built-in text");
        Files.writeString(dir.resolve("messages.yml"), "messages:\n  about:\n    - \"a\"\n    - \"b\"\n");
        assertEquals("a\nb", dev.korinetwork.networklock.core.config.Messages.load(dir.resolve("messages.yml")).get("about"));
    }

    @Test
    void denialsExplainTheCauseInTheConsole() throws Exception {
        String key = Keys.generate();
        Files.writeString(dir.resolve("config.yml"), "network:\n  server-id: bloodysmp\nauthentication:\n  trusted-proxies:\n"
                + "    - id: proxy-1\n      keys: [\"" + key + "\"]\n");
        List<String> logged = new java.util.ArrayList<>();
        Log capture = new Log() {
            public void info(String m) { logged.add(m); }
            public void warn(String m) { logged.add(m); }
            public void error(String m) { logged.add(m); }
        };
        BackendCore core = new BackendCore(dir, capture, now::get, Map.of());
        assertTrue(core.start());
        PlayerIdentity id = new PlayerIdentity(UUID.randomUUID(), "ScoobyDoo_PL", "192.0.2.7", List.of());
        core.verify(id, List.of(ForwardingToken.mint("proxy-1", Keys.decode(key), "smp", id, now.get())));
        assertTrue(logged.stream().anyMatch(l -> l.contains("WRONG_TARGET") && l.contains("'smp'") && l.contains("'bloodysmp'")), logged.toString());
        core.verify(id, List.of());
        assertTrue(logged.stream().anyMatch(l -> l.contains("MISSING") && l.contains("forwarding")), logged.toString());
        for (String line : logged) assertFalse(line.contains(key));
    }

    @Test
    void usageStatsSendOnlyAnonymousData() throws Exception {
        String key = Keys.generate();
        Files.writeString(dir.resolve("config.yml"), "network:\n  server-id: lobby\nauthentication:\n  trusted-proxies:\n"
                + "    - id: p\n      keys: [\"" + key + "\"]\n");
        BackendCore core = new BackendCore(dir, quiet, now::get, Map.of());
        assertTrue(core.start());
        assertTrue(core.metricsEnabled(), "on by default");
        String id = core.usageId();
        assertEquals(32, id.length());
        assertEquals(id, core.usageId(), "stable across calls and restarts");
        UsageStats stats = new UsageStats("1.0.1-dev", "paper", "server", core::usageId, core::metricsEnabled);
        String payload = stats.payload(id);
        assertEquals("{\"id\":\"" + id + "\",\"version\":\"1.0.1-dev\",\"platform\":\"paper\",\"side\":\"server\"}", payload);
        assertFalse(payload.contains("lobby") || payload.contains(key) || payload.contains(Keys.fingerprint(Keys.decode(key))));
        Files.writeString(dir.resolve("config.yml"), Files.readString(dir.resolve("config.yml")) + "metrics:\n  enabled: false\n");
        assertEquals(List.of(), core.reload());
        assertFalse(core.metricsEnabled(), "switched off by reload, no restart needed");
        assertTrue(UsageStats.ENDPOINT.getScheme().equals("https"), "pings are always encrypted");
    }

    @Test
    void commandNamesFromConfig() throws Exception {
        Files.writeString(dir.resolve("config.yml"), "commands:\n  name: \"NLadmin\"\n  aliases: [\"nl\", \"bad name!\", \"nladmin\"]\n");
        dev.korinetwork.networklock.core.config.Settings.CommandNames n =
                dev.korinetwork.networklock.core.config.Settings.CommandNames.read(dir.resolve("config.yml"));
        assertEquals("nladmin", n.name());
        assertEquals(List.of("nl"), n.aliases(), "invalid and duplicate aliases are dropped");
        Files.writeString(dir.resolve("config.yml"), "this: [is broken");
        assertEquals(dev.korinetwork.networklock.core.config.Settings.CommandNames.DEFAULT,
                dev.korinetwork.networklock.core.config.Settings.CommandNames.read(dir.resolve("config.yml")));
    }
}
