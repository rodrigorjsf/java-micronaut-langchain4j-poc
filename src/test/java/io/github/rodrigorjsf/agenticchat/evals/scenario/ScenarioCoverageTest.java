package io.github.rodrigorjsf.agenticchat.evals.scenario;

import io.github.rodrigorjsf.agenticchat.evals.scenario.ArtifactInventory.Artifact;
import io.github.rodrigorjsf.agenticchat.evals.scenario.ArtifactInventory.Kind;
import io.github.rodrigorjsf.agenticchat.evals.scenario.ScenarioCoverage.DomainCoverage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioCoverageTest {

    private static final ArtifactInventory INVENTORY = inventory(
            new Artifact(Kind.SKILL, "geo-and-weather"),
            new Artifact(Kind.TOOL, "get_weather"),
            new Artifact(Kind.TOOL, "lookup_company_by_cnpj"),
            new Artifact(Kind.CATALOGUE_KEY, "brasilapi"),
            new Artifact(Kind.SUB_AGENT, "weather_reporter"));

    @Test
    @DisplayName("a domain meeting the floor has no gap: 2 happy, 3 bad, and a multi-turn row when stateful")
    void aDomainMeetingTheFloorHasNoGap() {
        var coverage = ScenarioCoverage.of(List.of(
                row("w1", "weather", "happy"), row("w2", "weather", "happy"),
                row("w3", "weather", "missing-info"), row("w4", "weather", "injection"),
                row("w5", "weather", "ambiguous"), row("w6", "weather", "multi-turn")),
                Set.of("weather"), INVENTORY);

        assertThat(coverage.domains()).containsExactly(
                new DomainCoverage("weather", 6, 2, 3, 1, true, List.of()));
    }

    @Test
    @DisplayName("a domain below the floor names every shortfall")
    void aDomainBelowTheFloorNamesEachShortfall() {
        var coverage = ScenarioCoverage.of(List.of(
                row("w1", "weather", "happy"), row("w2", "weather", "upstream-failure"),
                row("w3", "weather", "out-of-scope")),
                Set.of("weather"), INVENTORY);

        assertThat(coverage.domains().getFirst().gaps()).containsExactly(
                "1 happy path of the 2 required",
                "1 bad path of the 3 required (ambiguous, injection, missing-info, out-of-territory, upstream-failure)",
                "no multi-turn scenario, and the domain holds state");
    }

    @Test
    @DisplayName("a domain that holds no state needs no multi-turn row")
    void aStatelessDomainNeedsNoMultiTurnRow() {
        var coverage = ScenarioCoverage.of(List.of(
                row("c1", "cnpj", "happy"), row("c2", "cnpj", "happy"),
                row("c3", "cnpj", "missing-info"), row("c4", "cnpj", "injection"),
                row("c5", "cnpj", "out-of-territory")),
                Set.of("weather"), INVENTORY);

        assertThat(coverage.domains().getFirst().gaps()).isEmpty();
        assertThat(coverage.domains().getFirst().stateful()).isFalse();
    }

    @Test
    @DisplayName("a cross-domain row counts toward each of its domains, domains listed by name")
    void aCrossDomainRowCountsForEachDomain() {
        var trip = new Scenario("t1", List.of("weather", "holidays"), "happy", "synthetic", List.of("oi"),
                new Scenario.Expectation(new Scenario.TrajectoryExpectation("ANSWERED", List.of()), null),
                List.of("get_weather"), false, null);

        var coverage = ScenarioCoverage.of(List.of(trip), Set.of(), INVENTORY);

        assertThat(coverage.domains()).extracting(DomainCoverage::domain).containsExactly("holidays", "weather");
        assertThat(coverage.domains()).extracting(DomainCoverage::happy).containsExactly(1L, 1L);
    }

    @Test
    @DisplayName("an artifact no row depends on is uncovered")
    void anArtifactNoRowDependsOnIsUncovered() {
        var coverage = ScenarioCoverage.of(List.of(row("w1", "weather", "happy")), Set.of(), INVENTORY);

        assertThat(coverage.uncovered()).containsExactly(
                new Artifact(Kind.TOOL, "lookup_company_by_cnpj"),
                new Artifact(Kind.CATALOGUE_KEY, "brasilapi"),
                new Artifact(Kind.SUB_AGENT, "weather_reporter"));
    }

    @Test
    @DisplayName("a dependsOn entry naming no artifact is reported, so a typo cannot hide a row from diff mode")
    void anUnknownDependencyIsReported() {
        var typo = new Scenario("w9", List.of("weather"), "happy", "synthetic", List.of("oi"),
                new Scenario.Expectation(new Scenario.TrajectoryExpectation("ANSWERED", List.of()), null),
                List.of("get_wether"), false, null);

        var coverage = ScenarioCoverage.of(List.of(typo), Set.of(), INVENTORY);

        assertThat(coverage.unknownDependencies()).containsExactly("w9 depends on 'get_wether'");
    }

    @Test
    @DisplayName("every dependsOn of the committed dataset names an artifact of this repository")
    void theCommittedDatasetNamesOnlyRealArtifacts() {
        var repository = ArtifactInventory.ofWorkingTree(Path.of(System.getProperty("user.dir")));

        var coverage = ScenarioCoverage.of(ScenarioDataset.loadCommitted(), ScenarioDataset.loadStatefulDomains(),
                repository);

        assertThat(coverage.unknownDependencies()).isEmpty();
    }

    private static Scenario row(String id, String domain, String kind) {
        return new Scenario(id, List.of(domain), kind, "synthetic",
                kind.equals("multi-turn") ? List.of("oi", "e amanhã?") : List.of("oi"),
                new Scenario.Expectation(new Scenario.TrajectoryExpectation("ANSWERED", List.of()), null),
                new ArrayList<>(List.of("geo-and-weather", "get_weather")), false,
                kind.equals("upstream-failure")
                        ? new Scenario.UpstreamFailure("open-meteo-forecast", Scenario.FailureShape.TIMEOUT) : null);
    }

    private static ArtifactInventory inventory(Artifact... artifacts) {
        var fingerprints = new LinkedHashMap<Artifact, String>();
        for (Artifact artifact : artifacts) {
            fingerprints.put(artifact, "text");
        }
        return new ArtifactInventory(fingerprints);
    }
}
