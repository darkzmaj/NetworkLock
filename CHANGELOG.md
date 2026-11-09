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
- The used signature is now removed from the player's live profile on Sponge and Spigot 1.21.9+ too (NL-07).
- BungeeCord/Waterfall: a connect that never happens (already on that server, already connecting, cancelled, server down) no longer leaves an unused signature on the player's proxy profile (NL-10).
- A login flood from many addresses can no longer make the proxy scan its whole address table for every new connection (NL-11).
- Changing `replay-cache-max-entries` with a reload no longer forgets used signatures (NL-12).
- The update check stays off while no config is loaded, and the anonymous statistics ID can no longer be worked out from the key fingerprint shown in logs (NL-13, NL-14).
- Sponge also checks logins that another plugin cancelled before NetworkLock, in case a later plugin lets them back in (NL-15).

### Fixes
- No more false "disabled while the server is running" error on every Spigot shutdown.
- `server-id` values with spaces at the ends or more than 64 characters are config errors now, instead of refusing every login.
- A proxy server name that can't be signed is reported clearly, once, instead of looking like "the player did not come through a proxy".
- Listing the same key twice for one proxy no longer warns about a shared key.
- Plugin reloads by plugin managers no longer keep the old plugin's classes in memory.
- `/networklock stats` on game servers shows how full the replay memory is.
- Console texts no longer depend on the server's language settings.

### Performance
- Each thread reuses its crypto objects instead of setting them up for every login: signatures are created about 16% faster and forged ones rejected about 24% faster. Same signatures as before; see docs/BENCHMARK-REPORT.md.

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
