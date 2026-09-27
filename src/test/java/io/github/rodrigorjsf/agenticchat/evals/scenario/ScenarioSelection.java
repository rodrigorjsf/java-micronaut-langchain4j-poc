package io.github.rodrigorjsf.agenticchat.evals.scenario;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Which committed scenarios a run pays for. Every filter that is set must select a row for it to
 * run; with none set, every row runs.
 *
 * <ul>
 *   <li>{@value #DOMAIN} — the rows of one domain, cross-domain rows included;</li>
 *   <li>{@value #IDS} — a comma-separated list of row ids;</li>
 *   <li>{@value #CHANGED_SINCE} — a git ref: the rows whose {@code dependsOn} names an artifact
 *   changed since it (diff mode).</li>
 * </ul>
 *
 * <p>A domain or an id that no row has is refused: a typo would otherwise run nothing and pass.
 *
 * @param domain           the domain filter, or {@code null}
 * @param ids              the id filter, or {@code null}
 * @param changedSince     the diff-mode ref, or {@code null}
 * @param changedArtifacts the artifacts changed since {@code changedSince}; empty outside diff mode
 */
public record ScenarioSelection(String domain, List<String> ids, String changedSince, Set<String> changedArtifacts) {

    public static final String DOMAIN = "scenario.domain";
    public static final String IDS = "scenario.ids";
    public static final String CHANGED_SINCE = "scenario.changedSince";

    public ScenarioSelection {
        ids = ids == null ? null : List.copyOf(ids);
        changedArtifacts = Collections.unmodifiableSortedSet(new TreeSet<>(changedArtifacts));
    }

    /**
     * @param property         reads a system property; {@code System::getProperty} in the eval
     * @param changedArtifacts the artifacts changed since a git ref; only called in diff mode
     */
    public static ScenarioSelection fromProperties(UnaryOperator<String> property,
                                                   Function<String, Set<String>> changedArtifacts) {
        String domain = blankToNull(property.apply(DOMAIN));
        String rawIds = blankToNull(property.apply(IDS));
        List<String> ids = rawIds == null ? null
                : Arrays.stream(rawIds.split(",")).map(String::strip).filter(id -> !id.isEmpty()).toList();
        String ref = blankToNull(property.apply(CHANGED_SINCE));
        return new ScenarioSelection(domain, ids, ref, ref == null ? Set.of() : changedArtifacts.apply(ref));
    }

    public List<Scenario> apply(List<Scenario> scenarios) {
        if (domain != null && scenarios.stream().noneMatch(s -> s.domains().contains(domain))) {
            throw new IllegalArgumentException(DOMAIN + " names '" + domain + "', which no scenario belongs to");
        }
        if (ids != null) {
            var unknown = new ArrayList<>(ids);
            unknown.removeAll(scenarios.stream().map(Scenario::id).toList());
            if (!unknown.isEmpty()) {
                throw new IllegalArgumentException(IDS + " names ids no scenario has: " + unknown);
            }
        }
        return scenarios.stream()
                .filter(s -> domain == null || s.domains().contains(domain))
                .filter(s -> ids == null || ids.contains(s.id()))
                .filter(s -> changedSince == null || s.dependsOn().stream().anyMatch(changedArtifacts::contains))
                .toList();
    }

    /** One line for the report: what this run was narrowed to. */
    public String describe() {
        var parts = new ArrayList<String>();
        if (domain != null) {
            parts.add("domain " + domain);
        }
        if (ids != null) {
            parts.add("ids " + ids);
        }
        if (changedSince != null) {
            parts.add("depends on an artifact changed since " + changedSince + ": " + changedArtifacts);
        }
        return parts.isEmpty() ? "every scenario" : String.join("; ", parts);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
