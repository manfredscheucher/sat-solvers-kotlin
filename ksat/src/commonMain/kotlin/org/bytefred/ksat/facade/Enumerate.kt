package org.bytefred.ksat.facade

import org.bytefred.ksat.SatResult

/**
 * Enumerate ALL satisfying assignments of a CNF over variables `1..numVars`, returning each
 * model as a `List<Boolean>` (index `i` = value of variable `i + 1`).
 *
 * The ported solvers don't accept new permanent clauses after a `solve()`, so each round uses
 * a FRESH solver replaying the CNF plus a blocking clause per model already found (the OR of
 * the negated literals of that model). Solve, record the model, block it, repeat until UNSAT.
 *
 * This is O(models²) in added clauses (each round replays all prior blocks) — fine for small
 * instances and demos, NOT an efficient all-SAT procedure for large model counts.
 *
 * [limit] caps the number of models returned (default unbounded); use it as a safety net when
 * the model count is unknown.
 */
fun enumerateModels(
    solver: Solver,
    numVars: Int,
    cnf: List<IntArray>,
    limit: Int = Int.MAX_VALUE,
): List<List<Boolean>> {
    val models = ArrayList<List<Boolean>>()
    val blocks = ArrayList<IntArray>()
    while (models.size < limit) {
        val k = Ksat(solver, numVars = numVars)
        for (c in cnf) k.addClause(c)
        for (b in blocks) k.addClause(b)
        if (k.solve() != SatResult.SAT) break
        val model = (1..numVars).map { k.valueOf(it) }
        models += model
        // block this exact assignment: OR of the negated literals
        blocks += IntArray(numVars) { i -> if (model[i]) -(i + 1) else (i + 1) }
    }
    return models
}
