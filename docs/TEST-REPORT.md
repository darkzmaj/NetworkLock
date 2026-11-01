# Test report: 2026-11-01

Environment: Windows 11 Pro, Intel i7-14700KF (28 threads), Microsoft OpenJDK 21.0.10 and Temurin 25.0.5. Everything ran on localhost.

## Automated unit tests (`./gradlew test`)

35 tests, all passing. They cover:

- **Token**: round trip; fresh nonce per mint; tampering with UUID, name, address, property values, removed properties and wrong key all rejected; **every single bit flip** in header and MAC rejected; property order independence; malformed, oversized, truncated and trailing data; version mismatch with no fallback; id validation.
- **Backend**: accept once then `REPLAYED`; missing, duplicate and malformed tokens; forged identity; cross-proxy key misuse; unknown proxy; wrong target; expiry and future-dated tokens with the skew allowance; rotation (two keys) and revocation; replay cache surviving reload; invalid reload keeping the old config; invalid startup config locking down; a fresh install locked down until configured; platform block surviving reload; keys never logged; 64 concurrent verifications of one token accepting exactly once.
- **Proxy**: key generated once and never overwritten; lockdown on broken config; in-process proxy-to-backend round trip; kick-code translation that ignores look-alike text.
- **Limiter**: burst, throttle and refill; temporary block and expiry; IPv6 /64 grouping; IPv4-mapped addresses; exemptions; bounded memory with idle sweep.
- **Other**: nonce cache bound (fail closed) and expiry; address normalization (IPv6 zone ids); CIDR parsing (hostnames rejected); `env:` and `file:` keys; shared-key warning; collected config errors that never echo secrets (including YAML syntax errors); MiniMessage subset (interactive tags and raw `§` dropped); command permission gating.

## Automated integration test (`it/run-integration.sh`)

The script downloads checksum-verified Paper 1.21.11, Velocity 3.5.1 and Waterfall 1.21, installs the same jar on all three and asserts 15 outcomes: five direct-attack rejections, valid token accepted, replay rejected with NL-04, joins through Velocity (legacy) and Waterfall (key via `env:`) both confirmed in the backend log, a backend rejection translated to the proxy's `messages.yml` text, and an invalid reload that is rejected while enforcement continues. Result on 2026-11-01 (Windows 11, JDK 21): **15/15 passed**. It also runs as the `integration` job in CI, which has not run yet because nothing has been pushed.

## Real-server integration tests (manual matrix)

Tool: `it/Probe.java`, a raw Minecraft protocol client that can forge BungeeCord handshakes and mint or replay tokens with a known key. Server consoles were the source of truth.

### Direct attacks on a backend (Paper 1.21.11, BungeeCord mode)

| # | Attempt | Result |
|---|---|---|
| 1 | Plain login, no forwarding data | Connection closed by Paper's own BungeeCord check |
| 2 | Forged handshake claiming Notch's UUID, no token | `NL-01` (MISSING) |
| 3 | Forged handshake with junk token | `NL-02` (MALFORMED) |
| 4 | Valid token, then the same token again | First ACCEPTED, replay `NL-04` |
| 5 | Valid token signed for `survival`, used on `lobby` | `NL-02` (WRONG_TARGET) |
| 6 | Token claiming `proxy-2` signed with proxy-1's key | `NL-02` (BAD_SIGNATURE) |
| 7 | Token from unknown `proxy-9` | `NL-02` (UNKNOWN_PROXY) |
| 8 | Genuine token from `proxy-2` | ACCEPTED |
| — | 48 concurrent logins, each followed by a replay | 48/48 accepted, 48/48 replays rejected, 6 s wall time including 96 JVM starts |

The same attack set on **Folia 1.21.11** gave identical outcomes (forged `NL-01`, valid+replay `NL-04`, other target `NL-02`).

### Through real proxies

| Path | Result |
|---|---|
| Velocity 3.5.1 legacy → Paper 1.21.11 | ACCEPTED; backend log `Accepted Steve` |
| Velocity 3.5.1 legacy → Paper with mismatched `server-id` | Backend denied `WRONG_TARGET`; the player saw the proxy's `messages.yml` text "Connection authentication failed." instead of the backend's built-in text |
| Velocity 3.5.1 modern → Paper 1.21.11 (Velocity forwarding) | ACCEPTED; no "credential still present" self-check warning |
| Waterfall 1.21 (key via `env:`) → Paper 1.21.11 | ACCEPTED (offline mode, profile injected by the reflective adapter) |
| BungeeCord → Paper 1.21.11 | ACCEPTED |
| Velocity 4.3.1 modern → Paper 26.3 (Java 25) | ACCEPTED (`Steve -> lobby has connected`) |
| One backend trusting proxy-1, proxy-2 and proxy-3 with distinct keys | All three accepted, each identified by its own key |

### Operations

| Check | Result |
|---|---|
| Backend `/nlock reload` after changing a proxy key, under live traffic | Applied; fingerprints matched the proxy |
| Proxy reload with an invalid `network.id` and empty key | Rejected with both problems listed; previous config stayed active |
| BungeeCord throttling (max 3, block after 2 violations) | Attempts 1–3 accepted, 4 "Too many connection attempts", 5–6 "Connection temporarily restricted" |
| `doctor` | Flagged forwarding `none` on Velocity, `ip_forward: false` on BungeeCord, and both Paper forwarding modes enabled (found during testing and added as a check) |
| Paper profile completion (`proxies.bungee-cord.online-mode: true`) | Properties seen at pre-login equalled the forwarded set (`properties=[]` for offline identities, including real-account UUIDs), so no false rejections |

## Performance (`it/Bench.java`, single JVM, not JMH)

| Path | Result |
|---|---|
| Mint, 1 thread | ~648 000/s (1.5 µs) |
| Verify (HMAC + replay cache) with a 1 KB textures property, 1 thread | ~213 000/s (4.7 µs) |
| Mint + verify, 28 threads | ~437 000 logins/s |
| Connection limiter check | ~9.8 M/s |
| Replay cache memory | ~100 B per entry (1.8 M entries in under 180 MB of heap); the default cap of 100k is about 10 MB |

Verification happens once per backend connection on Paper's async pre-login thread, never per tick or per packet. No comparison against BungeeGuard was run: BungeeGuard's check is one string lookup, so it is cheaper per login, and NetworkLock adds roughly 5 µs per backend connection.

## Not verified

- Online-mode proxies forwarding real Mojang textures (no test accounts available). Property binding is unit-tested.
- Purpur, Pufferfish, FlameCord, Spigot (deny-all path), Linux file-permission checks.
- Velocity `bungeeguard` forwarding mode together with NetworkLock.
- Sustained multi-hour uptime, very high churn against real proxies, and proxy failover with real clients.
- Rolling restarts and network partitions, beyond the restarts performed during these tests.
- Independent security review of the protocol (release gate 12).
