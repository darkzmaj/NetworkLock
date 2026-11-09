# Security audit: NetworkLock 1.0.1

Audited: NetworkLock 1.0.1-dev (`859578f`), then the first 1.0.1 build (`6f6daa7`), compared with v1.0.0 (`424ffb1`) and BungeeGuard v1.4.0 (`c254f72`, release jar SHA-256 `f2f3a3df…7e1d47`). Dates: 2026-11-08 (first pass) and 2026-11-09 (second pass: an independent full-code review, then every finding re-checked against the code and, where it involves a server, on real servers).

Method: line-by-line review of every authentication, configuration and platform file; regression tests for each fix; attack simulations against real servers on localhost (see [TEST-REPORT.md](TEST-REPORT.md)). Passing tests show that the tested attacks fail. They do not prove there are no other vulnerabilities.

## Findings and fixes

| ID | Severity | Finding | Status |
|---|---|---|---|
| NL-01 | Medium | Raising `max-token-age-seconds` by reload could let an already-used signature be accepted again | Fixed in 1.0.1 |
| NL-02 | Low | Player names from direct connections were logged unfiltered (log line forging on old servers) | Fixed in 1.0.1 |
| NL-03 | High (latent) | Ported to old Paper as-is, the kick call would throw on Paper 1.12.2 to 1.16.4 and let the player in | Fixed before release |
| NL-04 | Low | A failure while building the kick message would end the event handler without refusing the login | Fixed in 1.0.1 |
| NL-05 | Low | The replay cache reported "full" instead of "replay" for a used signature when full (still refused) | Fixed in 1.0.1 |
| NL-06 | High (availability) | 1.0.0 could not load on any server below 1.21, and refused every player on Spigot/CraftBukkit | Fixed in 1.0.1 |
| NL-07 | Low | On Sponge and Spigot 1.21.9+ (read-only profiles) the used signature stayed in the player's live profile | Fixed in 1.0.1 (second pass) |
| NL-08 | Info | Replay memory is per server and lost on restart | Design limitation, documented |
| NL-09 | Info | Update check and usage statistics contact GitHub and api.kori.my | Documented, both can be turned off |
| NL-10 | Medium | BungeeCord/Waterfall: a connect that never happened (already on that server, already connecting, cancelled later, server down) left a fresh, unused signature on the player's proxy profile for up to 30 s | Fixed in 1.0.1 (second pass) |
| NL-11 | Medium (availability) | A wide login flood that filled the proxy's address table made every new address scan the whole table | Fixed in 1.0.1 (second pass) |
| NL-12 | Low | A reload that changed `replay-cache-max-entries` started an empty replay memory, so signatures used in the last ~35 s worked once more | Fixed in 1.0.1 (second pass) |
| NL-13 | Low (privacy) | With `updates.check: false` but a broken config at startup, the update check still contacted GitHub | Fixed in 1.0.1 (second pass) |
| NL-14 | Low (privacy) | The anonymous statistics ID could be computed from the key fingerprint printed in logs and commands | Fixed in 1.0.1 (second pass) |
| NL-15 | Low | Sponge: a login cancelled by an earlier plugin was not checked, so a later plugin un-cancelling it let it through unchecked | Fixed in 1.0.1 (second pass) |
| NL-16 | Info | After a plugin reload by a plugin manager, pooled threads kept the old plugin's classloader and a key reference alive | Fixed in 1.0.1 (second pass) |
| NL-17 | Info | Misleading diagnostics: false "disabled while running" on every Spigot shutdown, locale-dependent text, unsignable server names reported as "not via proxy", bad `server-id` values only failing at login, a false shared-key warning, OUTDATED for an unknown own version | Fixed in 1.0.1 (second pass) |

### NL-01: replay window reopened by a reload (Medium)

`NonceCache` stored each used nonce with an absolute expiry calculated from the token lifetime at the moment of use (`BackendCore`: `issuedAt + maxAge + skew`). If an admin raised `max-token-age-seconds` with `/networklock reload`, entries were purged on the old, shorter schedule, while tokens kept being accepted under the new, longer lifetime. An attacker who had captured a signature (this needs a position on the proxy-to-server link) could use it again in that gap.

