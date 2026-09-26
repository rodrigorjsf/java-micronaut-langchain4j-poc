package io.github.rodrigorjsf.agenticchat.evals.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rodrigorjsf.agenticchat.evals.scenario.ScenarioResult.CheckResult;
import io.github.rodrigorjsf.agenticchat.evals.scenario.ScenarioResult.ToolCall;
import io.github.rodrigorjsf.agenticchat.evals.scenario.ScenarioResult.Trajectory;
import io.github.rodrigorjsf.agenticchat.observability.trace.ObservationType;
import io.github.rodrigorjsf.agenticchat.testsupport.RecordingAgentTracer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Runs one scenario end to end: every turn through {@code POST /api/chat}, the trajectory read
 * back from the recording tracer, then the checks.
 *
 * <p>Plain {@code java.net.http} rather than Micronaut's client, so the request is exactly the
 * JSON a caller outside this JVM would send.
 */
public final class ScenarioRunner {

    private static final String ACTIVATE_SKILL = "activate_skill";

    /** A real turn is a triage call plus several agent round trips and tool calls. */
    private static final Duration TURN_TIMEOUT = Duration.ofSeconds(180);

    private final ObjectMapper json = new ObjectMapper();

    private final URI chatEndpoint;
    private final RecordingAgentTracer tracer;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public ScenarioRunner(URI serverUrl, RecordingAgentTracer tracer) {
        this.chatEndpoint = serverUrl.resolve("/api/chat");
        this.tracer = tracer;
    }

    public ScenarioResult run(Scenario scenario) {
        tracer.reset();
        String conversationId = null;
        String outcome = null;
        String answer = null;
        long inputTokens = 0;
        long outputTokens = 0;
        long started = System.nanoTime();
        for (String turn : scenario.turns()) {
            HttpResponse<String> response = post(conversationId, turn);
            if (response.statusCode() != 200) {
                return failedRequest(scenario, response, System.nanoTime() - started);
            }
            JsonNode body = read(response.body());
            conversationId = body.path("conversationId").asText(null);
            outcome = body.path("outcome").asText(null);
            answer = body.path("reply").asText(null);
            inputTokens += body.path("usage").path("inputTokens").asLong();
            outputTokens += body.path("usage").path("outputTokens").asLong();
        }
        var latency = Duration.ofNanos(System.nanoTime() - started);
        var trajectory = trajectory(outcome);
        var checks = TrajectoryCheck.evaluate(scenario.expect().trajectory(), trajectory);
        return new ScenarioResult(scenario, trajectory, answer, checks, latency, inputTokens, outputTokens);
    }

    private ScenarioResult failedRequest(Scenario scenario, HttpResponse<String> response, long elapsedNanos) {
        var check = new CheckResult("request", false,
                "POST /api/chat answered HTTP " + response.statusCode() + ": " + response.body());
        return new ScenarioResult(scenario, trajectory(null), null, List.of(check),
                Duration.ofNanos(elapsedNanos), 0, 0);
    }

    private Trajectory trajectory(String outcome) {
        var calls = new ArrayList<ToolCall>();
        var activations = new ArrayList<String>();
        for (var recorded : tracer.recorded()) {
            if (recorded.type() != ObservationType.TOOL) {
                continue;
            }
            var call = new ToolCall(recorded.name(), text(recorded.input()), text(recorded.output()));
            calls.add(call);
            if (ACTIVATE_SKILL.equals(call.name())) {
                activations.add(skillName(call.arguments()));
            }
        }
        return new Trajectory(outcome, activations, calls);
    }

    private String skillName(String arguments) {
        try {
            return json.readTree(arguments).path("skill_name").asText(arguments);
        } catch (IOException | RuntimeException e) {
            return arguments;
        }
    }

    private static String text(Object value) {
        return value == null ? null : Objects.toString(value);
    }

    private HttpResponse<String> post(String conversationId, String message) {
        Map<String, String> payload = new LinkedHashMap<>();
        if (conversationId != null) {
            payload.put("conversationId", conversationId);
        }
        payload.put("message", message);
        try {
            var request = HttpRequest.newBuilder(chatEndpoint)
                    .timeout(TURN_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
                    .build();
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("POST " + chatEndpoint + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for " + chatEndpoint, e);
        }
    }

    private JsonNode read(String body) {
        try {
            return json.readTree(body);
        } catch (IOException e) {
            throw new IllegalStateException("POST /api/chat did not answer JSON: " + body, e);
        }
    }
}
