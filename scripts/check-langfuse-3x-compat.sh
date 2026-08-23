#!/usr/bin/env bash
#
# WHAT   Measures which of this project's Langfuse integrations still work on the last OSS
#        3.x line, against a REAL Langfuse 3.80.0. It pushes one trace carrying all ten
#        observation types, one refused turn, scores of all three data types, and the
#        experiment attributes, then reads every one of them back and prints, per feature,
#        what 3.80.0 does with it beside what 4.16.0 does.
#
# WHY    4.16.0 is this project's Langfuse and nothing here changes that. This script
#        answers a different question — what a reader pinned to 3.x actually gets — and it
#        exists as a script rather than as a paragraph because the answer is not derivable
#        from either version's documentation. Two examples it found that no doc states:
#        3.80.0 collapses six of the ten observation types to SPAN with a 200 and no
#        warning, and it ignores langfuse.observation.usage_details entirely, so every
#        token count and every cost reads as ZERO while the trace looks perfect.
#
#        A SEPARATE script rather than a flag on check-langfuse-ingestion.sh, because the
#        two ask different questions of different APIs. That harness asserts the v4
#        contract and is meant to FAIL when v4 changes; run it against 3.80.0 and 40 of its
#        46 assertions fail on endpoints that simply do not exist there — a wall of red
#        that says nothing about the features. This one asserts the 3.x contract and
#        reports a difference as a documented GAP, not as a failure.
#
# WHEN   After changing anything under observability/trace/, and before editing the
#        compatibility table in docs/08-langfuse-features.md — that table's every row comes
#        from this output.
#
# HOW    docker compose -f compose.observability.yaml --profile langfuse3 up -d
#        # wait for http://localhost:3002/api/public/health, then:
#        ./scripts/check-langfuse-3x-compat.sh
#
# Needs Docker and a running Langfuse 3.80.0. It starts nothing. NOT part of `./mvnw test`.
#
# ONE TRAP IS BUILT INTO THIS SCRIPT AND IS NOT A BUG IN EITHER VERSION. 3.80.0's OTLP
# endpoint accepts OTLP/JSON and hex-encodes the traceId STRING it receives, so a payload
# carrying the conventional 32-character hex id is stored under the 64-character hex of
# those characters. 4.16.0 normalises it instead. Nothing in this project hits that: the
# application exports with OtlpHttpSpanExporter, which is protobuf, and over protobuf both
# versions store the id unchanged — verified by running the real application against both.
# It only bites a hand-written JSON probe like this one, which is why every lookup below
# goes through the SESSION id and never through the trace id it sent.

set -uo pipefail
cd "$(dirname "$0")/.."

HOST="${LANGFUSE_HOST:-http://localhost:3002}"
PK="${LANGFUSE_PUBLIC_KEY:-pk-lf-0000000000000000000000000000000000}"
SK="${LANGFUSE_SECRET_KEY:-sk-lf-0000000000000000000000000000000000}"
AUTH="Basic $(printf '%s:%s' "$PK" "$SK" | base64 -w0)"

SESSION="compat-$(head -c4 /dev/urandom | od -An -tx1 | tr -d ' \n')"
TRACE_ID=$(head -c16 /dev/urandom | od -An -tx1 | tr -d ' \n')

GAPS=0
FAILURES=0
# gap() and bad() are DIFFERENT verdicts and the distinction is the whole point of this
# script. A GAP is a measured difference from 4.16.0 — the answer we came for, and not an
# error. A FAIL is this script or this server being broken: a payload nothing accepted, an
# endpoint neither version documents, an observation that never arrived. Only FAILURES
# decide the exit code.
ok()  { printf '  \033[32mok \033[0m   %s\n' "$1"; }
gap() { printf '  \033[33mGAP\033[0m   %s\n' "$1"; GAPS=$((GAPS + 1)); }
bad() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

VERSION=$(curl -sS "$HOST/api/public/health" 2>/dev/null | jq -r '.version // "unknown"')
echo "==> host $HOST, Langfuse $VERSION, session $SESSION"
if [[ "$VERSION" == 4.* ]]; then
  echo "    This is a v4 deployment. Run scripts/check-langfuse-ingestion.sh against it instead."
  exit 1
fi

echo "==> API surface  (what a v4-shaped client would call)"
for probe in "/api/public/v2/observations|v4|the Observations API v2" \
             "/api/public/v3/scores|v4|the Scores API v3" \
             "/api/public/observations|v3|the legacy Observations API" \
             "/api/public/traces|v3|the legacy Traces API" \
             "/api/public/scores|v3|the legacy Scores API"
