package io.github.rodrigorjsf.agenticchat.rag;

import io.github.rodrigorjsf.agenticchat.rag.RetrievalReport.Query;
import io.github.rodrigorjsf.agenticchat.testsupport.Git;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The corpus documents changed since a git ref, and the queries each one puts at risk.
 *
 * @param ref              the ref the run was compared against
 * @param changedDocuments file names under the corpus directory, added, edited, renamed or deleted
 */
record CorpusDiff(String ref, List<String> changedDocuments) {

    /** Where the corpus lives, relative to the repository root. */
    static final String CORPUS_DIRECTORY = "src/main/resources/knowledge";

    /**
     * Per changed document, the ids of the queries that depend on it: every positive
     * row naming it as {@code expected_source}, and every negative row. A negative
     * depends on the whole corpus, because top-k is a competition — an edit to any
     * document can make a question that used to retrieve nothing clear the threshold.
     */
    Map<String, List<String>> dependents(List<Query> queries) {
        var byDocument = new LinkedHashMap<String, List<String>>();
        for (String document : changedDocuments) {
            var ids = new ArrayList<String>();
            queries.stream().filter(q -> document.equals(q.expectedSource())).map(Query::id).forEach(ids::add);
            queries.stream().filter(q -> !q.positive()).map(Query::id).forEach(ids::add);
            byDocument.put(document, List.copyOf(ids));
        }
        return byDocument;
    }

    /**
     * Asks git which corpus documents differ between {@code ref} and the working tree,
     * untracked new documents included. {@code --no-renames} lists a rename as a
     * deletion plus an addition, so the rows still pointing at the old name show up.
     */
    static CorpusDiff since(String ref, Path repositoryRoot) {
        var names = new TreeSet<String>();
        names.addAll(Git.lines(repositoryRoot, "diff", "--name-only", "--no-renames", ref, "--", CORPUS_DIRECTORY));
        names.addAll(Git.lines(repositoryRoot, "ls-files", "--others", "--exclude-standard", "--", CORPUS_DIRECTORY));
        var documents = names.stream()
                .filter(name -> name.endsWith(".md"))
                .map(name -> Path.of(name).getFileName().toString())
                .toList();
        return new CorpusDiff(ref, documents);
    }
}
