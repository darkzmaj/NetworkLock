#!/usr/bin/env bash
# End-to-end head-to-head benchmark: real logins through Velocity 3.5.1 into Paper 1.21.11.
#
# Configurations (identical servers, JVM, flags and workloads; only the security plugin differs):
#   baseline     no security plugin, Velocity "legacy" forwarding (NOT a security solution; reference only)
#   bungeeguard  BungeeGuard 1.4.0 on Paper, Velocity's built-in "bungeeguard" forwarding (its documented setup)
#   nl100        NetworkLock 1.0.0 on Velocity and Paper, "legacy" forwarding
#   nl101        NetworkLock 1.0.1 on Velocity and Paper, "legacy" forwarding
#
#   it/bench-e2e.sh REPS            # e.g. 3; results in it/work/e2e/results.csv
#   CONFIGS=nl101 RATES=10 BURSTS=50 E2E_DIR=it/work/e2e-smoke it/bench-e2e.sh 1   # quick smoke run
#   START_REP=2 it/bench-e2e.sh 3   # resume: appends repetitions 2 and 3 to the existing results.csv
# Needs: jars in it/work/jars, BungeeGuard.jar and NetworkLock 1.0.0 at the paths below, ports 35680/35690.
set -uo pipefail

REPS=${1:-3}
ROOT=$(pwd)
W="$ROOT/${E2E_DIR:-it/work/e2e}"
JAVA=${JAVA:-$(command -v java)}
PAPER_JAR="$ROOT/it/work/jars/paper-1.21.11-132.jar"
VEL_JAR="$ROOT/it/work/jars/velocity-3.5.1-615.jar"
BG_JAR=${BG_JAR:-$(pwd)/it/work/BungeeGuard.jar} # BungeeGuard 1.4.0 release jar
NL100_JAR="$ROOT/it/work/baseline/NetworkLock.jar"
NL101_JAR="$ROOT/build/libs/NetworkLock.jar"
NL101X_JAR=${NL101X_JAR:-}   # optional experiment build, config name "nl101x"
SEP=":"; native() { echo "$1"; }
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";"; native() { cygpath -w "$1"; };; esac
CP="$(native "$ROOT/it/work/loadcls")$SEP$(native "$ROOT/networklock-core/build/classes/java/main")"
PROTO=774
KEY=$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n')
BGTOKEN=$(openssl rand -hex 32)
mkdir -p "$W"
RESULTS="$W/results.csv"
[ -f "$RESULTS" ] || echo "config,rep,workload,accepted,rejected,timeout,error,wall_s,throughput,peak_inflight,mean_ms,p50_ms,p95_ms,p99_ms,max_ms,paper_cpu_ms,velocity_cpu_ms,paper_gc_count,paper_gc_ms,paper_heap_mb,velocity_gc_count,velocity_gc_ms" > "$RESULTS"

cpu_ms() { powershell -NoProfile -Command "[int](Get-Process -Id $1).TotalProcessorTime.TotalMilliseconds" 2>/dev/null | tr -d '\r'; }
# jstat -gc: prints used heap (KB) = S0U+S1U+EU+OU, GC count = YGC+FGC(+CGC), GC time ms
gc_stat() { "$(dirname "$JAVA")/jstat" -gc "$1" | tail -1 | tr "," "." | awk '{printf "%d %.0f %.0f", $3+$4+$6+$8, $13+$15+$17, $NF*1000}'; }
pid_of() { "$(dirname "$JAVA")/jps" -l | grep -F "$1" | awk '{print $1}' | head -1; }

make_template() {
    local t="$W/template"
    [ -d "$t/world" ] && return 0
    rm -rf "$t" && mkdir -p "$t"
    echo "eula=true" > "$t/eula.txt"
    printf 'online-mode=false\nserver-ip=127.0.0.1\nserver-port=35680\nlevel-type=minecraft\\:flat\ngenerate-structures=false\nview-distance=2\nsimulation-distance=2\nwhite-list=false\nmax-players=2000\nspawn-protection=0\n' > "$t/server.properties"
    printf 'settings:\n  bungeecord: true\n' > "$t/spigot.yml"
    : > "$t/cmd.txt"
    (cd "$t" && tail -f cmd.txt | "$JAVA" -Xms1G -Xmx1G -jar "$PAPER_JAR" nogui > console.log 2>&1) < /dev/null > /dev/null 2>&1 &
    for _ in $(seq 1 180); do grep -q "Done (" "$t/console.log" 2>/dev/null && break; sleep 1; done
    echo stop >> "$t/cmd.txt"; sleep 15; echo >> "$t/cmd.txt"; sleep 1
    rm -f "$t/cmd.txt" "$t/console.log"
}

