#!/usr/bin/env bash
# Write a unified README.md + LICENSE for each solver sub-repo. Consistent structure:
# one-liner on what it is, that it is a byte-for-byte Kotlin port, and that the tests,
# shadow harness, benchmarks, facade and docs all live in the main repo. Re-runnable.
set -euo pipefail
MAIN="$(cd "$(dirname "$0")/.." && pwd)"
GH="$HOME/github"

# key -> display name, original author blurb, one-line character note
readme_for() {
  local name="$1" display="$2" author="$3" note="$4"
  cat > "$GH/${name}-kotlin/README.md" <<EOF
# ${name}-kotlin

A [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html) port of the
**${display}** SAT solver (${author}). Plain Kotlin, no FFI and no native library, so it
runs on any Kotlin target (JVM, Android, native, browser).

${note}

It implements the \`SatSolver\` interface from
[ksat-common](https://github.com/manfredscheucher/ksat-common), which is pulled in as a
git submodule (mounted at \`common/\`). Package namespace is \`org.bytefred.ksat\`.

## Byte-for-byte port

This is a line-by-line port of the original C/C++ solver, verified against it trace by
trace (same decisions, propagations and conflicts in the same order), not just on the
final SAT/UNSAT answer. That verification harness — the instrumented C reference, the
test CNFs, the golden traces and the shadow tests — lives in the main repo, together with
the benchmarks, the \`Ksat\` facade and the docs:
**[sat-solvers-kotlin](https://github.com/manfredscheucher/sat-solvers-kotlin)**.

This repo is just the solver source, so it can be reused on its own.

## Build

Requires a JDK and the Gradle wrapper in this repo. \`common/\` must be checked out
(clone with \`--recursive\`, or \`git submodule update --init\`).

\`\`\`bash
./gradlew compileKotlinJvm   # or build for all targets
\`\`\`

## License

MIT, see [LICENSE](LICENSE). This port is a derivative work of the MIT-licensed original
(${author}); the original license text is preserved in [LICENSE](LICENSE).
EOF

  # LICENSE = own MIT header for the port + the original solver's license text appended.
  {
    cat <<EOF
MIT License

Kotlin port Copyright (c) 2026 Manfred Scheucher

This is a Kotlin port of ${display}, a derivative work of the MIT-licensed original.
The port is released under the MIT License; the original solver's copyright and license
text follow below.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED. See the full disclaimer in the original license text below.

================================================================================
Original ${display} license
================================================================================

EOF
    cat "$MAIN/licenses/${name}-LICENSE.txt"
  } > "$GH/${name}-kotlin/LICENSE"

  echo "wrote README + LICENSE for ${name}-kotlin"
}

readme_for microsat "microSAT" "Marijn Heule" \
  "The smallest of the four. It uses integer VMTF (no floating-point activities), so the trace matches the C on the tested instances without any float-order caveats."

readme_for minisat "MiniSat" "Niklas Eén and Niklas Sörensson" \
  "The MiniSat core CDCL solver. \`double\` VSIDS activities are computed in the same order as the C, so the trace matches on the tested instances."

readme_for cadical "CaDiCaL" "Armin Biere and contributors" \
  "The CaDiCaL search core (CDCL only, not the inprocessing machinery). \`double\` activities and EMAs are computed in the same order as the C."

readme_for kissat "kissat" "Armin Biere and contributors" \
  "The kissat search core (CDCL only, not the inprocessing machinery). \`double\` activities are computed in the same order as the C."
