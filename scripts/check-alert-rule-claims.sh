#!/usr/bin/env bash
#
# WHAT   Guards the one claim in alert-rules.yaml that a reader acts on and a YAML linter
#        cannot check: what rule 1 detects. Two halves, and the second is why the first is
#        more than a string match.
#          (a) PROSE  — rule 1's comment block must not re-acquire the falsified claim that
#                       it fires on a single failed batch, and must still carry the blind
#                       spot; it must also still describe the expression that is actually
#                       there.
#          (b) EVIDENCE — a promtool unit test, generated into a temp dir and run on the
#                       real PromQL engine, that reproduces WHY the claim was false.
#
# WHY    A Grafana alert rule is prose plus PromQL, and only the PromQL is machine-checked.
#        alert-rules.yaml once said rule 1 "is allowed to fire on a single failed batch".
#        It cannot. `rate()` computes last-minus-first over the samples it finds and invents
#        no zero before the series existed, so `otelcol_exporter_send_failed_spans` going
#        from ABSENT to 3 and stopping yields 0, not 3. `increase()` is `rate * window` and
#        returns 0 for the identical reason. The rule was correct; the sentence describing
#        it was not, and a wrong sentence next to a green rule is what an operator reads at
#        03:00 to conclude no spans were dropped.
#        Half (b) exists because half (a) alone cannot tell "rate is blind here" from "my
#        harness returns 0 for everything" — so it asserts a NEGATIVE and a POSITIVE case
#        against the same engine, and the pair is the discriminator.
#
# WHEN   After editing alert-rules.yaml — always after editing rule 1's comment or its
#        `expr`. Rewording the comment on purpose means rewording the MUST-CONTAIN phrases
#        below on purpose; that coupling is the point, not an accident.
#
# HOW    ./scripts/check-alert-rule-claims.sh   (no arguments; exit 0 green, 1 red)
#        Needs Docker and the local prom/prometheus:v3.14.0 image. No network: the image is
#        already pulled by compose.observability.yaml, and promtool is inside it.
#
# It was first written INTO observability/grafana/provisioning/alerting/ and has been moved
# here, which is where this repository keeps its checks. The move was not cosmetic: Grafana's
# file provisioner reads that directory, and while it skipped a .sh safely — all four rules
# still loaded — it said so at WARN on every start:
#
#   logger=provisioning.alerting level=warn
#     msg="file has invalid suffix 'check-alert-rule-claims.sh' (.yaml,.yml,.json accepted), skipping"
#
# A provisioning directory that logs a warning every boot is a directory whose warnings stop
# being read, which is exactly the failure mode the rules in it exist to prevent. The path
# resolution below still accepts either location, so the script works from wherever it sits.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Two candidates, because this script is expected to be MOVED to scripts/ one day and a
# path resolved only from BASH_SOURCE would then point at a file that is not there —
# the anchor check would fail and read as "rule 1's comment block moved", which is a lie.
RULES=""
for candidate in \
  "${HERE}/alert-rules.yaml" \
  "${HERE}/../observability/grafana/provisioning/alerting/alert-rules.yaml"
do
  [ -f "$candidate" ] && { RULES="$(cd "$(dirname "$candidate")" && pwd)/alert-rules.yaml"; break; }
done
if [ -z "$RULES" ]; then
  printf 'FAIL  alert-rules.yaml not found relative to %s\n' "$HERE"
  exit 1
fi
PROM_IMAGE="prom/prometheus:v3.14.0"
FAILED=0

red()  { printf 'FAIL  %s\n' "$1"; FAILED=1; }
green(){ printf 'ok    %s\n' "$1"; }

# ---------------------------------------------------------------------------------------
# (a) PROSE — rule 1's comment block, delimited by its heading and its uid.
# ---------------------------------------------------------------------------------------
BLOCK="$(sed -n '/# 1\. Spans are being dropped on the way out of the collector\./,/uid: agentic-span-export-failing/p' "$RULES")"

if [ -z "$BLOCK" ]; then
  red "rule 1's comment block was not found in ${RULES} — the anchors moved"
  exit 1
fi

# Claims measurement falsified. Any of these back in the file is the defect returning.
while IFS= read -r phrase; do
  [ -z "$phrase" ] && continue
  if printf '%s' "$BLOCK" | grep -qiF -- "$phrase"; then
    red "rule 1 claims again: \"${phrase}\" — measurement says it cannot (see the promtool case below)"
  else
    green "rule 1 does not claim: \"${phrase}\""
  fi
