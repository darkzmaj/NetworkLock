#!/usr/bin/env bash
# JMH micro-benchmarks (networklock-bench/AuthBench) against one NetworkLock core version.
#
#   it/bench-jmh.sh LABEL CORE_JAR      e.g. it/bench-jmh.sh nl100 it/work/baseline/NetworkLock.jar
# Writes it/work/bench/jmh-LABEL.json and .txt. Build first:
#   ./gradlew :networklock-bench:jar :networklock-bench:benchClasspath
set -euo pipefail

LABEL=$1
CORE=$2
ROOT=$(pwd)
BG_JAR=${BG_JAR:-$(pwd)/it/work/BungeeGuard.jar} # BungeeGuard 1.4.0 release jar
SEP=":"; native() { echo "$1"; }
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";"; native() { cygpath -w "$1"; };; esac
YAML=$(find ~/.gradle/caches/modules-2 -name 'snakeyaml-2.4.jar' | head -1)
CP="$(cat networklock-bench/build/bench-classpath.txt)$SEP$(native "$ROOT/$(ls networklock-bench/build/libs/networklock-bench-*.jar | head -1)")"
CP="$CP$SEP$(native "$ROOT/$CORE")$SEP$(native "$YAML")$SEP$(native "$BG_JAR")"
mkdir -p it/work/bench
# JMH_FILTER / JMH_ARGS narrow a re-run, e.g. JMH_FILTER='AuthBench.networkLockVerify' JMH_ARGS='-f 6 -prof stack'
# shellcheck disable=SC2086
java -cp "$CP" org.openjdk.jmh.Main "${JMH_FILTER:-dev.korinetwork.networklock.bench.AuthBench}" ${JMH_ARGS:-} \
    -prof gc -rf json -rff "$(native "$ROOT/it/work/bench/jmh-$LABEL.json")" | tee "it/work/bench/jmh-$LABEL.txt" | grep -E "^Benchmark|AuthBench\.[A-Za-z]+ |gc.alloc.rate.norm"