do
  IFS='|' read -r path era label <<<"$probe"
  code=$(curl -sS -o /dev/null -w '%{http_code}' -H "Authorization: $AUTH" "$HOST${path}?limit=1")
  if [[ "$era" == "v3" && "$code" == "200" ]]; then
    ok "$label answers 200 — this is the read path on 3.x"
  elif [[ "$era" == "v4" && "$code" == "404" ]]; then
    gap "$label is absent (404). Every v4-only read in check-langfuse-ingestion.sh dies here."
  else
    bad "$label answered $code, which neither version documents"
  fi
done

echo "==> pushing one trace with all ten observation types"
PAYLOAD="$(mktemp)"
trap 'rm -f "$PAYLOAD"' EXIT
NOW=$(date +%s)000000000
TRACE_ID="$TRACE_ID" SESSION="$SESSION" NOW="$NOW" python3 - "$PAYLOAD" <<'PYCOMPAT'
import json, os, sys

def attr(key, value, kind="stringValue"):
    return {"key": key, "value": {kind: value}}

trace, session = os.environ["TRACE_ID"], os.environ["SESSION"]
now = int(os.environ["NOW"]); end = now + 900_000_000
ids = {}
def sid(label):
    ids.setdefault(label, "%016x" % (abs(hash(label)) % (16 ** 16)))
    return ids[label]

def span(label, kind, parent=None, extra=None, name=None):
    out = {"traceId": trace, "spanId": sid(label), "name": name or label, "kind": 1,
           "startTimeUnixNano": str(now), "endTimeUnixNano": str(end),
           "attributes": [attr("langfuse.observation.type", kind),
                          attr("langfuse.session.id", session),
                          attr("langfuse.environment", "compat")] + (extra or []),
           "status": {}}
    if parent:
        out["parentSpanId"] = sid(parent)
    return out

spans = [
    span("turn", "agent", name="chat-turn", extra=[
        attr("langfuse.internal.as_root", "true"),
        attr("langfuse.internal.is_app_root", True, "boolValue"),
        attr("langfuse.trace.name", "chat-turn"),
        attr("langfuse.user.id", "compat-user"),
        attr("langfuse.trace.tags", json.dumps(["compat", "3x"])),
        attr("langfuse.observation.input", json.dumps("vai chover amanha em Florianopolis?")),
        attr("langfuse.observation.output", json.dumps("70% de chance de chuva a tarde.")),
        attr("langfuse.observation.metadata.outcome", "ANSWERED"),
    ]),
    span("triage", "chain", parent="turn"),
    span("triage-judge", "span", parent="triage", extra=[
        attr("langfuse.observation.input", json.dumps("vai chover amanha em Florianopolis?")),
        attr("langfuse.observation.output", json.dumps({"decision": "IN_SCOPE"})),
    ]),
    # Two usage spellings on one generation, so the read-back says WHICH one this version
    # understands rather than only that something arrived.
    span("agent-generation", "generation", parent="turn", name="agent", extra=[
        attr("langfuse.observation.model.name", "gemini-3.1-flash-lite"),
        attr("langfuse.observation.usage_details", json.dumps(
            {"input": 2140, "input_cached_tokens": 1024, "output": 96,
             "output_reasoning_tokens": 310, "total": 3570})),
        attr("langfuse.observation.cost_details", json.dumps(
            {"input": 0.000535, "output": 0.000609, "total": 0.001144})),
        attr("gen_ai.usage.input_tokens", 2140, "intValue"),
        attr("gen_ai.usage.output_tokens", 96, "intValue"),
        attr("gen_ai.request.model", "gemini-3.1-flash-lite"),
    ]),
    span("guardrail-in", "guardrail", parent="turn", name="input-guardrails"),
    span("guardrail-out", "guardrail", parent="turn", name="voice-compliance", extra=[
        attr("langfuse.observation.level", "WARNING"),
        attr("langfuse.observation.status_message", "reprompted once"),
    ]),
    span("retriever", "retriever", parent="turn", name="knowledge-retrieval"),
    span("embedding", "embedding", parent="retriever"),
    span("tool", "tool", parent="turn", name="open_meteo_forecast"),
    span("event", "event", parent="turn", name="memory-compacted"),
    span("evaluator", "evaluator", parent="turn", name="answer-relevance"),
    span("experiment-item", "agent", parent=None, name="experiment-item", extra=[
        attr("langfuse.experiment.id", "exp-compat"),
        attr("langfuse.experiment.name", "triage-golden-set"),
        attr("langfuse.experiment.item.id", "item-1"),
    ]),
]
open(sys.argv[1], "w").write(json.dumps(
    {"resourceSpans": [{"resource": {"attributes": [attr("service.name", "agenticchat")]},
                        "scopeSpans": [{"scope": {"name": "check-langfuse-3x-compat"},
                                        "spans": spans}]}]}))
