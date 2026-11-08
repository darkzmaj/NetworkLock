# Compatibility report: NetworkLock 1.0.1

One file, `NetworkLock.jar`, was tested on every server below. Nothing was rebuilt per version: a second jar was never needed. Tested 2026-11-08 on Windows 11 (i7-14700KF) with Corretto 8, Temurin 17, Temurin 21 and Temurin 25. Server jars came from the official download sites and were checksum-checked where a checksum is published (Paper, Folia, Velocity and Waterfall SHA-256; Purpur MD5; Gale GitHub release digest). Spigot and CraftBukkit were built with BuildTools.

## How one jar runs on 1.8 through 26.3

| Part | Compiled for | Why |
|---|---|---|
| Shared core, Bukkit-family plugin | Java 8 (class version 52) | 1.8 to 1.16 servers usually run Java 8 |
| Velocity and BungeeCord plugins | Java 17 | never loaded by game servers |
| Sponge plugin | Java 21 | Sponge API 17+ needs it |

Each server reads only its own descriptor (`plugin.yml`, `bungee.yml`, `velocity-plugin.json`, `META-INF/sponge_plugins.json`) and loads only its own start class, so a Java 8 server never touches the Java 17/21 classes. `plugin.yml` declares `api-version: "1.13"`: 1.13+ servers accept it, older ones ignore the field.

On Bukkit-family servers NetworkLock picks the login hook at startup:

| Server has | Hook | Servers |
|---|---|---|
| Paper profile API | `AsyncPlayerPreLoginEvent` (async, before the player exists) | Paper 1.12.2+, Folia and all their forks |
| No Paper profile API | `PlayerLoginEvent`, reading the forwarded profile from the server's own `GameProfile` | Spigot, Paper before 1.12.2, 1.8 forks such as PandaSpigot |
| No proxy forwarding at all | none possible: every login refused, doctor explains | CraftBukkit |

Both hooks run before the player is placed in the world, and neither needs ProtocolLib.

## Game servers (`it/matrix.sh`)

Checks per server: forged UUID without signature, junk signature, signature for another server, valid signature accepted, the same signature replayed and refused, used signature removed from the profile, `doctor` output, and no NetworkLock errors in the console.

| Server | Minecraft (protocol) | Java | Hook | Result |
|---|---|---|---|---|
| Paper 445 | 1.8.8 (47) | 8 | PlayerLoginEvent | ✅ 7/7 |
| Spigot (BuildTools) | 1.8.8 (47) | 8 | PlayerLoginEvent | ✅ 7/7 |
| PandaSpigot 226 | 1.8.8 (47) | 8 | PlayerLoginEvent | ✅ 7/7 |
| Paper 1620 | 1.12.2 (340) | 8 | Paper API | ✅ 7/7 |
| Paper 794 | 1.16.5 (754) | 8 | Paper API | ✅ 7/7 |
| Purpur 1171 | 1.16.5 (754) | 8 | Paper API | ✅ 7/7 |
| Paper 411 | 1.17.1 (756) | 17 | Paper API | ✅ 7/7 |
| Paper 388 | 1.18.2 (758) | 17 | Paper API | ✅ 7/7 |
| Paper 550 | 1.19.4 (762) | 17 | Paper API | ✅ 7/7 |
| Kaiiju 240 (Folia fork) | 1.20.1 (763) | 17 | Paper API | ✅ 7/7 |
| Paper 499 | 1.20.4 (765) | 17 | Paper API | ✅ 7/7 |
| Paper 151 | 1.20.6 (766) | 21 | Paper API | ✅ 7/7 |
| Paper 132 | 1.21.11 (774) | 21 | Paper API | ✅ 7/7 |
| Folia 14 | 1.21.11 (774) | 21 | Paper API | ✅ 7/7 |
| Purpur 2568 | 1.21.11 (774) | 21 | Paper API | ✅ 7/7 |
| Pufferfish 39 | 1.21.10 (773) | 21 | Paper API | ✅ 7/7 |
| Spigot (BuildTools) | 1.21.11 (774) | 21 | PlayerLoginEvent | ✅ 6/6 ⚠️ used signature stays in the read-only profile (harmless) |
| CraftBukkit (BuildTools) | 1.21.11 (774) | 21 | none | ✅ 5/5, every login refused as expected |
| SpongeVanilla RC2761 | 1.21.11 (774) | 21 | Sponge auth event | ✅ 6/6 ⚠️ profile read-only (harmless) |
| Gale R2.3.0 | 26.2 (776) | 25 | Paper API | ✅ 7/7 |
| Paper 236 | 26.3 (777) | 25 | Paper API | ✅ 7/7 |
| Leaf 22 | 26.3 (777) | 25 | Paper API | ✅ 7/7 |
| SpongeVanilla RC2764 | 26.3 (777) | 25 | Sponge auth event | ✅ 6/6 ⚠️ profile read-only (harmless) |

