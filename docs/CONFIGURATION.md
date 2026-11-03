# Configuration

NetworkLock uses two files:

- `config.yml` on every server and proxy (on Sponge: `config/networklock/config.yml`)
- `messages.yml` on proxies only

The default files only contain the settings most people change. Everything else is listed below; add a line to the file if you want to change it. A reload checks the whole file first and only applies it if everything is valid.

## Where the key comes from

Start the proxy once. NetworkLock creates a random key and saves it in the proxy's `config.yml` as `security.key`. Copy that key into the `trusted-proxies` list on each game server.

Every proxy gets its own key. Run `/networklock proxies` on both sides to compare short fingerprints of the keys, so you can check they match without showing the key itself.

## Proxy config.yml

| Setting | Default | What it does |
|---|---|---|
| `network.id` | `proxy-1` | Name of this proxy. Use a different one on every proxy. |
| `security.key` | created on first start | The secret key. Can also be `env:VARIABLE` or `file:/path/to/file`. |
| `protection.connection-limit.enabled` | `true` | Limit how often one IP can try to log in. |
| `protection.connection-limit.max-attempts` | `10` | Logins allowed per IP... |
| `protection.connection-limit.window-seconds` | `10` | ...within this many seconds. |
| `protection.temporary-blocks.enabled` | `true` | Block IPs that keep going over the limit. |
| `protection.temporary-blocks.duration-seconds` | `60` | How long a block lasts. |
| `protection.exempt` | `[]` | IPs or ranges that are never limited, for example `["10.0.0.0/8"]`. |
| `commands.name` | `networklock` | Name of the command. Restart to apply. |
| `commands.aliases` | `["nlock"]` | Other names for the command. Restart to apply. |
| `updates.check` | `true` | Look for new versions on GitHub at startup and every 12 hours. |
| `updates.notify` | `true` | Tell staff with `networklock.notify` about a new version when they join. |
| `metrics.enabled` | `true` | Count this proxy in the public "servers protected" number. See [Usage statistics](#usage-statistics). |
| `logging.debug` | `false` | Log every player the proxy signs. |

Extra settings, not in the default file:

| Setting | Default | What it does |
|---|---|---|
| `protection.connection-limit.ipv6-prefix-length` | `64` | IPv6 addresses in the same block of this size count as one IP. |
| `protection.temporary-blocks.violations-before-block` | `5` | How many times an IP can go over the limit before it is blocked. |
| `protection.flood-warning-threshold` | `200` | Logins per second, across all IPs, that trigger a warning in the console. |
| `protection.max-tracked-addresses` | `100000` | Memory cap for the login limiter. |
| `logging.security-events` | `true` | Log blocked logins (limited to 20 lines every 10 seconds). |
| `logging.redact-addresses` | `false` | Hide the end of IP addresses in NetworkLock's log lines. |

## Game server config.yml

| Setting | Default | What it does |
|---|---|---|
| `network.server-id` | empty, must be set | This server's name in your proxy config, for example `lobby`. |
| `authentication.trusted-proxies` | empty, must be set | The proxies allowed to send players here, each with `id` and `keys`. A key can also be `env:VARIABLE` or `file:/path`. |
| `messages.authentication-failed` | not set | Optional text refused players see, instead of the default. See below. |
| `messages.authentication-timeout` | not set | Same, for an expired signature. |
| `messages.security-error` | not set | Same, when this server is locked down. |
| `commands.name` / `commands.aliases` | `networklock` / `["nlock"]` | Command name and aliases. Restart to apply. |
| `updates.check` | `true` | Look for new versions on GitHub. |
| `metrics.enabled` | `true` | Count this server in the public "servers protected" number. |
| `logging.debug` | `false` | Log every accepted player. |

Extra settings, not in the default file:

| Setting | Default | What it does |
|---|---|---|
| `authentication.max-token-age-seconds` | `30` | How long a proxy's signature stays valid. |
| `authentication.max-clock-skew-seconds` | `5` | How far ahead a proxy's clock may be. Keep server clocks synced. |
| `authentication.replay-cache-max-entries` | `100000` | How many used signatures to remember. If it fills up, logins are refused. |
| `logging.security-events` | `true` | Log refused logins. |
| `logging.redact-addresses` | `false` | Hide the end of IP addresses in log lines. |

There is no setting to switch the protection off. If you want it off, remove the plugin.

## messages.yml (proxy only)

Messages use `&` colour codes:

- `&0` to `&9` and `&a` to `&f` for colours
- `&l` bold, `&o` italic, `&n` underline, `&m` strikethrough, `&k` obfuscated, `&r` reset
- `&#ff8800` for any hex colour

MiniMessage tags like `<aqua>` also work, and you can mix them with `&` codes.

The available messages are `prefix`, `no-permission`, `reloaded`, `reload-failed`, `invalid-command`, `authentication-failed`, `authentication-timeout`, `connection-limited`, `temporarily-blocked`, `security-error`, `about`, `status-latest`, `status-outdated`, `status-unknown` and `update-available`. If one is missing from the file, the built-in text is used.

Any message can be a list of lines instead of one line:

```yaml
messages:
  about:
    - "&b&lNetworkLock &7v%version%"
    - "&7Version: %status%"
```

Placeholders: `about` has `%version%`, `%status%`, `%notify%` and `%servers%`; `status-outdated` has `%latest%`; `update-available` has `%current%`, `%latest%` and `%link%`. Links become clickable.

When a game server refuses a player, it sends a short default text with a code, and the proxy swaps it for your message:

| Code | Reason | Message used |
|---|---|---|
| NL-01 | Player came without a signature (direct connection) | `authentication-failed` |
| NL-02 | Signature is wrong, from an unknown proxy, or for another server | `authentication-failed` |
| NL-03 | Signature expired | `authentication-timeout` |
| NL-04 | Signature was already used | `authentication-failed` |
| NL-05 | Server is locked down or hit an internal error | `security-error` |

Queue plugins like ajQueue send players with their own connection method, which the proxy can't intercept, so they show the game server's text as it is. To change what they show, set the same message keys under `messages:` in the game server's `config.yml`:

```yaml
messages:
  authentication-failed:
    - "&c&lMy Server"
    - "&7Please join through &bplay.example.net"
```

When a game server has its own text, the proxy shows that text instead of its `messages.yml` one.

## Finding out why a player was refused

Every refused login is logged on the game server with the reason and what to do about it, for example:

```
Denied login name=Steve ... reason=WRONG_TARGET: signed for server 'smp', but this server's network.server-id is 'bloodysmp'. They must match the server name in the proxy config
```

`/networklock doctor` checks the whole setup.

## Usage statistics

With `metrics.enabled: true` (the default), each proxy and game server sends one small message a day over HTTPS, so we can show how many servers NetworkLock protects. It contains:

- an anonymous ID: a one-way hash, so the same server is only counted once, and it can't be turned back into anything
- the NetworkLock version
- the platform (for example `velocity` or `paper`) and whether it is a proxy or a game server

It never contains IP addresses, player names, server names or keys. Set `metrics.enabled: false` and run `/networklock reload` to turn it off.

## Changing a key without downtime

1. Make a new key, for example with `openssl rand -base64 32`.
2. On every game server, add the new key next to the old one in that proxy's `keys` list, then run `/networklock reload`.
3. Put the new key in the proxy's `security.key` and run `/networklock reload` there.
4. Remove the old key from the game servers and reload again.

If a key gets leaked, remove it (or the whole proxy entry) from every game server and reload. It stops working straight away.

## Switching from BungeeGuard

Remove BungeeGuard from the proxy and the game servers, install NetworkLock on both, and copy each proxy's key into the game servers' `trusted-proxies`. If Velocity is set to `bungeeguard` forwarding you can keep it; `modern` is recommended.
