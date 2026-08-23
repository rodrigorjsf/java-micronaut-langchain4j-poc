ALWAYS keep `caveman` and `ponytail` skill enabled in ultra mode
Always remember to consult the advisor when facing MEDIUM or HIGH complex resoning.

ALL code implementations MUST be done using the skill `/mattpocock-skills:tdd`

## Documentation

Study deliverable: docs are living, not write-once.

- `README.md` must stay in sync with the learnings. Update them in the **same commit** that changes behavior, scope, or run steps — never let them drift.
- Use **Mermaid diagrams** whenever they make a flow, architecture, or state clearer. Apply colors (`classDef`/`style`) and animated edges (`e1@{ animate: true }`) where the renderer supports them; colors are the baseline, animation is best-effort.

## Documentation Architecture

The learning material is a progressive technical book under `docs/`. These conventions are binding for every future evolution — add chapters, languages, OS, or harnesses by following them, not by inventing a new shape.

**README.md = slim hub.** Study context + ONE overview Mermaid diagram + a summary "when to use" table + a strong link to `docs/INDEX.md`. The deep teaching content does **not** live in the README — it lives in `docs/`. Each topic has a single source of truth; never duplicate deep content between README and `docs/` (drift is forbidden).

Example:
**`docs/` layout.**
- `docs/INDEX.md` — table of contents + introduction + the master reading order.
- **Spine (the linear book):** `01-foundations.md`, `02-cli-and-rules.md`, `03-agentic.md`, `04-when-to-use.md`, `05-best-practices.md`.
- **`docs/languages/`** — `java.md` (primary, deepest), `python.md`, `go.md`.
- **`docs/os/`** — `linux.md`, `wsl.md`, `macos.md`, `windows.md`.
- **`docs/harnesses/`** — `00-decision-policy.md` (the canonical, copy-pasteable "when ast-grep vs other tool" agent policy), then dedicated `claude-code.md`, `cursor.md`, `codex.md`, `pi.md`, `hermes.md`, plus a short table pointing Gemini CLI / Windsurf / Copilot to the canonical policy.
- **`docs/tools/`** — the companion **tool shelf** (the rest of the agent bench beside ast-grep): `00-overview.md` (taxonomy + decision matrix + recommended stack + the token-first tool policy), then one delta chapter per tool — `ripgrep.md`, `semgrep.md`, `repomix.md`, `files-to-prompt.md`, `markitdown.md`, `duckdb.md`, `qsv.md`, `ctags.md`. No `ast-grep.md` chapter (structural peers extend `04-when-to-use.md`). Each carries a `[verified]` benchmark from `scripts/bench-*.sh`.

**Progressive navigation.** One master linear order threads through everything: `INDEX → 01 → 02 → 03 → 04 → 05 → languages/* → os/* → harnesses/* → tools/*`. Every teaching doc ends with a manual `← Previous | Next →` footer following that order, so each has a real previous/next. In addition, reference docs cross-link siblings (java ↔ python ↔ go) and link back up to the spine chapter that introduced them (`↑ §03-agentic`).
**Separation by concepts, not copies.** Canonical content lives once (usually in the spine or the primary language doc). Language- and OS-specific docs hold only the *deltas* and point to the canonical doc for depth (e.g. `os/macos.md` = install channel + `.dylib` + no `sg` collision, then "see: os/linux.md / §02 for the rest"). Do not reword a canonical chapter into a near-duplicate per OS/language.
**Verification labeling is load-bearing.** Mark every non-trivial claim `[verified]` (ran the tool on this machine, output captured) or `[sourced]` (official docs/repo) or `[sourced — unverified]` (could not confirm). **A `[verified]` label may only be applied to a pattern/command actually executed in the main thread** — never let a parallel/fanned-out subagent stamp `[verified]`, because subagents cannot reliably run the tool. Subagents draft prose, structure, and sourced comparisons; all tool execution stays in the main thread. Migrate real captured output (scan diagnostics, `--debug-query` dumps, JSON) verbatim — never paraphrase tool output.
**Harness config blocks are fetched-and-quoted, not recalled.** Config file paths and MCP-mount syntax churn fast and are easy to hallucinate — quote the official doc with its URL and a date stamp; if it can't be found, the block is `[sourced — unverified]`.

