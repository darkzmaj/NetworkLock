# Test report: NetworkLock 1.0.1

Final run 2026-11-09 on the release jar (SHA-256 in [RELEASE-NOTES-1.0.1.md](../RELEASE-NOTES-1.0.1.md)), Windows 11, Intel i7-14700KF (20 cores, 28 threads), 32 GB RAM, with Java 8, 17, 21 and 25. GitHub Actions runs the unit tests and the Paper integration on Linux for every push. Everything ran against localhost only.

## Unit tests

55 tests, all passing on a clean build (`./gradlew clean test shadowJar`; results checked to be fresh, not left over from an earlier build).

| Class | Tests | Covers |
|---|---|---|
| `ForwardingTokenTest` | 8 | every header byte authenticated, UUID/name/IP/properties tampering caught, malformed input, fresh nonces, property order, and the optimised MAC byte-identical to the 1.0.0 algorithm over 300 random identities |
| `BackendCoreTest` | 14 | valid signature accepted once, expiry and clock skew, wrong server, forged identity, cross-proxy misuse, key rotation and revocation, 64-thread replay race (exactly one accepted), broken or missing config locks down, failed reload keeps the old config, secrets never logged |
| `SupportTest` | 23 | replay memory bounded and expiring, replay still blocked after the lifetime is raised (NL-01), attacker names cannot forge log lines (NL-02), login limiter (burst, refill, blocks, IPv6 /64 grouping, exemptions, memory cap), messages, commands and permissions, env/file keys, usage statistics carry only anonymous data, end-to-end proxy to backend |
| `ReviewFixesTest` | 10 | second-pass fixes: limiter sweeps at most once per second when full (NL-11), replay memory survives a resizing reload (NL-12), update check off without a config (NL-13), statistics ID not derivable from public fingerprints (NL-14), `server-id` validation, duplicate-key warning, unsignable proxy server names, unknown own version never OUTDATED, profile swap replaces only the exact instance |

## Game servers (`it/matrix.sh`, `it/backend-check.sh`)

23 Bukkit-family and Sponge servers from 1.8.8 to 26.3, each attacked directly with forged, junk, wrong-server and replayed signatures, then joined for real with a fresh signature, then put through a live key rotation: 23 of 23 behave as expected. On every one the joined player's live profile carries no signature, and a key removed by reload is refused right after the reload. Full table in [COMPATIBILITY-REPORT.md](COMPATIBILITY-REPORT.md).

## Full networks

| Setup | Result |
|---|---|
| Paper 1.21.11 + Velocity 3.5.1 (`legacy`) + Waterfall 1.21 (`it/run-integration.sh`) | 15/15 |
| Folia 1.21.11 + Velocity 3.5.1 (`bungeeguard`) + Waterfall 1.21 | 15/15 |
| BungeeCord (latest) + Paper 1.21.11 (`it/bungee-check.sh`, NL-10) | 4/4 |

The NL-10 check inside `run-integration.sh` reports SKIP on Waterfall because Waterfall can no longer download its `send` command module; it is covered on BungeeCord instead.

## Soak test (`it/soak.sh`, 3 hours)

A small real network ran for 3 hours on the release jar: Velocity 3.5.1 (proxy-1) in front of Paper 1.21.11 (`lobby`) and Waterfall 1.21 (proxy-2) in front of Folia 1.21.11 (`survival`), both backends trusting both proxies, each proxy with its own key. During the whole run: valid logins at 5/s through each proxy, forged direct logins at 2/s against each backend, a replay attempt and a wrong-server attempt against each backend every minute, a reconnect storm (300 logins, 10 names, 100/s) every 10 minutes, `/networklock reload` on all four every 5 minutes, a live key rotation of proxy-1 after one hour, and a restart of the Folia backend every 45 minutes. CPU, heap, threads and handles of every JVM were sampled every 30 s, with a forced GC and a heap reading every 10 minutes. Script: `it/soak.sh`, analysis: `it/soak-report.py`.

| Check | Result |
|---|---|
| Valid logins via Velocity → Paper | 53,700 of 53,700 accepted |
| Valid logins via Waterfall → Folia | 53,557 of 53,700 accepted; 142 failed while the Folia backend was restarting, 1 was Waterfall failing to reach it ("Could not connect to a default or fallback server") |
| Valid logins refused by NetworkLock | 0 |
| Forged direct logins | 0 of 43,384 accepted |
| Replay and wrong-server probes (354, including after 3 restarts and the key rotation) | every signature accepted once and its replay refused; every wrong-server signature refused |
| Threads, first vs last hour | Paper 85 → 85, Velocity 104 → 104, Waterfall 138 → 138, Folia 78 → 78 |
| Handles, first vs last hour | Paper 1,006 → 1,006, Velocity 1,808 → 1,810, Waterfall 1,980 → 1,981 |
| Heap after forced GC, first vs last hour | Paper 280 → 276 MB, Velocity 20 → 19 MB, Waterfall 132 → 130 MB (Folia restarts every 45 min) |
| Replay memory | at most 1,161 entries, rising and falling with traffic |
| NetworkLock errors in any console | 0 |

No memory, thread or handle growth, no authentication bypass, no unexplained resource growth.

Two harness flaws were found during this run and fixed afterwards: the old-key probe after the key rotation was sent in the same second as the reload command and arrived before the server had processed it (so it was still accepted, correctly at that moment), and process monitoring found servers by jar name while other tests ran the same jars in parallel (the analysis therefore keeps only processes that lived longer than 10 minutes). The fixed harness waits for the server to confirm the reload, and in a 10-minute verification run the removed key was then refused (NL-02). Key revocation on a live server is also checked on all 23 servers by `it/backend-check.sh`.


## Load and attack simulation

From the end-to-end benchmark ([BENCHMARK-REPORT.md](BENCHMARK-REPORT.md)), real logins through Velocity into Paper 1.21.11: every forged direct login was refused while real players were joining through the proxy; no signature was ever accepted twice; reconnect storms did not trip the replay check; and every failed valid login was traced to its recorded reason (Windows port exhaustion, Paper's join limit or Velocity's own duplicate-name check), never to NetworkLock.

## Not tested

- Real Mojang accounts and online-mode skins (the skin data itself is covered by unit tests).
- Velocity `modern` forwarding and Velocity 4.x were tested by hand on 1.0.0 and not re-run for 1.0.1 (the backend code path is the same Paper/Sponge event as `legacy`).
- Hybrid servers (Arclight, Mohist), the Luminol and Canvas Folia forks, and FlameCord.
- Linux or macOS game servers in this round (CI covers the unit tests and the Paper integration on Linux).
- Runs longer than 3 hours, and an outside security review.
