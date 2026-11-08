package dev.korinetwork.networklock.bench;

import dev.korinetwork.networklock.core.BackendCore;
import dev.korinetwork.networklock.core.ForwardingToken;
import dev.korinetwork.networklock.core.Keys;
import dev.korinetwork.networklock.core.Log;
import dev.korinetwork.networklock.core.PlayerIdentity;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Per-login cost of each plugin's own work on the game server, plus NetworkLock's proxy-side cost.
 * NetworkLock: {@link BackendCore#verify} (token parse, HMAC, server/time checks, replay cache).
 * BungeeGuard 1.4.0: its real {@code BungeeCordHandshake.decodeAndVerify} from the release jar
 * (handshake split, JSON parse of the forwarded properties, token lookup, JSON re-serialise).
 * Both get the same realistic forwarded profile: one Mojang "textures" property.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 3, jvmArgs = {"-Xms2g", "-Xmx2g"})
public class AuthBench {

    /** More unique tokens than one 1-second iteration can use, so every verify is a first use. */
    private static final int POOL = 1_000_000;

    private static final Log QUIET = new Log() {
        public void info(String m) {}
        public void warn(String m) {}
        public void error(String m) {}
    };

    private Path dir;
    private byte[] key;
    private PlayerIdentity id;
    private BackendCore core;
    private String[] tokens;
    private int next;
    private String forged;

    private MethodHandle bgVerify;
    private Object bgTokenStore;
    private String bgHandshake;
    private String bgHandshakeWrongToken;

    @Setup(Level.Trial)
    public void trial() throws Throwable {
        Random r = new Random(42);
        byte[] tex = new byte[300];
        byte[] sig = new byte[512];
        r.nextBytes(tex);
        r.nextBytes(sig);
        String textures = Base64.getEncoder().encodeToString(tex);
        String signature = Base64.getEncoder().encodeToString(sig);
        UUID uuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        id = new PlayerIdentity(uuid, "Notch", "203.0.113.9",
                Collections.singletonList(new PlayerIdentity.Property("textures", textures, signature)));

        String keyText = Keys.generate();
        key = Keys.decode(keyText);
        dir = Files.createTempDirectory("nlbench");
        Files.write(dir.resolve("config.yml"), ("network:\n  server-id: \"lobby\"\nauthentication:\n  trusted-proxies:\n"
                + "    - id: \"proxy-1\"\n      keys: [\"" + keyText + "\"]\n  replay-cache-max-entries: 10000000\n"
                + "logging:\n  security-events: false\nupdates:\n  check: false\nmetrics:\n  enabled: false\n")
                .getBytes(StandardCharsets.UTF_8));
        forged = ForwardingToken.mint("proxy-1", Keys.decode(Keys.generate()), "lobby", id, System.currentTimeMillis());

        // BungeeGuard 1.4.0, loaded from its release jar on the classpath
        String bgToken = "Xq3TzS0dZ9bL7mWpR2vN8kYcHf4uAeJ1oGtKiE6sQwD5xVnB0lMrUyCjP3hOgI9a"; // 64 chars like its generator
        Class<?> backendIface = Class.forName("me.lucko.bungeeguard.backend.BungeeGuardBackend");
        Object backend = Proxy.newProxyInstance(backendIface.getClassLoader(), new Class<?>[]{backendIface}, (p, m, a) -> {
            switch (m.getName()) {
                case "getTokens": return Collections.singletonList(bgToken);
                case "getMessage": return "";
                default: return null;
            }
        });
        Class<?> storeClass = Class.forName("me.lucko.bungeeguard.backend.TokenStore");
        bgTokenStore = storeClass.getConstructor(backendIface).newInstance(backend);
        storeClass.getMethod("load").invoke(bgTokenStore);
        Class<?> handshake = Class.forName("me.lucko.bungeeguard.spigot.BungeeCordHandshake");
        bgVerify = MethodHandles.publicLookup().findStatic(handshake, "decodeAndVerify",
                MethodType.methodType(handshake, String.class, storeClass));
        String props = "[{\"name\":\"textures\",\"value\":\"" + textures + "\",\"signature\":\"" + signature + "\"},"
                + "{\"name\":\"bungeeguard-token\",\"value\":\"%s\",\"signature\":\"\"}]";
        String base = "localhost\0" + "203.0.113.9\0" + uuid.toString().replace("-", "") + "\0";
        bgHandshake = base + String.format(props, bgToken);
        bgHandshakeWrongToken = base + String.format(props, "wrong-token-wrong-token-wrong-token-wrong-token-wrong-tok");
    }

    @Setup(Level.Iteration)
    public void iteration() {
        core = new BackendCore(dir, QUIET, System::currentTimeMillis, Collections.<String, String>emptyMap());
        if (!core.start()) throw new IllegalStateException("benchmark config did not load");
        tokens = new String[POOL];
        long now = System.currentTimeMillis();
        for (int i = 0; i < POOL; i++) tokens[i] = ForwardingToken.mint("proxy-1", key, "lobby", id, now);
        next = 0;
    }

    /** Game server: verify a valid, never-used token (the normal login path). */
    @Benchmark
    public Object networkLockVerify() {
        return core.verify(id, Collections.singletonList(tokens[next++ % POOL]));
    }

    /** Game server: a forged token signed with the wrong key (attacker path, full HMAC computed). */
    @Benchmark
    public Object networkLockRejectForged() {
        return core.verify(id, Collections.singletonList(forged));
    }

    /** Proxy: create one token. */
    @Benchmark
    public String networkLockMint() {
        return ForwardingToken.mint("proxy-1", key, "lobby", id, System.currentTimeMillis());
    }

    /** Game server: BungeeGuard accepting a correct token. */
    @Benchmark
    public Object bungeeGuardVerify() throws Throwable {
        return bgVerify.invoke(bgHandshake, bgTokenStore);
    }

    /** Game server: BungeeGuard rejecting a wrong token. */
    @Benchmark
    public Object bungeeGuardRejectWrongToken() throws Throwable {
        return bgVerify.invoke(bgHandshakeWrongToken, bgTokenStore);
    }

    static List<String> unused() {
        return Collections.emptyList();
    }

    static Map<String, String> none() {
        return Collections.emptyMap();
    }
}
