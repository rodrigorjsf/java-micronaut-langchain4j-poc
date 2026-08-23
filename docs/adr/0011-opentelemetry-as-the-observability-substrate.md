# 0011 — OpenTelemetry is the substrate; Langfuse and Grafana are two readers of it

**Status:** Accepted · **Date:** 2026-08-22 · **[measured]** **[sourced]**

## Context

The application already reported per-role token counts, cost and latency as Micrometer
counters (see [chapter 4](../04-operations.md)). That answers *how much* and never *why*:
a counter cannot say which turn spent the tokens, which tool call took four seconds, or
what the model was actually asked. A number without a turn behind it is an alarm with no
address.

The two obvious destinations answer different questions and neither replaces the other.
**Langfuse** is LLM-native — it knows what a generation, a tool call, a retrieval and a
score are, it renders prompts and completions, and it can attach an evaluation to a
specific observation. **Grafana** is service-native — it knows about rate, errors and
duration, it holds the traces beside the rest of the platform's, and its alert rules are
the ones an on-call rotation already reads.

Writing to both directly would mean two client libraries, two data models, and two places
where an attribute name can be wrong.

## Decision

**OpenTelemetry is the only instrumentation this application has.** Both destinations read
the same spans.

Langfuse makes this possible in a way that a proprietary SDK would not: it accepts plain
OTLP on `/api/public/otel/v1/traces` and maps `langfuse.*` span attributes onto its own
data model, so there is no Langfuse client library for Java and none is needed. `[sourced]`
Grafana reads those same spans from Tempo, plus RED metrics the collector derives from them
with the `span_metrics` connector.

The application exports to each destination independently — a collector for the Grafana
side, Langfuse directly for the other. That costs one extra egress and buys the property
that the two halves start, stop and fail independently.

### Everything is a seam, and none of them is a call site

The instruction that shaped the design was that a span must not be started and filled at
every point in the code where one belongs. LangChain4j 1.18.1 makes that achievable
because it publishes a listener for every layer:

| Layer | Seam | Observation type |
|---|---|---|
| the turn | `ChatTurnService.handle` — the only hand-opened observation | `agent` (root) |
| triage, compaction, memory | `@Observed` AOP | `chain` / `span` |
| model call | `ChatModelListener` | `generation` |
| AI-service invocation | `AiServiceStarted/Completed/ErrorListener` | `agent` |
| tool execution | `ToolExecutedEventListener` | `tool` |
| guardrails | `Input/OutputGuardrailExecutedListener` | `guardrail` |
| embeddings | `EmbeddingModelListener` | `embedding` |
| retrieval | `ContentRetrieverListener`, `EmbeddingStoreListener` | `retriever` |
| outbound HTTP | Micronaut's OTel client instrumentation | `span` |
| judge verdicts | `POST /api/public/scores` | *a score, not a span* |

The types are load-bearing rather than decorative: Langfuse draws an **agent graph** for a
trace only when it holds an observation typed something other than `span`, `event` or
`generation`, and only `generation` and `embedding` carry usage and cost. `[sourced]`

### The application does not import OpenTelemetry

`AgentTracer` is the only interface the rest of the code sees. That is one indirection with
one implementation, which this codebase would normally refuse — it is here because twelve
seams importing `io.opentelemetry` is the version that cannot be changed.

## Evidence

### The context survives every hop — measured, because the failure is silent

A probe ran a real `AiServices` with a scripted model, one tool and one output guardrail
inside a manually started span, recording `Span.current()` at each callback. Root span
`c599fd577746c985`:

```
caller                             c599fd577746c985 SAME-AS-ROOT
ChatModelListener.onRequest        c599fd577746c985 SAME-AS-ROOT
ChatModelListener.onResponse       c599fd577746c985 SAME-AS-ROOT
ToolExecutedEventListener          c599fd577746c985 SAME-AS-ROOT
Context.taskWrapping executor      c599fd577746c985 SAME-AS-ROOT
ToolExecutor.execute               c599fd577746c985 SAME-AS-ROOT
plain virtual-thread executor      NO-SPAN !!! LOST
OutputGuardrail.validate           c599fd577746c985 SAME-AS-ROOT
```

LangChain4j calls every listener on the caller's thread, so the listener design needs no
context plumbing at all. **The one loss was ours**: `WorkflowExecutorFactory` returned a
bare `Executors.newVirtualThreadPerTaskExecutor()`, so every trip-briefing sub-agent would
have opened its own root trace and the workflow would have appeared as four unrelated
traces. `Context.taskWrapping` fixes it in one line.

