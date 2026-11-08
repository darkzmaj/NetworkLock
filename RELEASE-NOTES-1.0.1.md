# NetworkLock 1.0.1

NetworkLock makes sure players can only join your game servers through your proxies. 1.0.1 brings it to every Minecraft version from 1.8.8 to 26.3, fixes four security issues found in a full audit, and includes everything from the 1.0.1-dev test build.

## Runs on many more servers

- **Minecraft 1.8.8 to 26.3**, one jar. Game servers can run it on Java 8.
- **Spigot works now.** 1.0.0 refused every player on Spigot. 1.0.1 checks players properly on Spigot, old Paper (before 1.12.2) and 1.8 forks like PandaSpigot, without ProtocolLib.
- Tested on 23 servers: Paper (1.8.8 to 26.3), Spigot, PandaSpigot, Purpur, Pufferfish, Leaf, Gale, Folia, Kaiiju, SpongeVanilla. All passed. See [COMPATIBILITY-REPORT.md](docs/COMPATIBILITY-REPORT.md).
- CraftBukkit has no proxy forwarding, so NetworkLock lets nobody join there and `/networklock doctor` tells you why.

## Security fixes

| ID | Severity | Fix |
|---|---|---|
| NL-01 | Medium | Raising `max-token-age-seconds` with a reload could let an already-used signature through again. |
| NL-02 | Low | Player names are cleaned before they are logged, so a crafted name can't fake console lines. |
| NL-04 | Low | A login is still refused if building the kick message fails. |
| NL-05 | Low | A replayed signature is reported as a replay even when the replay memory is full (it was refused before too). |

Full details, a 30-threat model and a comparison with BungeeGuard: [SECURITY-AUDIT.md](docs/SECURITY-AUDIT.md).

## From the 1.0.1-dev build

- The console says exactly why a login was refused and how to fix it.
- Game servers can set their own kick texts under `messages:` in `config.yml` (shown by queue plugins like ajQueue).
- `/networklock about`, an update checker (`updates.check`), renameable commands, multi-line messages with clickable links.
- Anonymous usage statistics once a day (version and platform only). Turn off with `metrics.enabled: false`.

## Upgrading from 1.0.0

Replace the jar on your proxies and game servers and restart. Your config files keep working; new settings get their defaults. Keys don't change.

## Performance

- The proxy creates signatures 17% faster and game servers reject forged logins 25% faster than 1.0.0. Checking a valid login costs the same (about 6 µs).
- In a head-to-head test with real logins through Velocity, NetworkLock 1.0.0, 1.0.1 and BungeeGuard all logged players in equally fast (76–80 ms, almost all of it Paper's own join work), and both plugins refused every forged login.
- Full numbers, including one unexplained difference at 100 logins/s per server: [BENCHMARK-REPORT.md](docs/BENCHMARK-REPORT.md). Head-to-head scores: [COMPARISON-BOARD.md](docs/COMPARISON-BOARD.md).

## Checksum

`NetworkLock.jar` SHA-256: `acff588a17ae334fd8ae5a693a0979f70ad0c62ffbf2f08f921d8a8131f18721`

Support: https://kori.my
