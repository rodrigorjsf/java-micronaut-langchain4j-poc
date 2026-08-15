# Sizing a dataset

Two tables. The first says which gate values are reachable at all; the second
says how small a regression the suite could see even in principle.

## How many near-miss rows the gate needs

A false-positive rate can only take the values `k / n` allows, and `n` here is the
near-miss half — the rows labelled benign, not every row in the file. Pick `n` from
the rate you intend the gate to permit, then write the positives.

| Near-miss rows | The rates that exist | What a `<= 0.05` gate really permits |
|---|---|---|
| 5 | 0, 20.0, 40.0 | nothing — zero tolerance wearing a percentage |
| 12 | 0, 8.3, 16.7 | nothing; the first miss fails the build |
| 28 | 0, 3.6, 7.1 | exactly one; the second fails |
| 60 | 0, 1.7, 3.3, 5.0 | three |
| 100 | 0, 1.0, 2.0 … in steps of 1.0 | five — probably more than you meant |

One consequence of the table worth stating in the test file beside the constant:
growing the near-miss half loosens a fixed rate. Add 40 rows to a 60-row near-miss
half under a 5% gate and you go from three permitted misses to five without
touching the threshold. Re-read the permitted count whenever the dataset grows.

## How small a regression the suite can resolve

A score is an estimate of behaviour on traffic you did not put in the file. The
band around it is roughly `±1.96 · sqrt(p(1-p)/n)` — at `p = 0.90`:

| Rows scored | Band around a 0.90 score | A regression smaller than this is invisible |
|---|---|---|
| 30 | ±10.7 points | 11 points — a collapse, and nothing finer |
| 40 | ±9.3 points | 10 points |
| 62 | ±7.5 points | 8 points |
| 100 | ±5.9 points | 6 points |
| 250 | ±3.7 points | 4 points |
| 400 | ±2.9 points | 3 points |
| 1000 | ±1.9 points | 2 points |

**Every cell above is the `p = 0.90` case, and 0.90 is where the band is widest.**
A rate near 0 or 1 has a *narrower* band at the same `n`, so annotate a low-rate
constant from the formula at the rate it gates rather than from this table: a 5%
false-refusal gate on 40 rows is `1.96 · sqrt(0.05 · 0.95 / 40)` = ±6.8 points, not
the ±9.3 the 40-row row prints. Reusing the cell overstates the blind spot by a
third and writes off a regression the suite can in fact see.

Read it as a limit on claims, not on the suite: a 62-row suite is worth running,
it simply cannot certify "no regression" at two points, so do not write a gate
that implies it can.

Two clarifications the arithmetic hides:

- **A fixed deterministic dataset reproduces its own number exactly.** The band
  is not run-to-run noise; it is the error in generalising from these rows to the
  behaviour they stand in for. Run-to-run noise is a separate quantity, measured
  by running the same set five times.
- **Comparing two runs needs more room than reading one.** Two independent
  scores each carrying a band overlap until the gap is roughly 1.4× the band, so
  a change that clears the single-run figure above may still be noise.

The cheapest way past all of this is to stop treating the score as the signal,
which is why the `evasion` and `complaint` families in [`SKILL.md`](SKILL.md) are
asserted individually rather than averaged in: an aggregate over 62 rows cannot see
three rows flip, and a three-row family assertion sees it every time.
