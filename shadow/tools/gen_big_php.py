#!/usr/bin/env python3
"""
Generate larger pigeonhole CNFs (php_9_8, php_10_9, ...) for the C-vs-Kotlin runtime
benchmark. PHP(n+1, n) is UNSAT and forces exponential CDCL search, so each extra hole
multiplies solve time — good for landing an instance in the few-seconds range where the
solver's actual search (not startup overhead) dominates.

Writes into shadow/cnf/. Usage: python3 gen_big_php.py 8 9   (holes values)
"""
import os
import sys

CNF_DIR = os.path.join(os.path.dirname(__file__), "..", "cnf")


def pigeonhole(holes):
    pigeons = holes + 1

    def var(p, h):
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


def write_cnf(holes):
    pigeons = holes + 1
    num_vars, clauses = pigeonhole(holes)
    name = "php_%d_%d.cnf" % (pigeons, holes)
    path = os.path.join(CNF_DIR, name)
    with open(path, "w") as f:
        f.write("c Pigeonhole PHP(%d,%d): %d pigeons in %d holes -> UNSAT.\n" % (pigeons, holes, pigeons, holes))
        f.write("p cnf %d %d\n" % (num_vars, len(clauses)))
        for cl in clauses:
            f.write(" ".join(str(x) for x in cl) + " 0\n")
    print("wrote %s (%d vars, %d clauses)" % (path, num_vars, len(clauses)))


if __name__ == "__main__":
    holes_list = [int(a) for a in sys.argv[1:]] or [8, 9]
    for h in holes_list:
        write_cnf(h)
