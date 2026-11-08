# Test report: NetworkLock 1.0.1

Tested 2026-11-08 on Windows 11, Intel i7-14700KF (20 cores, 28 threads), 32 GB RAM, with Java 8, 17, 21 and 25. GitHub Actions runs the unit tests and the Paper integration on Linux for every push. Everything ran against localhost only.

## Unit tests

45 tests, all passing on a clean build (`./gradlew clean test shadowJar`; results checked to be fresh, not left over from an earlier build).

| Class | Tests | Covers |
|---|---|---|
| `ForwardingTokenTest` | 8 | every header byte authenticated, UUID/name/IP/properties tampering caught, malformed input, fresh nonces, property order, and the 1.0.1 optimised MAC byte-identical to the 1.0.0 algorithm over 300 random identities |
| `BackendCoreTest` | 14 | valid signature accepted once, expiry and clock skew, wrong server, forged identity, cross-proxy misuse, key rotation and revocation, 64-thread replay race (exactly one accepted), broken or missing config locks down, failed reload keeps the old config, secrets never logged |
| `SupportTest` | 23 | replay memory bounded and expiring, replay still blocked after the lifetime is raised (NL-01), attacker names cannot forge log lines (NL-02), login limiter (burst, refill, blocks, IPv6 /64 grouping, exemptions, memory cap), messages, commands and permissions, env/file keys, usage statistics carry only anonymous data, end-to-end proxy to backend |

New regression tests in 1.0.1: `replayStaysBlockedWhenLifetimeIsRaised`, `attackerNamesCannotForgeLogLines`, `optimisedMacIsByteIdenticalToTheOriginalAlgorithm`, plus a stricter `nonceCacheIsBoundedAndExpires` (a replay is reported as a replay even when the cache is full).

## Game servers

23 Bukkit-family and Sponge servers from 1.8.8 to 26.3, each attacked directly with forged, junk, wrong-server and replayed signatures: all 23 behave as expected. Full table in [COMPATIBILITY-REPORT.md](COMPATIBILITY-REPORT.md).

## Full networks

| Setup | Result |
|---|---|
| Paper 1.21.11 + Velocity 3.5.1 (`legacy`) + Waterfall 1.21 | 15/15 |
| Folia 1.21.11 + Velocity 3.5.1 (`bungeeguard`) + Waterfall 1.21 | 15/15 |

## Load and attack simulation

From the end-to-end benchmark ([BENCHMARK-REPORT.md](BENCHMARK-REPORT.md)), real logins through Velocity into Paper 1.21.11:

- Every forged direct login was refused while real players were joining through the proxy (mixed workload).
- No signature was ever accepted twice, and no valid login was refused because of NetworkLock.
- Reconnect storms (10 names logging in over and over) did not trip the replay check: every connection gets a fresh signature.

## Not tested

- Real Mojang accounts and online-mode skins (the skin data itself is covered by unit tests).
- Hybrid servers (Arclight, Mohist) and FlameCord.
- Runs longer than the 2-minute sustained benchmark; no multi-hour soak.
- An outside security review.
