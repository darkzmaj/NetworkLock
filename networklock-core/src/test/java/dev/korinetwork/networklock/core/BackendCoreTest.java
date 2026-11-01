package dev.korinetwork.networklock.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class BackendCoreTest {

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
    final PlayerIdentity id = new PlayerIdentity(UUID.randomUUID(), "Steve", "198.51.100.4", List.of());
    BackendCore core;

    void writeConfig(String serverId, String proxies) throws Exception {
        Files.writeString(dir.resolve("config.yml"), "network:\n  server-id: \"" + serverId + "\"\n"
                + "authentication:\n  trusted-proxies:\n" + proxies);
    }

    String proxy(String id, String... keys) {
        StringBuilder sb = new StringBuilder("    - id: \"" + id + "\"\n      keys:\n");
        for (String k : keys) sb.append("        - \"").append(k).append("\"\n");
        return sb.toString();
    }

    String token(String proxyId, String key, String target) {
        return ForwardingToken.mint(proxyId, Keys.decode(key), target, id, now.get());
    }

    @BeforeEach
    void setUp() throws Exception {
        writeConfig("survival", proxy("proxy-1", key1) + proxy("proxy-2", key2));
        core = new BackendCore(dir, log, now::get, Map.of());
        assertTrue(core.start());
    }

    @Test
    void acceptsValidTokenOnce() {
        String t = token("proxy-1", key1, "survival");
        assertEquals(Outcome.OK, core.verify(id, List.of(t)));
        assertEquals(Outcome.REPLAYED, core.verify(id, List.of(t)));
    }

    @Test
    void rejectsMissingDuplicateAndMalformed() {
        assertEquals(Outcome.MISSING, core.verify(id, List.of()));
        String t = token("proxy-1", key1, "survival");
        assertEquals(Outcome.MULTIPLE, core.verify(id, List.of(t, t)));
        assertEquals(Outcome.MALFORMED, core.verify(id, List.of("garbage")));
        assertEquals(Outcome.MALFORMED, core.verify(id, List.of("x".repeat(100_000))));
    }

    @Test
    void forgedIdentityIsRejected() {
        String t = token("proxy-1", key1, "survival");
        PlayerIdentity victim = new PlayerIdentity(UUID.randomUUID(), "Admin", id.address(), List.of());
        assertEquals(Outcome.BAD_SIGNATURE, core.verify(victim, List.of(t)));
    }

    @Test
    void crossProxyCredentialMisuseIsRejected() {
        // proxy-2's key cannot sign as proxy-1, and an unknown proxy id is refused outright
        assertEquals(Outcome.BAD_SIGNATURE, core.verify(id, List.of(token("proxy-1", key2, "survival"))));
        assertEquals(Outcome.UNKNOWN_PROXY, core.verify(id, List.of(token("proxy-9", key1, "survival"))));
    }

    @Test
    void tokenForAnotherBackendIsRejected() {
        assertEquals(Outcome.WRONG_TARGET, core.verify(id, List.of(token("proxy-1", key1, "lobby"))));
    }

    @Test
    void expiryAndClockSkew() {
        String t = token("proxy-1", key1, "survival");
        now.addAndGet(30_001);
        assertEquals(Outcome.EXPIRED, core.verify(id, List.of(t)));
        String future = ForwardingToken.mint("proxy-1", Keys.decode(key1), "survival", id, now.get() + 5_001);
        assertEquals(Outcome.NOT_YET_VALID, core.verify(id, List.of(future)));
        String slightlyAhead = ForwardingToken.mint("proxy-1", Keys.decode(key1), "survival", id, now.get() + 4_000);
        assertEquals(Outcome.OK, core.verify(id, List.of(slightlyAhead)));
    }

    @Test
    void rotationAndRevocation() throws Exception {
        String newKey = Keys.generate();
        writeConfig("survival", proxy("proxy-1", key1, newKey));
        assertEquals(List.of(), core.reload());
        assertEquals(Outcome.OK, core.verify(id, List.of(token("proxy-1", key1, "survival"))));
        assertEquals(Outcome.OK, core.verify(id, List.of(token("proxy-1", newKey, "survival"))));
        writeConfig("survival", proxy("proxy-1", newKey));
        assertEquals(List.of(), core.reload());
        assertEquals(Outcome.BAD_SIGNATURE, core.verify(id, List.of(token("proxy-1", key1, "survival"))), "revoked key");
        assertEquals(Outcome.UNKNOWN_PROXY, core.verify(id, List.of(token("proxy-2", key2, "survival"))), "removed proxy");
    }

    @Test
    void replayProtectionSurvivesReload() throws Exception {
        String t = token("proxy-1", key1, "survival");
        assertEquals(Outcome.OK, core.verify(id, List.of(t)));
        assertEquals(List.of(), core.reload());
        assertEquals(Outcome.REPLAYED, core.verify(id, List.of(t)));
    }

    @Test
    void invalidReloadKeepsPreviousConfig() throws Exception {
        Files.writeString(dir.resolve("config.yml"), "network: [unclosed");
        assertFalse(core.reload().isEmpty());
        assertFalse(core.inLockdown());
        assertEquals(Outcome.OK, core.verify(id, List.of(token("proxy-1", key1, "survival"))));
    }

    @Test
    void invalidStartupConfigLocksDown() throws Exception {
        writeConfig("", "    - id: \"proxy-1\"\n      keys: [\"tooshort\"]\n");
        BackendCore fresh = new BackendCore(dir, log, now::get, Map.of());
        assertFalse(fresh.start());
        assertTrue(fresh.inLockdown());
        assertEquals(Outcome.LOCKDOWN, fresh.verify(id, List.of(token("proxy-1", key1, "survival"))));
    }

    @Test
    void freshInstallLocksDownUntilConfigured() {
        BackendCore fresh = new BackendCore(dir.resolve("new"), log, now::get, Map.of());
        assertFalse(fresh.start());
        assertTrue(Files.exists(dir.resolve("new/config.yml")));
        assertEquals(Outcome.LOCKDOWN, fresh.verify(id, List.of()));
    }

    @Test
    void platformBlockSurvivesReload() {
        core.blockPlatform("no Paper API");
        assertEquals(List.of(), core.reload());
        assertTrue(core.inLockdown());
        assertEquals(Outcome.LOCKDOWN, core.verify(id, List.of(token("proxy-1", key1, "survival"))));
    }

    @Test
    void secretsNeverReachTheLog() throws Exception {
        writeConfig("survival", proxy("proxy-1", key1 + "!!"));
        assertFalse(core.reload().isEmpty());
        core.verify(id, List.of(token("proxy-1", key1, "survival")));
        for (String line : logged) assertFalse(line.contains(key1), line);
    }

    @Test
    void concurrentReplayAcceptsExactlyOnce() throws Exception {
        String t = token("proxy-1", key1, "survival");
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<Outcome>> results = new ArrayList<>();
        for (int i = 0; i < 64; i++) results.add(pool.submit(() -> core.verify(id, List.of(t))));
        int ok = 0;
        for (Future<Outcome> f : results) if (f.get() == Outcome.OK) ok++;
        pool.shutdown();
        assertEquals(1, ok);
    }
}
