# Benchmark report: NetworkLock 1.0.0 vs BungeeGuard 1.4.0 vs NetworkLock 1.0.1

Measured 2026-11-08/09 on one Windows 11 PC: Intel i7-14700KF (20 cores, 28 threads), 32 GB RAM, Microsoft Build of OpenJDK 21.0.10. All servers, proxies and load clients ran on this one machine over localhost.

**Short version:** at any login rate a single game server can actually handle, all four setups log players in equally fast (about 76–80 ms median, nearly all of it Paper's own join work). The earlier 1.0.1 slowdown at 100 logins/s was traced to Paper's own join limit (5 per tick = 100/s) and hits 1.0.0 the same way (section 4). The authentication itself costs a few microseconds in both plugins: under 0.01% of a login. NetworkLock rejects forged logins about 2.5× faster than BungeeGuard; BungeeGuard has no per-login work on the proxy. There is no speed reason to prefer either one.

## 1. What was compared

| Name | Game server side | Proxy side | Velocity forwarding |
|---|---|---|---|
| baseline | nothing | nothing | `legacy` | 
| bungeeguard | BungeeGuard 1.4.0 (release jar), static 64-character token | Velocity's built-in BungeeGuard support | `bungeeguard` |
| nl100 | NetworkLock 1.0.0 | NetworkLock 1.0.0 | `legacy` |
| nl101 | NetworkLock 1.0.1 | NetworkLock 1.0.1 | `legacy` |

Builds: sections 2 and 3 measured the first 1.0.1 build (`6f6daa7`). The final 1.0.1 build adds the second-pass fixes, including two on the login path (the per-thread crypto cache now holds plain JDK objects, still signing the same bytes, and an "already connected" check on the proxy); it was measured against `6f6daa7` and 1.0.0 in the section 4 A/B runs (same speed within noise) and ran the multi-hour soak test in [TEST-REPORT.md](TEST-REPORT.md).

The baseline is **not secure**: it shows the cost of Paper + Velocity alone. Each plugin was set up the way its own documentation says. Same Paper 1.21.11 build 132 (1 GB heap), Velocity 3.5.1 build 615 (512 MB), same JVM flags, same flat pre-generated world, same workloads. NetworkLock's update check and statistics were off (no network calls during the run); its per-IP login limiter stayed on with a limit high enough for one-IP load testing, so its bookkeeping is still measured.

## 2. Authentication cost (JMH micro-benchmarks)

JMH 1.37, 3 forks × 10 one-second measurements after 5 warm-up seconds, 2 GB heap. Each NetworkLock verify uses a fresh, never-used signature (1,000,000 pre-signed per iteration) so the replay check runs every time. BungeeGuard is its real `BungeeCordHandshake.decodeAndVerify` from the release jar, given a realistic handshake with a skin property. Lower is better; ± is JMH's 99.9% interval.

| Operation | BungeeGuard 1.4.0 | NL 1.0.0 | NL 1.0.1 |
|---|---|---|---|
| Proxy: create the credential | none (static token) | 1,917 ± 73 ns | **1,596 ± 28 ns** |
| Server: reject a forged login | 3,045 – 3,401 ns | 1,634 ± 44 ns | **1,218 ± 33 ns** |
| Server: accept a valid login | 4,424 – 4,864 ns | 4,916 – 6,439 ns | 5,965 – 6,214 ns |

- The first 1.0.1 build created credentials 17% faster and rejected forged ones 25% faster than 1.0.0 (the final build is about 1% slower than that, see below) (per-thread reuse of the `Mac` and `MessageDigest`; the bytes signed are proven identical by a unit test).
- **Valid-login verify is equal between 1.0.0 and 1.0.1.** The first run showed 4,916 vs 5,951 ns; a re-run with 6 forks per build, alternated (1.0.0, 1.0.1, 1.0.0, 1.0.1), gave 6,439 / 6,081 ns for 1.0.0 and 6,214 / 5,965 ns for 1.0.1. This benchmark is memory-bound (it walks a ~100 MB token pool and a replay map that grows to ~200,000 entries per second) and moves by about 1 µs between sessions, so only same-session alternated runs are compared. The stack profiler found no 1.0.1 code in the difference; the visible hotspot is the replay-cache cleanup, identical in both versions and only significant at the ~200,000 logins/s this benchmark produces.
- BungeeGuard's ranges are its three runs (one per NetworkLock build); its code was identical each time.
- **Final 1.0.1 build vs the first 1.0.1 build (`6f6daa7`)**, same session, alternated final / first / final / first, 3 forks each: create 1,250 and 1,245 vs 1,232 and 1,233 ns (+1.2%); reject forged 1,063 and 1,072 vs 1,060 and 1,056 ns (+0.8%); accept valid 3,885 and 3,869 vs 4,215 and 4,075 ns (within noise). The second-pass fixes cost 10–15 ns per signature (the per-thread crypto cache now holds plain JDK objects so plugin reloads cannot leak the classloader) and nothing measurable on the accept path.
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

### Where a single server stops keeping up

| Workload | What happened (all four setups alike) |
|---|---|
| 250 logins/s | All logins accepted, but median latency jumps to ~5,100 ms in every setup |
| 500 logins/s | ~10,400–11,000 of 15,000 accepted, the rest timed out or failed, median ~5,100 ms |
| Bursts of 200 and 500 at once | 10–60% of logins fail in every setup, including the baseline |

The baseline, with no plugin at all, breaks at exactly the same point. Section 4 shows why: Paper admits at most `max-joins-per-tick` (default 5) new players per tick, which is 100 per second; above that the join queue only grows. With that limit raised, 250 logins/s ran at a 77–83 ms median in both NetworkLock versions. What remains then is the test PC running out of local TCP ports (Windows keeps closed connections in TIME_WAIT; every login uses two connections). These rows measure Paper's default and the PC, not the plugins, and are not used to rank anything. A 200-player network sees a few logins per second at peak.

### CPU and GC

CPU time of the Paper and Velocity processes was read before and after every workload. Per login it is 7–15 ms of Paper CPU at 25–100 logins/s in every setup, mostly world join work; the differences between setups are within the run-to-run spread (for example rate50 Paper CPU per repetition: BungeeGuard 4.1–5.8 s, NL 1.0.0 4.5–5.5 s, NL 1.0.1 4.4–8.4 s). Paper ran about 6 young-generation GCs per workload in every setup (baseline 6.3, BungeeGuard 6.0, NL 1.0.0 5.9, NL 1.0.1 6.0). GC pause time was not captured: `jstat` printed decimal commas on this Windows locale and the script read them as zero (fixed in the script for future runs).

## 4. The 100 logins/s anomaly: cause found (Paper's join limit, not NetworkLock)

**Symptom.** In the main run, 1.0.1's median at 100 logins/s was 109 ms against 99 ms for 1.0.0, and in the first clean A/B 1.0.1 was higher in 4 of 6 runs. Taken alone, that looked like a 1.0.1 regression.

**What was ruled out.**

- *NetworkLock code on the login path:* thread dumps of Paper and Velocity taken every 2 s under 100 logins/s never showed a NetworkLock frame. The JMH verify cost is equal between the versions (section 2).
- *CPU and GC:* across all A/B runs, 1.0.1 used the same or less CPU per login than 1.0.0 (for example 5.7 vs 6.5 ms Paper and 3.6 vs 4.3 ms Velocity at 100/s) and the same or less GC time.
- *The new Velocity `PostLoginEvent` listener (staff update notices):* a test build with only that listener removed (7 instead of 8 annotated handlers, verified in the bytecode) behaved like normal 1.0.1 (89.0, 97.4, 88.2 ms).
- *Running NetworkLock's Velocity handlers inline (`async = false`):* tried as an optimization, measured against the published 1.0.1 and 1.0.0 in a 12-run A/B, no gain (new build 95.3 ± 6.7 ms, 1.0.0 95.6 ± 9.6 ms at 100/s; at 50/s the 2–3 ms differences changed direction between experiments). Reverted.
- *Paper's main thread:* sampling Paper's tick time (`mspt`) during the runs showed 5–9 ms average ticks at 100 logins/s, far below the 50 ms budget.

**Every 100 logins/s run, all experiments** (median ms, fresh servers each run, builds alternated):

| Experiment | NL 1.0.0 | NL 1.0.1 |
|---|---|---|
| Clean A/B, 30 s runs | 93.2, **110.9**, 89.9 | 89.9, 89.4, 87.6 |
| 12-run A/B (published 1.0.1 vs new build vs 1.0.0) | 97.0, 89.3, **108.7**, 87.3 | 98.6, 92.4, 87.6, 102.7 (new build); 102.4, 95.2, 91.7, 93.9 (published) |
| Tick-time sampling | 88.7, 97.5, 89.8, 88.9 | 87.9, 91.6, **124.7**, 93.5 |

The slow runs (100–125 ms) hit both versions, randomly.

**Cause.** Paper's `paper-global.yml` has `misc.max-joins-per-tick: 5`: at most 5 new players are let into the world per tick, which is exactly 100 per second at 20 ticks per second. A queue fed at exactly its service rate is unstable: whenever arrivals bunch up, logins wait one or more extra ticks, and how often that happens differs from run to run. Above 100 per second the queue only grows, which is the ~5,100 ms wall at 250 and 500 logins/s in section 3.

**Proof.** The same A/B with `max-joins-per-tick: 1000` for both builds (3 runs each, alternated):

| Median ms | NL 1.0.0 | NL 1.0.1 |
|---|---|---|
| 100 logins/s | 77.5, 80.5, 75.7 | 76.2, 78.8, 80.3 |
| 250 logins/s | 76.8, 79.0, 79.1 | 78.7, 82.9, 79.2 |

The slow mode is gone for both, and 250/s no longer collapses. The failed logins in that run are all Windows port exhaustion (`BindException`, or Velocity unable to open its connection to Paper for the same reason); none is a refusal by NetworkLock.

**Conclusion.** The 100 logins/s difference was not caused by NetworkLock and is not a 1.0.1 regression; nothing in NetworkLock was changed for it. For server owners: a single Paper server admits at most 100 new players per second by default, whatever plugins it runs.

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
