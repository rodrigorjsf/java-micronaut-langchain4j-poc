# Worked example: the repository this skill was written in

Open this when the backend in front of you is the Micronaut + LangChain4j chat
service this skill was written in. It maps each step of [`SKILL.md`](SKILL.md) to
the paths, identifiers and commands that exist here. Everything else in the skill
is portable; this page is the only one that names this codebase.

## Where things are

| What | Path |
|---|---|
| the dataset, one file per domain | `src/test/resources/evals/scenarios/<domain>.json` |
| the stateful domains | `src/test/resources/evals/stateful-domains.json` (a JSON array of domain names) |
| the row type and its allowed kinds and sources | `src/test/java/.../evals/scenario/Scenario.java` |
| the loader that validates rows | `ScenarioDataset` — rejects an invalid row with file, id and reason |
| the artifact scan and fingerprints | `ArtifactInventory` — its javadoc is the identifier convention |
| the eval | `ScenarioSuiteEval`, tagged `evals`, driving `POST /api/chat` on an embedded server |
| the report | `eval-report.html` at the repository root (gitignored) |

## Step 1 — the inventory, as identifiers

| Kind | Identifier | Found in |
|---|---|---|
| skill | directory name, e.g. `geo-and-weather` | `src/main/resources/skills/<name>/` — 14 runtime skills |
| tool | `@Tool` method name, e.g. `get_weather` | the tool classes under `tools/` |
| system-prompt section | `system-prompt#role`, `#non-negotiable-rules`, `#skills`, `#how-to-answer`, `#arithmetic`, `#voice` | `SystemPromptBuilder`, `prompt/CALCULATION.md`, `voice/VOICE.md` |
| catalogue key | the `agentic.tools.apis` key, e.g. `open-meteo-forecast` | `application.yml` |
| sub-agent | the `@Agent(name = …)`: `weather_reporter`, `holiday_checker`, `trip_briefer`, `place_resolver` | `agent/workflow/` |
| guardrail | inventoried for expectations only — not yet a `dependsOn` kind (#53) | `guardrail/input/`, `guardrail/output/`, `guardrail/tool/`, triage |

A `dependsOn` entry that names no artifact is reported as an unknown dependency,
and a default-build test asserts the committed dataset has none — run the default
build after adding rows.

## Step 2 — domains

Domains are named per user flow and may be narrower than a skill: the committed
`weather` domain is one flow of the `geo-and-weather` skill. `trip-briefing` is a
sub-agent workflow and a domain of its own. Record a new stateful domain in
`stateful-domains.json` in the same diff that adds its multi-turn row.

## Step 4 — the fields this runner checks

The loader binds rows to `Scenario` with Jackson; write only these fields.

| Field | Supported values |
|---|---|
| `kind` | `happy`, `missing-info`, `out-of-territory`, `upstream-failure`, `injection`, `ambiguous`, `multi-turn`, `cross-domain`, `out-of-scope` |
| `source` | `trace`, `synthetic`, `user` |
| `expect.trajectory` | `outcome` (`ANSWERED`, `REFUSED`, `BLOCKED`) and `toolsCalled` |
| `expect.answer` | `language` (`pt-BR` or `en`), `contains[]` (case-insensitive), `grounded[]` (regexes; group 1 when present) |
| `expect.turns[]` | `{"turn": N, "trajectory": {…}, "answer": {…}}`, 1-based; check names are prefixed `turn N:` |
| `expect.rubric[]` | criteria for the `grader` model role — report-only |
| `upstream` | `{"catalogueKey": "<key>", "failure": "server-error" \| "timeout" \| "oversized"}`, only on `upstream-failure` rows |

The runner also checks **no link outside the catalogue** on every answer, whether
the row asks or not. The first committed row, as a model:

```json
{"id": "weather-happy-forecast-tomorrow", "domains": ["weather"], "kind": "happy",
 "source": "synthetic", "turns": ["Vai chover amanhã em São Paulo?"],
 "expect": {"trajectory": {"outcome": "ANSWERED", "toolsCalled": ["get_weather"]},
            "answer": {"language": "pt-BR",
                       "grounded": ["(-?\\d+(?:[.,]\\d+)?)\\s*(?:mm|milímetros|°C|ºC|°|graus|%)"]},
            "rubric": ["The answer tells the user directly whether rain is expected in São Paulo tomorrow, rather than only listing figures."]},
 "dependsOn": ["geo-and-weather", "get_weather", "open-meteo-forecast"],
 "critical": true}
```

## Step 7 and diff mode — commands

```bash
./mvnw test                                            # default build: loader, coverage, renderer, runner with a stub model
./mvnw test -Pevals                                    # the whole suite against the real model, 3 repetitions each
./mvnw test -Pevals -Dscenario.domain=weather          # one domain, cross-domain rows included
./mvnw test -Pevals -Dscenario.ids=weather-upstream-timeout
./mvnw test -Pevals -Dscenario.changedSince=main       # diff mode: rows whose dependsOn changed since main
./mvnw test -Pevals -Dscenarios.repetitions=1          # cheaper while iterating
```

The selection properties combine with AND. An unknown domain or id is refused, and
a selection that matches nothing aborts the eval rather than passing it. The
report's coverage table, uncovered artifacts and unknown dependencies are computed
over the whole dataset whatever the selection.

## Gaps to know before you trust a run

| Gap | Consequence | Tracked in |
|---|---|---|
| tool-calling turns fail on Gemini 3.x (missing `thought_signature`) | every tool-using row fails against the real model until fixed or the `agent` role moves | #18 |
| only `outcome` and `toolsCalled` exist in `expect.trajectory` | activation, argument constraints, forbidden tools and call bounds cannot be asserted yet; keep them out of rows | #52 |
| guardrails are not a `dependsOn` artifact kind | a guardrail edit selects no row in diff mode; run the `injection` and `out-of-scope` rows by id after one | #53 |
| the language check cannot fail a `pt-BR` expectation on a non-English answer | a Spanish answer passes `language: pt-BR` | #48 |
| grounding tolerates ±0.5 against any captured number | an invented integer near a real one can ground | #49 |
| an upstream tool's own 429 scores FAILED, not SKIPPED | an upstream quota reads as a regression | #50 |
| the grader is uncalibrated | rubric verdicts are opinions, never counted | #33 |
| the coverage floor is reported, not gated | a domain below the floor still runs green | #47 |
