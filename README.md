# NetworkLock

**NetworkLock makes sure players can only join your Minecraft servers through your proxy.**

On a network, players connect to a proxy (Velocity or BungeeCord), and the proxy sends them on to your game servers. If someone finds a game server's address, they can try to connect to it directly and pretend to be any player, including an admin. NetworkLock blocks that.

- The proxy stamps every player it sends with a secret signature that only works once, for that one player, for that one server, for 30 seconds.
- The game server lets a player in only if the stamp is valid. Everyone else is turned away before they even load in.

You put the **same `NetworkLock.jar`** on your proxies and your game servers, and it works out where it is running.

## Works on

Velocity, BungeeCord and Waterfall (proxies), and Paper, Folia, Purpur, Pufferfish and Sponge (game servers), Minecraft 1.21 to 26.3. Java 21 or newer. Plain Spigot and CraftBukkit are not supported. See the [compatibility matrix](docs/COMPATIBILITY.md) for exact tested versions.

## Setup

1. **On the proxy:** drop `NetworkLock.jar` into `plugins/` and start it once. NetworkLock creates your secret key in `plugins/NetworkLock/config.yml` (on Velocity: `plugins/networklock/config.yml`) under `security.key`. That's the key you copy to your servers in step 3. Give each proxy its own `network.id`.
2. **Turn on player forwarding:** in `velocity.toml` set `player-info-forwarding-mode = "modern"`, or on BungeeCord set `ip_forward: true`.
3. **On each game server:** turn on the matching forwarding in Paper (`proxies.velocity` in `config/paper-global.yml`, **or** `settings.bungeecord: true` in `spigot.yml`, but not both). Drop in `NetworkLock.jar`, start once, then edit `plugins/NetworkLock/config.yml`:

   ```yaml
   network:
     server-id: "lobby"            # this server's name in your proxy config
   authentication:
     trusted-proxies:
       - id: "proxy-1"             # the proxy's network.id
         keys: ["<the proxy's key>"]
   ```

4. Run `/networklock reload`, then `/networklock doctor` to check for problems.

On Sponge, put the jar in `mods/` instead, edit `config/networklock/config.yml`, and turn on forwarding in `config/sponge/sponge.conf`.

Until a game server is configured, it lets nobody in. That is on purpose: it never runs unprotected.

## Commands

`/networklock` (or `/nlock`): `help`, `status`, `reload`, `doctor`, `stats`, `proxies`, `version`. Each needs `networklock.command.<name>`, or `networklock.admin` for all of them. Keys are never shown, only a short fingerprint so you can check that proxy and server match.

## Also included

- Limits on how fast one address can try to log in, with short temporary blocks for spammers.
- Customisable player messages in `messages.yml` (proxy only), with `&a`-style colour codes (MiniMessage also works).
- Several proxies with their own keys, and key changes without downtime.

## Good to know

- It is not DDoS protection.
- Still firewall your game servers so only your proxies can reach them.
- If someone steals a proxy's key, remove that key from your servers and reload.

## More detail

- [Configuration and key rotation](docs/CONFIGURATION.md)
- [How the security works](docs/THREAT-MODEL.md)
- [Test results](docs/TEST-REPORT.md)
- [Security policy](SECURITY.md) · [Changelog](CHANGELOG.md)

## Building

```bash
./gradlew test shadowJar     # needs JDK 21; output: build/libs/NetworkLock.jar
it/run-integration.sh        # optional: tests against real Paper, Velocity and Waterfall servers
```

Dependency versions are pinned in `gradle.lockfile` files. After changing a dependency, refresh them with `./gradlew dependencies :networklock-core:dependencies :networklock-bukkit:dependencies :networklock-velocity:dependencies :networklock-bungee:dependencies --write-locks`.

## Support

Questions or problems: https://kori.my

## License

MIT, made by DarkZmaj. See [LICENSE](LICENSE). NetworkLock bundles SnakeYAML (Apache 2.0), see [THIRD-PARTY-NOTICES.txt](THIRD-PARTY-NOTICES.txt).
