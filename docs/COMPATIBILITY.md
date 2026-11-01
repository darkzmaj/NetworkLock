# Compatibility

Every server below was tested with the same `NetworkLock.jar` (1.0.0). Server jars came from the official download sites and were checksum-checked where a checksum is published.

## Game servers

| Server | Version tested | Java | Result |
|---|---|---|---|
| Paper | 1.21.11 (build 132) | 21 | Works. Tested with BungeeCord and Velocity forwarding. |
| Paper | 26.3 (build 236) | 25 | Works. Tested behind Velocity 4.3.1. |
| Folia | 1.21.11 (build 14) | 21 | Works. Tested directly and behind Velocity and Waterfall. |
| Purpur | 1.21.11 (build 2568) | 21 | Works. |
| Pufferfish | 1.21.10 (build 39) | 21 | Works. |
| Spigot | 1.21.11 (BuildTools) | 21 | Not supported. NetworkLock loads, says why in the console, and lets nobody join. |
| CraftBukkit | 1.21.11 (BuildTools) | 21 | Not supported. Same as Spigot: lets nobody join. |
| SpongeVanilla | 1.21.11 (18.0.0-RC2761) | 21 | Not supported. Sponge ignores the jar, so the server runs **without** protection. |

Spigot and CraftBukkit are missing the Paper API that NetworkLock uses to check players before they join, so it locks the server rather than running without protection. Sponge uses a different plugin system and does not load NetworkLock at all.

## Proxies

| Proxy | Version tested | Java | Result |
|---|---|---|---|
| Velocity | 3.5.1 (build 615) | 21 | Works with `modern`, `legacy` and `bungeeguard` forwarding. |
| Velocity | 4.3.1 (build 45) | 25 | Works with `modern` forwarding. |
| Waterfall | 1.21 (build 615) | 21 | Works. |
| BungeeCord | latest Jenkins build, downloaded 2026-11-01 | 21 | Works. |
| FlameCord | not tested | | No official build could be found. It is a BungeeCord fork, so it should work. |

## Requirements

- Java 21 or newer.
- Game servers: Paper or a Paper fork, 1.21 or newer.
- Proxy forwarding switched on: `modern`, `legacy` or `bungeeguard` on Velocity, `ip_forward: true` on BungeeCord and Waterfall.

## How one jar runs everywhere

The jar contains a `plugin.yml` for Paper, a `bungee.yml` for BungeeCord and a `velocity-plugin.json` for Velocity. Each server reads only its own file and loads only its own start class, so code for the other platforms is never touched.

## Things worth knowing

- Paper: turn on only one forwarding mode. If both BungeeCord mode and Velocity forwarding are on, Paper refuses Velocity players. `/networklock doctor` warns about this.
- Paper 26.x had its whitelist switched on in our tests. If players get "not whitelisted", that comes from Paper, not NetworkLock.
- Velocity has its own 3-second login limit per IP on top of NetworkLock's.
- On BungeeCord and Waterfall, NetworkLock has to reach into a proxy internal to attach its signature. If a future proxy update changes that internal, the proxy refuses logins instead of letting unsigned players through, and `doctor` tells you.
