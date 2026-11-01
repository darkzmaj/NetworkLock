import dev.korinetwork.networklock.core.BackendCore;
import dev.korinetwork.networklock.core.ConnectionLimiter;
import dev.korinetwork.networklock.core.ForwardingToken;
import dev.korinetwork.networklock.core.Keys;
import dev.korinetwork.networklock.core.Log;
import dev.korinetwork.networklock.core.Outcome;
import dev.korinetwork.networklock.core.PlayerIdentity;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Throughput of the hot paths: proxy mint, backend verify (MAC + replay cache) and the
 * connection limiter. Not JMH: numbers are indicative, run with a warm-up, single JVM.
 *
 * java -cp networklock-core/build/classes/java/main;networklock-core/build/resources/main;SNAKEYAML it/Bench.java
 */
public final class Bench {

    public static void main(String[] args) throws Exception {
        String key = Keys.generate();
        byte[] raw = Keys.decode(key);
        Path dir = Files.createTempDirectory("nlbench");
        Files.writeString(dir.resolve("config.yml"), "network:\n  server-id: lobby\nauthentication:\n  trusted-proxies:\n"
                + "    - id: p\n      keys: [\"" + key + "\"]\n  replay-cache-max-entries: 10000000\nlogging:\n  security-events: false\n");
        Log quiet = new Log() {
            public void info(String m) {}
            public void warn(String m) {}
            public void error(String m) {}
        };
        BackendCore core = new BackendCore(dir, quiet, System::currentTimeMillis, Map.of());
        if (!core.start()) throw new IllegalStateException("config");
        List<PlayerIdentity.Property> textures = List.of(new PlayerIdentity.Property("textures", "x".repeat(400), "y".repeat(684)));

        int n = 200_000;
        for (int round = 0; round < 2; round++) { // round 0 is warm-up
            PlayerIdentity[] ids = new PlayerIdentity[n];
            String[] tokens = new String[n];
            for (int i = 0; i < n; i++) ids[i] = new PlayerIdentity(UUID.randomUUID(), "Player" + i, "203.0.113.9", textures);
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) tokens[i] = ForwardingToken.mint("p", raw, "lobby", ids[i], System.currentTimeMillis());
            long t1 = System.nanoTime();
            int ok = 0;
            for (int i = 0; i < n; i++) if (core.verify(ids[i], List.of(tokens[i])) == Outcome.OK) ok++;
            long t2 = System.nanoTime();
            if (round == 1) {
                System.out.printf("mint   1 thread : %,.0f ops/s (%.1f us/op)%n", n / ((t1 - t0) / 1e9), (t1 - t0) / 1e3 / n);
                System.out.printf("verify 1 thread : %,.0f ops/s (%.1f us/op), accepted %d/%d%n", n / ((t2 - t1) / 1e9), (t2 - t1) / 1e3 / n, ok, n);
            }
        }

        int threads = Runtime.getRuntime().availableProcessors();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger accepted = new AtomicInteger();
        int perThread = 50_000;
        long t3 = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                for (int i = 0; i < perThread; i++) {
                    PlayerIdentity id = new PlayerIdentity(UUID.randomUUID(), "P", "203.0.113.9", textures);
                    String token = ForwardingToken.mint("p", raw, "lobby", id, System.currentTimeMillis());
                    if (core.verify(id, List.of(token)) == Outcome.OK) accepted.incrementAndGet();
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.MINUTES);
        long t4 = System.nanoTime();
        System.out.printf("mint+verify %d threads: %,.0f logins/s, accepted %d/%d%n", threads,
                threads * (double) perThread / ((t4 - t3) / 1e9), accepted.get(), threads * perThread);

        ConnectionLimiter limiter = new ConnectionLimiter(new ConnectionLimiter.Settings(true, 10, 10, true, 5, 60, 64,
                List.of(), 100_000, 200));
        InetAddress[] addrs = new InetAddress[65_536];
        for (int i = 0; i < addrs.length; i++) addrs[i] = InetAddress.getByAddress(new byte[]{10, 0, (byte) (i >> 8), (byte) i});
        long t5 = System.nanoTime();
        for (int i = 0; i < 2_000_000; i++) limiter.check(addrs[i & 0xFFFF], System.currentTimeMillis());
        long t6 = System.nanoTime();
        System.out.printf("limiter 1 thread: %,.0f checks/s, tracked %d%n", 2_000_000 / ((t6 - t5) / 1e9), limiter.tracked());

        Runtime rt = Runtime.getRuntime();
        System.gc();
        System.out.printf("heap used after run (replay cache holds %s entries): %d MB%n",
                core.statusLines().stream().filter(l -> l.startsWith("Replay")).findFirst().orElse("?"),
                (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
    }
}
