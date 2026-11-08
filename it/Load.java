import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * End-to-end login load generator for the benchmark (uses Probe's real Minecraft login).
 * Each login is timed from TCP connect until the backend finishes the configuration phase
 * (accepted) or the connection is refused.
 *
 * <pre>
 * java -Dproto=774 -cp probe-classes Load HOST PORT MODE COUNT RATE TAG OUT.csv [NAMES]
 *   MODE rate    COUNT logins started evenly at RATE per second
 *   MODE burst   COUNT logins started at the same moment (RATE ignored)
 *   MODE forged  like rate, but each login is a forged BungeeCord handshake with no credential
 *                (an attacker connecting straight to the game server)
 *   NAMES        optional: reuse this many player names (a reconnect storm); default all unique
 * </pre>
 * Prints one summary line; writes one CSV row per login.
 */
public final class Load {

    private static final int MAX_THREADS = 512;

    public static void main(String[] a) throws Exception {
        String host = a[0];
        int port = Integer.parseInt(a[1]);
        String mode = a[2];
        int count = Integer.parseInt(a[3]);
        double rate = Double.parseDouble(a[4]);
        String tag = a[5];
        String out = a[6];
        int names = a.length > 7 ? Integer.parseInt(a[7]) : count;
        int protocol = Integer.getInteger("proto");

        long[] startNs = new long[count];
        long[] latNs = new long[count];
        String[] result = new String[count];
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(count, MAX_THREADS));
        CountDownLatch done = new CountDownLatch(count);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        long t0 = System.nanoTime();
        for (int i = 0; i < count; i++) {
            final int n = i;
            if (mode.equals("rate") || mode.equals("forged")) {
                long due = t0 + (long) (n * 1e9 / rate);
                long wait = due - System.nanoTime();
                if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait);
            }
            pool.execute(() -> {
                try {
                    if (mode.equals("burst")) gate.await();
                    int now = inFlight.incrementAndGet();
                    peak.accumulateAndGet(now, Math::max);
                    String name = (tag + (n % names)).substring(0, Math.min(16, (tag + (n % names)).length()));
                    long s = System.nanoTime();
                    startNs[n] = s - t0;
                    String r;
                    try {
                        if (mode.equals("forged")) {
                            UUID u = UUID.nameUUIDFromBytes(name.getBytes());
                            r = Probe.login(host, port, protocol, name, Probe.bungeeHost(host, "127.0.0.1", u, "[]"), u);
                        } else {
                            r = Probe.login(host, port, protocol, name, host, null);
                        }
                    } catch (Exception e) {
                        r = "ERROR " + e.getClass().getSimpleName();
                    }
                    latNs[n] = System.nanoTime() - s;
                    result[n] = r;
                    inFlight.decrementAndGet();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        gate.countDown();
        boolean finished = done.await(10, TimeUnit.MINUTES);
        long wall = System.nanoTime() - t0;
        pool.shutdownNow();

        List<Double> ok = new ArrayList<>();
        int accepted = 0, rejected = 0, timeout = 0, error = 0;
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(Paths.get(out)))) {
            w.println("i,start_ms,latency_ms,result,detail");
            for (int i = 0; i < count; i++) {
                String r = result[i] == null ? "UNFINISHED" : result[i];
                String kind = r.startsWith("ACCEPTED") ? "accepted" : r.startsWith("REJECTED") ? "rejected"
                        : r.startsWith("TIMEOUT") || r.equals("UNFINISHED") ? "timeout" : "error";
                double ms = latNs[i] / 1e6;
                switch (kind) {
                    case "accepted": accepted++; ok.add(ms); break;
                    case "rejected": rejected++; break;
                    case "timeout": timeout++; break;
                    default: error++;
                }
                w.printf(Locale.ROOT, "%d,%.3f,%.3f,%s,%s%n", i, startNs[i] / 1e6, ms, kind, r.replaceAll("[,\r\n]", " "));
            }
        }
        Collections.sort(ok);
        System.out.printf(Locale.ROOT, "mode=%s count=%d rate=%s accepted=%d rejected=%d timeout=%d error=%d finished=%b wall_s=%.2f"
                        + " throughput=%.1f/s peak_inflight=%d mean_ms=%.2f p50_ms=%.2f p95_ms=%.2f p99_ms=%.2f max_ms=%.2f%n",
                mode, count, mode.equals("burst") ? "-" : String.valueOf(rate), accepted, rejected, timeout, error, finished,
                wall / 1e9, count / (wall / 1e9), peak.get(), mean(ok), pct(ok, 50), pct(ok, 95), pct(ok, 99),
                ok.isEmpty() ? 0 : ok.get(ok.size() - 1));
    }

    static double mean(List<Double> v) {
        double s = 0;
        for (double d : v) s += d;
        return v.isEmpty() ? 0 : s / v.size();
    }

    static double pct(List<Double> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }
}
