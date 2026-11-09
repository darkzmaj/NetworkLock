# NetworkLock 1.0.1

NetworkLock makes sure players can only join your game servers through your proxies. 1.0.1 brings it to every Minecraft version from 1.8.8 to 26.3, fixes the security issues found in two full audits, and includes everything from the 1.0.1-dev test build.

## Runs on many more servers

- **Minecraft 1.8.8 to 26.3**, one jar. Game servers can run it on Java 8.
- **Spigot works now.** 1.0.0 refused every player on Spigot. 1.0.1 checks players properly on Spigot, old Paper (before 1.12.2) and 1.8 forks like PandaSpigot, without ProtocolLib.
- Tested on 23 servers: Paper (1.8.8 to 26.3), Spigot, PandaSpigot, Purpur, Pufferfish, Leaf, Gale, Folia, Kaiiju, SpongeVanilla. All passed. See [COMPATIBILITY-REPORT.md](docs/COMPATIBILITY-REPORT.md).
- CraftBukkit has no proxy forwarding, so NetworkLock lets nobody join there and `/networklock doctor` tells you why.
- A 3-hour test on a network with two proxies (Velocity and Waterfall) and two backends (Paper and Folia), with reloads, a key change, backend restarts and constant attack traffic, accepted every valid login outside the restarts, let no forged login in, and showed no memory, thread or handle growth. See [TEST-REPORT.md](docs/TEST-REPORT.md).

## Security fixes

| ID | Severity | Fix |
|---|---|---|
| NL-01 | Medium | Raising `max-token-age-seconds` with a reload could let an already-used signature through again. |
| NL-02 | Low | Player names are cleaned before they are logged, so a crafted name can't fake console lines. |
| NL-04 | Low | A login is still refused if building the kick message fails. |
| NL-05 | Low | A replayed signature is reported as a replay even when the replay memory is full (it was refused before too). |
| NL-07 | Low | The used signature is now removed from the live profile on Sponge and Spigot 1.21.9+ too. |
| NL-10 | Medium | BungeeCord/Waterfall: a connect that never happens no longer leaves an unused signature on the player's proxy profile. |
| NL-11 | Medium | A login flood from many addresses can't make the proxy scan its whole address table per connection any more. |
| NL-12 | Low | Changing the replay memory size with a reload no longer forgets used signatures. |
| NL-13, NL-14 | Low | Update check off while no config is loaded; the anonymous statistics ID can't be derived from the key fingerprint shown in logs. |
| NL-15 | Low | Sponge also checks logins another plugin cancelled first. |

Plus clearer diagnostics: no false error on Spigot shutdowns, bad `server-id` values caught when the config loads, unsignable server names reported clearly, replay memory shown in `/networklock stats`.

Full details, a 30-threat model and a comparison with BungeeGuard: [SECURITY-AUDIT.md](docs/SECURITY-AUDIT.md).

## From the 1.0.1-dev build

- The console says exactly why a login was refused and how to fix it.
- Game servers can set their own kick texts under `messages:` in `config.yml` (shown by queue plugins like ajQueue).
- `/networklock about`, an update checker (`updates.check`), renameable commands, multi-line messages with clickable links.
- Anonymous usage statistics once a day (version and platform only). Turn off with `metrics.enabled: false`.

## Upgrading from 1.0.0

Replace the jar on your proxies and game servers and restart. Your config files keep working; new settings get their defaults. Keys don't change.

Two things you might notice: a `network.server-id` with spaces at the start or end (or longer than 64 characters) is now reported as a config error at startup; it could never match the proxy's server name anyway. And the anonymous statistics ID changes once with this update.

## Performance

- The proxy creates signatures about 16% faster and game servers reject forged logins about 24% faster than 1.0.0. Checking a valid login costs the same as before (4–6 µs, depending on the run).
- In a head-to-head test with real logins through Velocity, NetworkLock 1.0.0, 1.0.1 and BungeeGuard all logged players in equally fast (76–80 ms, almost all of it Paper's own join work), and both plugins refused every forged login.
- An apparent 1.0.1 slowdown at 100 logins/s turned out to be Paper's own join limit (`max-joins-per-tick: 5`, i.e. 100 new players per second per server); it affects every plugin and version alike. Full numbers: [BENCHMARK-REPORT.md](docs/BENCHMARK-REPORT.md). Head-to-head scores: [COMPARISON-BOARD.md](docs/COMPARISON-BOARD.md).

## Checksum

`NetworkLock.jar` SHA-256: `dac1de11bfee44c9a2a6b5a37e8bf67552f4e584c505626559438eeb2afeb51e`

Support: https://kori.my
