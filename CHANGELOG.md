# Changelog

## 1.0.1

Everything from 1.0.1-dev below, plus:

### Compatibility
- Runs on Minecraft 1.8.8 to 26.3. The same jar loads on Java 8 servers.
- Spigot and old Paper versions (before 1.12.2), which 1.0.0 locked, now check players properly, without ProtocolLib.
- Works with authlib 7 (Spigot 1.21.9 and newer).
- CraftBukkit has no proxy forwarding, so it stays locked; `/networklock doctor` now says so.
- Tested on 23 servers, including Folia, Kaiiju, Purpur, Pufferfish, Leaf, Gale, PandaSpigot and SpongeVanilla.

### Security fixes
- Raising `max-token-age-seconds` with a reload could let an already-used signature be accepted again (NL-01).
- Player names are cleaned before they are logged, so a crafted name can't forge console lines (NL-02).
- Refusing a login can no longer fail open if building the kick message goes wrong (NL-04).
- A replayed signature is reported as a replay even when the replay memory is full (NL-05).

### Performance
- Each thread reuses its crypto objects instead of setting them up for every login: signatures are created 17% faster and forged ones rejected 25% faster. Same signatures as before; see docs/BENCHMARK-REPORT.md.

## 1.0.1-dev (experimental, GitHub pre-release only)

- The console now says exactly why a login was refused and how to fix it (for example a `server-id` that doesn't match the proxy).
- Game servers can set their own kick texts under `messages:` in `config.yml`. Queue plugins like ajQueue show these.
- New `/networklock about`: version, LATEST or OUTDATED, update notify setting and server count. `/networklock version` still works.
- Update checker: looks for new versions on GitHub and tells staff with `networklock.notify` when they join. Can be turned off.
- Command name and aliases can be changed in `config.yml`.
- Messages can span several lines (a YAML list) and links in them are clickable.
- Anonymous usage statistics, once a day, to count how many servers NetworkLock protects. On by default, turn off with `metrics.enabled: false`.

## 1.0.0

First release.

- Players can only join game servers through your proxies.
- Each player gets a one-time signature tied to their UUID, name, IP and target server, valid for 30 seconds.
- One jar for Velocity, BungeeCord, Waterfall, Paper, Folia, Purpur, Pufferfish and Sponge.
- A separate key per proxy, with key changes without downtime.
- Login limit per IP with temporary blocks.
- Messages with `&` colour codes, set on the proxy.
- Commands: help, status, reload, doctor, stats, proxies, version.
