# Test report

NetworkLock 1.0.0, tested on 2026-11-01. Windows 11, Intel i7-14700KF, Java 21 and Java 25. The automated tests also run on Linux in GitHub Actions.

## Unit tests

36 tests, all passing (`./gradlew test`). They cover:

- signatures: every single changed bit is caught, and so is any change to UUID, name, IP, profile data, key, server or time
- replay: a signature works once, including when 64 threads try the same one at the same moment
- proxies: wrong key, unknown proxy, one proxy posing as another, key rotation and removal
- config: broken YAML, missing values, short keys, `env:` and `file:` keys, keys never printed in errors, failed reloads keeping the old config
- login limiter: limits, refill, temporary blocks, IPv6 grouping, exemptions, memory cap
- messages: `&` codes, hex colours, MiniMessage tags, and click/hover tags stripped on BungeeCord
- commands: permission checks

## Game server checks (`it/backend-check.sh`)

A test client connects straight to the game server, as an attacker would, and tries:

1. a fake UUID with no signature
2. a junk signature
3. a valid signature made for a different server
4. a valid signature, then the same one again

| Server | 1 | 2 | 3 | 4 | Signature removed from profile |
|---|---|---|---|---|---|
| Paper 1.21.11 | refused | refused | refused | accepted, then refused | yes |
| Paper 26.3 (Java 25) | refused | refused | refused | accepted, then refused | yes |
| Folia 1.21.11 | refused | refused | refused | accepted, then refused | yes |
| Purpur 1.21.11 | refused | refused | refused | accepted, then refused | yes |
| Pufferfish 1.21.10 | refused | refused | refused | accepted, then refused | yes |
| Spigot 1.21.11 | refused | refused | refused | refused (no Paper API, by design) | n/a |

## Full network checks (`it/run-integration.sh`)

Real Paper or Folia, Velocity and Waterfall running together. 15 checks: the attacks above, players joining through Velocity and Waterfall, a refused player seeing the proxy's red `&c` message, and a broken reload being rejected while protection keeps working.

| Setup | Result |
|---|---|
| Paper 1.21.11, Velocity 3.5.1 (legacy), Waterfall 1.21 | 15/15, locally and on GitHub Actions |
| Folia 1.21.11, Velocity 3.5.1 (bungeeguard), Waterfall 1.21 | 15/15 |

## Done by hand

- Velocity 3.5.1 with `modern` forwarding in front of Paper 1.21.11: works.
- Velocity 4.3.1 in front of Paper 26.3, both on Java 25: works.
- BungeeCord: works. Login limit tested: 3 logins allowed, the 4th refused with the limit message, then a temporary block.
- One game server trusting three proxies, each with its own key: all three work.
- 48 players logging in at the same moment, each followed by a replay attempt: 48 accepted, 48 replays refused.

## Speed

| Action | Time |
|---|---|
| Proxy creating a signature | about 1.5 µs |
| Game server checking a signature | about 4.7 µs |
| Both, on 28 threads | about 437,000 logins per second |

The check runs once per player connection on Paper's async login thread, never per tick. Memory for the replay list is about 100 bytes per login.

## Not tested

- Online-mode proxies sending real skins (needs real accounts). The skin data is covered by unit tests.
- FlameCord (no official build available).
- Long uptime runs over many hours.
- An outside security review of the design.
