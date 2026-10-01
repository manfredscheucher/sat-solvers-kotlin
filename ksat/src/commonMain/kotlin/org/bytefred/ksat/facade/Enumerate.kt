package org.bytefred.ksat.facade

import org.bytefred.ksat.SatResult

/**
 * Lazily enumerate the satisfying assignments of a CNF over variables `1..numVars`, as a
 * [Sequence] of models (each model a `List<Boolean>`, index `i` = value of variable `i + 1`).
 * In the spirit of PySAT's `enum_models`: iterate, and stop whenever you like (e.g. `.take(n)`).
 *
 * Each model is a FULL assignment over `1..numVars`, so set [numVars] to your problem's
 * variables: if the CNF leaves some of `1..numVars` unconstrained, every combination of those
 * free variables is a distinct model (2^k models for k free vars) — that's correct all-SAT
 * semantics, not a bug. (Variables a solver may use internally are never in `1..numVars` and
 * are not enumerated.)
 *
 * Enumeration works by blocking: solve, yield the model, add a clause forbidding that exact
 * assignment (the OR of its negated literals), solve again, until UNSAT.
 *
 * [assumptions] (signed DIMACS literals) are held for every solve, so you enumerate only the
 * models consistent with them. Not supported by [Solver.MICROSAT], which has no assumptions
 * path — passing non-empty assumptions with it throws.
 *
 * [incremental] picks the blocking strategy:
 *  - `false` (default): a FRESH solver each round replaying the CNF + all prior blocking
 *    clauses. Correct for ALL solvers; O(models²) in added clauses — fine for modest counts.
 *  - `true`: keep ONE solver and add each blocking clause to it (O(models), keeps learned
 *    clauses). Only [Solver.MINISAT] supports adding clauses after a solve correctly, so
 *    `incremental = true` with any other solver throws. (CaDiCaL and kissat are partial core
 *    ports whose post-solve clause addition doesn't take; see the port TODO.)
 */
fun enumerateModels(
    solver: Solver,
    numVars: Int,
    cnf: List<IntArray>,
    assumptions: IntArray = IntArray(0),
    incremental: Boolean = false,
): Sequence<List<Boolean>> {
    require(!(incremental && solver != Solver.MINISAT)) {
        "incremental enumeration is only supported for MINISAT (got $solver); use incremental = false"
    }
    return if (incremental) incrementalModels(solver, numVars, cnf, assumptions)
    else freshSolverModels(solver, numVars, cnf, assumptions)
}

/** Fresh solver per round: CNF + all prior blocks replayed. Correct for every solver. */
private fun freshSolverModels(
    solver: Solver,
    numVars: Int,
    cnf: List<IntArray>,
    assumptions: IntArray,
): Sequence<List<Boolean>> = sequence {
    val blocks = ArrayList<IntArray>()
    while (true) {
        val k = Ksat(solver, numVars = numVars)
        for (c in cnf) k.addClause(c)
        for (b in blocks) k.addClause(b)
        if (k.solve(assumptions) != SatResult.SAT) break
        val model = (1..numVars).map { k.valueOf(it) }
        yield(model)
        blocks += blockingClause(model, numVars)
    }
}

/** Single warm solver, blocking clause added after each solve. MINISAT only. */
private fun incrementalModels(
    solver: Solver,
    numVars: Int,
    cnf: List<IntArray>,
    assumptions: IntArray,
): Sequence<List<Boolean>> = sequence {
    val k = Ksat(solver, numVars = numVars)
    for (c in cnf) k.addClause(c)
    while (true) {
        if (k.solve(assumptions) != SatResult.SAT) break
        val model = (1..numVars).map { k.valueOf(it) }
        yield(model)
        k.addClause(blockingClause(model, numVars))
    }
}

/** OR of the negated literals of [model] — forbids exactly that assignment. */
private fun blockingClause(model: List<Boolean>, numVars: Int): IntArray =
    IntArray(numVars) { i -> if (model[i]) -(i + 1) else (i + 1) }
