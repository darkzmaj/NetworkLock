# Configuration

NetworkLock reads exactly two files from its data folder:

- `config.yml` on every server,
- `messages.yml` on proxies only.

Backends never create `messages.yml`. Every file is validated as a whole: a reload applies all of it or none of it.

The default files contain only the common settings. The rest are listed below with their defaults; add them to the file if you need to change them.

## Where the key comes from

Start the proxy once. NetworkLock writes a random key into the proxy's `config.yml` under `security.key`. Copy that value into every server's `authentication.trusted-proxies` entry for that proxy. Each proxy gets its own key. `/networklock proxies` shows a fingerprint on both sides so you can check they match without showing the key.

## Proxy `config.yml`

| Key | Default | Meaning |
|---|---|---|
| `network.id` | `proxy-1` | Unique proxy id, `A-Z a-z 0-9 . _ -`, max 64. Backends list it. |
| `security.key` | generated | 32-byte base64url key. Also accepts `env:VAR` or `file:/path`. Generated only when the value is `""`; never overwritten. |
| `protection.connection-limit.enabled` | `true` | Per-address login throttling |
| `protection.connection-limit.max-attempts` | `10` | Logins per window, which is also the burst size |
| `protection.connection-limit.window-seconds` | `10` | Window length; tokens refill continuously |
| `protection.connection-limit.ipv6-prefix-length` | `64` | IPv6 grouping prefix (32–128) |
| `protection.temporary-blocks.enabled` | `true` | Block repeat offenders |
| `protection.temporary-blocks.violations-before-block` | `5` | Throttled attempts in a row before a block |
| `protection.temporary-blocks.duration-seconds` | `60` | Block length |
| `protection.exempt` | `[]` | IPs or CIDR ranges never throttled (hostnames are rejected) |
| `protection.flood-warning-threshold` | `200` | Network-wide attempts per second that trigger a warning |
| `protection.max-tracked-addresses` | `100000` | Memory bound for the address table |
| `logging.security-events` | `true` | Log denials and throttling (rate-limited) |
| `logging.redact-addresses` | `false` | Mask client IPs in NetworkLock log lines |
| `logging.debug` | `false` | Log every signed connection |

## Backend `config.yml`

| Key | Default | Meaning |
|---|---|---|
| `network.server-id` | `""` (required) | This server's name in the proxy config. Credentials signed for another name are rejected. |
| `authentication.trusted-proxies` | `[]` (required) | List of `{id, keys: [...]}`. A single `key:` is also accepted. Each key may be `env:VAR` or `file:/path`. |
| `authentication.max-token-age-seconds` | `30` | Credential lifetime (5–300) |
| `authentication.max-clock-skew-seconds` | `5` | Allowed proxy clock lead (0–60). Keep clocks NTP-synced. |
| `authentication.replay-cache-max-entries` | `100000` | Replay-cache ceiling. Logins fail closed beyond it. |
| `logging.*` | as proxy | |

There's deliberately no option to turn authentication off. To stop enforcing, remove the plugin; NetworkLock never reports a backend as protected while it isn't.

## Proxy `messages.yml`

Messages use `&` colour codes by default: `&0`–`&9` and `&a`–`&f` for colours, `&l &o &n &m &k` for styles, `&r` to reset, and `&#rrggbb` for hex colours. MiniMessage tags such as `<aqua>` work too, and can be mixed with `&` codes. Keys: `prefix`, and under `messages:` `no-permission`, `reloaded`, `reload-failed`, `invalid-command`, `authentication-failed`, `authentication-timeout`, `connection-limited`, `temporarily-blocked`, `security-error`.

Missing keys fall back to built-in defaults, and values longer than 1024 characters are rejected. On BungeeCord-family proxies, colours and styles are rendered; MiniMessage click, hover and other interactive tags are dropped.

Backends kick with the fixed text `Unable to verify your connection. Please join through the network. [NL-xx]`. The proxy replaces it as follows:

| Code | Cause | messages.yml key |
|---|---|---|
| NL-01 | No credential (direct connection, or proxy without NetworkLock) | `authentication-failed` |
| NL-02 | Invalid, unknown proxy, wrong key, or wrong target server | `authentication-failed` |
| NL-03 | Expired or from the future (clock skew) | `authentication-timeout` |
| NL-04 | Replayed | `authentication-failed` |
| NL-05 | Backend in lockdown, replay cache full, or internal error | `security-error` |

## Key rotation (no downtime)

1. Generate a new key on the proxy. One way is to set `key: ""` in a copy of the config and start a throwaway proxy; another is any 32-byte random value in base64url.
2. On every backend, add the new key to that proxy's `keys:` list next to the old one, then run `/networklock reload`.
3. Put the new key in the proxy's `security.key` and run `/networklock reload` there.
4. Remove the old key from every backend and reload. Check with `/networklock proxies` that the fingerprints match.

To **revoke** a compromised proxy, remove its entry (or key) from every backend and reload. Its credentials stop working immediately.

## Migrating from BungeeGuard

Remove BungeeGuard from proxies and backends, install NetworkLock on both, and copy each proxy's key into the backends' `trusted-proxies`. If you use Velocity's built-in `bungeeguard` forwarding mode, switch it to `modern` (tested) or `legacy` (tested). `bungeeguard` mode with NetworkLock has not been tested.