23 servers, 23 passed. "Harmless" means the used signature can be seen in that player's profile but cannot be used again: it is tied to that player and server, already marked as used, and expires within 30 seconds.

## Proxies and full networks (`it/run-integration.sh`)

| Setup | Result |
|---|---|
| Paper 1.21.11 + Velocity 3.5.1 (`legacy`) + Waterfall 1.21 | ✅ 15/15 |
| Folia 1.21.11 + Velocity 3.5.1 (`bungeeguard`) + Waterfall 1.21 | ✅ 15/15 |
| Velocity 3.5.1, `modern` forwarding, Paper 1.21.11 | ✅ by hand on 1.0.0, not re-run for 1.0.1 |
| Velocity 4.3.1 + Paper 26.3, Java 25 | ✅ by hand on 1.0.0, not re-run for 1.0.1 |
| BungeeCord (latest build) | ✅ by hand on 1.0.0, not re-run for 1.0.1 |

The 15 checks: five direct attacks, a valid signature accepted once and its replay refused, joins through Velocity and Waterfall verified by the backend, a refused player seeing the proxy's own `&c` message, and a broken reload being refused while protection keeps working.

## Supported versions

| Platform | Supported | Lowest and highest tested |
|---|---|---|
| Paper and forks (Purpur, Pufferfish, Leaf, Gale, PandaSpigot, ...) | 1.8.8 to 26.3 | 1.8.8, 26.3 |
| Folia and forks (Kaiiju, ...) | 1.20.1 to 26.3 | 1.20.1, 1.21.11 |
| Spigot | 1.8.8 to 26.3 | 1.8.8, 1.21.11 |
| Sponge | 1.21.10 to 26.3 (API 17+) | 1.21.11, 26.3 |
| Velocity | 3.5+, including 4.x | 3.5.1, 4.3.1 |
| BungeeCord, Waterfall | 1.21 | 1.21 |

Players can join with any client version the proxy accepts (ViaVersion etc. are unaffected).

## Not tested

- ⏭️ Hybrid servers (Arclight, Mohist: Bukkit on Forge/NeoForge/Fabric). They need their mod loader installed; not covered.
- ⏭️ Other Folia forks (Luminol, Canvas): no official downloadable builds could be found.
- ⏭️ Spigot versions between 1.8.8 and 1.21.11, and Paper 1.9 to 1.11. Both hooks are the same code on every version and were proven at both ends, but those versions were not started.
- ⏭️ FlameCord: no official build available.
- ⏭️ Linux and macOS game servers in this round (GitHub Actions runs the unit tests and the Paper integration on Linux).

## Notes for server owners

- Turn on only one forwarding mode on Paper. With both BungeeCord mode and Velocity forwarding on, Paper refuses Velocity players; `/networklock doctor` reports it.
- Spigot and 1.8 servers: turn on `settings.bungeecord: true` in `spigot.yml`.
- Sponge: jar in `mods/`, config in `config/networklock/config.yml`, forwarding on in `config/sponge/sponge.conf`.
- Leaf asks for a restart on its very first start (its own loader); that is not NetworkLock.
