package io.github.rodrigorjsf.agenticchat.evals.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * Loads the committed scenario rows: one JSON array per domain file.
 *
 * <p>Order is part of the contract. Files are read by name and rows in file order, so two
 * runs list the same scenarios in the same place and two reports can be compared side by side.
 */
public final class ScenarioDataset {

    static final String COMMITTED_DIRECTORY = "/evals/scenarios";

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

    public static List<Scenario> load(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            var json = new ObjectMapper();
            var scenarios = new ArrayList<Scenario>();
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().toList()) {
                scenarios.addAll(Arrays.asList(json.readValue(file.toFile(), Scenario[].class)));
            }
            return List.copyOf(scenarios);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read scenarios from " + directory, e);
        }
    }
}
