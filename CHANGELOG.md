# Changelog

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
