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
| SpongeVanilla | 1.21.11 (API 18, RC2761) | 21 | Works. Tested directly and behind Velocity with Sponge `LEGACY` and `MODERN` forwarding. |
| SpongeVanilla | 26.3 (API 21, RC2764) | 25 | Works. |

Spigot and CraftBukkit are missing the Paper API that NetworkLock uses to check players before they join, so it locks the server rather than running without protection.

## Proxies

| Proxy | Version tested | Java | Result |
|---|---|---|---|
| Velocity | 3.5.1 (build 615) | 21 | Works with `modern`, `legacy` and `bungeeguard` forwarding. |
| Velocity | 4.3.1 (build 45) | 25 | Works with `modern` forwarding. |
| Waterfall | 1.21 (build 615) | 21 | Works. |
| BungeeCord | latest Jenkins build, downloaded 2026-11-01 | 21 | Works. |
| FlameCord | not tested | | No official build could be found. It is a BungeeCord fork, so it should work. |

## Supported versions

| Platform | Supported | Tested |
|---|---|---|
| Paper, Folia, Purpur, Pufferfish | Minecraft 1.21 to 26.3 | 1.21.10, 1.21.11, 26.3 |
| Sponge | Minecraft 1.21.10 to 26.3 (Sponge API 17 and newer) | 1.21.11, 26.3 |
| Velocity | 3.5 and newer, including 4.x | 3.5.1, 4.3.1 |
| BungeeCord | 1.21 builds | latest build |
| Waterfall | 1.21 (its final version) | 1.21 |

Players can join with any Minecraft version your proxy accepts.

## Requirements

- Java 21 or newer (Minecraft 26.x servers need Java 25).
- Game servers: Paper or a Paper fork, or Sponge.
- Proxy forwarding switched on: `modern`, `legacy` or `bungeeguard` on Velocity, `ip_forward: true` on BungeeCord and Waterfall.

## How one jar runs everywhere

The jar contains a `plugin.yml` for Paper, a `bungee.yml` for BungeeCord, a `velocity-plugin.json` for Velocity and a `META-INF/sponge_plugins.json` for Sponge. Each server reads only its own file and loads only its own start class, so code for the other platforms is never touched.

## Things worth knowing

- Paper: turn on only one forwarding mode. If both BungeeCord mode and Velocity forwarding are on, Paper refuses Velocity players. `/networklock doctor` warns about this.
- Paper 26.x had its whitelist switched on in our tests. If players get "not whitelisted", that comes from Paper, not NetworkLock.
- Velocity has its own 3-second login limit per IP on top of NetworkLock's.
- Sponge: put the jar in `mods/`. The config is `config/networklock/config.yml`. Turn on forwarding in `config/sponge/sponge.conf` (`modules.ip-forwarding=true` and `ip-forwarding.mode=LEGACY` or `MODERN`). Sponge profiles cannot be changed by plugins, so a used signature stays in the player's profile; it is useless because it only ever works once.
- On BungeeCord and Waterfall, NetworkLock has to reach into a proxy internal to attach its signature. If a future proxy update changes that internal, the proxy refuses logins instead of letting unsigned players through, and `doctor` tells you.
