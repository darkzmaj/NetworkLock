# NetworkLock

**Makes sure players can only join your game servers through your proxy. One jar for the proxy and every server.**

Without protection, anyone who finds a game server's address can skip the proxy and join as any player. NetworkLock signs every login on the proxy with a one-time pass, and the game server refuses any login without a valid one. The same jar goes on Velocity or BungeeCord and on every Paper, Spigot, Folia or Sponge server.

## Features

- **Blocks proxy bypass.** Direct connections that skip the proxy are refused.
- **Blocks fake players.** Logins using another player's name or UUID without a valid pass are refused.
- **One-time passes.** Each pass works once and only for a short time, so a copied pass is useless.
- **Several proxies.** Each proxy gets its own key, and a leaked key can be removed on its own.
- **Login limit per IP** on the proxy.
- **Doctor command.** `/networklock doctor` finds setup mistakes, and the console says why a login was refused.
- **Update checker** and custom kick messages, with renameable commands.

## Supported platforms

- **Paper and forks:** Minecraft 1.8.8 to 26.3.
- **Spigot:** Minecraft 1.8.8 to 26.3, without ProtocolLib.
- **Folia and forks:** Minecraft 1.20.1 to 26.3.
- **Sponge:** Minecraft 1.21.10 to 26.3.
- **Velocity:** 3.5 and newer, including 4.x.
- **BungeeCord and Waterfall:** 1.21.
- **Java:** game servers run it on Java 8 and newer. Proxies need what their proxy software needs.
- **Not supported:** CraftBukkit. It has no proxy forwarding, so NetworkLock lets nobody in there and says why.

## Installation

1. Download `NetworkLock.jar` from one of the links below.
2. Put the same jar in the `plugins` folder of your proxy and every game server, and start them once.
3. Copy the proxy's key to `trusted-proxies` on each game server, set `network.server-id`, and run `/networklock reload`.

Full setup: [Install guide](https://kori.my/docs/networklock/install/).

## Privacy

Once a day NetworkLock tells kori.my that one more server runs it: an anonymous ID, the plugin version, the platform and whether it is a proxy or a game server. No IP addresses, server names or player data. It also checks GitHub for new versions. Turn them off with `metrics.enabled: false` and `updates.check: false`. Details: [Privacy Policy](https://kori.my/privacy/).

## Links

- **Documentation:** https://kori.my/docs/networklock/
- **Download:** [GitHub](https://github.com/darkzmaj/NetworkLock/releases/latest), [Modrinth](https://modrinth.com/plugin/networklock), [SpigotMC](https://www.spigotmc.org/resources/140207/)
- **Source code:** https://github.com/darkzmaj/NetworkLock
- **Support:** [Discord](https://darkzmaj.eu/discord) and https://kori.my

[![Join our Discord: darkzmaj.eu/discord](https://kori.my/assets/img/brand/discord-banner.png)](https://darkzmaj.eu/discord)

Free and open source under the MIT licence. Made by DarkZmaj for KORI Network.

## For developers

- Reports: [security audit](docs/SECURITY-AUDIT.md), [threat model](docs/THREAT-MODEL.md), [compatibility](docs/COMPATIBILITY-REPORT.md), [long-run test](docs/TEST-REPORT.md), [benchmarks](docs/BENCHMARK-REPORT.md), [comparison](docs/COMPARISON-BOARD.md).
- Every config key: [docs/CONFIGURATION.md](docs/CONFIGURATION.md). Changes: [CHANGELOG.md](CHANGELOG.md).
- Contributing: [CONTRIBUTING.md](CONTRIBUTING.md). Reporting a vulnerability: [SECURITY.md](SECURITY.md).
