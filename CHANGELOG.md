# Changelog

## 1.0.0-SNAPSHOT (unreleased)

- Per-connection HMAC-SHA256 forwarding credential bound to proxy id, target server, UUID, name, client IP and profile properties, with a 30 s lifetime and per-backend replay protection.
- One universal `NetworkLock.jar` for Paper/Folia, Velocity and BungeeCord/Waterfall.
- Pre-login verification on backends; the credential is stripped before the profile is applied.
- Per-proxy keys with rotation and revocation; `env:` and `file:` key sources.
- Proxy login throttling, temporary blocks, flood warning and bounded memory.
- `/networklock` (`/nlock`) with help, status, reload, doctor, stats, proxies and version.
- Two config files (`config.yml`, and `messages.yml` on proxies only); atomic validated reload; lockdown on invalid startup config.
