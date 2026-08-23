#!/usr/bin/env bash
#
# WHAT  Pushes a real OTLP trace and a real score into a running Langfuse and reads both
#       back, asserting the parts of the contract that fail SILENTLY when they are wrong.
#
# WHY   Every Langfuse-specific claim in this repository is a wire contract with a server
#       it does not own, and each one fails without an error:
#         - langfuse.observation.type is matched CASE-SENSITIVELY against a lower-case
#           table. "GENERATION" maps to nothing, falls through, and the observation is
#           filed as a SPAN. No error, no warning, no metric.
#         - a root observation is a query-time predicate, not a record. A trace with no
#           span satisfying it is still ingested; it just has no root and no name.
#         - usage_details must be a JSON object serialised to a STRING, and its values
#           must be non-negative integers. cost_details values must be NUMBERS: a cost
#           sent as a string is dropped key by key, silently.
#         - a missing x-langfuse-ingestion-version: 4 header does not error. It selects
#           the slower path, and the data appears up to ten minutes later.
#       Reading it back is the only way any of that is actually known.
#
# WHEN  After changing anything under observability/trace/, after upgrading Langfuse, and
#       before believing a claim in docs/adr/0011 or docs/07-observability.md.
#
# HOW   docker compose -f compose.observability.yaml --profile langfuse up -d
#       # wait for http://localhost:3000/api/public/health to answer, then:
#       ./scripts/check-langfuse-ingestion.sh
#
# Needs Docker and a running Langfuse. NOT part of `./mvnw test`, which needs neither.

set -uo pipefail

cd "$(dirname "$0")/.."

HOST="${LANGFUSE_HOST:-http://localhost:3000}"
PK="${LANGFUSE_PUBLIC_KEY:-pk-lf-0000000000000000000000000000000000}"
SK="${LANGFUSE_SECRET_KEY:-sk-lf-0000000000000000000000000000000000}"
AUTH="Basic $(printf '%s:%s' "$PK" "$SK" | base64 -w0)"

# A trace id per run: Langfuse does not deduplicate a span id it has accepted, so a fixed
# one would make the second run assert against the first run's data.
TRACE_ID=$(head -c16 /dev/urandom | od -An -tx1 | tr -d ' \n')
ROOT_SPAN=$(head -c8 /dev/urandom | od -An -tx1 | tr -d ' \n')
GEN_SPAN=$(head -c8 /dev/urandom | od -An -tx1 | tr -d ' \n')
SESSION="check-$(head -c4 /dev/urandom | od -An -tx1 | tr -d ' \n')"

