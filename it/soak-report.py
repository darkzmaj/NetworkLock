"""Summarises an it/soak.sh run (it/work/soak) and prints PASS/FAIL verdicts.

    python it/soak-report.py [dir]
"""
import csv
import glob
import os
import re
import sys
from collections import defaultdict

d = sys.argv[1] if len(sys.argv) > 1 else "it/work/soak"
fails = []


def kv(line, key):
    m = re.search(key + r"=([0-9.]+)", line)
    return float(m.group(1)) if m else 0.0


# restart windows (survival backend down): from "stopping" to "up" + 20 s grace
events = [l.split(" ", 1) for l in open(os.path.join(d, "events.log"), encoding="utf-8")]
windows, start = [], None
for t, e in events:
    if "restart survival: stopping" in e:
        start = int(t)
    if "restart survival: up" in e and start:
        windows.append((start, int(t) + 20))
        start = None
print("restarts of survival:", len(windows))

PLUGIN_REFUSAL = re.compile(r"NL-\d|authentication|Unable to verify", re.I)
for tag, label in [("v", "valid via Velocity -> lobby"), ("w", "valid via Waterfall -> survival")]:
    n = accepted = 0
    reasons = defaultdict(int)
    refused_outside = []
    # newline="\n": Load's Windows output carries \r, which must not split a summary line in two
    for k, line in enumerate(open(os.path.join(d, tag + ".summary"), encoding="utf-8", newline="\n"), start=1):
        p = line.split(" ", 2)
        t0, t1 = int(p[0]), int(p[1])
        in_window = any(a <= t1 and t0 <= b for a, b in windows)
        raw = os.path.join(d, "raw", f"{tag}-{k}.csv")
        if not os.path.exists(raw):
            continue
        for r in csv.DictReader(open(raw, encoding="utf-8")):
            n += 1
            if r["result"] == "accepted":
                accepted += 1
                continue
            detail = r.get("detail", "")
            if PLUGIN_REFUSAL.search(detail):
                kind = "NetworkLock refusal" + (" (restart window)" if in_window else "")
                if not in_window:
                    refused_outside.append(detail)
            elif in_window:
                kind = "during backend restart"
            else:
                kind = "machine/network: " + re.sub(r"\d{3,}", "N", detail)[:60]
            reasons[kind] += 1
    print(f"{label}: {n} logins, accepted {accepted}, failed {n - accepted}")
    for kind, c in sorted(reasons.items(), key=lambda x: -x[1]):
        print(f"    {c:6d}  {kind}")
    if n == 0:
        fails.append(label + ": no traffic")
    if refused_outside:
        fails.append(f"{label}: NetworkLock refused {len(refused_outside)} valid logins outside restart windows: {refused_outside[0]}")

for tag, label in [("fl", "forged direct -> lobby"), ("fs", "forged direct -> survival")]:
    acc = rej = 0
    for line in open(os.path.join(d, tag + ".summary"), encoding="utf-8", newline="\n"):
        acc += kv(line, "accepted")
        rej += kv(line, "rejected")
    print(f"{label}: {int(acc + rej)} refused or accepted, ACCEPTED {int(acc)}")
    if acc:
        fails.append(f"{label}: {int(acc)} forged logins accepted")

probes = open(os.path.join(d, "probes.log"), encoding="utf-8", newline="\n").read().replace("\r", "").split("\n")
replays = [l for l in probes if "replay=[" in l]
bad_replay = [l for l in replays if not re.search(r"replay=\[ACCEPTED.*replay: REJECTED.*NL-04", l)
              and not any(a <= int(l.split()[0]) <= b for a, b in windows)]
bad_wrong = [l for l in replays if "wrongserver=[REJECTED" not in l
             and not any(a <= int(l.split()[0]) <= b for a, b in windows)]
oldkey = [l for l in probes if " oldkey" in l]
print(f"replay probes: {len(replays)}, unexpected {len(bad_replay)}; wrong-server unexpected {len(bad_wrong)};"
      f" old key after rotation: {oldkey[0].split("=", 1)[1] if oldkey else 'not run'}")
if bad_replay:
    fails.append(f"{len(bad_replay)} replay probes not 'accepted once, then refused': {bad_replay[0]}")
if bad_wrong:
    fails.append(f"{len(bad_wrong)} wrong-server probes not refused: {bad_wrong[0]}")
