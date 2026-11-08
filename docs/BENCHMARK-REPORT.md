# Benchmark report: NetworkLock 1.0.0 vs BungeeGuard 1.4.0 vs NetworkLock 1.0.1

Measured 2026-11-08/09 on one Windows 11 PC: Intel i7-14700KF (20 cores, 28 threads), 32 GB RAM, Microsoft Build of OpenJDK 21.0.10. All servers, proxies and load clients ran on this one machine over localhost.

**Short version:** at any login rate a single game server can actually handle, all four setups log players in equally fast (about 76–80 ms median, nearly all of it Paper's own join work). The authentication itself costs a few microseconds in both plugins: under 0.01% of a login. NetworkLock rejects forged logins about 2.5× faster than BungeeGuard; BungeeGuard has no per-login work on the proxy. There is no speed reason to prefer either one.

## 1. What was compared

| Name | Game server side | Proxy side | Velocity forwarding |
|---|---|---|---|
| baseline | nothing | nothing | `legacy` | 
| bungeeguard | BungeeGuard 1.4.0 (release jar), static 64-character token | Velocity's built-in BungeeGuard support | `bungeeguard` |
| nl100 | NetworkLock 1.0.0 | NetworkLock 1.0.0 | `legacy` |
| nl101 | NetworkLock 1.0.1 | NetworkLock 1.0.1 | `legacy` |

The baseline is **not secure**: it shows the cost of Paper + Velocity alone. Each plugin was set up the way its own documentation says. Same Paper 1.21.11 build 132 (1 GB heap), Velocity 3.5.1 build 615 (512 MB), same JVM flags, same flat pre-generated world, same workloads. NetworkLock's update check and statistics were off (no network calls during the run); its per-IP login limiter stayed on with a limit high enough for one-IP load testing, so its bookkeeping is still measured.

## 2. Authentication cost (JMH micro-benchmarks)

JMH 1.37, 3 forks × 10 one-second measurements after 5 warm-up seconds, 2 GB heap. Each NetworkLock verify uses a fresh, never-used signature (1,000,000 pre-signed per iteration) so the replay check runs every time. BungeeGuard is its real `BungeeCordHandshake.decodeAndVerify` from the release jar, given a realistic handshake with a skin property. Lower is better; ± is JMH's 99.9% interval.

| Operation | BungeeGuard 1.4.0 | NL 1.0.0 | NL 1.0.1 |
|---|---|---|---|
| Proxy: create the credential | none (static token) | 1,917 ± 73 ns | **1,596 ± 28 ns** |
| Server: reject a forged login | 3,045 – 3,401 ns | 1,634 ± 44 ns | **1,218 ± 33 ns** |
| Server: accept a valid login | 4,424 – 4,864 ns | 4,916 – 6,439 ns | 5,965 – 6,214 ns |

- 1.0.1 creates credentials 17% faster and rejects forged ones 25% faster than 1.0.0 (per-thread reuse of the `Mac` and `MessageDigest`; the bytes signed are proven identical by a unit test).
- **Valid-login verify is equal between 1.0.0 and 1.0.1.** The first run showed 4,916 vs 5,951 ns; a re-run with 6 forks per build, alternated (1.0.0, 1.0.1, 1.0.0, 1.0.1), gave 6,439 / 6,081 ns for 1.0.0 and 6,214 / 5,965 ns for 1.0.1. This benchmark is memory-bound (it walks a ~100 MB token pool and a replay map that grows to ~200,000 entries per second) and moves by about 1 µs between sessions, so only same-session alternated runs are compared. The stack profiler found no 1.0.1 code in the difference; the visible hotspot is the replay-cache cleanup, identical in both versions and only significant at the ~200,000 logins/s this benchmark produces.
- BungeeGuard's ranges are its three runs (one per NetworkLock build); its code was identical each time.
- Allocation per operation is not reported: the JMH GC profiler counted the per-iteration token-pool setup into it (even BungeeGuard's unchanged code read 26–38 KB/op), so those figures are invalid.

## 3. End to end: real logins through Velocity into Paper

`it/bench-e2e.sh`, client `it/Load.java`: each login is a real Minecraft 1.21.11 login (protocol 774) through Velocity, timed from TCP connect until Paper finishes the configuration phase. 3 repetitions; the order of the four setups changes every repetition (baseline/bungeeguard/nl100/nl101, then nl101/nl100/bungeeguard/baseline, then bungeeguard/nl101/baseline/nl100). Each setup gets fresh servers and a 30-login warm-up. Raw data: `it/work/e2e/results.csv`; summary: `python it/bench-summary.py`.

### Median login time (ms), mean of 3 repetitions

| Workload | baseline | BungeeGuard | NL 1.0.0 | NL 1.0.1 |
|---|---|---|---|---|
| 1 login/s, 10 s | 82.1 | 104.2 | 116.2 | 75.4 |
| 5 logins/s | 92.7 | 79.7 | 82.2 | 83.6 |
| 10 logins/s | 74.5 | 81.6 | 75.6 | 74.0 |
| 25 logins/s | 76.3 | 77.4 | 76.8 | 77.0 |
| 50 logins/s | 76.3 | 76.4 | 75.6 | 78.5 |
| 100 logins/s | 89.6 | 97.5 | 98.6 | 109.3 * |
| Reconnect storm (500 logins at 50/s, 10 names) | 79.0 | 80.1 | 78.1 | 79.2 |
| Mixed: 50 real/s + 50 forged/s direct to Paper | 90.6 † | 77.4 | 77.9 | 79.6 |
| Sustained: 6,000 logins at 50/s (1 repetition) | 79.3 | 76.6 | 78.8 | 79.6 |

The 1–5 logins/s rows have only 10–50 logins per repetition, so single slow logins (JIT, first chunk sends) move them a lot; their spread between repetitions is 10–50 ms.

\* See section 4.  † In the baseline the 50 forged logins/s get in and join the world too, which slows everyone down.

p95 and p99 follow the same pattern: at 25–50 logins/s every setup has p95 98–103 ms and p99 104–152 ms.

### Security under load

| Mixed workload, 3 × 500 forged direct logins | baseline | BungeeGuard | NL 1.0.0 | NL 1.0.1 |
|---|---|---|---|---|
| Forged logins that got in | **1,500** | 0 | 0 | 0 |
| Real logins accepted | 1,499 / 1,500 | 1,500 / 1,500 | 1,499 / 1,500 | 1,499 / 1,500 |

The single missing real login in three setups was a Windows "address already in use" error between Velocity and Paper (local port exhaustion), not a refusal by any plugin; it happened in the baseline too.

Every failed valid login in the whole run (outside the saturation rows below) was checked by its recorded reason: all are Windows port exhaustion (`BindException`, or Velocity "Unable to connect you to lobby" for the same cause) or, in the reconnect storm, Velocity's own "already connected" check for a reused name. **Neither plugin refused a single valid login**, and NetworkLock never accepted a signature twice.

### Where the test machine ran out

| Workload | What happened (all four setups alike) |
|---|---|
| 250 logins/s | All logins accepted, but median latency jumps to ~5,100 ms in every setup |
| 500 logins/s | ~10,400–11,000 of 15,000 accepted, the rest timed out or failed, median ~5,100 ms |
| Bursts of 200 and 500 at once | 10–60% of logins fail in every setup, including the baseline |

The baseline, with no plugin at all, breaks at exactly the same point. One Paper server, Velocity and up to 512 load-client threads share one PC, and Paper joins players on its main thread. These rows measure the machine, not the plugins, and are not used to rank anything. A 200-player network sees a few logins per second at peak, far below this.

### CPU and GC

CPU time of the Paper and Velocity processes was read before and after every workload. Per login it is 7–15 ms of Paper CPU at 25–100 logins/s in every setup, mostly world join work; the differences between setups are within the run-to-run spread (for example rate50 Paper CPU per repetition: BungeeGuard 4.1–5.8 s, NL 1.0.0 4.5–5.5 s, NL 1.0.1 4.4–8.4 s). Paper ran about 6 young-generation GCs per workload in every setup (baseline 6.3, BungeeGuard 6.0, NL 1.0.0 5.9, NL 1.0.1 6.0). GC pause time was not captured: `jstat` printed decimal commas on this Windows locale and the script read them as zero (fixed in the script for future runs).

## 4. The 1.0.1 CPU and 100 logins/s question

In the main run, 1.0.1's CPU per login and its median at 100 logins/s looked higher than 1.0.0's. Most of the CPU difference came from one run (repetition 2), which started seconds after the previous servers were force-stopped; it was high on every workload, including ones where 1.0.1 was lowest in the other repetitions. To check, a separate clean A/B run alternated 1.0.0 and 1.0.1 three times each on an idle machine:

Order nl100, nl101, nl101, nl100, nl100, nl101; fresh servers each time; GC time captured correctly this time.

| Workload | Median ms, NL 1.0.0 (3 runs) | Median ms, NL 1.0.1 (3 runs) | Paper CPU per login, 1.0.0 / 1.0.1 | Paper GC ms, 1.0.0 / 1.0.1 |
|---|---|---|---|---|
| 50 logins/s | 79.5, 79.5, 79.7 | 79.5, 79.4, 79.5 | 20.5 / 18.7 ms | 22 / 11 |
| 100 logins/s | 94.4, 94.4, 94.6 | 125.2, 94.3, 92.9 | 12.5 / 12.2 ms | 31 / 35 |
| Reconnect storm | 79.4, 79.5, 79.7 | 80.2, 79.6, 79.7 | 14.7 / 11.6 ms | 22 / 8 |
| Mixed (with attack) | 79.5, 79.6, 79.3 | 79.2, 79.8, 79.8 | 17.4 / 16.7 ms | 20 / 12 |

Result: **no CPU or GC regression** — 1.0.1 used the same or less CPU per login and less GC time on every workload. At 50 logins/s and below the two are identical.

**Open point, reported as found:** at 100 logins/s, 1.0.1's median was higher than 1.0.0's in 4 of the 6 runs across both experiments (mean 106.7 vs 96.6 ms; the other 2 runs were equal or lower). CPU and GC are not higher in those runs, and thread dumps taken under 100 logins/s never showed NetworkLock code on any Paper or Velocity thread, so the cause was not found. 100 logins/s on one game server is close to where this machine saturates (250/s breaks every setup), which makes latency there sensitive to small timing shifts. It does not affect realistic networks: a 200-player network peaks at a few logins per second, where every setup measures the same.

## 5. Problems found in the benchmark itself (and fixed before measuring)

A short smoke run showed NetworkLock 2–10× slower than BungeeGuard. Thread dumps proved NetworkLock was never on the stack; the harness was wrong:

1. Paper asked Mojang's session server about every synthetic player (no skin data + `proxies.bungee-cord.online-mode: true`), so the run measured Mojang's rate limiting, and whichever setup ran first looked worst. Turned off for every setup; real players arrive with skin data and don't trigger this.
2. Velocity's `login-ratelimit` was written outside `[advanced]` and ignored: its 3-second per-IP limit refused 96% of logins in every setup.
3. Bukkit's 4-second per-IP connection throttle was on. Turned off for every setup.
4. Decimal commas from the Windows locale broke the results file (fixed with `Locale.ROOT`).

All published numbers were measured after these fixes.

## 6. Optimizations in 1.0.1

Only one change, chosen from profiling 1.0.0: looking up the HMAC/SHA-256 providers and re-initialising the key dominated the cost of creating and checking a signature. 1.0.1 keeps one `Mac` and one `MessageDigest` per thread, re-keys only when the key changes, and skips copies and sorting where they are not needed. Result: −17% create, −25% reject, verify unchanged within noise. No security check was removed or reordered; `ForwardingTokenTest.optimisedMacIsByteIdenticalToTheOriginalAlgorithm` proves the signed bytes are the same as 1.0.0's.

## 7. Limits of this benchmark

- One machine; client, proxy and server compete for the same CPU. A real network has them on separate hosts.
- Offline synthetic players, no skins, flat world, players disconnect right after joining.
- Velocity only (no BungeeCord/Waterfall timings).
- Sustained run is 2 minutes, not hours.
- Absolute latencies include Paper's own join and configuration phase (~75 ms here) and will differ on other hardware; the comparison between setups is the result.
