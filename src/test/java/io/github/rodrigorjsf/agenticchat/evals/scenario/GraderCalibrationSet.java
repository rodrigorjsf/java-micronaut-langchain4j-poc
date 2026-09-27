package io.github.rodrigorjsf.agenticchat.evals.scenario;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The human-labelled answers the Grader is measured against: for each rubric criterion, answers a
 * person has already marked as meeting it or not.
 *
 * <p>Every example sits in one split. {@link Split#TRAIN} rows are the only ones the Grader may see
 * as few-shot examples; {@link Split#DEV} rows are for tuning its prompt; {@link Split#TEST} rows
 * are held back for the number that is reported. A row the Grader has seen as an example cannot
 * also measure it.
 *
 * <p>Sizing: a criterion's TPR and TNR mean something only once it has at least
 * {@value #MIN_ROWS_PER_CRITERION} labelled rows and both labels in the dev and in the test split —
 * with no FAIL row, TNR is undefined however good the Grader is. {@link #shortfalls} names what is
 * missing; until nothing is, the rubric stays report-only.
 *
 * @param criteria one entry per rubric criterion, worded exactly as the scenario rows word it
 */
public record GraderCalibrationSet(List<Criterion> criteria) {

    static final String COMMITTED = "/evals/grader-calibration.json";

    /** Labelled rows per criterion (one rubric criterion is one failure mode) before its rates count. */
    public static final int MIN_ROWS_PER_CRITERION = 60;

    public GraderCalibrationSet {
        criteria = criteria == null ? List.of() : List.copyOf(criteria);
    }

    public static GraderCalibrationSet loadCommitted() {
        try (var in = GraderCalibrationSet.class.getResourceAsStream(COMMITTED)) {
            if (in == null) {
                throw new IllegalStateException("No grader calibration set on the test classpath: " + COMMITTED);
            }
            return load(in, COMMITTED);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + COMMITTED, e);
        }
    }

    /**
     * Loads and validates the set. The first invalid row fails the whole load, naming the file, the
     * row and the rule it breaks: a row that loads is a row the measurement can trust.
     */
    public static GraderCalibrationSet load(InputStream in, String name) {
        GraderCalibrationSet set;
        try {
            set = new ObjectMapper().readValue(in, GraderCalibrationSet.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid grader calibration set " + name + ": " + e.getOriginalMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + name, e);
        }
        var criteria = new HashSet<String>();
        var ids = new HashSet<String>();
        for (var criterion : set.criteria()) {
            if (criterion.criterion() == null || criterion.criterion().isBlank()) {
                throw new IllegalArgumentException("Invalid grader calibration set " + name + ": a criterion is blank");
            }
            if (!criteria.add(criterion.criterion())) {
                throw new IllegalArgumentException("Invalid grader calibration set " + name + ", criterion '"
                        + criterion.criterion() + "': listed more than once");
            }
            for (int i = 0; i < criterion.examples().size(); i++) {
                var example = criterion.examples().get(i);
                var problem = problemWith(example, ids);
                if (problem != null) {
                    var row = example.id() == null || example.id().isBlank()
                            ? "row " + (i + 1) : "row '" + example.id() + "'";
                    throw new IllegalArgumentException("Invalid grader calibration set " + name + ", criterion '"
                            + criterion.criterion() + "', " + row + ": " + problem);
                }
                ids.add(example.id());
            }
        }
        return set;
    }

    private static String problemWith(Example example, Set<String> earlierIds) {
        if (example.id() == null || example.id().isBlank()) {
            return "id is missing";
        }
        if (earlierIds.contains(example.id())) {
            return "id is already used by an earlier row";
        }
        if (example.split() == null) {
            return "split is missing (train, dev or test)";
        }
        if (example.turns().isEmpty()) {
            return "turns is missing or empty";
        }
        if (example.answer() == null || example.answer().isBlank()) {
            return "answer is missing";
        }
        if (example.pass() == null) {
            return "pass (the human label) is missing";
        }
        if (example.labelledBy() == null || example.labelledBy().isBlank()) {
            return "labelledBy is missing: every label names the person who gave it";
        }
        return null;
    }

    /**
     * What each of {@code rubricCriteria} still lacks before its rates count, in the order given:
     * too few rows, or a label missing from the dev or the test split. A criterion the set does not
     * list at all is short by the whole floor. Empty when every criterion is sized.
     */
    public List<String> shortfalls(Collection<String> rubricCriteria) {
        Map<String, Criterion> byName = criteria.stream()
                .collect(Collectors.toMap(Criterion::criterion, Function.identity()));
        var shortfalls = new ArrayList<String>();
        for (var name : rubricCriteria) {
            var criterion = byName.getOrDefault(name, new Criterion(name, List.of()));
            int rows = criterion.examples().size();
            if (rows < MIN_ROWS_PER_CRITERION) {
                shortfalls.add(name + ": " + rows + " labelled rows, " + (MIN_ROWS_PER_CRITERION - rows)
                        + " short of " + MIN_ROWS_PER_CRITERION);
            }
            if (rows == 0) {
                continue;
            }
            for (var split : List.of(Split.DEV, Split.TEST)) {
                var rowsInSplit = criterion.in(split);
                for (boolean label : List.of(true, false)) {
                    if (rowsInSplit.stream().noneMatch(example -> example.pass() == label)) {
                        shortfalls.add(name + ": no " + (label ? "PASS" : "FAIL") + "-labelled row in " + split.json());
                    }
                }
            }
        }
        return List.copyOf(shortfalls);
    }

    public enum Split {
        TRAIN, DEV, TEST;

        @JsonValue
        String json() {
            return name().toLowerCase(Locale.ROOT);
        }

        @JsonCreator
        static Split fromJson(String value) {
            for (Split split : values()) {
                if (split.json().equals(value)) {
                    return split;
                }
            }
            throw new IllegalArgumentException("unknown split '" + value + "'; known splits: train, dev, test");
        }
    }

    /**
     * One rubric criterion and the labelled answers for it.
     *
     * @param criterion the rubric line, as a scenario row writes it
     * @param examples  the labelled answers
     */
    public record Criterion(String criterion, List<Example> examples) {

        public Criterion {
            examples = examples == null ? List.of() : List.copyOf(examples);
        }

        public List<Example> in(Split split) {
            return examples.stream().filter(example -> example.split() == split).toList();
        }
    }

    /**
     * One answer a person labelled against one criterion.
     *
     * @param id         stable identifier, unique across the set
     * @param split      which split the row belongs to
     * @param turns      the user messages the answer replied to, in order
     * @param answer     the assistant's final answer
     * @param pass       the human label: does the answer meet the criterion
     * @param critique   the labeller's one-sentence reason; shown to the Grader for train rows
     * @param labelledBy who labelled the row — a person, never a model
     */
    public record Example(String id, Split split, List<String> turns, String answer, Boolean pass,
                          String critique, String labelledBy) {

        public Example {
            turns = turns == null ? List.of() : List.copyOf(turns);
            critique = critique == null ? "" : critique;
        }
    }
}