PYCOMPAT

STATUS=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$HOST/api/public/otel/v1/traces" \
  -H 'Content-Type: application/json' -H "Authorization: $AUTH" --data-binary @"$PAYLOAD")
if [[ "$STATUS" =~ ^2 ]]; then ok "OTLP/JSON accepted ($STATUS)"; else bad "OTLP answered $STATUS"; fi

echo "==> waiting for ingestion  (found by SESSION, never by the id we sent — see the header)"
STORED_TRACE=""
for _ in $(seq 1 40); do
  STORED_TRACE=$(curl -sS -H "Authorization: $AUTH" "$HOST/api/public/traces?sessionId=${SESSION}&limit=5" 2>/dev/null \
    | jq -r '.data[0].id // empty')
  [[ -n "$STORED_TRACE" ]] && break
  sleep 3
done
if [[ -z "$STORED_TRACE" ]]; then
  bad "nothing arrived for session ${SESSION} after 120s — the rest cannot be measured"
  exit 1
fi
ok "the trace arrived (stored id ${STORED_TRACE:0:16}…)"
if [[ "$STORED_TRACE" != "$TRACE_ID" ]]; then
  gap "OTLP/JSON: the trace id was re-encoded (sent ${TRACE_ID:0:12}…, stored ${STORED_TRACE:0:12}…). Protobuf is unaffected."
else
  ok "the trace id survived OTLP/JSON unchanged"
fi

OBS=$(curl -sS -H "Authorization: $AUTH" "$HOST/api/public/observations?traceId=${STORED_TRACE}&limit=50" 2>/dev/null)

echo "==> observation types  (the ten Langfuse names, and what this version stored)"
declare -A EXPECTED=( [chat-turn]=AGENT [triage]=CHAIN [triage-judge]=SPAN [agent]=GENERATION \
                      [input-guardrails]=GUARDRAIL [voice-compliance]=GUARDRAIL \
                      [knowledge-retrieval]=RETRIEVER [embedding]=EMBEDDING \
                      [open_meteo_forecast]=TOOL [memory-compacted]=EVENT \
                      [answer-relevance]=EVALUATOR )
for name in chat-turn triage triage-judge agent input-guardrails voice-compliance \
            knowledge-retrieval embedding open_meteo_forecast memory-compacted answer-relevance
do
  got=$(jq -r --arg n "$name" '.data[] | select(.name==$n) | .type' <<<"$OBS" 2>/dev/null | head -1)
  want="${EXPECTED[$name]}"
  if [[ "$got" == "$want" ]]; then
    ok "$name is $want, as on 4.16.0"
  elif [[ -z "$got" ]]; then
    bad "$name did not arrive at all"
  else
    gap "$name is $got here and $want on 4.16.0 — the type was accepted and discarded"
  fi
done

# NESTING, which is the half the types cannot fix. Even where 4.16.0 and 3.80.0 disagree
# about what an observation IS, they must agree about what it is UNDER — the graph's edges
# and the trace tree both come from the parent links, and a version that flattened them
# would render every turn as a list with the right names in the wrong order.
JUDGE_PARENT=$(jq -r '.data[] | select(.name=="triage-judge") | .parentObservationId // ""' <<<"$OBS" 2>/dev/null | head -1)
TRIAGE_ID=$(jq -r '.data[] | select(.name=="triage") | .id // ""' <<<"$OBS" 2>/dev/null | head -1)
if [[ -n "$TRIAGE_ID" && "$JUDGE_PARENT" == "$TRIAGE_ID" ]]; then
  ok "nesting survives — the judge span sits under the triage chain"
else
  gap "the judge span's parent is '$JUDGE_PARENT', expected the triage observation '$TRIAGE_ID'"
fi

GRAPHABLE=$(jq -r '[.data[].type] | map(select(. as $t | ["SPAN","EVENT","GENERATION"] | index($t) | not)) | length' <<<"$OBS" 2>/dev/null || echo 0)
if [[ "$GRAPHABLE" -ge 1 ]]; then
  ok "the trace carries $GRAPHABLE graph-eligible observations — the agent graph can draw"
else
  gap "no observation is outside span/event/generation: the agent graph cannot draw on this version"
