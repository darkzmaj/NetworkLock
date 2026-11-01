# How the security works

## The problem

Game servers behind a proxy run in offline mode and trust whatever player data the proxy forwards. Anyone who can reach a game server directly can send fake forwarding data and join as any player.

## What NetworkLock does

When the proxy sends a player to a game server, it adds a signature to the player's data:

```
signature = HMAC-SHA256(proxy key, proxy id, target server, time, random nonce,
                        player UUID, name, IP, other profile data)
```

The game server checks, in this order:

1. The proxy id is in its `trusted-proxies` list.
2. The signature matches one of that proxy's keys.
3. The signature was made for this server (`server-id`).
4. It is less than 30 seconds old.
5. Its nonce has not been used before on this server.

This happens before the player exists on the server (`AsyncPlayerPreLoginEvent` on Paper, `ServerSideConnectionEvent.Auth` on Sponge). If any check fails, the player is refused. On Paper the signature is then removed from the player's profile. Sponge does not allow that, so there the used signature stays in the profile, where it is worthless because it cannot be used again.

Keys are 32 random bytes and never leave the config files. Only the signatures travel over the network. Each proxy has its own key.

## What it protects against

| Attack | Result |
|---|---|
| Joining a game server directly | Refused: there is no valid signature. |
| Faking a UUID, name or IP | Refused: all of them are covered by the signature. |
| Reusing a captured signature | Refused after the first use on that server. |
| Using a signature on another server | Refused: it is tied to one `server-id`. |
| One proxy pretending to be another | Refused: each proxy id has its own key. |
| Another plugin letting a refused player back in | NetworkLock refuses them again at the end of the login event. |

## What it does not protect against

- **Someone on the network between proxy and game server.** They could grab a signature and use it within 30 seconds, before the real player does. Keep that link on a private network or VPN.
- **A stolen proxy key.** Whoever has it can join as anyone on the servers that trust that proxy, until you remove the key. Other proxies are not affected.
- **A hacked game server.** It knows the keys it trusts and could use them against other game servers that trust the same proxies.
- **NetworkLock being removed or failing to load.** Then nothing is checked. Firewall your game servers so only the proxies can reach them.
- **DDoS attacks.** The login limit on the proxy only slows down login spam.

## Replay protection without a database

Each game server remembers used nonces until the signature would have expired anyway (about 35 seconds). Only valid signatures get stored, so the list stays small: roughly 100 bytes per login, capped at 100,000 entries by default. If the cap is ever hit, new logins are refused rather than let through.

The list survives `/networklock reload` but not a restart. Give every game server its own `server-id`; two servers with the same name would each accept the same signature once.

## When something goes wrong

| Situation | What happens |
|---|---|
| Game server config missing or broken at startup | Nobody can join until it is fixed. |
| Broken config on reload | The reload is refused and the old config stays. |
| Plain Spigot (no Paper API) | Nobody can join. The console says why. |
| Error while checking a player | That player is refused. |
| Broken proxy config | The proxy refuses logins. |
| BungeeCord internals changed by an update | The proxy refuses logins instead of sending unsigned players. |
| Plugin disabled while the server runs | No protection. A warning is logged. |

## Logs

Keys are never logged or shown, only short fingerprints. Config errors never print the value that caused them. Refused logins are logged at most 20 times every 10 seconds, so an attack can't flood the console.
