package io.github.rodrigorjsf.agenticchat.evals.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Loads the committed scenario rows: one JSON array per domain file.
 *
 * <p>Order is part of the contract. Files are read by name and rows in file order, so two
 * runs list the same scenarios in the same place and two reports can be compared side by side.
 */
public final class ScenarioDataset {

    static final String COMMITTED_DIRECTORY = "/evals/scenarios";

    /** The domains that hold state across turns, and so need a multi-turn row to meet the floor. */
    static final String STATEFUL_DOMAINS = "/evals/stateful-domains.json";

    private ScenarioDataset() {
    }

    public static List<Scenario> loadCommitted() {
        try {
            var url = ScenarioDataset.class.getResource(COMMITTED_DIRECTORY);
            if (url == null) {
                throw new IllegalStateException("No scenario directory on the test classpath: " + COMMITTED_DIRECTORY);
            }
            return load(Path.of(url.toURI()));
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Set<String> loadStatefulDomains() {
        try (var in = ScenarioDataset.class.getResourceAsStream(STATEFUL_DOMAINS)) {
            if (in == null) {
                throw new IllegalStateException("No stateful-domain list on the test classpath: " + STATEFUL_DOMAINS);
            }
            return Set.of(new ObjectMapper().readValue(in, String[].class));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + STATEFUL_DOMAINS, e);
        }
    }

    /**
     * Loads and validates every row. The first invalid row fails the whole load, naming its file,
     * its id (or its position when it has none) and the reason: a row that loads is a row the
     * runner, the coverage floor and the diff mode can all trust.
     */
    public static List<Scenario> load(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            var json = new ObjectMapper();
            var scenarios = new ArrayList<Scenario>();
            var ids = new HashSet<String>();
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().toList()) {
                var rows = json.readValue(file.toFile(), Scenario[].class);
                for (int i = 0; i < rows.length; i++) {
                    var problem = problemWith(rows[i], ids);
                    if (problem != null) {
                        var name = rows[i].id() == null || rows[i].id().isBlank()
                                ? "row " + (i + 1)
                                : "row '" + rows[i].id() + "'";
                        throw new IllegalArgumentException("Invalid scenario in " + file.getFileName()
                                + ", " + name + ": " + problem);
                    }
                    ids.add(rows[i].id());
                }
                scenarios.addAll(Arrays.asList(rows));
            }
            return List.copyOf(scenarios);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read scenarios from " + directory, e);
        }
    }

    /** The first rule the row breaks, or {@code null} when it breaks none. */
    private static String problemWith(Scenario row, Set<String> earlierIds) {
        if (row.id() == null || row.id().isBlank()) {
            return "id is missing";
        }
        if (earlierIds.contains(row.id())) {
            return "id is already used by an earlier row";
        }
        if (!Scenario.KINDS.contains(row.kind())) {
            return "unknown kind '" + row.kind() + "'; known kinds: " + Scenario.KINDS;
        }
        if (!Scenario.SOURCES.contains(row.source())) {
            return "unknown source '" + row.source() + "'; known sources: " + Scenario.SOURCES;
        }
        if (isEmpty(row.domains())) {
            return "domains is missing or empty";
        }
        if (isEmpty(row.turns())) {
            return "turns is missing or empty";
        }
        if (row.expect() == null || row.expect().trajectory() == null
                || row.expect().trajectory().outcome() == null || row.expect().trajectory().outcome().isBlank()) {
            return "expect.trajectory.outcome is missing";
        }
        if (isEmpty(row.dependsOn())) {
            return "dependsOn is missing or empty";
        }
        // Two fields say "this row fakes an upstream", and the runner reads only one of them:
        // they must agree, or a row reports a fake it never made, or makes one it never reports.
        boolean upstreamKind = Scenario.UPSTREAM_FAILURE.equals(row.kind());
        if (upstreamKind && row.upstream() == null) {
            return "kind upstream-failure requires an upstream override";
        }
        if (!upstreamKind && row.upstream() != null) {
            return "an upstream override is only allowed on kind upstream-failure";
        }
        if (Scenario.MULTI_TURN.equals(row.kind()) && row.turns().size() < 2) {
            return "kind multi-turn needs at least two turns";
        }
        return null;
    }

    private static boolean isEmpty(List<String> values) {
        return values == null || values.isEmpty();
    }
}