fi

echo "==> payload fidelity"
check() { # <jq filter> <expected substring> <label>
  local value; value=$(jq -r "$1" <<<"$OBS" 2>/dev/null)
  if [[ "$value" == *"$2"* && -n "$2" ]]; then ok "$3"; else gap "$3 — read back as '${value:0:60}'"; fi
}
check '.data[] | select(.name=="chat-turn") | .input | tostring' 'Florianopolis' "input survives"
check '.data[] | select(.name=="chat-turn") | .output | tostring' 'chuva' "output survives"
check '.data[] | select(.name=="chat-turn") | .metadata.outcome // ""' 'ANSWERED' "prefixed metadata is a top-level key"
check '.data[] | select(.name=="voice-compliance") | .level' 'WARNING' "observation level survives"
check '.data[] | select(.name=="voice-compliance") | .statusMessage // ""' 'reprompted' "status message survives"
check '.data[] | select(.name=="chat-turn") | .environment' 'compat' "langfuse.environment survives"
check '.data[] | select(.name=="agent") | .model // ""' 'gemini-3.1-flash-lite' "model name survives"

USAGE=$(jq -r '.data[] | select(.name=="agent") | .usageDetails | tostring' <<<"$OBS" 2>/dev/null)
if [[ "$USAGE" == *"input_cached_tokens"* ]]; then
  ok "langfuse.observation.usage_details is mapped, exclusive buckets and all"
elif [[ "$USAGE" == *"2140"* ]]; then
  gap "usage came from gen_ai.usage.* and NOT from langfuse.observation.usage_details: the cached and reasoning buckets are lost ($USAGE)"
else
  gap "no usage was mapped at all ($USAGE) — every token count on this version reads zero"
fi

COST=$(jq -r '.data[] | select(.name=="agent") | .calculatedTotalCost // .costDetails.total // "null"' <<<"$OBS" 2>/dev/null)
if [[ "$COST" == "0.001144" ]]; then
  ok "langfuse.observation.cost_details is the ingested number"
else
  gap "cost_details was not mapped: totalCost reads '$COST', so the cost dashboard is zero"
fi

USER_ID=$(curl -sS -H "Authorization: $AUTH" "$HOST/api/public/traces/${STORED_TRACE}" 2>/dev/null | jq -r '.userId // ""')
# sort, because Langfuse returns tags in ITS order and not in the order they were sent —
# comparing the raw join reports a difference that is only an ordering.
TAGS=$(curl -sS -H "Authorization: $AUTH" "$HOST/api/public/traces/${STORED_TRACE}" 2>/dev/null | jq -r '.tags | sort | join(",")')
[[ "$USER_ID" == "compat-user" ]] && ok "langfuse.user.id survives" || gap "userId read back as '$USER_ID'"
[[ "$TAGS" == "3x,compat" ]] && ok "langfuse.trace.tags survive" || gap "tags read back as '$TAGS'"

echo "==> the trace root, which is a predicate and not a stored flag"
# Asserted here because 4.16.0's harness asserts it and requirement 5 is "every feature
# already implemented". This project writes BOTH spellings — the boolean
# langfuse.internal.is_app_root for the v4 events path and the string
# langfuse.internal.as_root for the legacy one — precisely because which path runs is a
# property of the deployment. 3.x is the deployment that runs the legacy one.
ROOT_ID=$(jq -r '.data[] | select(.name=="chat-turn") | .id' <<<"$OBS" 2>/dev/null | head -1)
ROOT_PARENT=$(jq -r '.data[] | select(.name=="chat-turn") | .parentObservationId // "null"' <<<"$OBS" 2>/dev/null | head -1)
TRACE_NAME=$(curl -sS -H "Authorization: $AUTH" "$HOST/api/public/traces/${STORED_TRACE}" 2>/dev/null | jq -r '.name // ""')
if [[ "$ROOT_PARENT" == "null" ]]; then ok "the turn observation has no parent, so it heads the trace"; else gap "the turn's parent is '$ROOT_PARENT'"; fi
if [[ "$TRACE_NAME" == "chat-turn" ]]; then
  ok "langfuse.trace.name survives — the trace is named, not anonymous"
else
  gap "the trace name reads '$TRACE_NAME'; on 3.x a trace is a real entity and this is where its name lives"
fi

