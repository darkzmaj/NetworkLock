# Comparison board: NetworkLock 1.0.0 vs BungeeGuard 1.4.0 vs NetworkLock 1.0.1

Every score below comes from something measured or read in the code during this audit: [SECURITY-AUDIT.md](SECURITY-AUDIT.md), [COMPATIBILITY-REPORT.md](COMPATIBILITY-REPORT.md), [BENCHMARK-REPORT.md](BENCHMARK-REPORT.md), [TEST-REPORT.md](TEST-REPORT.md). BungeeGuard was judged on its 1.4.0 release jar and source as published; it was not tested on the 1.8–1.20 servers, so its compatibility is scored from its documentation and marked ⏭️.

Legend: ✅ full marks · ⚠️ partial · ❌ missing · ⏭️ not tested here, scored from documentation · 🏆 category winner

## Score (100 points)

### Security (40)

| Criterion | Pts | BungeeGuard 1.4.0 | NL 1.0.0 | NL 1.0.1 |
|---|---|---|---|---|
| Blocks direct joins and forged forwarding | 6 | ✅ 6 | ✅ 6 | ✅ 6 |
| Captured credential useless after use (replay) | 7 | ❌ 0 — static token works forever | ✅ 7 | ✅ 7 |
| Credential bound to player UUID, name, IP, skin | 6 | ❌ 0 | ✅ 6 | ✅ 6 |
| Credential bound to one target server | 4 | ❌ 0 | ✅ 4 | ✅ 4 |
| Per-proxy keys, rotation without downtime | 4 | ⚠️ 2 — several tokens allowed, all equal | ✅ 4 | ✅ 4 |
| Fails closed (bad config, errors, unknown server type) | 5 | ⚠️ 3 — refuses on missing config; errors logged | ⚠️ 3 — NL-01 (reload reopens replay), NL-04 (kick failure) | ✅ 5 |
| Safe logging (no secrets, no log forging, flood cap) | 4 | ⚠️ 2 — wrong tokens logged (base64), stack trace per bad handshake | ⚠️ 2 — names logged raw (NL-02) | ✅ 4 |
| Resists other plugins re-allowing a refused login | 2 | ❌ 0 | ✅ 2 | ✅ 2 |
| Credential removed from the live profile | 2 | ✅ 2 | ⚠️ 1 — not on Sponge | ✅ 2 — verified on a joined player on all 23 servers, Sponge and Spigot 1.21.9+ included |
| **Subtotal** | **40** | **15** | **35** | **🏆 40** |

### Compatibility (20)

| Criterion | Pts | BungeeGuard 1.4.0 | NL 1.0.0 | NL 1.0.1 |
|---|---|---|---|---|
| Minecraft versions on game servers | 6 | ⏭️ 5 — 1.8+, needs ProtocolLib below Paper's handshake event | ❌ 1 — 1.21+ only | ✅ 6 — 1.8.8 to 26.3, 23 servers tested |
| Paper forks and Folia | 4 | ⏭️ 4 — declares `folia-supported: true`; not tested here | ✅ 4 | ✅ 4 |
| Spigot | 3 | ⏭️ 2 — needs ProtocolLib | ❌ 0 — refuses everyone | ✅ 3 — no extra plugin |
| Sponge | 3 | ⚠️ 1 — Sponge module in the repository, not in the 1.4.0 release jar; not tested | ✅ 3 | ✅ 3 |
| Proxies (Velocity, BungeeCord, Waterfall) | 4 | ✅ 4 — built into Velocity, plugin for Bungee | ✅ 4 | ✅ 4 |
| **Subtotal** | **20** | **16** | **12** | **🏆 20** |

### Performance (15)

| Criterion | Pts | BungeeGuard 1.4.0 | NL 1.0.0 | NL 1.0.1 |
|---|---|---|---|---|
| End-to-end login latency at realistic load (≤100/s) | 6 | ✅ 6 | ✅ 6 | ✅ 6 |
| Cost to accept a valid login (µs, game server) | 3 | ✅ 3 — 4.4–4.9 µs | ✅ 3 — 4.9–6.4 µs | ✅ 3 — 6.0–6.2 µs |
| Cost to reject a forged login (attack path) | 3 | ⚠️ 2 — 3.0–3.4 µs | ✅ 3 — 1.6 µs | ✅ 3 — 1.2 µs |
| Work on the proxy per login | 3 | ✅ 3 — none | ⚠️ 2 — 1.9 µs | ⚠️ 2 — 1.6 µs |
| **Subtotal** | **15** | **🏆 14** | **14** | **14** |

Tie on points; BungeeGuard keeps the trophy for doing no work at all on the proxy. None of these differences is visible to a player: every setup logs players in in ~76–80 ms.

### Operations (15)

