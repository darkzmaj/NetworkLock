"""Averages it/work/e2e/results.csv over repetitions: one row per workload and config.

    python it/bench-summary.py [results.csv]
Latency columns are the mean of each repetition's percentile; cpu_us_per_login is Paper + Velocity
CPU time divided by logins attempted.
"""
import csv
import sys
from collections import defaultdict
from statistics import mean, stdev

path = sys.argv[1] if len(sys.argv) > 1 else "it/work/e2e/results.csv"
rows = defaultdict(list)
for r in csv.DictReader(open(path, encoding="utf-8")):
    rows[(r["workload"], r["config"])].append(r)

order = ["baseline", "bungeeguard", "nl100", "nl101"]
workloads = sorted({w for w, _ in rows}, key=lambda w: (w.rstrip("0123456789"), int("0" + "".join(c for c in w if c.isdigit()))))
print("workload,config,reps,accepted,rejected,error,p50_ms,p50_sd,p95_ms,p99_ms,paper_cpu_us_per_login,velocity_cpu_us_per_login,paper_gc_ms")
for w in workloads:
    for c in order:
        rs = rows.get((w, c))
        if not rs:
            continue
        f = lambda k: [float(r[k]) for r in rs]
        n = [float(r["accepted"]) + float(r["rejected"]) + float(r["timeout"]) + float(r["error"]) for r in rs]
        p50 = f("p50_ms")
        print(",".join([w, c, str(len(rs)),
                        "%d" % sum(f("accepted")), "%d" % sum(f("rejected")), "%d" % (sum(f("error")) + sum(f("timeout"))),
                        "%.1f" % mean(p50), "%.1f" % (stdev(p50) if len(p50) > 1 else 0),
                        "%.1f" % mean(f("p95_ms")), "%.1f" % mean(f("p99_ms")),
                        "%.0f" % mean(a * 1000 / b for a, b in zip(f("paper_cpu_ms"), n)),
                        "%.0f" % mean(a * 1000 / b for a, b in zip(f("velocity_cpu_ms"), n)),
                        "%.0f" % mean(f("paper_gc_ms"))]))
