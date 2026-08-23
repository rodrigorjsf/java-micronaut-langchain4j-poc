#!/usr/bin/env bash
#
# WHAT  Starts the Grafana observability profile, pushes a probe trace through the real
#       OTLP door, and asserts that every link of the chain worked: the collector accepted
#       it, the span_metrics connector derived the metric names the dashboard queries use,
#       Tempo stored the trace, and Grafana provisioned its data sources and dashboard.
#
# WHY   Four of those five links fail SILENTLY. A collector component renamed to snake_case
#       still runs under its deprecated alias until the alias is removed; the Prometheus
#       metric name is composed from a namespace, a unit and a counter suffix by three
#       different pieces of code, so a dashboard query can be wrong while everything is
#       healthy; and a provisioning file with a bad field logs at startup and is never seen
#       again. Reading the metric names off a running collector is the only honest check.
#
# WHEN  After editing anything under observability/, after bumping any image tag in
#       compose.observability.yaml, and before believing a dashboard panel that shows no
#       data.
#
# HOW   ./scripts/check-observability-stack.sh            # start, check, leave running
#       ./scripts/check-observability-stack.sh --down     # start, check, tear down
#
# Needs a Docker daemon and roughly 2.5 GB of free memory. It is NOT part of `./mvnw test`,
# which is required to need neither.

set -euo pipefail

cd "$(dirname "$0")/.."

COMPOSE=(docker compose -f compose.observability.yaml --profile grafana)
TRACE_ID="5b8aa5a2d2c872e8321cf37308d69df2"
FAILURES=0

ok()   { printf '  \033[32mok\033[0m    %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

cleanup() {
  if [[ "${1:-}" == "--down" ]]; then
    echo "==> tearing down"
    "${COMPOSE[@]}" down >/dev/null 2>&1 || true
  fi
}

echo "==> starting the grafana profile"
"${COMPOSE[@]}" up -d >/dev/null

echo "==> waiting for the collector to report healthy"
for _ in $(seq 1 60); do
  if curl -fsS http://localhost:13133 >/dev/null 2>&1; then break; fi
  sleep 2
done

echo "==> collector"
if curl -fsS http://localhost:13133 >/dev/null 2>&1; then
  ok "health_check extension answers on :13133"
else
  fail "health_check extension never answered on :13133"
fi

# A deprecated component alias still WORKS; it only warns. That warning is the whole signal.
if "${COMPOSE[@]}" logs otel-collector 2>&1 | grep -qi "deprecat"; then
  "${COMPOSE[@]}" logs otel-collector 2>&1 | grep -i "deprecat" | head -5
  fail "the collector logged a deprecation warning — a component type has been renamed"
else
  ok "no deprecated component types in the collector config"
fi

echo "==> pushing a probe trace through the real OTLP door"
NOW_NANOS=$(date +%s)000000000
PAYLOAD=$(cat <<JSON
{"resourceSpans":[{"resource":{"attributes":[
  {"key":"service.name","value":{"stringValue":"agenticchat"}}]},
 "scopeSpans":[{"scope":{"name":"check-observability-stack"},"spans":[
  {"traceId":"${TRACE_ID}","spanId":"051581bf3cb55c13","name":"chat-turn","kind":1,
   "startTimeUnixNano":"${NOW_NANOS}","endTimeUnixNano":"$((NOW_NANOS + 120000000))",
   "attributes":[{"key":"langfuse.observation.type","value":{"stringValue":"agent"}}],"status":{}},
  {"traceId":"${TRACE_ID}","spanId":"eee19b7ec3c1b174","parentSpanId":"051581bf3cb55c13",
   "name":"agent","kind":1,
   "startTimeUnixNano":"${NOW_NANOS}","endTimeUnixNano":"$((NOW_NANOS + 800000000))",
   "attributes":[
     {"key":"langfuse.observation.type","value":{"stringValue":"generation"}},
     {"key":"gen_ai.operation.name","value":{"stringValue":"chat"}},
     {"key":"gen_ai.request.model","value":{"stringValue":"gemini-3.1-flash-lite"}}],
   "status":{}}]}]}]}
JSON
)
STATUS=$(curl -sS -o /dev/null -w '%{http_code}' -X POST http://localhost:4318/v1/traces \
  -H 'Content-Type: application/json' -d "${PAYLOAD}")
if [[ "${STATUS}" == "200" ]]; then
  ok "the collector accepted an OTLP/HTTP trace (200)"
else
  fail "the OTLP door answered ${STATUS}"
fi

# metrics_flush_interval is 15s in observability/otel-collector/config.yaml.
echo "==> waiting for the span_metrics flush"
sleep 20

echo "==> span metrics"
METRICS=$(curl -fsS http://localhost:8889/metrics || true)
# These are the exact names the dashboard queries. The unit and the _total suffix are
# appended by the prometheus exporter, not by the connector, which is why they are read
# back rather than assumed.
for name in traces_span_metrics_calls_total traces_span_metrics_duration_milliseconds_bucket; do
  if grep -q "^${name}{" <<<"${METRICS}"; then ok "${name}"; else fail "${name} is absent"; fi
done
# The dimensions are what make the dashboard sliceable at all.
for label in span_name langfuse_observation_type gen_ai_request_model gen_ai_operation_name service_name; do
  if grep -q "${label}=" <<<"${METRICS}"; then ok "label ${label}"; else fail "label ${label} is absent"; fi
done

echo "==> tempo"
if curl -fsS "http://localhost:3200/api/traces/${TRACE_ID}" | grep -q "chat-turn"; then
  ok "tempo returned the probe trace by id"
else
  fail "tempo does not have the probe trace"
fi

echo "==> grafana"
DATASOURCES=$(curl -fsS http://localhost:3001/api/datasources || true)
for uid in prometheus tempo; do
  if grep -q "\"uid\":\"${uid}\"" <<<"${DATASOURCES}"; then
    ok "data source ${uid} provisioned"
  else
    fail "data source ${uid} is not provisioned"
  fi
done
if curl -fsS "http://localhost:3001/api/dashboards/uid/agentic-llm" | grep -q '"uid":"agentic-llm"'; then
  ok "dashboard agentic-llm provisioned"
else
  fail "dashboard agentic-llm is not provisioned"
fi

cleanup "${1:-}"

echo
if [[ ${FAILURES} -eq 0 ]]; then
  echo "observability stack: OK"
else
  echo "observability stack: ${FAILURES} check(s) failed"
  exit 1
fi