The remaining hop — `ToolHttpClient`'s Reactor `Mono` answering on a Netty event loop — is
pinned by `OutboundCallObservationTest`: the outbound client span's parent is the tool
observation, in the same trace.

### The Grafana side, end to end

`./scripts/check-observability-stack.sh` starts the profile, pushes an OTLP trace through
the real door and reads back what the collector produced rather than asserting what it
should be:

```
  ok    health_check extension answers on :13133
  ok    no deprecated component types in the collector config
  ok    the collector accepted an OTLP/HTTP trace (200)
  ok    traces_span_metrics_calls_total
  ok    traces_span_metrics_duration_milliseconds_bucket
  ok    label span_name
  ok    label langfuse_observation_type
  ok    label gen_ai_request_model
  ok    label gen_ai_operation_name
  ok    label service_name
  ok    tempo returned the probe trace by id
  ok    data source prometheus provisioned
  ok    data source tempo provisioned
  ok    dashboard agentic-llm provisioned
```

It reads them back because that metric name is composed by three separate pieces of code —
the connector's namespace, the exporter's unit suffix, the exporter's `_total` for a
monotonic sum — so a dashboard query can be wrong while every container is healthy.

### The Langfuse leg, exercised against a running instance **[measured]**

`./scripts/check-langfuse-ingestion.sh` against self-hosted Langfuse `4.16.0`, 2026-08-23.
It pushes an OTLP trace and two scores through the real endpoints and reads both back:

```
  ok    OTLP accepted (200)
  ok    both observations ingested (2)
  ok    root observation typed AGENT (not SPAN)
  ok    child observation typed GENERATION
  ok    root input survived the round trip
  ok    root output survived the round trip
  ok    usage_details parsed, exclusive buckets kept: {"input":86,"input_cached_tokens":17817,"output":188,"total":18091}
  ok    cost_details ingested rather than inferred: {"input":0.0000215,"output":0.000282,"total":0.0003035}
  ok    model name mapped
  ok    the root observation is recognised as the root
  ok    trace is named 'chat-turn'
  ok    the session id is on every observation
  ok    prefixed metadata is a top-level, filterable key
  ok    totalCost is the ingested number, not an inferred one
  ok    score created (200, id 06c1e398-da7f-4b52-8e55-c2eab147c8b8)
  ok    categorical score accepted (200)
  ok    numeric score reads back as 0.93
  ok    categorical score reads back as IN_SCOPE
  ok    the score is attached to the OBSERVATION, not the trace
```

The observation as Langfuse stored it, read back verbatim:

```
=== chat-turn AGENT
    input = "qual o cep da avenida paulista?"
    output = "O CEP e 01310-100."
    metadata = {'outcome': 'ANSWERED', 'scope.name': 'check-langfuse-ingestion', …}
    traceName = chat-turn
    sessionId = check-e399a323
    isRootObservation = True
=== agent GENERATION
    model = gemini-3.1-flash-lite
    usageDetails = {'input': 86, 'input_cached_tokens': 17817, 'output': 188, 'total': 18091}
    costDetails = {'input': 2.15e-05, 'output': 0.000282, 'total': 0.0003035}
    totalCost = 0.0003035
```

So the two silent failures below are now confirmed rather than inferred: the lower-case
`agent` produced `type = AGENT` (an upper-case one would have produced `SPAN`), and the
explicit root markers produced `isRootObservation = True`. `totalCost` equals the number
this application sent, so nothing was re-priced. The `langfuse.observation.metadata.`
prefix put `outcome` at the top level where a filter can reach it, while the unprefixed
OpenTelemetry attributes went to the `attributes.*` catch-all exactly as documented.

**Three things this cost, all of which look like something else.**

**ClickHouse must be 25.12.** Pinning 25.3 — a version this project chose without checking
the one upstream pins — fails Langfuse's migration 39, which creates a skip index whose
argument is not a literal:

```
Code: 80. DB::Exception: Only literals can be skip index arguments. (INCORRECT_QUERY)
```

The migration then leaves `schema_migrations` at version 39 with `dirty = 1`, and every
subsequent boot of `langfuse-web` exits 1 with `Dirty database version 39. Fix and force
version.` — a restart loop whose message names the database and not the cause. The first
diagnosis was wrong for an instructive reason: the table also held version 38 twice, once
dirty and once clean, which looks exactly like two services racing on the migration. It was
one service failing, restarting, and trying again.

**Two read endpoints do not exist on a v4 deployment.** `GET /api/public/traces/{traceId}`
and `GET /api/public/observations/{id}` both answer 404 with *"This endpoint is not
available on deployments running in Langfuse v4 events_only mode"*. There is no trace
entity to read — a trace is a query over observations — so the trace's name and session are
read off the observations themselves.

