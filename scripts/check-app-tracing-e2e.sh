#!/usr/bin/env bash
#
# WHAT  Runs the REAL application in Docker beside the Grafana observability profile,
#       sends one REAL chat turn through it, and then reads that turn back out of Tempo
#       and Prometheus. It asserts the whole chain end to end: the seams fired inside a
#       running JVM, the collector received the spans, Tempo stored them with the right
#       observation types, the span_metrics connector derived metrics from real traffic,
#       the OpenTelemetry GenAI client metrics arrived over OTLP, and the prompt was
#       stripped before anything reached Tempo.
#
# WHY   check-observability-stack.sh and check-langfuse-ingestion.sh both push SYNTHETIC
#       curl payloads. They prove the stack accepts a well-formed trace; they cannot prove
#       the application produces one. That gap was not theoretical: before this script
#       existed, compose.yaml set no OTLP endpoint at all, so the containerised app had a
#       complete tracing layer wired to nothing — every container healthy, every dashboard
#       empty, and nothing anywhere saying why.
#
#       The three things only a real run can show:
#         1. the twelve listener/AOP seams fire in a real JVM, not only in a unit test;
#         2. the OTLP METRICS path works — gen_ai.client.* is a different pipeline from the
#            span_metrics connector and shares none of its configuration;
#         3. the collector's attributes/strip-payloads processor really removes the prompt,
#            which is a data-protection claim this repository makes in writing.
#
# WHEN  After changing compose.yaml, application-docker.yml, the collector config, the
#       tracing defaults, or any observability seam — and before believing that "the
#       dashboard is empty" means "nothing happened".
#
# HOW   ./scripts/check-app-tracing-e2e.sh            # start, check, leave running
#       ./scripts/check-app-tracing-e2e.sh --down     # start, check, tear down
#
# Needs a Docker daemon, roughly 5.5 GB of free memory (app 3 GB + floci + the Grafana
# profile's 2.5 GB), and a .env holding a REAL GOOGLE_API_KEY — the turn calls the model
# for real, because a stubbed one would not exercise the generation seam. It is NOT part
# of `./mvnw test`, which is required to need none of those.
#
# The Langfuse profile is deliberately NOT started here. It is another ~4 GB and it has
# its own end-to-end script; the two profiles are meant to run one at a time.

set -euo pipefail

cd "$(dirname "$0")/.."

COMPOSE=(docker compose -f compose.yaml -f compose.observability.yaml --profile grafana)
FAILURES=0
CONVERSATION="e2e-$$"