## Automation scripts (`scripts/`)

Deterministic, repeatable agent-ops procedures live in [`scripts/`](./scripts/). **Prefer the
script over re-deriving the steps** — each one encodes hard-won traps so they are not re-suffered.
Every script carries a `WHAT / WHY / WHEN / HOW` header comment; read it before first use.

| Script | Use it when |
|---|---|
| [`check-tool-catalogue.py`](scripts/check-tool-catalogue.py) | after adding or renaming a tool, or editing `agentic.tools.apis` — pairs Java catalogue keys with configured endpoints both ways, and gates https |
| [`check-skill-docs.py`](scripts/check-skill-docs.py) | after adding, renaming or removing a skill, or editing the tables in `docs/06-skills.md` — asserts set equality between `.claude/skills/` and the chapter, both ways |
| [`check-observability-stack.sh`](scripts/check-observability-stack.sh) | after editing anything under `observability/` or bumping an image tag — pushes a real trace through the collector, reads the derived metric names back, and reads the provisioned alert rules, contact point and notification policy back out of Grafana |
| [`check-langfuse-ingestion.sh`](scripts/check-langfuse-ingestion.sh) | after changing anything under `observability/trace/` or upgrading Langfuse — pushes a real trace, real scores, a CORRECTION and an experiment-shaped trace into a running instance and reads every one back |
| [`check-alert-rule-claims.sh`](scripts/check-alert-rule-claims.sh) | after editing `observability/grafana/provisioning/alerting/alert-rules.yaml` — asserts the one claim a YAML linter cannot check, and reproduces it with a `promtool` unit test on the real PromQL engine |
| [`check-app-tracing-e2e.sh`](scripts/check-app-tracing-e2e.sh) | after changing `compose.yaml`, the collector config, `TracingDefaults` or any seam — runs the REAL app in Docker, sends SIX REAL turns, and reads them back out of Tempo and Prometheus |
| [`check-langfuse-3x-compat.sh`](scripts/check-langfuse-3x-compat.sh) | after changing anything under `observability/trace/`, and before editing the compatibility table in `docs/08-langfuse-features.md` — measures what Langfuse 3.80.0 does with every integration built against 4.16.0, and reports a difference as a GAP rather than a failure |

**Standing rule — export repeatable procedures.** Whenever you hit a multi-step procedure that is
deterministic and likely to recur (release, registry verification, a gating/render check, an
environment workaround), **capture it as a `scripts/` script** with a `WHAT / WHY / WHEN / HOW`
header comment instead of re-deriving it inline, and add a row to the table above. If the script is
also useful to a human maintainer, the header comment is its documentation. This saves tokens and
makes the procedure auditable and reproducible.

## Git Conventions

- ALL commits MUST be atomic — one logical change per commit, never bundle unrelated changes
- Scope commits by concern: rules changes, CLAUDE.md changes, version bumps, and documentation each get separate commits
- Commit message format: `{type}({scope}): {description}` — use `feat`, `fix`, `docs`, `chore`, `refactor`
- Stage only files belonging to the same logical change; never `git add -A` across unrelated changes
- If asked to "commit everything", break it into atomic commits by scope first, then commit each group

## Implementation Completion Protocol

After any substantive implementation in this repository, before declaring the work done:

1. Make the deliverable durable first (write the file, save, commit if appropriate).
2. Call `advisor()` for a second-opinion review of the change.
3. Run `/mattpocock-skills:code-review` on the diff and resolve any P0/P1 findings before stopping.

## Agent skills

### Issue tracker