done <<'FORBIDDEN'
fire on a single failed batch
fires on a single failed batch
an empty result is PROOF OF HEALTH
FORBIDDEN

# The blind spot has to be stated, or the rule is undocumented rather than wrong.
while IFS= read -r phrase; do
  [ -z "$phrase" ] && continue
  if printf '%s' "$BLOCK" | grep -qF -- "$phrase"; then
    green "rule 1 documents: \"${phrase}\""
  else
    red "rule 1 no longer documents: \"${phrase}\" — the blind spot is unstated"
  fi
done <<'REQUIRED'
WHAT IT CANNOT SEE
first-and-only increment
increase()
REQUIRED

# The prose describes a rate. If the expression stops being one, the prose is stale even
# though every word of it is still there.
if grep -qF 'expr: sum by (exporter) (rate(otelcol_exporter_send_failed_spans[5m]))' "$RULES"; then
  green "rule 1's expr is still the rate form the comment describes"
else
  red "rule 1's expr changed — the comment above it describes rate() and is now stale"
fi

# ---------------------------------------------------------------------------------------
# (b) EVIDENCE — the PromQL engine, not an assertion about it.
#
# `> bool 0` instead of a raw rate value on purpose: the extrapolated rate of the positive
# case is a fiddly float, and the question is only whether the alert's `gt 0` would trip.
# ---------------------------------------------------------------------------------------
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
# The prometheus image runs as uid 65534, and `mktemp -d` is 0700 — without this the bind
# mount is readable only by the host user and promtool reports "permission denied" for a
# file that is plainly there.
chmod 0755 "$WORK"

cat > "${WORK}/first-appearance.yml" <<'PROMTOOL'
# Reproduces, on Prometheus's own PromQL engine, the three states that are all green under
# rule 1's `noDataState: OK` + threshold `gt 0`, and the one state that is not.
evaluation_interval: 1m
tests:
  # NEGATIVE 1 — the counter APPEARS at 3 and stops. This is a collector restart followed by
  # one permanent rejection: N spans lost for ever, and both rate() and increase() say 0.
  - interval: 1m
    name: first-and-only increment is invisible to rate() and to increase()
    input_series:
      - series: 'otelcol_exporter_send_failed_spans{exporter="otlp_grpc/tempo"}'
        values: '_ _ 3 3 3 3 3'
    promql_expr_test:
      - expr: sum by (exporter) (rate(otelcol_exporter_send_failed_spans[5m])) > bool 0
        eval_time: 6m
        exp_samples:
          - labels: '{exporter="otlp_grpc/tempo"}'
            value: 0
      - expr: sum by (exporter) (increase(otelcol_exporter_send_failed_spans[5m])) > bool 0
        eval_time: 6m
        exp_samples:
          - labels: '{exporter="otlp_grpc/tempo"}'
            value: 0

  # NEGATIVE 2 — one sample in the window. rate() needs two, so it returns NOTHING at all,
  # which reaches Grafana as No Data and `noDataState: OK` calls it healthy.
  - interval: 1m
    name: a single sample in the window yields no data at all
    input_series:
      - series: 'otelcol_exporter_send_failed_spans{exporter="otlp_grpc/tempo"}'
        values: '_ _ _ _ _ _ 3'
    promql_expr_test:
      - expr: sum by (exporter) (rate(otelcol_exporter_send_failed_spans[5m])) > bool 0
        eval_time: 6m
        exp_samples: []

  # POSITIVE — a further increment once the series exists. Without this case the two above
  # prove nothing: a harness that returned 0 for everything would pass them.
  - interval: 1m
    name: a further increment does trip the threshold
    input_series:
      - series: 'otelcol_exporter_send_failed_spans{exporter="otlp_grpc/tempo"}'
        values: '_ _ 3 3 3 3 15'
    promql_expr_test:
      - expr: sum by (exporter) (rate(otelcol_exporter_send_failed_spans[5m])) > bool 0
        eval_time: 6m
        exp_samples:
          - labels: '{exporter="otlp_grpc/tempo"}'
            value: 1
PROMTOOL
chmod 0644 "${WORK}/first-appearance.yml"

if docker run --rm -v "${WORK}:/t" --entrypoint promtool "$PROM_IMAGE" test rules /t/first-appearance.yml; then
  green "promtool: rate()/increase() are blind to a first-and-only increment, and see a later one"
else
  red "promtool: the first-appearance measurement no longer reproduces — re-read rule 1's comment before trusting it"
fi

exit "$FAILED"
