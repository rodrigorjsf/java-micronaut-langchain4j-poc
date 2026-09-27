package io.github.rodrigorjsf.agenticchat.evals.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;

import java.util.ArrayList;
import java.util.List;

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
 */
public final class RubricGrader {

    /** No grader at all: every criterion is reported UNGRADED. */
    public static final RubricGrader NONE = new RubricGrader(null);

    private static final String INSTRUCTIONS = """
            You grade one answer of a chat assistant against one criterion.
            Judge only the criterion given, nothing else about the answer.
            Reply with a JSON object and nothing else:
            {"pass": true or false, "critique": "one sentence saying why"}""";

    private final ObjectMapper json = new ObjectMapper();
    private final ChatModel model;

    /** @param model the registry's {@code grader} role, never the agent's model */
    public RubricGrader(ChatModel model) {
        this.model = model;
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
        String reply;
        try {
            reply = model.chat(ChatRequest.builder()
                    .messages(SystemMessage.from(INSTRUCTIONS), UserMessage.from(prompt(criterion, turns, answer)))
                    .responseFormat(ResponseFormat.JSON)
                    .build()).aiMessage().text();
        } catch (RuntimeException e) {
            return RubricVerdict.ungraded(criterion, "the grader failed: " + e.getMessage());
        }
        return read(criterion, reply);
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
        return RubricVerdict.ungraded(criterion, "the grader did not answer a verdict: " + reply);
    }
}
