# Calibrating a judge

Open this before any verdict of a model-as-scorer gates anything, and again when
the grader model, its prompt or the model under test changes. The golden set's
labelling rules it reuses are in [`GOLDEN-SET.md`](GOLDEN-SET.md).

## A judge is an unevaluated classifier

When the scorer is itself a model — grading an answer for helpfulness,
faithfulness or tone — it carries every defect you are gating against, and none of
them have been measured. (This page calls it a judge, or a grader when it scores a
rubric; the two words name the same thing.) Before one of its verdicts gates anything, give it its
own human-labelled set, and label it exactly as the golden set: two
labellers, agreement ceiling and low-agreement remedy included.

**Size it per failure mode, with both classes: at least 60 rows per failure mode,
about 100 when you can**, and each failure mode holds rows a human labelled *fail*
and rows a human labelled *pass*. Below 60 the band around each rate is too wide
to conclude anything — [`SIZING.md`](SIZING.md) has the arithmetic.

**Score it as two rates, not one agreement number.** With the human labels as
ground truth, the **true positive rate** (TPR) is the share of human-*fail* rows
the grader also fails, and the **true negative rate** (TNR) the share of
human-*pass* rows it also passes. Each divides by its own class — the asymmetric
gates of `SKILL.md`, applied to the grader. Raw agreement hides the difference: on a set
that is 90% passes, a grader that passes everything agrees 0.90 of the time and
catches no failure at all.

```
BAD    grader agreement 55/60 = 0.92 (54 pass rows, 6 fail rows)
       passes all 54, fails 1 of the 6 failures — TPR 1/6 = 0.17

GOOD   60 rows for "cites a figure no tool returned": 30 fail, 30 pass
       TPR 27/30 = 0.90   TNR 28/30 = 0.93   each reported, each gated
```

**Recalibrate when anything the verdict depends on changes** — the grader model,
the grader's prompt, or the model under test. Each shifts the outputs the grader
sees or the way it reads them, so the TPR and TNR measured before describe a
different instrument. Re-run the calibration set and re-read both rates before
the grader's next verdict gates.

[sourced, read 2026-09-27 — the LangChain4j *Testing and Evaluation* tutorial,
docs.langchain4j.dev/tutorials/testing-and-evaluation (raw
`docs/docs/tutorials/testing-and-evaluation.md`), and the two posts it lists
first: Hamel Husain, *Creating a LLM-as-a-Judge That Drives Business Results*,
https://hamel.dev/blog/posts/llm-judge/ (modified 2026-09-01) — per-failure-mode
sizing ("about 100 examples per failure mode, with enough Pass and Fail examples
to measure both classes. Below 60 examples, the confidence intervals are often
too wide to support a useful conclusion"), TPR and TNR over raw agreement
("report the judge's True Positive Rate and True Negative Rate separately"),
re-running the review "whenever something material changes", synthetic data
limited to user inputs, and error analysis; and *Your AI Product Needs Evals*,
https://hamel.dev/blog/posts/evals/ — reading traces, and LLM-drafted test
inputs.]

Then ask it a question it can answer the same way twice.

```
BAD    "rate this answer 1-5 for helpfulness"
       one unchanged answer scores 4, 3, 4 across three runs
       the >= 3.5 gate flaps, and nobody can say what 3.5 means

GOOD   "which answer is better?" — candidate against a fixed baseline answer
       ask each pair twice: baseline first, then candidate first
       count a win only when the same answer wins both orders
       an order-flip is a no-win, not a discarded row
```

An absolute score is a scale the judge re-invents every run; a pairwise verdict
compares against something fixed. Both orders removes position bias and most of
the judge's preference for length; counting the flip keeps the denominator honest.
