package io.github.rodrigorjsf.agenticchat.rag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * Renders one retrieval run as a self-contained HTML page: inline CSS, no script,
 * nothing fetched from the network, so the file opens from disk anywhere.
 *
 * <p>A pure function on purpose. {@code KnowledgeBaseTest} does the retrieving and
 * writes the file; everything a reader sees here is unit tested without loading the
 * embedding model.
 */
final class RetrievalReport {

    /** One row of the committed query set. {@code expectedSource} is null for a negative row. */
    record Query(String id, String question, String label, String expectedSource, String provenance) {
        boolean positive() {
            return "positive".equals(label);
        }
    }

    /** One chunk the shipped retriever returned, with the relevance score it cleared the threshold at. */
    record Chunk(String source, String title, double score, String text) {
    }

    /** What one query retrieved. The pass rule lives here so the test and the report cannot disagree. */
    record Result(Query query, List<Chunk> chunks) {
        boolean passed() {
            if (!query.positive()) {
                return chunks.isEmpty();
            }
            return chunks.stream().anyMatch(chunk -> chunk.source().equals(query.expectedSource()));
        }
    }

    /**
     * @param corpusDocuments every document file name in the corpus
     * @param diff            null unless the run was asked for a diff against a ref
     */
    record Run(double threshold, int maxResults, List<String> corpusDocuments,
               List<Result> results, CorpusDiff diff) {
    }

    private RetrievalReport() {
    }

    static String render(Run run) {
        var html = new StringBuilder(8_192);
        html.append("""
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Retrieval report</title>
                <style>
                body{font:14px/1.45 system-ui,sans-serif;margin:24px;color:#1b1b1b;background:#fff}
                table{border-collapse:collapse;width:100%;margin:8px 0 24px}
                th,td{border:1px solid #ccc;padding:4px 8px;text-align:left;vertical-align:top}
                th{background:#f2f2f2}
                .PASS{color:#0a6b2d;font-weight:600}.FAIL{color:#b00020;font-weight:600}
                .chunk{margin:2px 0;font-size:12px;color:#444}
                @media (prefers-color-scheme: dark){body{background:#161616;color:#e6e6e6}
                th{background:#262626}td,th{border-color:#444}.chunk{color:#bbb}}
                </style></head><body>
                <h1>Retrieval report</h1>
                """);
        long passed = run.results().stream().filter(Result::passed).count();
        html.append("<p>Threshold (min-score) <b>").append(score(run.threshold()))
                .append("</b> · top-k <b>").append(run.maxResults())
                .append("</b> · ").append(passed).append('/').append(run.results().size())
                .append(" queries pass</p>\n");

        if (run.diff() != null) {
            renderDiff(run, html);
        }

        html.append("<h2>Queries</h2>\n<table><tr><th>id</th><th>question</th><th>expected</th>"
                + "<th>retrieved (score)</th><th>threshold</th><th>result</th></tr>\n");
        for (Result result : run.results()) {
            Query query = result.query();
            String verdict = result.passed() ? "PASS" : "FAIL";
            html.append("<tr><td>").append(escape(query.id()))
                    .append("</td><td>").append(escape(query.question()))
                    .append("</td><td>").append(query.positive() ? escape(query.expectedSource()) : "<i>nothing</i>")
                    .append("</td><td>");
            if (result.chunks().isEmpty()) {
                html.append("<i>nothing</i>");
            }
            for (Chunk chunk : result.chunks()) {
                html.append("<div><b>").append(escape(chunk.source())).append("</b> (")
                        .append(score(chunk.score())).append(") ").append(escape(chunk.title()))
                        .append("<div class=\"chunk\">").append(escape(chunk.text())).append("</div></div>");
            }
            html.append("</td><td>").append(score(run.threshold()))
                    .append("</td><td class=\"").append(verdict).append("\">").append(verdict)
                    .append("</td></tr>\n");
        }
        html.append("</table>\n");

        // Coverage per corpus topic. A topic here is one document: that is the unit a
        // positive row names, and the unit a knowledge author adds or edits.
        html.append("<h2>Coverage per document</h2>\n<table><tr><th>document</th>"
                + "<th>positive queries</th><th>passing</th></tr>\n");
        var uncovered = new ArrayList<String>();
        for (String document : run.corpusDocuments()) {
            var rows = run.results().stream()
                    .filter(result -> document.equals(result.query().expectedSource()))
                    .toList();
            if (rows.isEmpty()) {
                uncovered.add(document);
            }
            html.append("<tr><td>").append(escape(document)).append("</td><td>").append(rows.size())
                    .append("</td><td>").append(rows.stream().filter(Result::passed).count())
                    .append("</td></tr>\n");
        }
        html.append("</table>\n<h2>Uncovered documents</h2>\n");
        if (uncovered.isEmpty()) {
            html.append("<p>None: every corpus document is some positive query's expected document.</p>\n");
        } else {
            html.append("<ul>");
            uncovered.forEach(document -> html.append("<li>").append(escape(document)).append("</li>"));
            html.append("</ul>\n");
        }

        html.append("</body></html>\n");
        return html.toString();
    }

    private static void renderDiff(Run run, StringBuilder html) {
        html.append("<h2>Changed since ").append(escape(run.diff().ref())).append("</h2>\n");
        if (run.diff().changedDocuments().isEmpty()) {
            html.append("<p>No corpus document changed.</p>\n");
            return;
        }
        var byId = new HashMap<String, Result>();
        run.results().forEach(result -> byId.put(result.query().id(), result));
        var queries = run.results().stream().map(Result::query).toList();
        html.append("<table><tr><th>changed document</th><th>dependent queries</th></tr>\n");
        run.diff().dependents(queries).forEach((document, ids) -> {
            html.append("<tr><td>").append(escape(document)).append("</td><td>");
            if (ids.isEmpty()) {
                html.append("<i>none</i>");
            }
            for (String id : ids) {
                String verdict = byId.get(id).passed() ? "PASS" : "FAIL";
                html.append("<div>").append(escape(id)).append(" <span class=\"").append(verdict).append("\">")
                        .append(verdict).append("</span></div>");
            }
            html.append("</td></tr>\n");
        });
        html.append("</table>\n");
    }

    private static String score(double value) {
        return String.format(Locale.ROOT, "%.4f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    static String escape(String text) {
        if (text == null) {
            return "";
        }
        var out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