setup() { # config dir
    local cfg=$1 d=$2
    rm -rf "$d" && mkdir -p "$d/velocity/plugins"
    cp -r "$W/template" "$d/paper"
    mkdir -p "$d/paper/plugins"
    # Bukkit's per-IP connection throttle (4 s) would hit every synthetic client on 127.0.0.1 in every config
    if grep -q "connection-throttle" "$d/paper/bukkit.yml" 2>/dev/null; then sed -i 's/connection-throttle: .*/connection-throttle: -1/' "$d/paper/bukkit.yml"
    else printf 'settings:\n  connection-throttle: -1\n' > "$d/paper/bukkit.yml"; fi
    # synthetic players have no skin data; with bungee-cord online-mode on, Paper would ask Mojang's session
    # server for every login (complete(true)) and the benchmark would measure Mojang, not the plugins
    sed -i '/^  bungee-cord:/{n;s/online-mode: true/online-mode: false/}' "$d/paper/config/paper-global.yml"
    # JOINS=n: Paper admits at most max-joins-per-tick (default 5 = 100 joins/s) new players per tick
    [ -n "${JOINS:-}" ] && sed -i "s/max-joins-per-tick: .*/max-joins-per-tick: $JOINS/" "$d/paper/config/paper-global.yml"
    grep -A1 "^  bungee-cord:" "$d/paper/config/paper-global.yml" | grep -q "online-mode: false" || { echo "could not turn off bungee-cord online-mode"; exit 2; }
    local mode=legacy
    case $cfg in
        bungeeguard)
            mode=bungeeguard
            printf '%s' "$BGTOKEN" > "$d/velocity/forwarding.secret"
            cp "$BG_JAR" "$d/paper/plugins/BungeeGuard.jar"
            mkdir -p "$d/paper/plugins/BungeeGuard"
            printf 'allowed-tokens:\n  - "%s"\nno-data-kick-message: "&cUnable to authenticate - no data was forwarded by the proxy."\ninvalid-token-kick-message: "&cUnable to authenticate."\n' "$BGTOKEN" > "$d/paper/plugins/BungeeGuard/config.yml"
            ;;
        nl100|nl101|nl101x)
            local jar=$NL100_JAR; [ $cfg = nl101 ] && jar=$NL101_JAR; [ $cfg = nl101x ] && jar=$NL101X_JAR
            cp "$jar" "$d/paper/plugins/NetworkLock.jar"
            cp "$jar" "$d/velocity/plugins/NetworkLock.jar"
            mkdir -p "$d/paper/plugins/NetworkLock" "$d/velocity/plugins/networklock"
            printf 'network:\n  server-id: "lobby"\nauthentication:\n  trusted-proxies:\n    - id: "proxy-1"\n      keys: ["%s"]\nupdates:\n  check: false\nmetrics:\n  enabled: false\n' "$KEY" > "$d/paper/plugins/NetworkLock/config.yml"
            # every synthetic client shares 127.0.0.1, so the per-IP limit is set high enough never to trigger;
            # the limiter still runs its normal bucket bookkeeping for every login
            printf 'network:\n  id: "proxy-1"\nsecurity:\n  key: "%s"\nprotection:\n  connection-limit:\n    enabled: true\n    max-attempts: 10000\n    window-seconds: 1\nupdates:\n  check: false\nmetrics:\n  enabled: false\n' "$KEY" > "$d/velocity/plugins/networklock/config.yml"
            ;;
    esac
    printf 'config-version = "2.7"\nbind = "127.0.0.1:35690"\nonline-mode = false\nplayer-info-forwarding-mode = "%s"\n[servers]\nlobby = "127.0.0.1:35680"\ntry = ["lobby"]\n[forced-hosts]\n[advanced]\nlogin-ratelimit = 0\nconnection-timeout = 10000\nread-timeout = 30000\n' "$mode" > "$d/velocity/velocity.toml"
}

start() { # dir jar xmx ready
    : > "$1/cmd.txt"
    (cd "$1" && tail -f cmd.txt | "$JAVA" -Xms$3 -Xmx$3 -jar "$2" nogui > console.log 2>&1) < /dev/null > /dev/null 2>&1 &
    for _ in $(seq 1 180); do grep -q "$4" "$1/console.log" 2>/dev/null && return 0; sleep 1; done
    echo "start failed in $1"; tail -5 "$1/console.log"; return 1
}

stop_all() { # dir
    echo stop >> "$1/paper/cmd.txt"; echo shutdown >> "$1/velocity/cmd.txt"; sleep 15
    echo >> "$1/paper/cmd.txt"; echo >> "$1/velocity/cmd.txt"; sleep 2
}

