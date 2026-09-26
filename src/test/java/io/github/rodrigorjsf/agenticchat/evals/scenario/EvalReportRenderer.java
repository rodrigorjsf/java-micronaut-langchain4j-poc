package io.github.rodrigorjsf.agenticchat.evals.scenario;

import io.github.rodrigorjsf.agenticchat.evals.scenario.ScenarioResult.CheckResult;
import io.github.rodrigorjsf.agenticchat.evals.scenario.ScenarioResult.ToolCall;

import java.util.List;

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
            .summary{color:var(--muted);margin:0 0 24px}
            details{background:var(--card);border:1px solid var(--line);border-radius:8px;\
            margin:0 0 12px;padding:12px 16px}
            summary{cursor:pointer;font-weight:600}
            .pass{color:var(--pass)}.fail{color:var(--fail)}
            .meta{color:var(--muted);font-weight:400}
            table{border-collapse:collapse;width:100%;margin:8px 0}
            th,td{border-top:1px solid var(--line);padding:6px 8px;text-align:left;vertical-align:top}
            th{color:var(--muted);font-weight:500;width:160px}
            pre{background:var(--code);padding:8px;border-radius:6px;margin:0;\
            white-space:pre-wrap;word-break:break-word;font-size:13px}
            """;

    private EvalReportRenderer() {
    }

    public static String render(List<ScenarioResult> results) {
        long passed = results.stream().filter(ScenarioResult::passed).count();
        var html = new StringBuilder(8_192)
                .append("<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>Scenario eval report</title>\n<style>\n").append(STYLE).append("</style>\n")
                .append("</head>\n<body>\n<main>\n<h1>Scenario eval report</h1>\n")
                .append("<p class=\"summary\">").append(passed).append(" of ").append(results.size())
                .append(" scenarios passed</p>\n");
        for (ScenarioResult result : results) {
            scenario(html, result);
        }
        return html.append("</main>\n</body>\n</html>\n").toString();
    }

    private static void scenario(StringBuilder html, ScenarioResult result) {
        var scenario = result.scenario();
        var trajectory = result.trajectory();
        html.append(result.passed() ? "<details>\n" : "<details open>\n")
                .append("<summary>").append(verdict(result.passed())).append(' ')
                .append(escape(scenario.id()))
                .append(" <span class=\"meta\">").append(escape(String.join(", ", scenario.domains())))
                .append(" · ").append(escape(scenario.kind()))
                .append(scenario.critical() ? " · critical" : "")
                .append("</span></summary>\n<table>\n");
        row(html, "Source", escape(scenario.source()));
        row(html, "Turns", list(scenario.turns()));
        var expected = scenario.expect().trajectory();
        row(html, "Expected", "outcome " + escape(expected.outcome()) + "; tools "
                + escape(String.valueOf(expected.toolsCalled())));
        row(html, "Outcome", escape(trajectory.outcome()));
        row(html, "Activations", escape(String.join(", ", trajectory.activations())));
        row(html, "Tool calls", toolCalls(trajectory.toolCalls()));
        row(html, "Answer", "<pre>" + escape(result.answer()) + "</pre>");
        row(html, "Checks", checks(result.checks()));
        row(html, "Latency", result.latency().toMillis() + " ms");
        row(html, "Tokens", result.inputTokens() + " in / " + result.outputTokens() + " out");
        html.append("</table>\n</details>\n");
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
            cell.append("<li>").append(verdict(check.passed())).append(' ')
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

    private static String verdict(boolean passed) {
        return passed ? "<span class=\"pass\">PASS</span>" : "<span class=\"fail\">FAIL</span>";
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