| Criterion | Pts | BungeeGuard 1.4.0 | NL 1.0.0 | NL 1.0.1 |
|---|---|---|---|---|
| Setup effort | 4 | ✅ 4 — one token, Velocity has it built in | ⚠️ 3 — key per proxy, server-id per server | ⚠️ 3 — same |
| Diagnostics (`doctor`, refusal reasons) | 4 | ⚠️ 1 — reason enum in console | ⚠️ 3 — doctor, outcome codes | ✅ 4 — doctor plus plain-language cause and fix |
| Messages and commands | 3 | ⚠️ 1 — two kick texts | ✅ 3 | ✅ 3 |
| Maturity and track record | 4 | ✅ 4 — since 2020, widely deployed | ❌ 0 — new | ⚠️ 1 — new, audited |
| **Subtotal** | **15** | **10** | **9** | **🏆 11** |

### Quality and privacy (10)

| Criterion | Pts | BungeeGuard 1.4.0 | NL 1.0.0 | NL 1.0.1 |
|---|---|---|---|---|
| Automated tests (unit + real servers) | 4 | ❌ 0 — no test files in the GitHub repository | ⚠️ 3 — 36 unit, 9 servers | ✅ 4 — 55 unit, 23 servers with a real join, 2 full networks, BungeeCord checks, 3-hour soak |
| No outgoing network connections | 3 | ✅ 3 | ✅ 3 | ⚠️ 2 — update check and anonymous stats, both opt-out |
| Code size / reviewability | 3 | ✅ 3 — very small | ⚠️ 2 | ⚠️ 2 |
| **Subtotal** | **10** | **6** | **🏆 8** | **🏆 8** |

Both NetworkLock versions tie here: 1.0.1 has more tests, 1.0.0 had no outgoing connections. BungeeGuard is the smallest and makes no outgoing connections, but has no automated tests.

## Total

| | BungeeGuard 1.4.0 | NL 1.0.0 | NL 1.0.1 |
|---|---|---|---|
| Security (40) | 15 | 35 | **40** 🏆 |
| Compatibility (20) | 16 | 12 | **20** 🏆 |
| Performance (15) | **14** 🏆 | 14 | 14 |
| Operations (15) | 10 | 9 | **11** 🏆 |
| Quality and privacy (10) | 6 | **8** 🏆 | **8** 🏆 |
| **Total (100)** | **61** | **78** | **93** 🏆 |

### What changed since the first scoring (92 → 93)

Re-scored with the same rubric after the second audit pass. One criterion moved, on evidence: NetworkLock 1.0.1 now removes the used signature from the live profile on Sponge and Spigot 1.21.9+ as well, checked on a really joined player on every server in the matrix (+1). The other second-pass fixes (BungeeCord unused-signature leftovers, limiter flood behaviour, replay memory on resizing reloads, privacy of the stats ID) correct defects inside criteria NetworkLock already had full marks for, so they do not add points; they make those marks deserved. Performance stays at 14: the 100 logins/s question was traced to Paper's own join limit, which affects every setup alike, so there is no gain to score. BungeeGuard's score is unchanged.

## Verified, assumed, untested

- **Verified by measurement or on real servers:** every NetworkLock score; BungeeGuard's security, logging and performance scores (its release jar was run in the benchmarks and its bytecode/source read); BungeeGuard's `folia-supported: true` declaration and its lack of tests (GitHub repository).
- **From documentation, not tested here (⏭️):** BungeeGuard on 1.8–1.20 servers, on Spigot with ProtocolLib, and on Folia.
- **Not covered by the rubric:** residual risks shared by both designs (someone on the proxy-to-server link, a stolen key, a compromised backend, the plugin being removed), the 30-second race window for a captured NetworkLock signature, and replay memory being lost on restart (NL-08).
- **Known limits of the evidence:** one Windows test machine, synthetic offline players, a 3-hour (not multi-day) soak, no outside security review.

## Recommendation for a 200-player network

**Use NetworkLock 1.0.1.**

A 200-player network sees a handful of logins per second at peak. At that rate the speed difference between the two plugins is a few microseconds per login, which is invisible: in the benchmark every setup logged players in in 76–80 ms. So the choice comes down to what each one protects against.

- BungeeGuard keeps one secret for the whole network. Anyone who sees it once (on the proxy-to-server link, in a backup, in a profile on a misconfigured server) can join any of your servers as any player, from anywhere, until you notice and change it.
- NetworkLock gives every login its own signature, valid once, for one player, one IP and one server, for 30 seconds. A copied signature is worth almost nothing.
- NetworkLock 1.0.1 runs on every server type you're likely to have, from 1.8 lobbies to 26.x, including Spigot, without ProtocolLib.

Pick BungeeGuard instead only if you want the smallest possible setup on a single Velocity + Paper network, and you accept the static-token risk.

Whichever you use, also firewall your game servers so only your proxies can reach them.
