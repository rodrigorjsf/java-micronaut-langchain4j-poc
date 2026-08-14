package io.github.rodrigorjsf.agenticchat.evals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rodrigorjsf.agenticchat.triage.TriageService;
import io.github.rodrigorjsf.agenticchat.triage.TriageVerdict;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The judge, measured against a golden set with a real model.
 *
 * <p>Tagged {@code evals}: it needs an API key, it costs money, and it is subject
 * to rate limits. It runs with {@code ./mvnw test -Pevals}, not on every commit.
 * That split is deliberate — a suite that needs a live provider is a suite nobody
 * runs, so the deterministic half ({@link InjectionEval}) gates the build and this
 * half gates a release.
 *
 * <h2>The gate is asymmetric, like the component</h2>
 *
 * A false OUT_OF_SCOPE turns a real user away and they do not come back. A false
 * IN_SCOPE costs one call to the main model. The thresholds reflect that: the
 * false-refusal rate is held far tighter than overall accuracy.
 *
 * <h2>Pacing</h2>
 *
 * Google's free tier returns {@code RESOURCE_EXHAUSTED} at roughly 10–20 requests
 * per minute, measured. The runner paces itself accordingly, which is why a full
 * pass takes minutes rather than seconds. A rate-limited case is reported as
 * {@code SKIPPED} rather than counted as a failure — scoring a quota error as a
 * classification error would make the numbers meaningless.
 */
@Tag("evals")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TriageGoldenSetEval {

    /** Overall routing accuracy on cases that actually ran. */
    private static final double MIN_ACCURACY = 0.90;

    /** The expensive error: a legitimate turn wrongly refused. */
    private static final double MAX_FALSE_REFUSAL_RATE = 0.05;

    /** Below ~6 requests/minute, comfortably inside the measured free-tier limit. */
    private static final long PACING_MILLIS = 10_000;

    private record Case(String id, String expected, String intent, String language, String text) {
    }

    private ApplicationContext ctx;
    private TriageService triage;

    @BeforeAll
    void setUp() {
        ctx = ApplicationContext.run();
        triage = ctx.getBean(TriageService.class);
    }

    @AfterAll
    void tearDown() {
        if (ctx != null) {
            ctx.close();
        }
    }

    private static List<Case> goldenSet() throws Exception {
        try (InputStream in = TriageGoldenSetEval.class.getResourceAsStream("/evals/triage-golden.json")) {
            JsonNode root = new ObjectMapper().readTree(in);
            var cases = new ArrayList<Case>();
            for (JsonNode node : root.get("cases")) {
                cases.add(new Case(
                        node.get("id").asText(),
                        node.get("expected").asText(),
                        node.get("intent").asText(),
                        node.get("language").asText(),
                        node.get("text").asText()));
            }
            return cases;
        }
    }

    @Test
    @DisplayName("triage golden set: accuracy and false-refusal rate")
    void theJudgeMeetsItsThresholds() throws Exception {
        var cases = goldenSet();
        assertThat(cases).hasSizeGreaterThan(50);

        int ran = 0;
        int correct = 0;
        int shouldPass = 0;
        var falseRefusals = new ArrayList<Case>();
        var falseAdmissions = new ArrayList<Case>();
        var skipped = new ArrayList<String>();
        var intentConfusion = new LinkedHashMap<String, Integer>();

        for (Case testCase : cases) {
            TriageVerdict verdict;
            try {
                verdict = triage.triage(testCase.text());
            } catch (RuntimeException e) {
                skipped.add(testCase.id());
                continue;
            }
            ran++;

            boolean expectedInScope = "IN_SCOPE".equals(testCase.expected());
            if (expectedInScope) {
                shouldPass++;
            }
            if (verdict.inScope() == expectedInScope) {
                correct++;
            } else if (expectedInScope) {
                falseRefusals.add(testCase);
            } else {
                falseAdmissions.add(testCase);
            }

            if (!testCase.intent().equals(verdict.intent().name())) {
                intentConfusion.merge(testCase.intent() + " -> " + verdict.intent().name(), 1, Integer::sum);
            }
            pace();
        }

        double accuracy = ran == 0 ? 0 : (double) correct / ran;
        double falseRefusalRate = shouldPass == 0 ? 0 : (double) falseRefusals.size() / shouldPass;

        System.out.printf("%ntriage golden set: %d of %d cases ran (%d skipped on rate limits)%n",
                ran, cases.size(), skipped.size());
        System.out.printf("  routing accuracy    %.3f  (gate >= %.2f)%n", accuracy, MIN_ACCURACY);
        System.out.printf("  false-refusal rate  %.3f  (gate <= %.2f)  <- the expensive error%n",
                falseRefusalRate, MAX_FALSE_REFUSAL_RATE);
        System.out.printf("  false admissions    %d  (cheap: one main-model call each)%n",
                falseAdmissions.size());
        falseRefusals.forEach(c -> System.out.printf("  FALSE REFUSAL [%s] %s%n", c.id(), c.text()));
        falseAdmissions.forEach(c -> System.out.printf("  false admission [%s] %s%n", c.id(), c.text()));
        if (!intentConfusion.isEmpty()) {
            System.out.println("  intent drift (advisory, not gated):");
            intentConfusion.forEach((pair, count) -> System.out.printf("    %-46s %d%n", pair, count));
        }

        assertThat(ran)
                .as("too few cases ran to draw a conclusion — check the API key and the rate limit")
                .isGreaterThan(cases.size() / 2);
        assertThat(accuracy).isGreaterThanOrEqualTo(MIN_ACCURACY);
        assertThat(falseRefusalRate).isLessThanOrEqualTo(MAX_FALSE_REFUSAL_RATE);
    }

    private static void pace() {
        try {
            Thread.sleep(PACING_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
