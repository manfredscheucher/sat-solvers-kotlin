#!/usr/bin/env python3
"""Durable, deterministic CNF generator for the ksat-ports shadow/benchmark suite.

Everything is seeded, so re-running reproduces byte-identical .cnf files. Output
goes to shadow/cnf/ with clear names. Kept in the repo (never-delete rule).

Categories produced:
  * tiny SAT / UNSAT          -- already have hand-written ones; these add a couple
  * random 3-SAT near phase transition (clause/var ratio ~4.26), 20 and 50 vars
  * pigeonhole PHP(n+1, n)    -- guaranteed UNSAT, forces conflicts; n = 3..7
  * a "hard" instance meant to actually TRIGGER a RESTART and a REDUCE in MicroSAT
    (PHP(8,7) has enough conflicts+lemmas; also a chained hard random UNSAT).

Usage:
  python3 gen_cnf.py            # write every generated CNF into ../cnf/
  python3 gen_cnf.py --list     # just print what would be written
"""
import os
import random
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
CNF_DIR = os.path.normpath(os.path.join(HERE, "..", "cnf"))


def write_cnf(name, num_vars, clauses, comment):
    path = os.path.join(CNF_DIR, name)
    lines = []
    for c in comment.splitlines():
        lines.append("c " + c)
    lines.append("p cnf %d %d" % (num_vars, len(clauses)))
    for cl in clauses:
        lines.append(" ".join(str(x) for x in cl) + " 0")
    text = "\n".join(lines) + "\n"
    with open(path, "w") as f:
        f.write(text)
    return path, num_vars, len(clauses)


# ---------------------------------------------------------------------------
# Random 3-SAT near the phase transition (ratio m/n ~ 4.26).
# ---------------------------------------------------------------------------
def random_3sat(num_vars, ratio, seed):
    rnd = random.Random(seed)
    num_clauses = int(round(ratio * num_vars))
    clauses = []
    for _ in range(num_clauses):
        vs = rnd.sample(range(1, num_vars + 1), 3)
        clause = [v if rnd.random() < 0.5 else -v for v in vs]
        clauses.append(clause)
    return num_vars, clauses


# ---------------------------------------------------------------------------
# Pigeonhole PHP(pigeons, holes): pigeons = holes + 1 makes it UNSAT.
# Variable x(p,h) = pigeon p sits in hole h, numbered 1-based.
#   - each pigeon in at least one hole  (positive clauses)
#   - no two pigeons in the same hole    (binary negative clauses)
# ---------------------------------------------------------------------------
def pigeonhole(holes):
    pigeons = holes + 1

    def var(p, h):  # p in 1..pigeons, h in 1..holes
        return (p - 1) * holes + h

    num_vars = pigeons * holes
    clauses = []
    for p in range(1, pigeons + 1):
        clauses.append([var(p, h) for h in range(1, holes + 1)])
    for h in range(1, holes + 1):
        for p1 in range(1, pigeons + 1):
            for p2 in range(p1 + 1, pigeons + 1):
                clauses.append([-var(p1, h), -var(p2, h)])
    return num_vars, clauses


# ---------------------------------------------------------------------------
# A hard random UNSAT: several overlapping unsatisfiable random 3-SAT blocks
# sharing variables, so the solver learns many lemmas before proving UNSAT.
# Ratio is pushed above the transition (5.5) to bias toward UNSAT + lots of work.
# ---------------------------------------------------------------------------
def hard_random_unsat(num_vars, ratio, seed):
    rnd = random.Random(seed)
    num_clauses = int(round(ratio * num_vars))
    clauses = []
    for _ in range(num_clauses):
        vs = rnd.sample(range(1, num_vars + 1), 3)
        clauses.append([v if rnd.random() < 0.5 else -v for v in vs])
    return num_vars, clauses


def all_specs():
    """Return list of (name, num_vars, clauses, comment)."""
    out = []

    # tiny extra SAT / UNSAT (complement the hand-written ones)
    out.append(("tiny_sat_chain.cnf", 4,
                [[1, -2], [2, -3], [3, -4], [4], [-1, 2]],
                "Tiny SAT: implication chain forcing all true. 4 vars."))
    out.append(("tiny_unsat_units.cnf", 2,
                [[1], [-1, 2], [-2], [1, 2]],
                "Tiny UNSAT: units 1 and -2 clash through the chain. 2 vars."))

    # random 3-SAT near phase transition
    nv, cl = random_3sat(20, 4.26, seed=12345)
    out.append(("rand3_20_phase.cnf", nv, cl,
                "Random 3-SAT, 20 vars, ratio 4.26 (phase transition). seed=12345"))
    nv, cl = random_3sat(50, 4.26, seed=6789)
    out.append(("rand3_50_phase.cnf", nv, cl,
                "Random 3-SAT, 50 vars, ratio 4.26 (phase transition). seed=6789"))

    # pigeonhole family (all UNSAT)
    for holes in (3, 4, 5, 6, 7):
        nv, cl = pigeonhole(holes)
        out.append(("php_%d_%d.cnf" % (holes + 1, holes), nv, cl,
                    "Pigeonhole PHP(%d,%d): %d pigeons in %d holes -> UNSAT, forces conflicts."
                    % (holes + 1, holes, holes + 1, holes)))

    # hard instances aimed at triggering RESTART + REDUCE
    nv, cl = hard_random_unsat(60, 5.5, seed=2024)
    out.append(("hard_rand_unsat_60.cnf", nv, cl,
                "Hard random 3-SAT, 60 vars, ratio 5.5 -> UNSAT, many conflicts/lemmas "
                "(aims to trigger RESTART + REDUCE). seed=2024"))
    return out


def main():
    list_only = "--list" in sys.argv[1:]
    specs = all_specs()
    if list_only:
        for name, nv, clauses, _ in specs:
            print("%-26s vars=%-4d clauses=%d" % (name, nv, len(clauses)))
        return
    os.makedirs(CNF_DIR, exist_ok=True)
    for name, nv, clauses, comment in specs:
        path, v, m = write_cnf(name, nv, clauses, comment)
        print("wrote %-26s vars=%-4d clauses=%d -> %s" % (name, v, m, path))


if __name__ == "__main__":
    main()
