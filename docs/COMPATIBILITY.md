# Compatibility matrix

Every entry below ran **the same `NetworkLock.jar`** (1.0.0-SNAPSHOT, branch `networklock-redesign`) on Windows 11, 2026-11-01. Server jars came from the official PaperMC download API (SHA-256 verified) and from BungeeCord's official Jenkins.

| Platform | Build | Java | Loaded | Result |
|---|---|---|---|---|
| Paper | 1.21.11 #132 | Microsoft OpenJDK 21.0.10 | yes | **Verified**: legacy and Velocity-modern forwarding, all direct-attack cases, reload, doctor, 48-way concurrent burst |
| Paper | 26.3 #236 | Temurin 25.0.5 | yes | **Verified**: Velocity modern via Velocity 4.3.1; status/doctor |
| Folia | 1.21.11 #14 | OpenJDK 21.0.10 | yes | **Verified**: legacy forwarding, direct attacks, replay, wrong target, status/reload/doctor on the region-threaded server |
| Velocity | 3.5.1 #615 | OpenJDK 21.0.10 | yes | **Verified**: legacy and modern forwarding, kick-message translation, key generation, doctor |
| Velocity | 4.3.1 #45 | Temurin 25.0.5 | yes | **Verified**: modern forwarding to Paper 26.3 (jar compiled against velocity-api 3.4.0) |
| Waterfall | 1.21 #615 | OpenJDK 21.0.10 | yes | **Verified**: legacy forwarding, offline-mode profile injection, key from `env:` |
| BungeeCord | Jenkins lastSuccessfulBuild (downloaded 2026-11-01, sha256 `1d9dbc31…71fd`) | OpenJDK 21.0.10 | yes | **Verified**: legacy forwarding, throttling and temporary blocks, rejected invalid reload |
| Purpur, Pufferfish | n/a | | | **Not tested.** Expected to work (Paper forks with the same API), but unverified |
| FlameCord | n/a | | | **Not tested.** Expected to work (BungeeCord fork), but unverified |
| Spigot | n/a | | | **Unsupported.** Lacks the Paper profile API; NetworkLock loads a deny-all listener and stays in lockdown (logic unit-tested, not run on Spigot) |

## Requirements

- Java 21 or newer (bytecode targets 21).
- Backend: Paper API with `AsyncPlayerPreLoginEvent#getPlayerProfile/setPlayerProfile` (Paper 1.21+ tested).
- Proxy forwarding enabled: Velocity `modern` or `legacy`, or BungeeCord `ip_forward: true`.

## How one jar serves every platform

The jar contains `plugin.yml` (Bukkit family), `bungee.yml` (BungeeCord family, which prefers it over `plugin.yml`) and `velocity-plugin.json` (Velocity). Each names a different entry class. A platform only loads its own entry class, and no class references another platform's API, so nothing incompatible is ever linked. Core code is platform-free.

SnakeYAML is bundled and relocated (`dev.korinetwork.networklock.lib.snakeyaml`) and used with `SafeConstructor`, because platforms ship different versions. The manifest declares `paperweight-mappings-namespace: mojang`, so Paper skips its Spigot remapping pass.

## Known platform notes

- **Paper**: enable only one forwarding mode. With both `settings.bungeecord` and Velocity forwarding on, Paper 1.21.11 refuses Velocity connections; `doctor` reports this.
- **Paper 26.x** shipped with the whitelist enabled in our test; that rejection is Paper's, not NetworkLock's.
- **Velocity** keeps its own 3-second per-IP `login-ratelimit` in addition to NetworkLock's throttling.
- **BungeeCord family**: NetworkLock changes forwarded properties through `InitialHandler#loginProfile` by reflection (the API offers no way). If a future build renames it, the proxy refuses logins instead of forwarding unsigned connections, and `doctor` reports `FAIL proxy internals not recognised`.