ok()   { printf '  \033[32mok\033[0m    %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
note() { printf '        %s\n' "$1"; }

if [[ ! -f .env ]]; then
  echo "no .env — this script sends a REAL turn and needs GOOGLE_API_KEY" >&2
  exit 2
fi
if ! grep -qE '^GOOGLE_API_KEY=.+' .env; then
  echo "GOOGLE_API_KEY is empty in .env — the turn would fail at the provider" >&2
  exit 2
fi

echo "==> building and starting the application beside the grafana profile"
"${COMPOSE[@]}" up -d --build

echo "==> waiting for the application to report healthy (up to 180s)"
deadline=$((SECONDS + 180))
until curl -sf http://localhost:8080/health >/dev/null 2>&1; do
  if (( SECONDS > deadline )); then
    echo "the application never became healthy; last 40 log lines:" >&2
    docker logs agentic-app 2>&1 | tail -40 >&2
    exit 1
  fi
  sleep 3
done
ok "the application answers /health"

# The exporter is the thing most likely to be silently absent, and it announces itself in
# the log at startup. Checking it here separates "nothing was traced" from "nothing ran".
if docker logs agentic-app 2>&1 | grep -qiE 'otlp|opentelemetry'; then
  ok "the application logged OpenTelemetry startup"
else
  note "no OpenTelemetry line in the startup log (not fatal; the assertions below decide)"
fi

echo "==> sending one real turn"
RESPONSE=$(curl -sf -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d "{\"conversationId\":\"${CONVERSATION}\",\"message\":\"Qual a previsao do tempo em Recife amanha?\"}" \
  || true)

if [[ -z "$RESPONSE" ]]; then
  fail "POST /api/chat returned nothing"
  docker logs agentic-app 2>&1 | tail -30
else
  ok "POST /api/chat answered"
  note "$(echo "$RESPONSE" | head -c 160)"
fi

# The collector batches on a 5 s timeout and the span_metrics connector flushes every 15 s.
echo "==> waiting for the collector batch and the span_metrics flush"
sleep 25

echo "==> tempo"
# start/end are NOT optional, and their absence is not an error. Tempo answers
# {"traces":[],"metrics":{...}} for a trace it is holding and will return by id — measured on
# this stack — so a search without an explicit window reports "the application exported
# nothing" about a pipeline that is working perfectly. The window is generous on both sides
# because the container's clock and this shell's need not agree to the second.
#
# The predicate is this run's SESSION ID and not `name = "chat-turn"`, and that is not a
# refinement — it is a correction. check-observability-stack.sh pushes a synthetic probe
# named `chat-turn` typed `agent`, so a search on the name matches it too, `.traces[0]`
# picks whichever Tempo returns first, and the assertions below then describe the probe.
# Measured: that made this script report "no generation observation" and "no session id"
# about a turn that had produced both. The session id is set by the application from the
# conversation id, so nothing but this run can carry it.
#
# POLLED, not slept once. Tempo answers /api/traces/{id} for a trace its SEARCH index has
# not caught up with yet — measured here: at 25 s the search returned
# {"traces":[]} for a turn that /api/traces/{id} was already serving, and a minute later the
# same query found it. A fixed sleep therefore turns an ingestion lag into "the application
# exported nothing", which is the one conclusion this script must never reach by accident.
NOW_EPOCH=$(date +%s)
SEARCH='{}'
TRACE_ID=""
search_deadline=$((SECONDS + 150))
while :; do
  SEARCH=$(curl -sG http://localhost:3200/api/search \
    --data-urlencode "q={ span.langfuse.session.id = \"${CONVERSATION}\" }" \
    --data-urlencode "start=$((NOW_EPOCH - 3600))" \
    --data-urlencode "end=$((NOW_EPOCH + 300))" \
    --data-urlencode 'limit=20' || echo '{}')
  TRACE_ID=$(echo "$SEARCH" | jq -r '.traces[0].traceID // empty')
  [[ -n "$TRACE_ID" ]] && break
  (( SECONDS > search_deadline )) && break
  sleep 10
done
if [[ -z "$TRACE_ID" ]]; then
  fail "Tempo has no chat-turn trace from the application"
  note "search response: $(echo "$SEARCH" | head -c 300)"
  note "this is the failure the script exists for: the app ran and exported nowhere"
else
  ok "Tempo has the turn's trace ($TRACE_ID)"

  TRACE=$(curl -sf "http://localhost:3200/api/traces/${TRACE_ID}" || echo '{}')
  # Every attribute value in the trace, flattened once, so the assertions below are greps
  # over a stable shape rather than nine different jq paths.
  ATTRS=$(echo "$TRACE" | jq -r '
    [ .. | objects | select(has("key")) | {k: .key, v: (.value | (.stringValue // .intValue // .boolValue // .doubleValue // "" ) | tostring)} ]
    | map("\(.k)=\(.v)") | .[]' 2>/dev/null || echo '')

  TYPES=$(echo "$ATTRS" | grep '^langfuse.observation.type=' | cut -d= -f2 | sort -u | tr '\n' ' ')
  note "observation types in the trace: ${TYPES:-none}"

  # An agent graph is drawn only when a trace holds a type other than span/event/generation,
  # so the last three are what separate a real agent trace from a flat list of spans.
  # Measured on this stack: one real turn produces agent, chain, generation, guardrail,
  # span and tool.
  for want in agent generation chain guardrail tool; do
    if echo "$TYPES" | grep -qw "$want"; then
      ok "the trace contains a '$want' observation"
    else
      fail "the trace contains NO '$want' observation"
    fi
  done

  if echo "$ATTRS" | grep -q '^langfuse.session.id='; then
    ok "the session id reached the spans"
  else
    fail "no langfuse.session.id on any span — trace-level attributes are not propagating"
  fi

  if echo "$ATTRS" | grep -q "^langfuse.environment=docker"; then
    ok "langfuse.environment=docker, from the compose wiring"
  else
    fail "langfuse.environment is not 'docker' — compose.yaml's AGENTIC_ENVIRONMENT did not arrive"
  fi

  # The data-protection claim, checked rather than asserted in prose. The application sends
  # the SAME spans to Langfuse and here; the collector's attributes/strip-payloads processor
  # is the only thing that keeps the prompt out of Tempo.
  if echo "$ATTRS" | grep -qE '^langfuse.observation.(input|output)='; then
    fail "the prompt/completion reached Tempo — attributes/strip-payloads did not run"
  else
    ok "no observation input/output in Tempo (the collector stripped the payloads)"
  fi
fi

echo "==> prometheus: span metrics derived from REAL traffic"
promq() { curl -sG http://localhost:9090/api/v1/query --data-urlencode "query=$1"; }

REAL_CALLS=$(promq 'traces_span_metrics_calls_total{span_name="chat-turn"}' | jq -r '.data.result | length')
if [[ "${REAL_CALLS:-0}" -gt 0 ]]; then
  ok "traces_span_metrics_calls_total has a chat-turn series"
else
  fail "no chat-turn series in span metrics"
fi

echo "==> prometheus: the OpenTelemetry GenAI client metrics (the OTLP metrics path)"
# A DIFFERENT pipeline from the span_metrics connector above: these are emitted by the
# application's own MeterProvider and only exist when otel.metrics.exporter is otlp, which
# TracingDefaults enables only when a collector endpoint is configured. If the endpoint is
# missing, the spans below still arrive and these do not — which is exactly the asymmetry
# that makes this a separate check.
# The names are read off a running Prometheus, not composed by convention. The exporter
# appends the UNIT to the name of a histogram whose unit is a real one, so the duration is
# `gen_ai_client_operation_duration_seconds_count` — while `{token}` is an annotation rather
# than a unit and adds nothing, leaving `gen_ai_client_token_usage_count`. Guessing them
# symmetrically gets exactly one of the two wrong, and reports FAIL on a working pipeline.
for metric in gen_ai_client_token_usage_count gen_ai_client_operation_duration_seconds_count; do
  COUNT=$(promq "$metric" | jq -r '.data.result | length')
  if [[ "${COUNT:-0}" -gt 0 ]]; then
    ok "$metric is being scraped"
  else
    fail "$metric is absent — the OTLP metrics path is not working"
  fi
done

TOKEN_TYPES=$(promq 'gen_ai_client_token_usage_count' \
  | jq -r '[.data.result[].metric.gen_ai_token_type] | unique | join(" ")')
if [[ "$TOKEN_TYPES" == *input* && "$TOKEN_TYPES" == *output* ]]; then
  ok "gen_ai.token.type carries both input and output ($TOKEN_TYPES)"
else
  fail "gen_ai.token.type is incomplete: '${TOKEN_TYPES:-none}'"
fi

echo "==> prometheus: the application's own Micrometer counters"
if [[ "$(promq 'agentic_llm_cost_usd_total' | jq -r '.data.result | length')" -gt 0 ]]; then
  ok "agentic_llm_cost_usd_total is being scraped from the app"
else
  fail "agentic_llm_cost_usd_total is absent — the agentic-app scrape job is not working"
fi

echo
if (( FAILURES == 0 )); then
  echo "application tracing end to end: OK"
else
  echo "application tracing end to end: ${FAILURES} FAILURE(S)"
fi

if [[ "${1:-}" == "--down" ]]; then
  echo "==> tearing down"
  "${COMPOSE[@]}" down >/dev/null 2>&1 || true
fi

exit $(( FAILURES > 0 ? 1 : 0 ))