Fix: the cache stores the issue time and purges with the retention passed in by the current settings (`NonceCache.use(nonce, issuedAt, now, retention)`). Regression test: `SupportTest.replayStaysBlockedWhenLifetimeIsRaised`. Before the fix the second use returned FRESH (accepted); after the fix it returns REPLAY.

### NL-02: log line forging through player names (Low)

`BackendCore` and both Bukkit listeners wrote the login name straight into the console. Servers before 1.18 do not validate names on a direct (attacker) connection, so a name like `x\n[Server] Op granted to Attacker` produced a fake second log line. Terminal escape sequences were also passed through.

Fix: `SecurityLog.clean()` replaces anything outside printable ASCII with `?` and caps the name at 32 characters, everywhere a name is logged. Regression test: `SupportTest.attackerNamesCannotForgeLogLines`.

### NL-03: fail-open kick on Paper 1.12.2 to 1.16.4 (High, never shipped)

`PaperLoginListener` refused logins with `disallow(Result, Component)`. Adventure `Component` only exists on Paper 1.16.5 and newer. Supporting older Paper (this release) would have made that call throw `NoSuchMethodError` outside the `try` block. Bukkit logs the exception and carries on, so the login would have been accepted. 1.0.0 never loaded on those versions (see NL-06), so no released version was affected.

Fix: refuse with the plain-text overload every version has. Verified on real Paper 1.12.2 and 1.16.5 (forged logins refused, see TEST-REPORT).

### NL-04: kick message failure could skip the refusal (Low)

Both listeners call `deny()` after their `try` block. Any exception while formatting the kick text would have escaped the handler, and Bukkit would have let the login continue. Fix: `deny()` catches everything and falls back to a bare `KICK_OTHER` without a message.

### NL-05: "cache full" instead of "replay" (Low)

When the replay cache was at its limit, a replayed signature got `REPLAY_CACHE_FULL` instead of `REPLAYED`. It was still refused, but with the wrong reason. Fix: known nonces are recognised first; `putIfAbsent` still makes the atomic decision when two copies arrive at once (test: `BackendCoreTest.concurrentReplayAcceptsExactlyOnce`).

### NL-06: no support below 1.21, Spigot locked (High, availability)

1.0.0 was compiled for Java 21 and declared `api-version: "1.21"`. Servers on 1.13 to 1.20 refused to load it, servers on 1.8 to 1.16 (Java 8) could not even read the classes, and Spigot/CraftBukkit refused every player because the Paper profile API was missing. A server owner on those versions had no protection at all.

Fix: core and the Bukkit side are now compiled for Java 8, `api-version` is `1.13` (ignored below 1.13), and servers without the Paper profile API use a new check in `PlayerLoginEvent` that reads the forwarded profile from the server's own `GameProfile`. That event fires before the player joins the world on every version from 1.8 to today, and needs no ProtocolLib. Verified on real servers from 1.8.8 to 26.3 (see COMPATIBILITY-REPORT).

### NL-07: used signature stayed in the live profile on some servers (Low, fixed)

Sponge's API exposes profiles read-only, and authlib 7 (Spigot 1.21.9+) made the profile and its property map immutable records. In the first 1.0.1 build the used signature therefore stayed in the joined player's profile on those servers, where it could be sent to other players' tab lists. It was already used, bound to that player and server, and expired within 30 s, so it could not be replayed, but it should not be there.

Fix: `ProfileScrubber` builds a copy of the profile without the signature and swaps it into the server objects that hold exactly the original instance: the player entity on Spigot (in `PlayerLoginEvent`), and Minecraft's login listener plus Sponge's connection object on Sponge (in the Auth event, before the player entity is created). If anything does not fit on some future version, it changes nothing and the old behaviour remains. Verified on real servers by a full join with a fresh signature and a check of the joined player's live profile: Spigot 1.21.11, SpongeVanilla 1.21.11 and 26.3, and every other server in the matrix. The signature is still in the login-success packet the server sends to that player's own client, which received it from the proxy anyway.