FAILURES=0
ok()   { printf '  \033[32mok\033[0m    %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

echo "==> host $HOST, trace $TRACE_ID, session $SESSION"

NOW=$(date +%s)000000000
END=$((NOW + 900000000))

# The payload is built by python, not by a heredoc. Two attribute values here are
# themselves JSON documents — usage_details and cost_details are JSON objects serialised to
# a STRING, which is what Langfuse requires — and a shell heredoc that has to carry escaped
# quotes inside escaped quotes produces something that looks right and does not parse. That
# happened, and the server's 400 was correct.
PAYLOAD_FILE="$(mktemp)"
trap 'rm -f "$PAYLOAD_FILE"' EXIT
TRACE_ID="$TRACE_ID" ROOT_SPAN="$ROOT_SPAN" GEN_SPAN="$GEN_SPAN" SESSION="$SESSION" \
NOW="$NOW" END="$END" python3 - "$PAYLOAD_FILE" <<'PY'
import json, os, sys

def attr(key, value, kind="stringValue"):
    return {"key": key, "value": {kind: value}}

now, end = os.environ["NOW"], os.environ["END"]
trace, root, gen = os.environ["TRACE_ID"], os.environ["ROOT_SPAN"], os.environ["GEN_SPAN"]
session = os.environ["SESSION"]

root_span = {
    "traceId": trace, "spanId": root, "name": "chat-turn", "kind": 1,
    "startTimeUnixNano": now, "endTimeUnixNano": end,
    "attributes": [
        attr("langfuse.observation.type", "agent"),
        attr("langfuse.internal.is_app_root", True, "boolValue"),
        attr("langfuse.internal.as_root", "true"),
        attr("langfuse.trace.name", "chat-turn"),
        attr("langfuse.session.id", session),
        attr("langfuse.environment", "default"),
        attr("langfuse.observation.input", json.dumps("qual o cep da avenida paulista?")),
        attr("langfuse.observation.output", json.dumps("O CEP e 01310-100.")),
        attr("langfuse.observation.metadata.outcome", "ANSWERED"),
    ],
    "status": {},
}

# Exclusive buckets, integers only — the contract TokenUsageDetails enforces.
usage = {"input": 86, "input_cached_tokens": 17817, "output": 188, "total": 18091}
# Numbers, never strings: a cost sent as a string is dropped key by key with no error.
cost = {"input": 0.0000215, "output": 0.000282, "total": 0.0003035}

generation = {
    "traceId": trace, "spanId": gen, "parentSpanId": root, "name": "agent", "kind": 1,
    "startTimeUnixNano": now, "endTimeUnixNano": end,
    "attributes": [
        attr("langfuse.observation.type", "generation"),
        attr("langfuse.session.id", session),
        attr("langfuse.observation.model.name", "gemini-3.1-flash-lite"),
        attr("langfuse.observation.usage_details", json.dumps(usage)),
        attr("langfuse.observation.cost_details", json.dumps(cost)),
        attr("gen_ai.operation.name", "chat"),
        attr("gen_ai.request.model", "gemini-3.1-flash-lite"),
    ],
    "status": {},
}

payload = {"resourceSpans": [{
    "resource": {"attributes": [attr("service.name", "agenticchat")]},
    "scopeSpans": [{"scope": {"name": "check-langfuse-ingestion"},
                    "spans": [root_span, generation]}],
}]}
with open(sys.argv[1], "w") as handle:
    json.dump(payload, handle)
PY

if ! jq -e . "$PAYLOAD_FILE" >/dev/null 2>&1; then
  fail "the probe payload is not valid JSON — the script is broken, not the server"
fi
RESPONSE=$(curl -sS -w '\n%{http_code}' -X POST "$HOST/api/public/otel/v1/traces" \
  -H 'Content-Type: application/json' \
  -H "Authorization: $AUTH" \
  -H 'x-langfuse-ingestion-version: 4' \
  --data-binary @"$PAYLOAD_FILE")
STATUS=$(tail -1 <<<"$RESPONSE")
if [[ "$STATUS" =~ ^2 ]]; then
  ok "OTLP accepted ($STATUS)"
else
  fail "OTLP answered $STATUS: $(head -n -1 <<<"$RESPONSE" | head -c 400)"
fi

echo "==> waiting for ingestion"
OBS=""
for _ in $(seq 1 40); do
  # fields= is not optional, and neither is any group in it. Without the parameter the
  # endpoint returns core+basic only, so input, output, model, usageDetails and
  # costDetails all come back null — which reads exactly like an ingestion that dropped
  # them. traceName is in trace_context specifically, and leaving that one out reads as a
  # trace that was never named.
  OBS=$(curl -sS -H "Authorization: $AUTH" \
    "$HOST/api/public/v2/observations?traceId=${TRACE_ID}&fields=core,basic,io,metadata,model,usage,metrics,trace_context&limit=50" 2>/dev/null)
  if [[ $(jq -r '.data | length' <<<"$OBS" 2>/dev/null || echo 0) -ge 2 ]]; then break; fi
  sleep 3
done

COUNT=$(jq -r '.data | length' <<<"$OBS" 2>/dev/null || echo 0)
if [[ "$COUNT" -ge 2 ]]; then ok "both observations ingested ($COUNT)"; else fail "only $COUNT observation(s) after 120s"; fi

ROOT_TYPE=$(jq -r --arg id "$ROOT_SPAN" '.data[] | select(.id==$id) | .type' <<<"$OBS" 2>/dev/null)
# THE assertion. An upper-case type would arrive here as "SPAN" with nothing reporting it.
if [[ "$ROOT_TYPE" == "AGENT" ]]; then ok "root observation typed AGENT (not SPAN)"; else fail "root type is '$ROOT_TYPE', expected AGENT"; fi

GEN_TYPE=$(jq -r --arg id "$GEN_SPAN" '.data[] | select(.id==$id) | .type' <<<"$OBS" 2>/dev/null)
if [[ "$GEN_TYPE" == "GENERATION" ]]; then ok "child observation typed GENERATION"; else fail "child type is '$GEN_TYPE'"; fi

for check in \
  "input|$(jq -r --arg id "$ROOT_SPAN" '.data[] | select(.id==$id) | .input | tostring' <<<"$OBS" 2>/dev/null)|paulista" \
  "output|$(jq -r --arg id "$ROOT_SPAN" '.data[] | select(.id==$id) | .output | tostring' <<<"$OBS" 2>/dev/null)|01310-100"
do
  IFS='|' read -r label value needle <<<"$check"
  if [[ "$value" == *"$needle"* ]]; then ok "root $label survived the round trip"; else fail "root $label is '$value'"; fi
done

USAGE=$(jq -r --arg id "$GEN_SPAN" '.data[] | select(.id==$id) | .usageDetails | tostring' <<<"$OBS" 2>/dev/null)
if [[ "$USAGE" == *"input_cached_tokens"* && "$USAGE" == *"17817"* ]]; then
  ok "usage_details parsed, exclusive buckets kept: $USAGE"
else
  fail "usage_details came back as '$USAGE'"
fi

COST=$(jq -r --arg id "$GEN_SPAN" '.data[] | select(.id==$id) | .costDetails | tostring' <<<"$OBS" 2>/dev/null)
if [[ "$COST" == *"total"* && "$COST" != "null" && "$COST" != "{}" ]]; then
  ok "cost_details ingested rather than inferred: $COST"
else
  fail "cost_details came back as '$COST'"
fi

MODEL=$(jq -r --arg id "$GEN_SPAN" '.data[] | select(.id==$id) | .model' <<<"$OBS" 2>/dev/null)
if [[ "$MODEL" == "gemini-3.1-flash-lite" ]]; then ok "model name mapped"; else fail "model is '$MODEL'"; fi

echo "==> trace identity"
# NOT GET /api/public/traces/{id}. On a v4 deployment in events_only mode that endpoint
# answers 404 with "This endpoint is not available on deployments running in Langfuse v4
# events_only mode" — because there is no trace entity to read. A trace is a query over
# observations, so its name and session are read off the observations themselves.
IS_ROOT=$(jq -r --arg id "$ROOT_SPAN" '.data[] | select(.id==$id) | .isRootObservation' <<<"$OBS" 2>/dev/null)
TRACE_NAME=$(jq -r --arg id "$ROOT_SPAN" '.data[] | select(.id==$id) | .traceName // empty' <<<"$OBS" 2>/dev/null)
if [[ "$IS_ROOT" == "true" ]]; then ok "the root observation is recognised as the root"; else fail "isRootObservation is '$IS_ROOT'"; fi
if [[ "$TRACE_NAME" == "chat-turn" ]]; then ok "trace is named 'chat-turn'"; else fail "trace name is '$TRACE_NAME'"; fi

# On EVERY observation, not only the root — the whole reason TurnAttributesSpanProcessor
# copies it rather than leaving it where it was set.
SESSIONS=$(jq -r '[.data[].sessionId] | unique | join(",")' <<<"$OBS" 2>/dev/null)
if [[ "$SESSIONS" == "$SESSION" ]]; then ok "the session id is on every observation"; else fail "session ids are '$SESSIONS'"; fi

# The langfuse.observation.metadata. prefix is what keeps a key filterable; without it the
# attribute lands under metadata.attributes and Langfuse cannot filter on it.
OUTCOME=$(jq -r --arg id "$ROOT_SPAN" '.data[] | select(.id==$id) | .metadata.outcome // empty' <<<"$OBS" 2>/dev/null)
if [[ "$OUTCOME" == "ANSWERED" ]]; then ok "prefixed metadata is a top-level, filterable key"; else fail "metadata.outcome is '$OUTCOME'"; fi

TOTAL_COST=$(jq -r --arg id "$GEN_SPAN" '.data[] | select(.id==$id) | .totalCost' <<<"$OBS" 2>/dev/null)
if [[ "$TOTAL_COST" == "0.0003035" ]]; then ok "totalCost is the ingested number, not an inferred one"; else fail "totalCost is '$TOTAL_COST'"; fi

echo "==> POST /api/public/scores  (the exact body LangfuseScoreWriter builds)"
SCORE_BODY=$(cat <<JSON
{"traceId":"${TRACE_ID}","observationId":"${ROOT_SPAN}","name":"triage_confidence",
 "value":0.93,"dataType":"NUMERIC","comment":"DATA_REQUEST","environment":"default"}
JSON
)
SCORE_RESPONSE=$(curl -sS -w '\n%{http_code}' -X POST "$HOST/api/public/scores" \
  -H 'Content-Type: application/json' -H "Authorization: $AUTH" -d "$SCORE_BODY")
SCORE_STATUS=$(tail -1 <<<"$SCORE_RESPONSE")
SCORE_ID=$(jq -r '.id // empty' <<<"$(head -n -1 <<<"$SCORE_RESPONSE")" 2>/dev/null)
if [[ "$SCORE_STATUS" =~ ^2 && -n "$SCORE_ID" ]]; then
  ok "score created ($SCORE_STATUS, id $SCORE_ID)"
else
  fail "score POST answered $SCORE_STATUS: $(head -n -1 <<<"$SCORE_RESPONSE" | head -c 300)"
fi

CAT_BODY=$(cat <<JSON
{"traceId":"${TRACE_ID}","observationId":"${ROOT_SPAN}","name":"triage_decision",
 "value":"IN_SCOPE","dataType":"CATEGORICAL","environment":"default"}
JSON
)
CAT_STATUS=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$HOST/api/public/scores" \
  -H 'Content-Type: application/json' -H "Authorization: $AUTH" -d "$CAT_BODY")
if [[ "$CAT_STATUS" =~ ^2 ]]; then ok "categorical score accepted ($CAT_STATUS)"; else fail "categorical score answered $CAT_STATUS"; fi

echo "==> GET /api/public/v3/scores"
SCORES=""
for _ in $(seq 1 20); do
  SCORES=$(curl -sS -H "Authorization: $AUTH" \
    "$HOST/api/public/v3/scores?traceId=${TRACE_ID}&fields=subject" 2>/dev/null)
  if [[ $(jq -r '.data | length' <<<"$SCORES" 2>/dev/null || echo 0) -ge 2 ]]; then break; fi
  sleep 3
done
NUMERIC=$(jq -r '.data[] | select(.name=="triage_confidence") | .value' <<<"$SCORES" 2>/dev/null)
CATEGORICAL=$(jq -r '.data[] | select(.name=="triage_decision") | .value' <<<"$SCORES" 2>/dev/null)
SUBJECT=$(jq -r '.data[] | select(.name=="triage_confidence") | .subject.kind' <<<"$SCORES" 2>/dev/null)
if [[ "$NUMERIC" == "0.93" ]]; then ok "numeric score reads back as 0.93"; else fail "numeric score reads back as '$NUMERIC'"; fi
if [[ "$CATEGORICAL" == "IN_SCOPE" ]]; then ok "categorical score reads back as IN_SCOPE"; else fail "categorical reads back as '$CATEGORICAL'"; fi
if [[ "$SUBJECT" == "observation" ]]; then ok "the score is attached to the OBSERVATION, not the trace"; else fail "score subject is '$SUBJECT'"; fi

echo "==> corrections  (the exact body Score.correction(text) produces)"
# name and dataType are FIXED by the feature, not chosen by the caller. A correction filed
# under any other name is an ordinary score that never reaches the diff view, and nothing
# anywhere reports that — which is why this is checked against a live server rather than
# trusted from the documentation.
CORRECTION_BODY=$(cat <<JSON
{"traceId":"${TRACE_ID}","observationId":"${ROOT_SPAN}","name":"output",
 "value":"Isso esta dentro do escopo.","dataType":"CORRECTION","environment":"default"}
JSON
)
CORRECTION_STATUS=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$HOST/api/public/scores" \
  -H 'Content-Type: application/json' -H "Authorization: $AUTH" -d "$CORRECTION_BODY")
if [[ "$CORRECTION_STATUS" =~ ^2 ]]; then ok "correction accepted ($CORRECTION_STATUS)"; else fail "correction answered $CORRECTION_STATUS"; fi

CORRECTIONS=""
for _ in $(seq 1 20); do
  # `fields=subject,details` is not optional: without it the projection is lean and `value`
  # reads back null, which is indistinguishable from a write that never landed.
  CORRECTIONS=$(curl -sS -H "Authorization: $AUTH" \
    "$HOST/api/public/v3/scores?traceId=${TRACE_ID}&dataType=CORRECTION&fields=subject,details" 2>/dev/null)
  if [[ $(jq -r '.data | length' <<<"$CORRECTIONS" 2>/dev/null || echo 0) -ge 1 ]]; then break; fi
  sleep 3
done
CORR_NAME=$(jq -r '.data[0].name // empty' <<<"$CORRECTIONS" 2>/dev/null)
CORR_VALUE=$(jq -r '.data[0].value // empty' <<<"$CORRECTIONS" 2>/dev/null)
CORR_SUBJECT=$(jq -r '.data[0].subject.kind // empty' <<<"$CORRECTIONS" 2>/dev/null)
CORR_TARGET=$(jq -r '.data[0].subject.id // empty' <<<"$CORRECTIONS" 2>/dev/null)
if [[ "$CORR_NAME" == "output" ]]; then ok "the correction is named 'output'"; else fail "correction name is '$CORR_NAME'"; fi
if [[ "$CORR_VALUE" == "Isso esta dentro do escopo." ]]; then ok "the corrected output survived the round trip"; else fail "correction value is '$CORR_VALUE'"; fi
if [[ "$CORR_SUBJECT" == "observation" && "$CORR_TARGET" == "$ROOT_SPAN" ]]; then
  ok "the correction is attached to the ROOT observation"
else
  fail "correction subject is '$CORR_SUBJECT' on '$CORR_TARGET', expected observation $ROOT_SPAN"
fi

echo "==> experiments  (langfuse.experiment.* and an EVALUATOR-typed child)"
EXP_TRACE=$(head -c16 /dev/urandom | od -An -tx1 | tr -d ' \n')
EXP_ROOT=$(head -c8 /dev/urandom | od -An -tx1 | tr -d ' \n')
EXP_CHILD=$(head -c8 /dev/urandom | od -An -tx1 | tr -d ' \n')
EXP_FILE="$(mktemp)"
trap 'rm -f "$PAYLOAD_FILE" "$EXP_FILE"' EXIT
EXP_TRACE="$EXP_TRACE" EXP_ROOT="$EXP_ROOT" EXP_CHILD="$EXP_CHILD" NOW="$NOW" END="$END" \
python3 - "$EXP_FILE" <<'PYEXP'
import json, os, sys

def attr(key, value, kind="stringValue"):
    return {"key": key, "value": {kind: value}}

trace, root, child = os.environ["EXP_TRACE"], os.environ["EXP_ROOT"], os.environ["EXP_CHILD"]
now, end = os.environ["NOW"], os.environ["END"]

# Every langfuse.experiment.* key belongs on EVERY span of the item trace: Langfuse v4
# queries observations, not traces, so an experiment id on the root alone leaves the
# children unattributable. TurnAttributesSpanProcessor is what does this in the application.
experiment = [
    attr("langfuse.experiment.id", "exp-check"),
    attr("langfuse.experiment.name", "triage-golden-set"),
    attr("langfuse.experiment.dataset.id", "ds-triage"),
    attr("langfuse.experiment.item.id", "item-3"),
    attr("langfuse.experiment.item.root_observation_id", root),
    attr("langfuse.environment", "experiment"),
]
spans = [
    {"traceId": trace, "spanId": root, "name": "experiment-item", "kind": 1,
     "startTimeUnixNano": now, "endTimeUnixNano": end,
     "attributes": experiment + [
         attr("langfuse.observation.type", "agent"),
         attr("langfuse.internal.as_root", "true"),
         attr("langfuse.internal.is_app_root", True, "boolValue"),
         attr("langfuse.trace.name", "experiment-item"),
         attr("langfuse.observation.input", '"bom dia"'),
         attr("langfuse.observation.output", '"OUT_OF_SCOPE"'),
         attr("langfuse.experiment.item.expected_output", '"IN_SCOPE"'),
     ]},
    {"traceId": trace, "spanId": child, "parentSpanId": root, "name": "grade", "kind": 1,
     "startTimeUnixNano": now, "endTimeUnixNano": end,
     "attributes": experiment + [attr("langfuse.observation.type", "evaluator")]},
]
open(sys.argv[1], "w").write(json.dumps(
    {"resourceSpans": [{"resource": {"attributes": [attr("service.name", "agenticchat")]},
                        "scopeSpans": [{"scope": {"name": "check-langfuse-ingestion"},
                                        "spans": spans}]}]}))
PYEXP

EXP_STATUS=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$HOST/api/public/otel/v1/traces" \
  -H 'Content-Type: application/json' -H "Authorization: $AUTH" \
  -H 'x-langfuse-ingestion-version: 4' --data-binary @"$EXP_FILE")
if [[ "$EXP_STATUS" =~ ^2 ]]; then ok "experiment trace accepted ($EXP_STATUS)"; else fail "experiment trace answered $EXP_STATUS"; fi

EXP_OBS=""
for _ in $(seq 1 40); do
  EXP_OBS=$(curl -sS -H "Authorization: $AUTH" \
    "$HOST/api/public/v2/observations?traceId=${EXP_TRACE}&fields=core,basic,io,metadata" 2>/dev/null)
  if [[ $(jq -r '.data | length' <<<"$EXP_OBS" 2>/dev/null || echo 0) -ge 2 ]]; then break; fi
  sleep 3
done

EVALUATOR_TYPE=$(jq -r --arg id "$EXP_CHILD" '.data[] | select(.id==$id) | .type' <<<"$EXP_OBS" 2>/dev/null)
if [[ "$EVALUATOR_TYPE" == "EVALUATOR" ]]; then
  ok "the grading child is typed EVALUATOR (lower-case 'evaluator' on the wire)"
else
  fail "the grading child is typed '$EVALUATOR_TYPE', expected EVALUATOR"
fi

EXP_ENVIRONMENT=$(jq -r --arg id "$EXP_ROOT" '.data[] | select(.id==$id) | .environment' <<<"$EXP_OBS" 2>/dev/null)
if [[ "$EXP_ENVIRONMENT" == "experiment" ]]; then
  ok "langfuse.environment separates the run from production traffic"
else
  fail "environment is '$EXP_ENVIRONMENT', expected experiment"
fi

# WHAT THIS ASSERTS, AND WHY IT IS PHRASED AS IT IS. On self-hosted 4.16.0 the
# langfuse.experiment.* family is NOT mapped to first-class experiment fields: every value
# arrives and every one lands in the unmapped catch-all as
# metadata["attributes.langfuse.experiment.*"], the way Langfuse files any attribute it does
# not recognise. The trace is intact and the data is queryable; what does not happen is the
# experiments view assembling it. The check asserts what this deployment actually does, so
# the day a version DOES map them this line fails and someone re-reads the mapping instead
# of finding out years later.
EXP_ID_ARRIVED=$(jq -r --arg id "$EXP_ROOT" '.data[] | select(.id==$id) | .metadata["attributes.langfuse.experiment.id"] // empty' <<<"$EXP_OBS" 2>/dev/null)
if [[ "$EXP_ID_ARRIVED" == "exp-check" ]]; then
  ok "experiment attributes arrive (unmapped, under metadata.attributes — see docs/07)"
else
  fail "langfuse.experiment.id did not arrive at all: '$EXP_ID_ARRIVED'"
fi

echo
if [[ $FAILURES -eq 0 ]]; then
  echo "langfuse ingestion: OK"
else
  echo "langfuse ingestion: ${FAILURES} check(s) failed"
  exit 1
fi