run_load() { # cfg rep workload mode count rate [names]
    local cfg=$1 rep=$2 wl=$3 mode=$4 count=$5 rate=$6 names=${7:-$5}
    local c1p c1v g1p g1v c2p c2v g2p g2v line
    c1p=$(cpu_ms "$PP"); c1v=$(cpu_ms "$VP"); g1p=($(gc_stat "$PP")); g1v=($(gc_stat "$VP"))
    local before smp=""
    before=$(wc -l < "$d/paper/console.log")
    if [ "${MSPT:-0}" = 1 ]; then   # MSPT=1: sample Paper's tick times every 5 s during the workload
        ( while :; do sleep 5; echo mspt >> "$d/paper/cmd.txt"; done ) & smp=$!
    fi
    line=$(java -Dproto=$PROTO -cp "$CP" Load 127.0.0.1 35690 "$mode" "$count" "$rate" "r${rep}${wl:0:3}" "$(native "$W/raw/$cfg-r$rep-$wl.csv")" "$names")
    c2p=$(cpu_ms "$PP"); c2v=$(cpu_ms "$VP"); g2p=($(gc_stat "$PP")); g2v=($(gc_stat "$VP"))
    if [ -n "$smp" ]; then
        kill "$smp" 2>/dev/null; sleep 1
        # first avg/min/max triple of each mspt report = the last 5 s; mean of the averages and the worst max
        # Paper colours the numbers and prints decimal commas on some locales: strip both first
        tail -n +"$((before + 1))" "$d/paper/console.log" | sed 's/\x1b\[[0-9;]*m//g' \
            | grep -oE '[0-9]+[.,][0-9]+/[0-9]+[.,][0-9]+/[0-9]+[.,][0-9]+' | tr ',' '.' | awk -F/ -v k="$cfg,$rep,$wl" \
            'NR % 3 == 1 { s += $1; n++; if ($3 > m) m = $3 } END { if (n) printf "%s,%.2f,%.2f,%d\n", k, s / n, m, n }' >> "$W/mspt.csv"
    fi
    local v; v() { echo "$line" | grep -o "$1=[^ ]*" | cut -d= -f2; }
    echo "$cfg,$rep,$wl,$(v accepted),$(v rejected),$(v timeout),$(v error),$(v wall_s),$(v throughput),$(v peak_inflight),$(v mean_ms),$(v p50_ms),$(v p95_ms),$(v p99_ms),$(v max_ms),$((c2p-c1p)),$((c2v-c1v)),$((g2p[1]-g1p[1])),$((g2p[2]-g1p[2])),$((g2p[0]/1024)),$((g2v[1]-g1v[1])),$((g2v[2]-g1v[2]))" >> "$RESULTS"
    echo "  $cfg r$rep $wl: $line"
    sleep 3
}

make_template
mkdir -p "$W/raw" "$ROOT/it/work/loadcls"
javac -d "$ROOT/it/work/loadcls" -cp "$ROOT/networklock-core/build/classes/java/main" "$ROOT/it/Load.java" "$ROOT/it/Probe.java" || exit 2
ORDERS=("baseline bungeeguard nl100 nl101" "nl101 nl100 bungeeguard baseline" "bungeeguard nl101 baseline nl100")
for rep in $(seq "${START_REP:-1}" "$REPS"); do
    for cfg in ${CONFIGS:-${ORDERS[$(( (rep-1) % 3 ))]}}; do
        d="$W/run/$cfg"
        echo "== rep $rep: $cfg"
        setup "$cfg" "$d"
        start "$d/paper" "$PAPER_JAR" 1G "Done (" || { stop_all "$d"; continue; }
        start "$d/velocity" "$VEL_JAR" 512M "Done" || { stop_all "$d"; continue; }
        PP=$(pid_of paper-1.21.11-132.jar); VP=$(pid_of velocity-3.5.1-615.jar)
        java -Dproto=$PROTO -cp "$CP" Load 127.0.0.1 35690 rate 300 30 warm "$(native "$W/raw/warmup.csv")" > /dev/null
        sleep 3
        for r in ${RATES-1 5 10 25 50 100 250 500}; do
            n=$((r * ${DUR:-10})); [ $n -lt 10 ] && n=10   # DUR: seconds per rate
            run_load "$cfg" "$rep" "rate$r" rate "$n" "$r"
        done
        for b in ${BURSTS-50 100 200 500}; do run_load "$cfg" "$rep" "burst$b" burst "$b" 0; done
        if [ "${EXTRA:-1}" = 1 ]; then   # EXTRA=0: rates and bursts only
        run_load "$cfg" "$rep" "reconnect" rate 500 50 10
        # mixed: forged direct connections to Paper while real players join through the proxy
        java -Dproto=$PROTO -cp "$CP" Load 127.0.0.1 35680 forged 500 50 atk "$(native "$W/raw/$cfg-r$rep-attack.csv")" > "$W/attack-$cfg-r$rep.txt" &
        ATK=$!
        run_load "$cfg" "$rep" "mixed" rate 500 50
        wait $ATK
        echo "  attack: $(cat "$W/attack-$cfg-r$rep.txt")"
        [ "$rep" = 1 ] && run_load "$cfg" "$rep" "sustained" rate 6000 50
        fi
        grep -ciE "exception|error" "$d/paper/console.log" | xargs -I{} echo "  paper log exception/error lines: {}"
        stop_all "$d"
    done
done
echo "done: $RESULTS"
