# NetworkLock

**Players can only join your servers through your proxy. Nobody gets around it.**

If someone finds the address of one of your game servers, they can connect to it directly and log in as any player, admins included. NetworkLock stops that.

Your proxy gives every player a one-time pass that only works for that player, on that server, for 30 seconds. The server checks the pass before the player even loads in. No valid pass, no entry.

[![discord](https://cdn.modrinth.com/data/cached_images/c97c06e73051a5a1a196babde1652b0cac4b09f4.png)](https://discord.com/invite/4NF45wy2F2)

## Features

- **One jar for everything.** Put the same `NetworkLock.jar` on your proxy and on every server.
- **Blocks proxy bypass.** Direct connections are refused before the player joins.
- **Blocks fake players.** Nobody can log in with someone else's name or UUID.
- **Passes can't be reused.** Each one works once, on one server, for 30 seconds.
- **Several proxies.** Each proxy has its own key. If one leaks, remove just that one.
- **Login limit.** Too many logins from one IP get blocked for a while.
- **Your messages.** Every message players see is set on the proxy, with `&` colour codes.
- **Safe by default.** If the config is broken, the server lets nobody in instead of running unprotected.
- **No database, no extra plugins.**

## Setup

1. Put `NetworkLock.jar` in your proxy's `plugins` folder and start it once.
2. Open the proxy's `plugins/NetworkLock/config.yml` (on Velocity: `plugins/networklock/config.yml`) and copy the `key`.
3. Turn on player forwarding on the proxy (`modern` on Velocity, `ip_forward: true` on BungeeCord) and the matching setting on your game servers.
4. Put `NetworkLock.jar` on each server, start it once, and fill in `plugins/NetworkLock/config.yml`:

```yaml
network:
  server-id: "lobby"        # the server's name in your proxy config

authentication:
  trusted-proxies:
    - id: "proxy-1"
      keys: ["paste the proxy key here"]
```

5. Run `/networklock reload`, then `/networklock doctor` to check everything.

On Sponge, use the `mods` folder and `config/networklock/config.yml`, and turn on `ip-forwarding` in `config/sponge/sponge.conf`.

## Commands

| Command | What it does |
|---|---|
| `/networklock status` | Shows if protection is active |
| `/networklock reload` | Reloads the config (only if it's valid) |
| `/networklock doctor` | Finds setup mistakes |
| `/networklock stats` | Shows allowed and blocked logins |
| `/networklock proxies` | Shows key fingerprints to compare proxy and server |
| `/networklock version` | Shows the version |

Alias: `/nlock`. Permission: `networklock.admin` for everything, or `networklock.command.<name>` for each command.

## Supported versions

| Platform | Versions |
|---|---|
| Paper, Folia, Purpur, Pufferfish | Minecraft 1.21 to 26.3 |
| Sponge | Minecraft 1.21.10 to 26.3 |
| Velocity | 3.5 and newer, including 4.x |
| BungeeCord, Waterfall | 1.21 |

Players can join with any Minecraft version your proxy accepts. Needs Java 21 or newer (Java 25 for Minecraft 26.x).

**Plain Spigot and CraftBukkit are not supported**, because they are missing the Paper API that NetworkLock needs. On them it locks the server instead of running without protection.

## Good to know

- This is not DDoS protection.
- Still firewall your servers so only your proxies can reach them. NetworkLock is the lock behind that.
- If a proxy key leaks, remove it from your servers and run `/networklock reload`.

## Support

Questions or problems? Get help at **https://kori.my**

## License

Open source under the MIT license.
