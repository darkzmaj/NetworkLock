# NetworkLock threat model

## Assets and boundary

The asset is **identity admission on backends**: only players routed through a trusted proxy may join, and only as the identity (UUID, name, IP, profile properties) that proxy authenticated. The boundary is the TCP connection from proxy to backend. Backends normally run in offline mode behind the proxy, so without extra checks the backend believes whatever forwarding data arrives.

## Adversaries considered

| # | Adversary | Capability |
|---|---|---|
| A1 | Remote attacker | Can reach a backend port directly and send arbitrary handshakes, including forged BungeeCord forwarding data |
| A2 | Malicious player | Joins legitimately through a proxy and can see anything the proxy or backend sends to their own client |
| A3 | Network observer | Can read proxy-to-backend traffic, but not modify it |
| A4 | On-path attacker | Can read, block and inject on the proxy-to-backend link |
| A5 | Compromised proxy | Has one proxy's key |
| A6 | Compromised backend | Has the key list of one backend |

## Protocol

```
token  = base64url( header || mac )
header = 0x01 || len proxyId || len target || issuedAtMillis:i64 || nonce:16
mac    = HMAC-SHA256(proxyKey, "NetworkLock/forwarding/v1\0" || header
                     || uuid:16 || len name || len clientIp || SHA-256(sorted other properties))
```

- Keys are 32 random bytes from `SecureRandom`, one per proxy. Backends may hold several per proxy during rotation.
- The domain string separates this MAC from any other use of the key. Every variable-length field is length-prefixed, so field boundaries can't be shifted.
- Verification steps, in order: structure → known proxy id → MAC (constant-time, tried against every key, with no early exit) → target == own `server-id` → age ≤ 30 s and ≤ 5 s in the future → nonce unused. Only fields authenticated by the MAC are examined after the MAC check.
- The version byte is checked exactly. There is no fallback to an older or unsigned scheme, so no downgrade path exists.
- The check runs in Paper's `AsyncPlayerPreLoginEvent`, before the player entity, world access or any gameplay state exists. NetworkLock re-applies its denial at MONITOR priority if another plugin re-allows the login.

## Guarantees against each adversary

| Adversary | Result |
|---|---|
| A1 direct/forged login | Denied: no valid MAC without a proxy key (tested on Paper 1.21.11, Paper 26.3, Folia 1.21.11) |
| A1 UUID/name/IP/property spoofing | Denied: all are inside the MAC |
| A2 credential leakage | The backend strips the credential before the profile is applied, and the proxy removes it after the connection completes. Keys never leave the config file; only MACs travel on the wire. |
| A3 replay of a captured credential | Denied after first use: per-backend nonce cache. Use on a *different* backend is denied by target binding (tested) |
| A4 on-path race | **Not prevented.** An attacker who blocks the real connection and replays the credential within 30 s to the same backend gets in as that player. Mitigation: private network or VPN between proxies and backends. |
| A5 compromised proxy | Can impersonate any player on backends that trust **that** proxy. Can't sign as another proxy id (tested). Recovery: remove its key from backends and reload. |
| A6 compromised backend | Learns the keys it trusts, and can then mint credentials for other backends that trust the same proxies. HMAC is symmetric, so a backend can't be prevented from forging. A future version could use Ed25519 signatures to remove this. |

## Replay protection without a central database

Each backend remembers nonces until the credential's validity window closes (default 35 s). Only MAC-valid credentials enter the cache, so its size is bounded by legitimate login volume. The hard cap (`replay-cache-max-entries`, default 100 000, about 10 MB) makes logins fail closed when exceeded. The cache survives `/networklock reload` but not a restart. A credential captured within 35 s before a backend restart could be replayed once after it; the restart window and the A4 requirement make this narrow.

Credentials are bound to a target server name. Two backend *instances* configured with the **same** `server-id` would each accept one copy of the same credential, so give every instance a unique name.

## Proxy-side protections

Per-address token-bucket login throttling (IPv6 grouped by /64), temporary blocks for repeat offenders, a network-wide flood warning, and a bounded address table. When the table is full, untracked addresses are allowed rather than blocked, which keeps memory bounded at the cost of throttling during very wide floods. This is application-level throttling, not volumetric DDoS mitigation.

## Fail-closed behaviour

| Situation | Behaviour |
|---|---|
| Backend config missing or invalid at startup | Every login denied (lockdown) |
| Invalid config on reload | Rejected entirely; the previous config stays active |
| Server without the Paper profile API (plain Spigot) | Deny-all listener; permanent lockdown |
| Exception during verification | Login denied |
| Startup exception | Plugin stays enabled in lockdown, because a disabled plugin would unregister its listener |
| Proxy config invalid | Proxy refuses logins (`security-error`) |
| BungeeCord internals not recognised | Proxy refuses logins rather than forwarding unsigned connections |
| Plugin removed, or disabled while running | **Unprotected**: logged at SEVERE. Firewall backends independently. |

## Logging

Keys are never logged or displayed; only 64-bit SHA-256 fingerprints are. YAML parse errors report the line number, never the surrounding text. Security events are rate-limited (20 per 10 s, then a suppressed count), and client addresses can be redacted.
