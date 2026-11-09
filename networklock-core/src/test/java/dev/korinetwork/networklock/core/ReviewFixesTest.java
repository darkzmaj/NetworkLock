package dev.korinetwork.networklock.core;

import dev.korinetwork.networklock.core.config.ConfigException;
import dev.korinetwork.networklock.core.config.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Regression tests for the issues found in the 1.0.1 production review. */
class ReviewFixesTest {

    @TempDir
    Path dir;
    final AtomicLong now = new AtomicLong(1_700_000_000_000L);
    final List<String> logged = new ArrayList<>();
    final Log log = new Log() {
        public void info(String m) { logged.add(m); }
        public void warn(String m) { logged.add(m); }
        public void error(String m) { logged.add(m); }
    };
    final String key1 = Keys.generate();
    final String key2 = Keys.generate();
    final PlayerIdentity id = new PlayerIdentity(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), "Notch", "203.0.113.9", List.of());

    void backendConfig(String serverId, String extra, String proxies) throws Exception {
        Files.writeString(dir.resolve("config.yml"), "network:\n  server-id: \"" + serverId + "\"\nauthentication:\n" + extra
                + "  trusted-proxies:\n" + proxies);
    }

    static String proxy(String id, String... keys) {
        StringBuilder sb = new StringBuilder("    - id: \"" + id + "\"\n      keys:\n");
        for (String k : keys) sb.append("        - \"").append(k).append("\"\n");
        return sb.toString();
    }

    @Test
    void limiterSweepsAtMostOncePerSecondWhenFull() throws Exception {
        ConnectionLimiter l = new ConnectionLimiter(SupportTest.limits(5, 60, false, List.of(), 1000));
        for (int i = 0; i < 1000; i++) l.check(SupportTest.ip("10.0." + (i >> 8) + "." + (i & 255)), 0);
        assertEquals(1000, l.tracked());
        int before = l.sweeps();
        for (int i = 0; i < 5000; i++) l.check(SupportTest.ip("10.1." + (i >> 8 & 255) + "." + (i & 255)), 500);
        assertEquals(before, l.sweeps(), "a full table must not be swept for every new address");
        l.check(SupportTest.ip("192.0.2.1"), 1600);
        assertEquals(before + 1, l.sweeps(), "one sweep once a second has passed");
    }

    @Test
    void replayCacheSurvivesAReloadThatResizesIt() throws Exception {
        backendConfig("lobby", "", proxy("proxy-1", key1));
        BackendCore core = new BackendCore(dir, log, now::get, Map.of());
        assertTrue(core.start());
        String t = ForwardingToken.mint("proxy-1", Keys.decode(key1), "lobby", id, now.get());
        assertEquals(Outcome.OK, core.verify(id, List.of(t)));
        backendConfig("lobby", "  replay-cache-max-entries: 5000\n", proxy("proxy-1", key1));
        assertEquals(List.of(), core.reload());
        assertEquals(Outcome.REPLAYED, core.verify(id, List.of(t)), "a resized cache must still know used tokens");
        assertTrue(core.statsLines().contains("replay memory: 1 / 5000"), core.statsLines().toString());
    }

    @Test
    void updateChecksStayOffWhileNoConfigIsLoaded() throws Exception {
        Files.writeString(dir.resolve("config.yml"), "network: [broken");
        BackendCore backend = new BackendCore(dir, log, now::get, Map.of());
        assertFalse(backend.start());
        assertFalse(backend.updatesEnabled(), "the owner's opt-out is unknown without a config");
        assertFalse(backend.metricsEnabled());
        Path p = dir.resolve("proxy");
        Files.createDirectories(p);
        Files.writeString(p.resolve("config.yml"), "network: [broken");
        ProxyCore proxyCore = new ProxyCore(p, log, now::get, Map.of());
        assertFalse(proxyCore.start());
        assertFalse(proxyCore.updatesEnabled());
    }

    @Test
    void serverIdIsValidatedAtLoad() throws Exception {
        backendConfig(" lobby", "", proxy("proxy-1", key1));
        ConfigException spaces = assertThrows(ConfigException.class, () -> Settings.Backend.load(dir.resolve("config.yml"), Map.of()));
        assertTrue(spaces.getMessage().contains("spaces"), spaces.getMessage());
        backendConfig("x".repeat(65), "", proxy("proxy-1", key1));
        assertThrows(ConfigException.class, () -> Settings.Backend.load(dir.resolve("config.yml"), Map.of()));
    }

    @Test
    void sameKeyTwiceForOneProxyIsNotAWarningButSharedAcrossProxiesIs() throws Exception {
        backendConfig("lobby", "", "    - id: \"proxy-1\"\n      key: \"" + key1 + "\"\n      keys: [\"" + key1 + "\"]\n");
        Settings.Backend once = Settings.Backend.load(dir.resolve("config.yml"), Map.of());
        assertEquals(List.of(), once.warnings());
        assertEquals(1, once.proxies().get("proxy-1").keys().size());
        backendConfig("lobby", "", proxy("proxy-1", key1) + proxy("proxy-2", key1));
        assertFalse(Settings.Backend.load(dir.resolve("config.yml"), Map.of()).warnings().isEmpty());
    }

    @Test
    void usageIdCannotBeWorkedOutFromPublicFingerprints() throws Exception {
        backendConfig("lobby", "", proxy("proxy-1", key1));
        BackendCore core = new BackendCore(dir, log, now::get, Map.of());
        assertTrue(core.start());
        String usage = core.usageId();
        // the 1.0.1-dev formula: SHA-256 over a label, "server", the server-id and the public fingerprints
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update("NetworkLock/usage-id/v1".getBytes(StandardCharsets.US_ASCII));
        for (String part : List.of("server", "lobby", Keys.fingerprint(Keys.decode(key1)))) {
            sha.update((byte) 0);
            sha.update(part.getBytes(StandardCharsets.UTF_8));
        }
        assertNotEquals(Keys.hex(sha.digest(), 0, 16), usage);
        BackendCore again = new BackendCore(dir, log, now::get, Map.of());
        again.start();
        assertEquals(usage, again.usageId(), "stable across restarts");
        backendConfig("lobby", "", proxy("proxy-1", key2));
        again.reload();
        assertNotEquals(usage, again.usageId(), "a different secret gives a different id");
    }

    @Test
    void proxyRefusesToSignUnsignableServerNamesWithoutThrowing() throws Exception {
        ProxyCore proxyCore = new ProxyCore(dir, log, now::get, Map.of());
        assertTrue(proxyCore.start());
        String longName = "x".repeat(65);
        assertNull(proxyCore.mint(id, longName));
        assertNull(proxyCore.mint(id, longName));
        assertNull(proxyCore.mint(id, "bad\nname"));
        assertEquals(2, logged.stream().filter(m -> m.contains("cannot be signed")).count(), "logged once per name: " + logged);
        assertNotNull(proxyCore.mint(id, "lobby"));
    }

    @Test
    void unreadableOwnVersionNeverReportsOutdated() throws Exception {
        UpdateChecker checker = new UpdateChecker("unknown", log, () -> true);
        Field latest = UpdateChecker.class.getDeclaredField("latest");
        latest.setAccessible(true);
        latest.set(checker, "1.0.2");
        assertEquals(UpdateChecker.Status.UNKNOWN, checker.status());
        UpdateChecker known = new UpdateChecker("1.0.1", log, () -> true);
        latest.set(known, "1.0.2");
        assertEquals(UpdateChecker.Status.OUTDATED, known.status());
    }

    static class Base {
        private final Object profile;
        Base(Object p) { profile = p; }
        Object profile() { return profile; }
    }

    static final class Holder extends Base {
        Object same;
        Object other = "other";
        Holder(Object p) { super(p); same = p; }
    }

    @Test
    void profileScrubberReplacesOnlyTheExactInstanceEvenInFinalSuperclassFields() {
        Object old = new StringBuilder("old");
        Object fresh = new StringBuilder("fresh");
        Holder h = new Holder(old);
        assertEquals(2, ProfileScrubber.replaceReferences(h, old, fresh));
        assertSame(fresh, h.profile());
        assertSame(fresh, h.same);
        assertEquals("other", h.other);
        assertNull(ProfileScrubber.withoutCredential("not a profile"), "anything unexpected leaves the profile alone");
    }

    @Test
    void connectionLimiterChecksStillAllowNormalTraffic() throws Exception {
        ConnectionLimiter l = new ConnectionLimiter(SupportTest.limits(3, 10, false, List.of(), 1000));
        InetAddress a = SupportTest.ip("198.51.100.7");
        assertEquals(ConnectionLimiter.Decision.ALLOWED, l.check(a, 0));
        assertEquals(ConnectionLimiter.Decision.ALLOWED, l.check(a, 1));
        assertEquals(ConnectionLimiter.Decision.ALLOWED, l.check(a, 2));
        assertEquals(ConnectionLimiter.Decision.LIMITED, l.check(a, 3));
    }
}