**`/api/public/v2/observations` returns a lean projection unless asked otherwise.** Without
`fields=`, `input`, `output`, `model`, `usageDetails` and `costDetails` all come back
`null`, which is indistinguishable from an ingestion that dropped them; `traceName` needs
the `trace_context` group specifically. Both of those cost a round of false diagnosis here.

### Two silent failures found by reading the Langfuse source

`[sourced]` `packages/shared/src/server/otel/ObservationTypeMapper.ts` at v4.16.0.

**The observation type is matched case-sensitively, and a miss is not an error.** The
mapper is a bare key lookup on the raw attribute value against a lower-case table;
`.toUpperCase()` is applied to the *result*, never to the input. `GENERATION` therefore
maps to nothing, the loop falls through, and the registry defaults the observation to
`SPAN` — no error, no warning, no metric. `ObservationType.wireValue()` lower-cases for
exactly this reason.

**A root observation is a query-time predicate, not a record.** Langfuse asks
`parent_span_id = '' OR is_app_root = true`, and the legacy write path reads a second
attribute with a string comparison. A trace with no row satisfying it is not rejected —
every observation is ingested and the trace simply has no root and no name. The turn
therefore says outright that it is the root, on both attributes, instead of relying on the
server-span exclusion that currently makes it true by accident.

### Cost is ingested, not inferred

Langfuse can price a generation from its own model definitions. This application sends
`cost_details` from the existing `CostCalculator` instead, so the number in a trace and the
number in `agentic_llm_cost_usd_total` cannot disagree — the same argument that put those
prices in configuration rather than in a constant.

Building that path surfaced a defect in the accounting that already shipped. langchain4j
maps Gemini's `outputTokenCount` from `candidatesTokenCount` alone, and `thoughtsTokenCount`
sits beside it; Google bills those thinking tokens at the output rate, and the `agent` role
runs with `thinking-level: low`. **Every turn's cost was understated by however much the
model thought.** OpenAI has the opposite convention — `completion_tokens` already contains
its reasoning tokens — so one is added and the other subtracted, and getting that backwards
is invisible in both directions.

## Consequences

**Nothing is exported by default.** With no endpoint configured the injected `Tracer` is
OpenTelemetry's own no-op and `otel.traces.exporter` is `none` — OpenTelemetry's own default
is `otlp`, which would have every test run dial `localhost:4318`. The default build still
needs no network, no Docker and no API key.

**Baggage is not propagated, at either end.** Langfuse recommends OTel Baggage with a
`BaggageSpanProcessor` for copying trace-level attributes to every span, and its own
documentation carries the reason not to: baggage is injected into outbound request headers.
Every tool here calls a public API with arguments the model chose, so the conversation id
would travel to fifty third parties for the sake of an in-process copy. A private
`ContextKey` does the same job and cannot be injected into a header by any propagator, and
`otel.propagators` is `tracecontext` rather than the default `tracecontext,baggage`.

**Span content is a switch, not an assumption.** Inputs and outputs are the user's message,
the system prompt and whatever a public API returned. Capturing them is the point of tracing
an agent — a trace with no content shows the shape of a turn and nothing about it — and it
is also where observability becomes a data-protection decision. `capture-content` defaults
to on, with a `ContentRedactor` bean list for the rules that are deployment-specific.

**The two stacks do not fit together on this machine.** Langfuse self-hosted is six
containers wanting ~4 GB, the Grafana side wants ~2.5 GB, and the application plus floci want
3 GB more, against 7 GB of RAM. `compose.observability.yaml` therefore ships two profiles and
the documentation says to run one at a time. Pretending otherwise produces an OOM-killed
ClickHouse and a Langfuse answering 500 with nothing in its logs to explain it.

**Scores are a second transport.** They are not spans and cannot be — Langfuse's own
migration guide says so — so the judge's verdicts travel over `POST /api/public/scores` on a
bounded queue with a background consumer. Synchronous would spend the 0.91 s median that is
the entire justification for having a cheap judge.

## What would make us revisit this

- **Langfuse gains a Java SDK.** The `langfuse.*` attribute names are a wire contract with a
  server this codebase does not own, and every one of them fails quietly when it is wrong.
- **The collector becomes mandatory anyway.** If the Grafana side ever needs tail sampling or
  redaction, the collector stops being optional and the direct-to-Langfuse leg becomes the
  odd one out.
- **A second service starts a trace that reaches this one.** The turn would stop being the
  OTLP root, and only the explicit root markers would keep the trace headed.

---

← [0010 — retrieval](0010-rag-over-the-assistants-own-documentation.md) | [Back to the index](README.md) →
