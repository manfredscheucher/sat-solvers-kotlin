package org.bytefred.ksat

/**
 * A CNF formula parsed from DIMACS text.
 *
 * @property numVars number of variables from the `p cnf n m` header.
 * @property clauses each clause is an [IntArray] of non-zero signed DIMACS literals.
 */
class DimacsCnf(val numVars: Int, val clauses: List<IntArray>) {

    /** True iff every clause has at least one literal made true by [assignment]. */
    fun isSatisfiedBy(assignment: (Int) -> Boolean): Boolean =
        clauses.all { clause ->
            clause.any { lit ->
                val v = if (lit < 0) -lit else lit
                assignment(v) == (lit > 0)
            }
        }

    companion object {
        /**
         * Parse DIMACS CNF from [text], mirroring how MicroSAT's `parse` reads a file:
         * skip `c`-comment lines, read the `p cnf n m` header, then read whitespace-separated
         * integers, ending each clause at a `0`. Extra tokens after the declared clause count
         * are ignored (as the C reads exactly `m` clauses).
         */
        fun parse(text: String): DimacsCnf {
            var numVars = 0
            var numClauses = -1
            val clauses = ArrayList<IntArray>()
            var current = ArrayList<Int>()
            var clausesRead = 0

            for (rawLine in text.lineSequence()) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue
                if (line[0] == 'c') continue
                if (line[0] == 'p') {
                    // p cnf <n> <m>
                    val parts = line.split(Regex("\\s+"))
                    // parts[0]=p, parts[1]=cnf, parts[2]=n, parts[3]=m
                    numVars = parts[2].toInt()
                    numClauses = parts[3].toInt()
                    continue
                }
                // literal line(s)
                for (tok in line.split(Regex("\\s+"))) {
                    if (tok.isEmpty()) continue
                    val lit = tok.toInt()
                    if (lit == 0) {
                        clauses.add(current.toIntArray())
                        current = ArrayList()
                        clausesRead++
                        if (numClauses >= 0 && clausesRead >= numClauses) {
                            return DimacsCnf(numVars, clauses)
                        }
                    } else {
                        current.add(lit)
                    }
                }
            }
            // Tolerate a final clause not terminated by 0.
            if (current.isNotEmpty()) clauses.add(current.toIntArray())
            return DimacsCnf(numVars, clauses)
        }
    }
}
