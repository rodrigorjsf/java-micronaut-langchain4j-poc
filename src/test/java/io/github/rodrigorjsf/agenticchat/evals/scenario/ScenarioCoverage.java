package io.github.rodrigorjsf.agenticchat.evals.scenario;

import io.github.rodrigorjsf.agenticchat.evals.scenario.ArtifactInventory.Artifact;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * How well the dataset covers the domains and the artifacts: the floor per domain, the artifacts
 * no row depends on, and the {@code dependsOn} entries that name nothing.
 *
 * <p>Computed over the whole dataset, not over the rows a filter selected: a run narrowed to one
 * domain still reports what the dataset as a whole is missing. Reported, never gated — the floor
 * is what the scenario-suite skill aims for when it drafts rows, and a gap is work to do rather
 * than a regression. Whether it should gate is open in #65.
 *
 * @param domains             one entry per domain, by name
 * @param uncovered           artifacts no row's {@code dependsOn} names, by kind then id
 * @param unknownDependencies {@code dependsOn} entries that name no artifact of the inventory
 */
public record ScenarioCoverage(List<DomainCoverage> domains, List<Artifact> uncovered,
                               List<String> unknownDependencies) {

    static final int MIN_HAPPY = 2;
    static final int MIN_BAD = 3;

    /**
     * @param rows      every row that names the domain
     * @param happy     rows of kind {@code happy}
     * @param bad       rows of a kind in {@link Scenario#BAD_PATH_KINDS}
     * @param multiTurn rows of kind {@code multi-turn}
     * @param stateful  whether the domain holds state across turns, so it needs a multi-turn row
     * @param gaps      each way the domain falls short of the floor; empty when it meets it
     */
    public record DomainCoverage(String domain, long rows, long happy, long bad, long multiTurn,
                                 boolean stateful, List<String> gaps) {
    }

    /** Nothing measured yet: what the report shows when the dataset never loaded. */
    public static ScenarioCoverage empty() {
        return new ScenarioCoverage(List.of(), List.of(), List.of());
    }

    public ScenarioCoverage {
        domains = List.copyOf(domains);
        uncovered = List.copyOf(uncovered);
        unknownDependencies = List.copyOf(unknownDependencies);
    }

    /**
     * @param scenarios       every committed row
     * @param statefulDomains the domains that need a multi-turn row
     * @param inventory       the artifacts of the repository under test
     */
    public static ScenarioCoverage of(List<Scenario> scenarios, Set<String> statefulDomains,
                                      ArtifactInventory inventory) {
        var names = new TreeSet<String>();
        scenarios.forEach(s -> names.addAll(s.domains()));
        var domains = new ArrayList<DomainCoverage>();
        for (String domain : names) {
            var rows = scenarios.stream().filter(s -> s.domains().contains(domain)).toList();
            domains.add(domain(domain, rows, statefulDomains.contains(domain)));
        }

        var depended = new HashSet<String>();
        var unknown = new ArrayList<String>();
        var known = inventory.ids();
        for (Scenario scenario : scenarios) {
            for (String dependency : scenario.dependsOn()) {
                depended.add(dependency);
                if (!known.contains(dependency)) {
                    unknown.add(scenario.id() + " depends on '" + dependency + "'");
                }
            }
        }
        var uncovered = inventory.artifacts().stream()
                .filter(artifact -> !depended.contains(artifact.id()))
                .sorted(Comparator.comparing(Artifact::kind).thenComparing(Artifact::id))
                .toList();
        return new ScenarioCoverage(domains, uncovered, unknown);
    }

    private static DomainCoverage domain(String domain, List<Scenario> rows, boolean stateful) {
        long happy = rows.stream().filter(s -> Scenario.HAPPY.equals(s.kind())).count();
        long bad = rows.stream().filter(s -> Scenario.BAD_PATH_KINDS.contains(s.kind())).count();
        long multiTurn = rows.stream().filter(s -> Scenario.MULTI_TURN.equals(s.kind())).count();
        var gaps = new ArrayList<String>();
        if (happy < MIN_HAPPY) {
            gaps.add(happy + " happy path of the " + MIN_HAPPY + " required");
        }
        if (bad < MIN_BAD) {
            gaps.add(bad + " bad path of the " + MIN_BAD + " required ("
                    + String.join(", ", Scenario.BAD_PATH_KINDS) + ")");
        }
        if (stateful && multiTurn == 0) {
            gaps.add("no multi-turn scenario, and the domain holds state");
        }
        return new DomainCoverage(domain, rows.size(), happy, bad, multiTurn, stateful, gaps);
    }
}
