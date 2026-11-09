#!/usr/bin/env bash
# Runs the direct-attack checks against a game server jar (Paper, Purpur, Pufferfish, Folia,
# Spigot, CraftBukkit, SpongeVanilla) with NetworkLock.jar installed. No proxy needed: tokens are
# minted locally. Jars with "sponge" in the name get the Sponge layout (mods/, config/networklock/).
#
#   it/backend-check.sh path/to/server.jar [expect]
#
# expect = "enforce" (default): forged logins rejected, valid token accepted once.
# expect = "deny-all": every login rejected (a server where players cannot be checked, e.g. CraftBukkit).
# SERVER_JAVA=/path/to/java runs the server on another Java (e.g. Java 8 for 1.8); the test
# client always uses the java on PATH (21+).
# Needs bash, openssl, Java on PATH and port 35570 free. Run from the repository root.
set -uo pipefail

SERVER_JAR=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
EXPECT=${2:-enforce}
SERVER_JAVA=${SERVER_JAVA:-java}
ROOT=$(pwd)
WORK="$ROOT/it/work/backend-check"
SEP=":"; native() { echo "$1"; }
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";"; native() { cygpath -w "$1"; };; esac
FAILED=0

S="$WORK/srv"
SPONGE=0; case "$(basename "$SERVER_JAR")" in *sponge*) SPONGE=1;; esac
if [ "$SPONGE" = 1 ]; then PLUGINS="$S/mods"; DATA="$S/config/networklock"; else PLUGINS="$S/plugins"; DATA="$S/plugins/NetworkLock"; fi
rm -rf "$WORK" && mkdir -p "$PLUGINS" "$DATA" "$WORK/probe"
javac -d "$WORK/probe" -cp "$ROOT/networklock-core/build/classes/java/main" "$ROOT/it/Probe.java" || exit 2
KEY=$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n')
cp "$ROOT/build/libs/NetworkLock.jar" "$PLUGINS/"
echo "eula=true" > "$S/eula.txt"
printf 'online-mode=false\nserver-ip=127.0.0.1\nserver-port=35570\nlevel-type=minecraft\\:flat\ngenerate-structures=false\nview-distance=2\nsimulation-distance=2\nwhite-list=false\n' > "$S/server.properties"
if [ "$SPONGE" = 1 ]; then
    mkdir -p "$S/config/sponge"
    printf 'modules {\n    ip-forwarding=true\n}\nip-forwarding {\n    mode=LEGACY\n}\n' > "$S/config/sponge/sponge.conf"
else
    printf 'settings:\n  bungeecord: true\n' > "$S/spigot.yml"
fi
printf 'network:\n  server-id: "lobby"\nauthentication:\n  trusted-proxies:\n    - id: "proxy-1"\n      keys: ["%s"]\nlogging:\n  debug: true\n' "$KEY" > "$DATA/config.yml"

launch() {
    : > "$S/cmd.txt"
    (cd "$S" && tail -f cmd.txt | "$SERVER_JAVA" -Xmx1G -jar "$SERVER_JAR" nogui > console.log 2>&1) < /dev/null > /dev/null 2>&1 &
    PID=$!
    for _ in $(seq 1 180); do
        grep -q "Done (" "$S/console.log" 2>/dev/null && return 0
        grep -q "start the server again" "$S/console.log" 2>/dev/null && return 1 # e.g. Leaf's first-run loader config
        sleep 1
    done
}
cleanup() { echo stop >> "$S/cmd.txt"; sleep 10; echo >> "$S/cmd.txt"; sleep 1; kill "$PID" 2>/dev/null; }
trap cleanup EXIT
launch || { sleep 5; echo >> "$S/cmd.txt"; kill "$PID" 2>/dev/null; launch; }
grep -q "Done (" "$S/console.log" || { echo "server did not start"; tail -20 "$S/console.log"; exit 1; }

CP="$(native "$WORK/probe")$SEP$(native "$ROOT/networklock-core/build/classes/java/main")"
PROTO=$(java -cp "$CP" Probe 127.0.0.1 35570 x protocol)
probe() { java -Dproto="$PROTO" -cp "$CP" Probe 127.0.0.1 35570 "$@" | tr '\n' ' ' | sed 's/ *$//'; }
check() { if [[ "$3" == "$2"* ]]; then echo "PASS  $1"; else echo "FAIL  $1: expected '$2', got '$3'"; FAILED=1; fi; }
U=069a79f4-44e9-4726-a5be-fca90e38aaf5

