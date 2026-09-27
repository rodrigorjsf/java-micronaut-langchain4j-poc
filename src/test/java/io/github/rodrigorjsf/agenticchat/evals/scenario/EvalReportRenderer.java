package io.github.rodrigorjsf.agenticchat.evals.scenario;

import io.github.rodrigorjsf.agenticchat.evals.scenario.ScenarioResult.CheckResult;
import io.github.rodrigorjsf.agenticchat.evals.scenario.ScenarioResult.Cost;
import io.github.rodrigorjsf.agenticchat.evals.scenario.ScenarioResult.ToolCall;
import io.github.rodrigorjsf.agenticchat.evals.scenario.ScenarioResult.Trajectory;
import io.github.rodrigorjsf.agenticchat.evals.scenario.ScenarioRuns.Status;

import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Turns scenario results into {@code eval-report.html}: one self-contained page, inline CSS,
 * no script and nothing fetched when it opens.
 *
 * <p>A pure function on purpose. Everything a reader sees is decided here from the results
 * alone, so the page is unit tested without a model, a server or a file system.
 *
 * <p>Every string that came from a user, a model or a tool goes through {@link #escape}. The
 * answers under test are model output, and an injection scenario's answer is exactly the text
 * most likely to carry markup.
 */
public final class EvalReportRenderer {

    private static final String STYLE = """
            :root{--bg:#fbfbfa;--fg:#1d1d1b;--muted:#6b6b66;--line:#e2e1dc;--card:#ffffff;\
            --pass:#1f7a3f;--fail:#b3261e;--code:#f2f1ec}
            @media (prefers-color-scheme: dark){:root{--bg:#161615;--fg:#ecebe6;--muted:#a09f99;\
            --line:#34332f;--card:#1f1f1d;--pass:#6fcf8f;--fail:#f28b82;--code:#2a2926}}
            body{margin:0;padding:24px 16px;background:var(--bg);color:var(--fg);\
            font:15px/1.5 system-ui,-apple-system,Segoe UI,sans-serif}
            main{max-width:1080px;margin:0 auto}
            h1{font-size:1.5rem;margin:0 0 4px}
            .summary{color:var(--muted);margin:0 0 24px;padding-left:20px}
            .summary strong{color:var(--fg)}
            h3{font-size:1rem;margin:16px 0 4px}
            details{background:var(--card);border:1px solid var(--line);border-radius:8px;\
            margin:0 0 12px;padding:12px 16px}
            summary{cursor:pointer;font-weight:600}
            .pass{color:var(--pass)}.fail{color:var(--fail)}.flaky,.skipped{color:var(--muted)}
            .meta{color:var(--muted);font-weight:400}
            table{border-collapse:collapse;width:100%;margin:8px 0}
            th,td{border-top:1px solid var(--line);padding:6px 8px;text-align:left;vertical-align:top}
            th{color:var(--muted);font-weight:500;width:160px}
            pre{background:var(--code);padding:8px;border-radius:6px;margin:0;\
            white-space:pre-wrap;word-break:break-word;font-size:13px}
            """;

    private EvalReportRenderer() {
    }

    public static String render(List<ScenarioRuns> scenarios) {
        var html = new StringBuilder(8_192)
                .append("<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>Scenario eval report</title>\n<style>\n").append(STYLE).append("</style>\n")
                .append("</head>\n<body>\n<main>\n<h1>Scenario eval report</h1>\n");
        summary(html, scenarios);
        for (ScenarioRuns runs : scenarios) {
            scenario(html, runs);
        }
        return html.append("</main>\n</body>\n</html>\n").toString();
    }

    /** One line per fact, each opening with its label, so the page reads at a glance. */
    private static void summary(StringBuilder html, List<ScenarioRuns> scenarios) {
        long passed = scenarios.stream().filter(runs -> runs.status() == Status.PASSED).count();
        var repetitions = scenarios.stream().flatMap(runs -> runs.repetitions().stream()).toList();
        long skipped = repetitions.stream().filter(ScenarioResult::skipped).count();
        long inputTokens = repetitions.stream().mapToLong(ScenarioResult::inputTokens).sum();
        long outputTokens = repetitions.stream().mapToLong(ScenarioResult::outputTokens).sum();
        var cost = repetitions.stream().map(ScenarioResult::cost).reduce(Cost.NONE, Cost::plus);
        String gateFailures = scenarios.stream().filter(ScenarioRuns::failsGate)
                .map(runs -> escape(runs.scenario().id())).collect(Collectors.joining(", "));
        String flaky = scenarios.stream().filter(runs -> runs.status() == Status.FLAKY)
                .map(runs -> escape(runs.scenario().id()) + " (" + runs.passes() + "/" + runs.executed() + ")")
                .collect(Collectors.joining(", "));

        html.append("<ul class=\"summary\">\n")
                .append("<li><strong>").append(passed).append(" of ").append(scenarios.size())
                .append(" scenarios passed</strong> every repetition that ran</li>\n")
                .append("<li><strong>Coverage per domain</strong> (a scenario counts toward every domain it names): ")
                .append(coverage(scenarios)).append("</li>\n")
                .append("<li><strong>Gate failures</strong> (critical, any repetition failed): ")
                .append(gateFailures.isEmpty() ? "none" : gateFailures).append("</li>\n")
                .append("<li><strong>Flaky</strong> (passed some repetitions, failed others): ")
                .append(flaky.isEmpty() ? "none" : flaky).append("</li>\n")
                .append("<li><strong>Skipped repetitions</strong> (rate limited, counted neither way): ")
                .append(skipped).append("</li>\n")
                .append("<li><strong>Tokens</strong> (as <code>/api/chat</code> reported them) ").append(inputTokens).append(" in / ")
                .append(outputTokens).append(" out</li>\n")
                .append("<li><strong>Estimated cost</strong> (every model call the tracer saw, judge and sub-agents included) $")
                .append(cost.usd().setScale(6, RoundingMode.HALF_UP).toPlainString())
                .append(cost.unpricedCalls() == 0 ? "" : " (" + cost.unpricedCalls()
                        + " model calls unpriced, not included)")
                .append("</li>\n</ul>\n");
    }

    private static String coverage(List<ScenarioRuns> scenarios) {
        var perDomain = scenarios.stream()
                .flatMap(runs -> runs.scenario().domains().stream())
                .collect(Collectors.groupingBy(domain -> domain, TreeMap::new, Collectors.counting()));
        if (perDomain.isEmpty()) {
            return "none";
        }
        return perDomain.entrySet().stream()
                .map(entry -> escape(entry.getKey()) + ": " + entry.getValue()
                        + (entry.getValue() == 1 ? " scenario" : " scenarios"))
                .collect(Collectors.joining(", "));
    }

    private static void scenario(StringBuilder html, ScenarioRuns runs) {
        var scenario = runs.scenario();
        var status = runs.status();
        html.append(status == Status.PASSED ? "<details>\n" : "<details open>\n")
                .append("<summary>").append(badge(status)).append(' ')
                .append(escape(scenario.id()))
                .append(" <span class=\"meta\">").append(escape(String.join(", ", scenario.domains())))
                .append(" · ").append(escape(scenario.kind()))
                .append(scenario.critical() ? " · critical" : "")
                .append(" · ").append(runs.passes()).append(" of ").append(runs.executed()).append(" passed")
                .append(runs.skipped() == 0 ? "" : ", " + runs.skipped() + " skipped")
                .append("</span></summary>\n<table>\n");
        row(html, "Source", escape(scenario.source()));
        row(html, "Faked upstream", scenario.upstream() == null
                ? "none: every upstream was real"
                : escape(scenario.upstream().describe()));
        row(html, "Turns", list(scenario.turns()));
        var expected = scenario.expect().trajectory();
        row(html, "Expected", describe(expected));
        var answer = scenario.expect().answer();
        if (answer != null) {
            row(html, "Expected answer", describe(answer));
        }
        for (var turn : scenario.expect().turns()) {
            var parts = new ArrayList<String>();
            if (turn.trajectory() != null) {
                parts.add(describe(turn.trajectory()));
            }
            if (turn.answer() != null) {
                parts.add(describe(turn.answer()));
            }
            row(html, "Expected at turn " + turn.turn(), parts.isEmpty() ? "nothing" : String.join("; ", parts));
        }
        html.append("</table>\n");
        var repetitions = runs.repetitions();
        for (int i = 0; i < repetitions.size(); i++) {
            repetition(html, repetitions.get(i), i + 1, repetitions.size());
        }
        html.append("</details>\n");
    }

    private static void repetition(StringBuilder html, ScenarioResult result, int number, int of) {
        var trajectory = result.trajectory();
        html.append("<h3>Repetition ").append(number).append(" of ").append(of).append(" — ")
                .append(result.skipped() ? badge(Status.SKIPPED) : badge(result.passed()))
                .append("</h3>\n<table>\n");
        var turns = result.turns();
        if (turns.size() > 1) {
            for (var turn : turns) {
                html.append("<tr><th colspan=\"2\">Turn ").append(turn.number()).append(" of ")
                        .append(result.scenario().turns().size()).append("</th></tr>\n");
                row(html, "Message", escape(turn.message()));
                row(html, "Conversation", escape(turn.conversationId()));
                turn(html, turn.trajectory(), turn.answer());
            }
        } else {
            turn(html, trajectory, result.answer());
        }
        row(html, "Checks", checks(result.checks()));
        row(html, "Latency", result.latency().toMillis() + " ms");
        row(html, "Tokens", result.inputTokens() + " in / " + result.outputTokens() + " out");
        html.append("</table>\n");
    }

    private static void turn(StringBuilder html, Trajectory trajectory, String answer) {
        row(html, "Outcome", escape(trajectory.outcome()));
        row(html, "Activations", escape(String.join(", ", trajectory.activations())));
        row(html, "Tool calls", toolCalls(trajectory.toolCalls()));
        row(html, "Answer", "<pre>" + escape(answer) + "</pre>");
    }

    private static String describe(Scenario.TrajectoryExpectation expected) {
        return "outcome " + escape(expected.outcome()) + "; tools " + escape(String.valueOf(expected.toolsCalled()));
    }

    private static String describe(Scenario.AnswerExpectation answer) {
        return "language " + escape(answer.language())
                + "; contains " + escape(String.valueOf(answer.contains()))
                + "; grounded " + escape(String.valueOf(answer.grounded()));
    }

    private static String toolCalls(List<ToolCall> calls) {
        if (calls.isEmpty()) {
            return "none";
        }
        var cell = new StringBuilder();
        for (ToolCall call : calls) {
            cell.append("<p><strong>").append(escape(call.name())).append("</strong></p>")
                    .append("<pre>").append(escape(call.arguments())).append("</pre>")
                    .append("<pre>").append(escape(call.result())).append("</pre>");
        }
        return cell.toString();
    }

    private static String checks(List<CheckResult> checks) {
        var cell = new StringBuilder("<ul>");
        for (CheckResult check : checks) {
            cell.append("<li>").append(badge(check.passed())).append(' ')
                    .append(escape(check.name())).append(": ").append(escape(check.reason())).append("</li>");
        }
        return cell.append("</ul>").toString();
    }

    private static String list(List<String> items) {
        var cell = new StringBuilder("<ol>");
        items.forEach(item -> cell.append("<li>").append(escape(item)).append("</li>"));
        return cell.append("</ol>").toString();
    }

    private static void row(StringBuilder html, String label, String cell) {
        html.append("<tr><th>").append(label).append("</th><td>").append(cell).append("</td></tr>\n");
    }

    private static String badge(boolean passed) {
        return passed ? "<span class=\"pass\">PASS</span>" : "<span class=\"fail\">FAIL</span>";
    }

    private static String badge(Status status) {
        return switch (status) {
            case PASSED -> badge(true);
            case FAILED -> badge(false);
            case FLAKY -> "<span class=\"flaky\">FLAKY</span>";
            case SKIPPED -> "<span class=\"skipped\">SKIPPED</span>";
        };
    }

    static String escape(String text) {
        if (text == null) {
            return "";
        }
        var escaped = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
