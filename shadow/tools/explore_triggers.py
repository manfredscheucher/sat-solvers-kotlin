#!/usr/bin/env python3
"""Exploration helper: search for a CNF that makes the instrumented C MicroSAT
emit BOTH a RESTART and a REDUCE trace line.

Not part of the golden pipeline; a durable search tool kept in the repo so the
choice of triggering instance is reproducible. Writes a scratch file
shadow/cnf/__explore.cnf while probing (overwritten each call), and prints, for
each candidate, how many CONFLICT / RESTART / REDUCE lines the C trace has.

Usage:  python3 explore_triggers.py
"""
import os
import random
import subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
SH = os.path.normpath(os.path.join(HERE, ".."))
CBIN = os.path.join(SH, "microsat-c", "microsat_trace")
CNF_DIR = os.path.join(SH, "cnf")


def write(path, nv, clauses):
    with open(path, "w") as f:
        f.write("p cnf %d %d\n" % (nv, len(clauses)))
        for c in clauses:
            f.write(" ".join(map(str, c)) + " 0\n")


def rand_ksat(nv, k, ratio, seed):
    r = random.Random(seed)
    m = int(round(ratio * nv))
    cl = []
    for _ in range(m):
        vs = r.sample(range(1, nv + 1), k)
        cl.append([v if r.random() < 0.5 else -v for v in vs])
    return nv, cl


def probe(nv, cl, timeout=60):
    p = os.path.join(CNF_DIR, "__explore.cnf")
    write(p, nv, cl)
    env = dict(os.environ)
    env["LSTRACE"] = "1"
    out = subprocess.run([CBIN, p], env=env, capture_output=True,
                         text=True, timeout=timeout).stdout
    lines = out.splitlines()
    nr = sum(1 for l in lines if l == "RESTART")
    nred = sum(1 for l in lines if l == "REDUCE")
    ncon = sum(1 for l in lines if l == "CONFLICT")
    res = [l for l in lines if l.startswith("RESULT")]
    return dict(lines=len(lines), conflict=ncon, restart=nr, reduce=nred,
                result=res[-1] if res else "?")


def report(name, nv, cl):
    st = probe(nv, cl)
    print("%-30s vars=%-4d clauses=%-5d lines=%-6d CONFLICT=%-5d RESTART=%-4d "
          "REDUCE=%-3d %s" % (name, nv, len(cl), st["lines"], st["conflict"],
                              st["restart"], st["reduce"], st["result"]))
    return st


def gen_candidates(outdir):
    """Pure generation only (no subprocess): write candidate CNFs to outdir and
    print a manifest of name->path so a bash driver can run the C binary."""
    os.makedirs(outdir, exist_ok=True)
    specs = []
    for nv in (80, 100, 120, 140, 160, 200, 250):
        for ratio in (4.2, 4.3, 5.0, 6.0):
            for seed in (1, 2, 3):
                specs.append(("rand3_%d_%02d_s%d" % (nv, int(ratio * 10), seed),
                              rand_ksat(nv, 3, ratio, seed)))
    for name, (nv, cl) in specs:
        path = os.path.join(outdir, name + ".cnf")
        write(path, nv, cl)
        print(path)


def main():
    import sys
    if len(sys.argv) > 1 and sys.argv[1] == "gen":
        gen_candidates(sys.argv[2])
        return
    winners = []
    for nv in (80, 100, 120, 140, 160, 200, 250):
        for ratio in (4.2, 4.3, 5.0, 6.0):
            for seed in (1, 2, 3):
                name = "rand3_%d_%.1f_s%d" % (nv, ratio, seed)
                st = report(name, *rand_ksat(nv, 3, ratio, seed))
                if st["restart"] > 0 and st["reduce"] > 0:
                    print("  >>> WINNER (restart+reduce) above")
                    winners.append((name, nv, ratio, seed, st))
    print("\nwinners:", [w[0] for w in winners])


if __name__ == "__main__":
    main()