echo "== $(grep -m1 -oE 'This server is running [^(]*|Starting minecraft server version [^ ]*' "$S/console.log") (protocol $PROTO), expect $EXPECT"
check "forged UUID without token" "REJECTED" "$(probe Notch forged $U)"
check "junk token"                "REJECTED" "$(probe Notch garbage $U)"
check "token for another server"  "REJECTED" "$(probe Notch token $U proxy-1 "$KEY" survival 127.0.0.1)"
R=$(probe Notch token $U proxy-1 "$KEY" lobby 127.0.0.1 replay)
if [ "$EXPECT" = "deny-all" ]; then
    check "valid token still refused" "REJECTED" "$R"
else
    check "valid token accepted" "ACCEPTED" "$R"
    [[ "$R" == *"replay: REJECTED"*"NL-04"* ]] && echo "PASS  replay rejected (NL-04)" || { echo "FAIL  replay: $R"; FAILED=1; }
    if grep -q "left in (read-only)" "$S/console.log"; then
        echo "INFO  credential left in a read-only profile on this version (used, so it cannot be replayed)"
    elif grep -q "still present" "$S/console.log"; then
        echo "FAIL  credential reached the live profile"; FAILED=1
    elif grep -q "Used credential removed from the profile" "$S/console.log"; then
        echo "PASS  credential removed from the live profile (read back)"
    else
        echo "FAIL  no confirmation that the credential was removed"; FAILED=1
    fi
    # a full join with a fresh signature: the joined player's live profile must not carry it
    J=$(java -Djoin=true -Dproto="$PROTO" -cp "$CP" Probe 127.0.0.1 35570 Jeb token 5b1c2d3e-4f50-4a61-8b72-93a4b5c6d7e8 proxy-1 "$KEY" lobby 127.0.0.1 | tr '\n' ' ')
    sleep 2
    if [[ "$J" != ACCEPTED*"+joined"* ]]; then
        echo "FAIL  full join: $J"; FAILED=1
    elif grep -q "still present" "$S/console.log"; then
        echo "FAIL  credential present on the joined player's profile"; FAILED=1
    elif grep -q "Credential absent at join for Jeb" "$S/console.log"; then
        echo "PASS  credential absent from the joined player's profile"
    else
        echo "FAIL  no join-time confirmation"; FAILED=1
    fi
fi
if [ "$EXPECT" != "deny-all" ]; then
    # key rotation without downtime, waiting for the server to confirm each reload (no race with the probe)
    reload() {
        local n; n=$(grep -c "Configuration reloaded" "$S/console.log")
        echo "nlock reload" >> "$S/cmd.txt"
        for _ in $(seq 1 30); do [ "$(grep -c "Configuration reloaded" "$S/console.log")" -gt "$n" ] && return 0; sleep 1; done
        return 1
    }
    KEY2=$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n')
    sed -i "s/keys: \[\"$KEY\"\]/keys: [\"$KEY\", \"$KEY2\"]/" "$DATA/config.yml"
    reload || echo "FAIL  reload not confirmed"
    check "new key accepted after it was added" "ACCEPTED" "$(probe Rot1 token 6e0b2a4c-1d3f-4a5b-9c7d-8e9f0a1b2c3d proxy-1 "$KEY2" lobby 127.0.0.1)"
    sed -i "s/keys: \[\"$KEY\", \"$KEY2\"\]/keys: [\"$KEY2\"]/" "$DATA/config.yml"
    reload || echo "FAIL  reload not confirmed"
    check "removed key refused right after the reload" "REJECTED" "$(probe Rot2 token 7f1c3b5d-2e4a-4b6c-8d8e-9fa0b1c2d3e4 proxy-1 "$KEY" lobby 127.0.0.1)"
fi
echo "nlock doctor" >> "$S/cmd.txt"; sleep 3
if [ "$EXPECT" = "deny-all" ]; then
    grep -q "FAIL this is CraftBukkit\|FAIL lockdown" "$S/console.log" && echo "PASS  doctor explains why" \
        || { echo "FAIL  doctor does not explain why logins are refused"; FAILED=1; }
else
    grep -q "OK   proxy forwarding" "$S/console.log" && echo "PASS  doctor ran" || { echo "FAIL  doctor output missing"; FAILED=1; }
fi
grep -qE "Exception|Could not load" <(grep -i networklock "$S/console.log") && { echo "FAIL  NetworkLock errors in console"; FAILED=1; }

[ "$FAILED" = 0 ] && echo "== ALL PASSED" || echo "== FAILURES (log: $S/console.log)"
exit "$FAILED"