Issues live in GitHub Issues for `rodrigorjsf/java-micronaut-langchain4j-poc`, accessed via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Five canonical roles — `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix` — using default label names. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `CONTEXT.md` and `docs/adr/` at the repo root (created lazily). See `docs/agents/domain.md`.

## Applied Learning

When something fails repeatedly, when User has to re-explain, or when a workaround is found for a platform/tool limitation, add a one-line bullet here. Keep each bullet under 15 words. No explanations. Only add things that will save time in future sessions.

- Agents fail silently on wrong paths. Always verify hardcoded paths.
- Before creating a new project artifact, check if an existing one can be extended or merged.
- Java text block `\` continuation keeps extra indent; prompts get stray spaces.
- Micronaut `@ConfigurationProperties` interface needs `@AccessorsStyle(readPrefixes = "")`.
- `@EachProperty` + `@Parameter` needs a class, not an interface.
- Micronaut `@Cacheable` is proxy-based: self-invocation never caches.
- `@EachProperty` entries in application.yml merge into every test context.
- Reordering `@Factory` methods leaves stale `Bean1`/`Bean2` definitions; run `mvnw clean`.
- `git checkout -- <file>` discards unstaged edits; copy the file first.
- `ToolJson.project` on a container path keeps it whole; use `projectList`.
- Workflow `resumeFromRunId` is same-session only; make long fan-outs idempotent.
- `git add -A` sweeps subagent scratch files into the commit.
- Output guardrail retries exhausted = exception; bound reprompts in the guardrail.
- Test doubles called by parallel sub-agents need concurrent collections.
- Answering a workflow agent by message resumes it beside the workflow's own next round.
- Transport ceiling floor is the largest raw body, not the largest response budget.
- Per-piece critics cannot see docs the code falsified; run a cross-piece sweep.
- Hand-off to a user-invoked skill must say "ask the user to run it".
- Recursive splitters pack the next heading on; run one before printing a chunk.
- Micronaut empty default is `${VAR:}`; `${VAR:``}` yields the literal two backticks.
- "Unresolved compilation problem" at runtime = stale IDE classes; `./mvnw clean`.
- Langfuse self-host: ClickHouse tag must match upstream's; 25.3 fails migration 39.
- Langfuse v4 events_only has no `GET /traces/{id}`; read the observations instead.
- `/api/public/v2/observations` needs `fields=`, else payload fields read back null.
- Docker Desktop credsStore can break in WSL; anonymous pull works with `{}` config.
- `check-langfuse-ingestion.sh` needs Langfuse already up; it starts nothing.
- Workflow concurrency caps at CPUs-2; on 4 CPUs only 2 agents run.
- Background command piped to `tail -N` shows nothing until it exits.
- Any floci teardown desyncs its Valkey child; wipe `floci-data` before `up`.
- `docker compose down` then `up` on one profile can orphan the network; prune it.
- Micronaut nested placeholder default `${a:${b:x}}` leaks the inner `}` into the value.
- Micronaut `otel.exclusions` regexes are FULL matches; `/health` misses `/health/liveness`.
- `otel.exclusions` feeds the HTTP CLIENT filter too, not only the server one.
- Micronaut `InterceptPhase.CACHE` (-100) wraps `TRACE` (-80): `@Observed` misses cache hits.
- `pkill -f <pattern>` kills this session's own shell when the pattern is in its command line.
- Langfuse 3.80.0 knows only SPAN/GENERATION/EVENT; every richer type stores as SPAN.
- Langfuse 3.80.0 reads usage from `gen_ai.usage.*` only, never `usage_details`.
- Langfuse OTLP/JSON on 3.80.0 re-hexes the traceId string; protobuf is unaffected.
- Micronaut Serde cannot serialize `ChatMessage`; use LangChain4j's `ChatMessageSerializer`.
- Langfuse v3 scores name their target in `subject.kind`, not an `observationId` field.