if oldkey and "REJECTED" not in oldkey[0]:
    # the probe is only meaningful once the backend has confirmed the reload; older runs sent it in the same second
    t = int(oldkey[0].split()[0])
    print("  old-key probe was accepted: check it ran after 'Configuration reloaded' (" + str(t) + "); "
          "revocation itself is verified by it/backend-check.sh")
    if "reload-confirmed" in oldkey[0]:
        fails.append("old key still accepted after a confirmed reload")

rows = list(csv.DictReader(open(os.path.join(d, "monitor.csv"), encoding="utf-8")))
gc = list(csv.DictReader(open(os.path.join(d, "heap-after-gc.csv"), encoding="utf-8")))
t_first = min(int(r["t"]) for r in rows)
t_last = max(int(r["t"]) for r in rows)
hour = min(3600, (t_last - t_first) // 3)
print(f"\nmonitoring {round((t_last - t_first) / 3600, 2)} h; comparing first vs last {hour // 60} min")
# Other test servers may run the same jars at the same time; the soak's own processes live for at least
# 45 minutes (90 samples), test servers for minutes, so processes with fewer than 20 samples are ignored.
count = defaultdict(int)
for r in rows:
    count[(r["name"], r["pid"])] += 1
MIN = min(20, max(count.values()) // 2)  # short runs have fewer samples per process
rows = [r for r in rows if count[(r["name"], r["pid"])] >= MIN]
# a heap-after-GC sample belongs to the process of the monitor row taken just before it
for g in gc:
    before = [r for r in rows if r["name"] == g["name"] and int(r["t"]) <= int(g["t"])]
    g["pid"] = max(before, key=lambda r: int(r["t"]))["pid"] if before else None
gc = [g for g in gc if g["pid"] and count[(g["name"], g["pid"])] >= MIN]
for name in ("lobby", "survival", "velocity", "waterfall"):
    rs = [r for r in rows if r["name"] == name and r["threads"]]
    gs = [g for g in gc if g["name"] == name and g["heap_after_gc_kb"]]
    if not rs:
        continue
    first = [r for r in rs if int(r["t"]) < t_first + hour]
    last = [r for r in rs if int(r["t"]) > t_last - hour]
    avg = lambda xs, k: sum(float(x[k]) for x in xs) / max(1, len(xs))
    g1 = [float(g["heap_after_gc_kb"]) for g in gs if int(g["t"]) < t_first + hour]
    g2 = [float(g["heap_after_gc_kb"]) for g in gs if int(g["t"]) > t_last - hour]
    pids = sorted({r["pid"] for r in rs})
    print(f"{name:9} threads {avg(first, 'threads'):6.0f} -> {avg(last, 'threads'):6.0f}   handles {avg(first, 'handles'):7.0f} -> {avg(last, 'handles'):7.0f}"
          f"   heap after GC {sum(g1) / max(1, len(g1)) / 1024:7.1f} -> {sum(g2) / max(1, len(g2)) / 1024:7.1f} MB   processes {len(pids)}")
    # growth checks only within one process lifetime (survival restarts)
    if len(pids) == 1 and g1 and g2 and sum(g2) / len(g2) > 1.25 * sum(g1) / len(g1) + 20 * 1024:
        fails.append(f"{name}: heap after GC grew from {sum(g1) / len(g1) / 1024:.0f} to {sum(g2) / len(g2) / 1024:.0f} MB")
    if len(pids) == 1 and avg(last, "threads") > avg(first, "threads") + 10:
        fails.append(f"{name}: thread count grew")

for b in ("lobby", "survival"):
    log = open(os.path.join(d, b, "console.log"), encoding="utf-8", errors="replace").read()
    mem = re.findall(r"replay memory: (\d+) / (\d+)", log)
    nl_err = [l for l in log.splitlines() if "NetworkLock" in l and re.search(r"Exception|SEVERE|ERROR", l)]
    print(f"{b}: replay memory samples {[int(m[0]) for m in mem][-6:]} (max {max([int(m[0]) for m in mem] or [0])}),"
          f" NetworkLock errors {len(nl_err)}")
    if nl_err:
        fails.append(f"{b}: {len(nl_err)} NetworkLock error lines, first: {nl_err[0][:160]}")

print()
print("FAIL\n  " + "\n  ".join(fails) if fails else "PASS  all soak checks")
sys.exit(1 if fails else 0)
