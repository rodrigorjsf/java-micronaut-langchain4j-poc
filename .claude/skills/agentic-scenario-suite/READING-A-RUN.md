# Reading a run: the scenario report

Open this to read a finished run (step 7 of [`SKILL.md`](SKILL.md)), or to give a
scenario runner in another stack the same report. The row shape is in
[`SCENARIO-SCHEMA.md`](SCENARIO-SCHEMA.md); this page covers what a run writes
down about itself and how to act on it.

## One self-contained page

The runner writes `eval-report.html` at the repository root after every run: inline
CSS, no script, nothing fetched, so it opens from disk on any machine. Every piece
of model and user text is HTML-escaped — an answer is untrusted text, not markup.
The file is gitignored: it is a run artefact, never history.

Render it with a **pure function** from run results to an HTML string, and write
the file from a test listener after the run. A pure renderer is testable in the
default build, with no model and no network.

## What the page holds

| Section | Per | Contents | The question it answers |
|---|---|---|---|
| summary | run | the selection (all, a domain, a list of ids, or diff mode since a ref), passed scenarios, gate failures, flaky scenarios with their pass rate, skipped repetitions, tokens and estimated cost | is the suite healthy, and what did this run cost? |
| coverage per domain | domain | happy rows, named bad paths, multi-turn row if stateful, each against the floor, with the shortfall spelled out | which domain is under-tested? |
| uncovered artifacts | artifact | every artifact no row names in `dependsOn` | what ships without a scenario? |
| unknown dependencies | `dependsOn` entry | entries that name no artifact | which row is invisible to diff mode because of a typo? |
| each scenario | row | domain(s), kind, `source`, input turns, the expectation, the faked upstream if any, then per repetition: activations, tool calls with arguments and results, the answer, every check with pass/fail and its reason, rubric verdicts, latency, tokens, trace link when available | why did this row pass or fail? |

**Every check carries a reason.** "failed" is not diagnosable; "tool called:
get_weather — not called; tools called: find_place" is. The same holds for
grounding: name the value that was not found in any captured tool result.

## How a run adds up

| Situation | Status | Effect |
|---|---|---|
| every repetition that ran passed | PASSED | — |
| some passed, some failed | FLAKY | a `critical` row fails the suite; any other row is reported only |
| none passed | FAILED | a `critical` row fails the suite; any other row is reported only |
| every repetition hit a provider rate limit | SKIPPED | neither; a quota error is not a regression |

Repetition counts and whether a pass rate is enough to gate are measurement
decisions — read them off `agentic-evals`.

**The rubric is labelled uncalibrated and never gates.** A grader is a model with
its own error rates; until they are measured against human labels, its PASS or
FAIL is an opinion shown beside the checks, never counted among them. A grader
that errors or answers something that is not a verdict shows UNGRADED.

## Acting on what you read

| You see | Do this |
|---|---|
| a critical row FAILED or FLAKY | diagnose before editing: open the repetition, read the trajectory, then decide whether the row, the artifact or the agent is wrong |
| a grounding check failed with an invented value | the agent made a number up; that is an agent defect, not a row to loosen |
| a domain below the floor | draft the missing kinds through steps 3–6 of [`SKILL.md`](SKILL.md) |
| an uncovered artifact | draft rows that depend on it; a new tool or skill with no row is untested behaviour |
| an unknown dependency | fix the identifier; until then diff mode cannot select that row |
| most repetitions SKIPPED | the run measured nothing; re-run later or with pacing, and say it was skipped, not green |
| a rubric FAIL on a row whose checks passed | a candidate for a new deterministic check, or for the grader's calibration set — never a reason to fail the row |
