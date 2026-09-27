package io.github.rodrigorjsf.agenticchat.evals.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The last layer of checks: the {@code grader} model scores each rubric criterion of a scenario,
 * one call per criterion, pass or fail with a short critique.
 *
 * <p>Report-only. Nothing has measured this Grader against human labels, so a verdict is an
 * opinion, not a measurement: it is shown in the report labelled uncalibrated and never becomes a
 * check. A Grader that errors or answers something other than a verdict leaves the criterion
 * UNGRADED rather than failed, and never throws.
 *
 * <p>One call per criterion rather than one per rubric, so each verdict is about one thing and a
 * long rubric cannot blur them together.
 *
 * <p>Built {@link #withFewShot with a calibration set}, the Grader is shown that criterion's
 * human-labelled train rows as worked examples before the answer it grades. Only train rows: the
 * dev and test rows exist to measure it, and a row it has seen cannot.
 */
public final class RubricGrader {

    /** No grader at all: every criterion is reported UNGRADED. */
    public static final RubricGrader NONE = new RubricGrader(null);

    private static final String INSTRUCTIONS = """
            You grade one answer of a chat assistant against one criterion.
            Judge only the criterion given, nothing else about the answer.
            Reply with a JSON object and nothing else:
            {"pass": true or false, "critique": "one sentence saying why"}""";

    /** How much of an unreadable reply the report echoes, so a chatty grader cannot bloat it. */
    private static final int MAX_ECHOED_REPLY = 200;

    private final ObjectMapper json = new ObjectMapper();
    private final ChatModel model;
    private final Map<String, List<GraderCalibrationSet.Example>> examplesByCriterion;

    /** A zero-shot grader. @param model the registry's {@code grader} role, never the agent's model */
    public RubricGrader(ChatModel model) {
        this(model, Map.of());
    }

    private RubricGrader(ChatModel model, Map<String, List<GraderCalibrationSet.Example>> examplesByCriterion) {
        this.model = model;
        this.examplesByCriterion = examplesByCriterion;
    }

    /**
     * A grader shown, for each criterion, the {@link GraderCalibrationSet.Split#TRAIN train} rows the
     * calibration set labels for it. A criterion with no train rows is graded zero-shot.
     */
    public static RubricGrader withFewShot(ChatModel model, GraderCalibrationSet calibration) {
        return new RubricGrader(model, trainExamples(calibration));
    }

    /**
     * A digest of everything the Grader is told besides the answer it grades: its instructions and
     * every train example. A calibration measured under one fingerprint says nothing about a grader
     * prompted under another. Dev and test rows are not part of the prompt, so they do not move it.
     */
    public static String promptFingerprint(GraderCalibrationSet calibration) {
        var text = new StringBuilder(INSTRUCTIONS);
        trainExamples(calibration).forEach((criterion, examples) -> {
            for (var example : examples) {
                fewShot(criterion, example).forEach(message -> text.append('\u0000').append(message));
            }
        });
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JVM ships SHA-256", e);
        }
    }

    private static Map<String, List<GraderCalibrationSet.Example>> trainExamples(GraderCalibrationSet calibration) {
        var byCriterion = new LinkedHashMap<String, List<GraderCalibrationSet.Example>>();
        for (var criterion : calibration.criteria()) {
            var train = criterion.in(GraderCalibrationSet.Split.TRAIN);
            if (!train.isEmpty()) {
                byCriterion.put(criterion.criterion(), train);
            }
        }
        return Map.copyOf(byCriterion);
    }

    public List<RubricVerdict> grade(List<String> criteria, List<String> turns, String answer) {
        var verdicts = new ArrayList<RubricVerdict>(criteria.size());
        for (String criterion : criteria) {
            verdicts.add(gradeOne(criterion, turns, answer));
        }
        return verdicts;
    }

    private RubricVerdict gradeOne(String criterion, List<String> turns, String answer) {
        if (model == null) {
            return RubricVerdict.ungraded(criterion, "no grader configured for this run");
        }
        if (answer == null) {
            return RubricVerdict.ungraded(criterion, "there is no answer to grade");
        }
        var messages = new ArrayList<ChatMessage>();
        messages.add(SystemMessage.from(INSTRUCTIONS));
        for (var example : examplesByCriterion.getOrDefault(criterion, List.of())) {
            messages.addAll(fewShot(criterion, example));
        }
        messages.add(UserMessage.from(prompt(criterion, turns, answer)));
        String reply;
        try {
            reply = model.chat(ChatRequest.builder()
                    .messages(messages)
                    .responseFormat(ResponseFormat.JSON)
                    .build()).aiMessage().text();
        } catch (RuntimeException e) {
            // The type only: a provider exception's message can carry URLs and response bodies,
            // and the report is a file people share.
            return RubricVerdict.ungraded(criterion, "the grader failed: " + e.getClass().getSimpleName());
        }
        return read(criterion, reply);
    }

    /** A labelled row as one earlier exchange: the question the Grader is asked, and the human's verdict. */
    private static List<ChatMessage> fewShot(String criterion, GraderCalibrationSet.Example example) {
        var verdict = JsonNodeFactory.instance.objectNode()
                .put("pass", example.pass())
                .put("critique", example.critique());
        return List.of(UserMessage.from(prompt(criterion, example.turns(), example.answer())),
                AiMessage.from(verdict.toString()));
    }

    private static String prompt(String criterion, List<String> turns, String answer) {
        var text = new StringBuilder("Criterion:\n").append(criterion).append("\n\nThe user wrote, in order:\n");
        for (int i = 0; i < turns.size(); i++) {
            text.append(i + 1).append(". ").append(turns.get(i)).append('\n');
        }
        return text.append("\nThe assistant's final answer:\n").append(answer).toString();
    }

    private RubricVerdict read(String criterion, String reply) {
        try {
            JsonNode verdict = json.readTree(reply);
            JsonNode pass = verdict.path("pass");
            if (pass.isBoolean()) {
                return new RubricVerdict(criterion,
                        pass.booleanValue() ? RubricVerdict.Verdict.PASS : RubricVerdict.Verdict.FAIL,
                        verdict.path("critique").asText(""));
            }
        } catch (Exception e) {
            // falls through: an unreadable reply is reported, not thrown
        }
        return RubricVerdict.ungraded(criterion, "the grader did not answer a verdict: " + excerpt(reply));
    }

    private static String excerpt(String reply) {
        if (reply == null) {
            return "(empty)";
        }
        return reply.length() <= MAX_ECHOED_REPLY ? reply : reply.substring(0, MAX_ECHOED_REPLY) + "…";
    }
}
