# TODO: packaging consistency with sat-solvers-dafny

Written by the dafny-repo agent for the kotlin-repo agent. These are **non-code** packaging
changes so that `sat-solvers-kotlin` and its sibling `sat-solvers-dafny` present consistently
(README / LICENSE / attribution / docs layout). None of this touches solver code or tests.

Context: the two repos port the same four solvers (microSAT, MiniSat, CaDiCaL, kissat) — dafny =
memory-safety-verified, kotlin = KMP runtime portability. Same author (Manfred Scheucher), both
MIT, both keep the upstream MIT license texts under `licenses/` (already byte-identical between
the repos — do NOT touch those). A fresh reviewer compared both; the dafny repo is the reference
for packaging conventions. Apply the items below to the kotlin repo.

---

## 1. Add a `NOTICE` file; trim LICENSE back to just MIT

Right now `LICENSE` overloads the MIT grant with an inline per-solver attribution block (after
the `---`). Move that attribution into a standalone `NOTICE` (the conventional home for
third-party attribution; keeps LICENSE clean), modeled on the dafny repo's
`/Users/manfred/github/dafny-satbox/NOTICE`.

- Create `NOTICE` with: per-solver upstream copyright/years/contributors, the "these are ports /
  derivative works of MIT-licensed originals" framing, and a scope note (CaDiCaL & kissat = CDCL
  core only — match whatever the README says).
- IMPORTANT difference from dafny: dafny says "the original C/C++ trees are NOT included". The
  kotlin repo DOES ship the C references under `shadow/` (needed to regenerate golden traces), so
  say that honestly instead — e.g. "The original C/C++ solver sources are included under `shadow/`
  as verbatim references for the byte-for-byte shadow tests." Do not copy dafny's "not included".
- Then trim `LICENSE` to just the MIT preamble + grant (drop the inline per-solver block, it now
  lives in NOTICE). Keep the "derivative work ... preserved under licenses/ and referenced in
  NOTICE and each source file's header" preamble wording (mirror dafny's LICENSE).

Correct upstream copyright years (verified against the byte-identical `licenses/*.txt`):
- microSAT: (c) 2014-2017 Marijn Heule
- MiniSat: (c) 2003-2006 Niklas Eén, Niklas Sörensson; (c) 2007-2010 Niklas Sörensson
- CaDiCaL: (c) 2016-2026 Armin Biere and contributors (Mathias Fleury, Nils Froleyks,
  Katalin Fazekas, Florian Pollitt, Tobias Faller; JKU Linz / Univ. Freiburg / TU Wien)
  — NOTE: current kotlin LICENSE says 2016-2021, which is STALE. The upstream license spans
    2016-2026. Use 2016-2026.
- kissat: (c) 2019-2025 Armin Biere and contributors (Mathias Fleury, Florian Pollitt;
  JKU Linz / Univ. Freiburg)

## 2. Rename `doc/` -> `docs/`

Use the GitHub-recognized plural `docs/` (matches dafny). Update:
- the two README references (`doc/shadowing-methodology` -> `docs/shadowing-methodology`)
- the `.gitignore` comment that mentions `doc/*.pdf, docs/*.pdf`

## 3. Add a `docs/README.md` index

Short landing page listing the doc set (`shadowing-methodology` — the byte-for-byte methodology +
per-solver status; `shadow-test-setup`), so the doc dir is discoverable. dafny has
`docs/README.md` as its index; the top README should point at it ("Start with docs/README.md").

## 4. Add a sibling cross-link to README.md

Near the top, a blockquote pointing at the dafny repo:

    > Sibling project: [sat-solvers-dafny](https://github.com/manfredscheucher/sat-solvers-dafny) —
    > the same four solvers ported to Dafny, verified for memory safety.

(The dafny README now has the reciprocal link to sat-solvers-kotlin.)

## 5. Update the README License section

Once NOTICE exists, mention attribution lives "in NOTICE, in `licenses/`, and in each source
file's header" (mirrors dafny's wording). Currently it points only at `licenses/`.

## 6. (optional) Author-name spelling

Use the accented "Niklas Eén, Niklas Sörensson" consistently (their actual names). Minor.

---

Reference file to copy/adapt from: `/Users/manfred/github/dafny-satbox/NOTICE`
(and `/Users/manfred/github/dafny-satbox/README.md` / `LICENSE` for wording).

Non-issues — do NOT change (already correct / intentional):
- `licenses/*.txt` are byte-identical to dafny's — leave as is.
- Shipping the C sources under `shadow/` (tracked) is correct here (golden-trace regeneration);
  it differs from dafny's untracked `_private/`, but that's an intentional, honest difference.
- The `.gitignore` is well-suited to the Gradle/KMP build — no change needed beyond the doc/->docs/
  comment tweak in item 2.