echo "==> scores  (all three data types this project writes)"
for spec in "triage_confidence|0.93|NUMERIC" "triage_decision|IN_SCOPE|CATEGORICAL" "output|corrigido|CORRECTION"; do
  IFS='|' read -r name value dtype <<<"$spec"
  if [[ "$dtype" == "NUMERIC" ]]; then json_value="$value"; else json_value="\"$value\""; fi
  # observationId, not traceId alone. This project attaches every score to an OBSERVATION,
  # because an observation-level evaluator matches an observation and does not read its
  # siblings — so "scores work on 3.x" is only true if THIS shape is accepted.
  code=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$HOST/api/public/scores" \
    -H 'Content-Type: application/json' -H "Authorization: $AUTH" \
    -d "{\"traceId\":\"${STORED_TRACE}\",\"observationId\":\"${ROOT_ID}\",\"name\":\"${name}\",\"value\":${json_value},\"dataType\":\"${dtype}\",\"environment\":\"compat\"}")
  if [[ "$code" =~ ^2 ]]; then
    ok "a $dtype score is accepted ($code)"
  elif [[ "$dtype" == "CORRECTION" ]]; then
    gap "CORRECTION is rejected ($code): Score.correction() has no counterpart on this version"
  else
    bad "a $dtype score answered $code"
  fi
done

# `?traceId=` IS IGNORED ON 3.x, and finding that out is why this filters in jq instead.
# The legacy scores endpoint accepts the parameter, answers 200, and returns every score in
# the project — so `[0]` is whichever score the server felt like listing first, which on a
# second run of this script is a score from the FIRST run. That produced two confident
# false differences ("the score attached to the wrong observation", "the score lost its
# environment") about a version that had done neither. The trace id is on every row, so the
# filter belongs here.
SCORE_ROW='null'
for _ in $(seq 1 15); do
  SCORE_ROW=$(curl -sS -H "Authorization: $AUTH" "$HOST/api/public/scores?limit=100" 2>/dev/null \
    | jq -c --arg t "$STORED_TRACE" '[.data[] | select(.traceId==$t and .name=="triage_confidence")][0] // null')
  [[ "$SCORE_ROW" != "null" && -n "$SCORE_ROW" ]] && break
  sleep 3
done
SCORE_TARGET=$(jq -r '.observationId // ""' <<<"$SCORE_ROW" 2>/dev/null)

SCORE_FILTERED=$(curl -sS -H "Authorization: $AUTH" "$HOST/api/public/scores?traceId=${STORED_TRACE}&limit=100" 2>/dev/null \
  | jq -r --arg t "$STORED_TRACE" '[.data[] | select(.traceId != $t)] | length')
if [[ "${SCORE_FILTERED:-0}" -eq 0 ]]; then
  ok "GET /api/public/scores?traceId= filters, as on 4.16.0"
else
  gap "GET /api/public/scores?traceId= does NOT filter here — it answered 200 with $SCORE_FILTERED score(s) from other traces. Filter client-side on the row's traceId"
fi
if [[ -n "$ROOT_ID" && "$SCORE_TARGET" == "$ROOT_ID" ]]; then
  ok "a score attaches to the OBSERVATION, not only to the trace"
else
  gap "the score read back on observation '$SCORE_TARGET', expected the turn '$ROOT_ID' — observation-level evaluators would have nothing to match"
fi

# `environment` is on the score body LangfuseScoreWriter builds, and it is not decoration:
# Langfuse's environment filter is applied to scores as well as to observations, so a score
# that arrives without one can be invisible in a project that filters.
SCORE_ENV=$(jq -r '.environment // ""' <<<"$SCORE_ROW" 2>/dev/null)
if [[ "$SCORE_ENV" == "compat" ]]; then
  ok "a score keeps its environment, so it is not hidden by the environment filter"
else
  gap "the score's environment reads '$SCORE_ENV', expected 'compat'"
fi

echo "==> experiments"
EXP=$(curl -sS -H "Authorization: $AUTH" "$HOST/api/public/observations?limit=50&name=experiment-item" 2>/dev/null \
  | jq -r '.data[0].metadata.attributes["langfuse.experiment.id"] // ""')
if [[ "$EXP" == "exp-compat" ]]; then
  gap "langfuse.experiment.* arrives unmapped, under metadata.attributes — exactly as on 4.16.0, so this is not a 3.x regression"
else
  bad "langfuse.experiment.id did not arrive at all: '$EXP'"
fi

echo
echo "langfuse 3.x compatibility: ${GAPS} documented difference(s) from 4.16.0, ${FAILURES} failure(s)"
if [[ $FAILURES -eq 0 ]]; then
  echo "  (a GAP is the result this script exists to produce, not a failure of it — see docs/08-langfuse-features.md)"
fi
exit "$FAILURES"