### NL-08: replay memory is per server and not persistent (Info)

Each game server remembers used nonces in memory for the token lifetime (35 s by default). After a restart, a signature captured in the last 35 seconds before the restart could be used once more. Two servers configured with the same `server-id` would each accept one copy. Both need an attacker on the proxy-to-server link; documented in the threat model.

### NL-09: outgoing connections (Info)

The update check (GitHub API, every 12 h) and usage statistics (api.kori.my, every 24 h) reveal the server's IP address to those services. The statistics send only an anonymous one-way ID, the version and the platform. Both are on by default and can be turned off (`updates.check`, `metrics.enabled`).

### NL-10: unused signature left on BungeeCord profiles (Medium, fixed)

BungeeCord fires `ServerConnectEvent` before it checks whether the player is already on, or already connecting to, the target (verified in Waterfall's `UserConnection.connect0` bytecode). NetworkLock signed the connection in that event and removed the signature only on `ServerConnectedEvent` or `ServerKickEvent`. A connect that then did not happen (`/server lobby` while on lobby, a second request while connecting, a later plugin cancelling, a backend that is down) left a valid, never-used signature on the player's proxy profile for its lifetime. Proxy tab-list handlers or other plugins could relay it; with legacy forwarding an attacker who can reach the backend directly could use it once to log in as that player.

Fix: no signature for a connect to the server the player is already on (BungeeCord's own check), and a safety net that removes exactly that signature after the connect timeout plus 2 s. BungeeCord reads the profile as soon as the TCP connection opens, so by then it was either sent or never will be. Profile changes are synchronized per player, so the safety net never removes a newer signature. Velocity checks "already connected" before firing its event; NetworkLock now also skips signing when another plugin redirects a player to the server they are already on.

### NL-11: connection-limiter sweep storm (Medium, availability, fixed)

When the proxy's address table was full (default 100,000) and no entry had been idle for a full window, every connection from a new address ran a full sweep of the table. Fix: at most one sweep per second, claimed atomically. Memory stays bounded because a full table already lets untracked addresses pass without storing them. Regression test: `ReviewFixesTest.limiterSweepsAtMostOncePerSecondWhenFull`.

### NL-12: replay memory reset by a resizing reload (Low, fixed)

A reload that changed `replay-cache-max-entries` replaced the replay memory with an empty one, and a login being checked during any reload wrote into the old one. Fix: one replay memory for the plugin's lifetime; a reload changes its capacity in place. Regression test: `ReviewFixesTest.replayCacheSurvivesAReloadThatResizesIt`.

### NL-13 and NL-14: privacy of the optional connections (Low, fixed)

The update check treated "no config loaded" as "on", so an owner who had opted out but had a config error at startup was still checked against GitHub until a reload succeeded; it is now off without a config, like the statistics. The statistics ID was a hash of the public key fingerprint (printed at startup and in `/networklock status`), so anyone with a pasted log could compute it; it is now derived from the secret key bytes. What is sent did not change: the update check is an HTTPS GET to the GitHub API every 12 h (response capped at 256 KB, redirects off, 10 s timeouts); the statistics are one HTTPS POST a day with the anonymous ID, plugin version, platform and side. Turning either off (also by reload) stops all traffic; no other data leaves the server. The two scheduler threads still exist while opted out; they sleep and send nothing.

### NL-15 to NL-17 (Low/Info, fixed)

Sponge now checks logins that an earlier listener cancelled (`@IsCancelled(UNDEFINED)`), so a later plugin un-cancelling them cannot skip the check. The per-thread crypto cache holds only JDK objects, so threads that outlive a plugin reload do not keep NetworkLock's classloader alive. The diagnostics fixes are listed in the table; each has a regression test in `ReviewFixesTest` where it is testable without a server.

### Known, not changed

- A proxy reload rebuilds the login limiter, which clears temporary IP blocks. Only an admin can reload; keeping the state would add complexity without a security benefit.

## Threat model

Attacker positions: **D** = can reach the game server port directly; **N** = can read or change proxy-to-server traffic; **P** = controls a player account; **K** = holds a stolen key; **S** = controls a game server; **A** = admin mistake. "BG" is BungeeGuard 1.4.0 under the same attack.

| # | Threat | Who | NetworkLock 1.0.1 | BG |
|---|---|---|---|---|
| T01 | Join a game server directly, no forwarding data | D | Refused (no signature) | Refused |
| T02 | Forge BungeeCord forwarding with any UUID/name | D | Refused (no valid MAC) | Refused (no token) |
| T03 | Junk or truncated signature | D | Refused (length/format checks, 512-char cap) | Refused |
| T04 | Valid signature for server A sent to server B | D+N | Refused (target bound) | Accepted (token is global) |
| T05 | Replay a captured signature after the real login | N | Refused (one-time nonce) | Accepted forever |
| T06 | Replay a captured signature before the real login (race) | N | Accepted once within 30 s (residual) | Accepted |
| T07 | Replay after server restart | N | Accepted once if within 35 s of restart (NL-08) | Accepted |
| T08 | Replay after admin raises token lifetime by reload | N | Refused (NL-01 fixed) | n/a |
| T09 | Change UUID or name inside a captured login | N | Refused (bound to UUID and name) | Accepted |
| T10 | Change client IP (ban evasion, IP spoof) | N | Refused (bound to IP) | Accepted |
| T11 | Change skin/textures or other profile properties | N | Refused (SHA-256 over properties) | Accepted |
| T12 | One proxy impersonates another proxy id | K | Refused (key per proxy id) | n/a (tokens shared) |
| T13 | Use a key that was rotated out | K | Refused right after reload | Accepted until token changed |
| T14 | Brute-force the key or MAC | D | Infeasible (256-bit key, 256-bit MAC) | Infeasible for a 64-hex token |
| T15 | Timing attack on MAC compare | D | Constant-time compare, all keys tried | String set lookup (hash-based, not exploitable in practice) |
| T16 | Field-shifting / length-extension on the MAC input | D | Prevented (HMAC, length prefixes, domain string) | n/a |
| T17 | Signature leaks to other players via profile (tab list) | P | Removed from the live profile on every tested server, Sponge and Spigot 1.21.9+ included (NL-07); unused proxy-side leftovers removed (NL-10) | Token removed from the properties on both paths |
| T18 | Another plugin re-allows a refused login | S | Refused again at MONITOR priority | Not re-checked |
| T19 | Log line forging through player name | D | Cleaned (NL-02 fixed) | Safe: connection details are logged base64-encoded |
| T20 | Console flood with refused logins | D | 20 lines per 10 s cap | One line (plus stack trace on bad handshake) per attempt |
| T21 | Replay cache filled to exhaustion | N | Only valid signatures stored; full cache refuses (fails closed) | n/a |
| T22 | Login flood through the proxy | P | Per-IP limiter with temporary blocks | Not covered |
| T23 | Missing or broken config at startup | A | Every login refused until fixed | Plugin refuses all |
| T24 | Broken config on reload | A | Reload refused, old config stays | n/a |
| T25 | Exception inside the login check | D | Login refused (fail closed, NL-03/NL-04) | Exception logged |
| T26 | Server type without a usable login hook (CraftBukkit) | A | Every login refused, doctor explains | Refuses or needs ProtocolLib |
| T27 | Plugin disabled while running | A | Unprotected; warning logged (residual) | Same |
| T28 | Key leaks through logs or error messages | A | Never logged; fingerprints only; YAML errors without values | Wrong tokens logged base64-encoded |
| T29 | Clock skew between proxy and server | A | 5 s allowance; doctor warns over 1 s | Not time-based |
| T30 | Update check / statistics endpoint abused for tracking | S | Opt-out, anonymous ID only, redirects disabled (NL-09) | No outgoing connections |

## Protocol review

| Property | Result |
|---|---|
| Primitive | HMAC-SHA256 (JDK `Mac`), no custom cryptography |
| Keys | 32 bytes from `SecureRandom`; at least 32 bytes enforced; one key per proxy |
| Nonce | 128 bits from `SecureRandom` per signature |
| Binding | proxy id, target server, issue time, nonce, UUID, name, client IP and a SHA-256 over all other profile properties; length-prefixed fields and a fixed domain string, so fields cannot be shifted |
| Comparison | `MessageDigest.isEqual` (constant time), against every key of the proxy without stopping early |
| Expiry | 30 s by default, 5 s allowance for a clock that runs ahead |
| One-time use | atomic `ConcurrentHashMap.putIfAbsent` per nonce; 64-thread race test accepts exactly one |
| Rotation | several keys per proxy; old key removed by reload takes effect immediately |
| Failure | invalid or missing config, unknown platform, any exception: login refused |
| Secrets in logs | keys never logged or shown, only 64-bit fingerprints; YAML errors report line numbers only |

The 1.0.1 performance change (per-thread `Mac`/`MessageDigest` reuse) feeds the HMAC exactly the same bytes; `ForwardingTokenTest.optimisedMacIsByteIdenticalToTheOriginalAlgorithm` compares it with the 1.0.0 algorithm over 300 random identities, alternating keys.

## Cryptographic review

- **MAC strength.** HMAC-SHA256 with a 256-bit key and an untruncated 256-bit tag. Forging a tag without the key is not feasible; HMAC is not affected by SHA-256 length extension.
- **Input encoding.** Every variable field is length-prefixed (4-byte big-endian length, then UTF-8), the input starts with the fixed string `NetworkLock/forwarding/v1\0`, and the header carries a version byte. Two different identities cannot produce the same MAC input, and a v1 tag cannot be reused under a future format.
- **Other profile data.** Properties other than the signature are sorted by name, value and signature, then hashed with SHA-256 into the MAC input. Proxies that reorder properties still verify; adding, removing or changing a skin breaks the tag.
- **Nonce.** 128 bits from `SecureRandom`. Two random nonces collide with probability about n²/2¹²⁹; at a million logins inside one 35-second window that is below 10⁻²⁷. A collision would refuse a login, never accept one.
- **Time.** The issue time is inside the MAC. Accepted window: from 5 s in the future to 30 s in the past. Replay memory keeps each nonce for 35 s, which covers the whole window (fixed in NL-01 so a reload can't shorten it).
- **Comparison.** `MessageDigest.isEqual` against every key of the claimed proxy; the loop never stops early, so timing doesn't reveal which key matched.
- **1.0.1 object reuse.** Each thread keeps one `Mac` and one `MessageDigest`. The `Mac` is re-keyed whenever the key object changes (compared by identity; config reloads always create new key arrays) and reset after any exception, so a half-fed state can't leak into the next check. A thread keeps a reference to the last key it used until it checks the next login, so a removed key can stay in memory a little longer. It is never used again for verification, because the key list is read from the active config every time.
- **Encoding of the token.** Base64url without padding, at most 512 characters, header fields bounds-checked before any MAC work. Malformed input is rejected with a fixed reason and no detail from the input.

## BungeeGuard 1.4.0 for comparison

These describe BungeeGuard's design as documented, not coding errors.

| Topic | BungeeGuard 1.4.0 |
|---|---|
| Credential | one static token per proxy, sent in every handshake |
| Replay | a token seen once (for example on the proxy-to-server link, or leaked from a profile) works forever, on every server, for any player, until the admin changes it |
| Identity binding | none: the token is not tied to UUID, name, IP or server |
| Identity checks | UUID and name are taken from the BungeeCord handshake as sent |
| Old servers | needs ProtocolLib on servers without Paper's handshake event (1.8.8, Spigot) |
| Logs | a wrong token is logged base64-encoded; bad handshakes print a stack trace |
| Strengths | very small and simple; one string lookup per login; mature (since 2020), widely used |

## Residual risk

- Someone who can read the proxy-to-server traffic can use a captured signature once, within 30 seconds, before the real player does. Use a private network or VPN.
- A stolen proxy key lets the thief log in as anyone on servers that trust that proxy, until the key is removed.
- A compromised game server knows the keys it trusts and could use them against other servers that trust the same proxies.
- If NetworkLock is removed or disabled while running, nothing is checked. Firewall game servers so only proxies can reach them.
- No outside security review has been done.
