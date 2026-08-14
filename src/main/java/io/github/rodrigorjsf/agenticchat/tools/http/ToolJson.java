package io.github.rodrigorjsf.agenticchat.tools.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Singleton;


/**
 * Keeps the fields a tool actually needs and drops the rest.
 *
 * <p>Byte truncation alone is not enough. A public API that returns 284 KB of
 * municipalities cut at 32 KB gives the model <em>invalid JSON</em> — a fragment
 * ending mid-object, which it will then try to interpret. Projection cuts on
 * field boundaries instead, so what reaches the model is smaller <em>and</em>
 * still well-formed.
 *
 * <p>The token argument is the same one that runs through the whole tool layer:
 * a tool result lands in the conversation and is replayed into every later prompt.
 * A company record with a 60-entry shareholder array costs the same tokens on turn
 * twenty as it did on turn one, and the model needed four fields of it.
 *
 * <p>Failures degrade to the original text rather than throwing. A projection that
 * cannot parse its input is a bug in the projection, and the tool result — however
 * fat — is still more useful to the model than an error.
 */
@Singleton
public class ToolJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Marker appended when an array was capped, so the model knows the list is partial. */
    static final String MORE = "_more";

    /**
     * Keeps only {@code paths} from a JSON object, or from every element of a JSON
     * array. Paths are dotted and may cross arrays, e.g.
     * {@code location.coordinates.latitude}.
     *
     * @return compact JSON, or the input unchanged when it cannot be parsed
     */
    public String project(String json, String... paths) {
        return projectCapped(json, Integer.MAX_VALUE, paths);
    }

    /**
     * As {@link #project}, and additionally caps a top-level array at {@code max}
     * elements, appending {@code {"_more": n}} so the model can say "and 47 others"
     * instead of believing it has the whole list.
     */
    public String projectCapped(String json, int max, String... paths) {
        if (json == null || json.isBlank()) {
            return json;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode result = root.isArray()
                    ? projectArray((ArrayNode) root, max, paths)
                    : projectObject(root, paths);
            return MAPPER.writeValueAsString(result);
        } catch (Exception cannotParse) {
            return json;
        }
    }

    /** Caps a top-level array without touching the shape of its elements. */
    public String cap(String json, int max) {
        if (json == null || json.isBlank()) {
            return json;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (!root.isArray()) {
                return json;
            }
            return MAPPER.writeValueAsString(capArray((ArrayNode) root, max, node -> node));
        } catch (Exception cannotParse) {
            return json;
        }
    }

    private JsonNode projectArray(ArrayNode array, int max, String... paths) {
        return capArray(array, max, element -> projectObject(element, paths));
    }

    private ArrayNode capArray(ArrayNode array, int max, java.util.function.UnaryOperator<JsonNode> shape) {
        var out = MAPPER.createArrayNode();
        int kept = Math.min(array.size(), Math.max(0, max));
        for (int i = 0; i < kept; i++) {
            out.add(shape.apply(array.get(i)));
        }
        if (array.size() > kept) {
            out.add(MAPPER.createObjectNode().put(MORE, array.size() - kept));
        }
        return out;
    }

    private JsonNode projectObject(JsonNode node, String... paths) {
        if (paths.length == 0 || node == null || !node.isObject()) {
            return node;
        }
        ObjectNode out = MAPPER.createObjectNode();
        for (String path : paths) {
            JsonNode value = at(node, path);
            if (value != null && !value.isMissingNode() && !value.isNull()) {
                // The leaf name, not the whole path: "location.coordinates.latitude"
                // becomes "latitude", because the model reads the field name and the
                // nesting carried no information it needed.
                out.set(leafOf(path), value);
            }
        }
        return out;
    }

    private static JsonNode at(JsonNode node, String path) {
        JsonNode current = node;
        for (String segment : path.split("\\.")) {
            if (current == null) {
                return null;
            }
            current = current.get(segment);
        }
        return current;
    }

    private static String leafOf(String path) {
        int lastDot = path.lastIndexOf('.');
        return lastDot < 0 ? path : path.substring(lastDot + 1);
    }

    /** Convenience for the common shape: project a {@link ToolResponse} in place. */
    public ToolResponse project(ToolResponse response, String... paths) {
        if (!response.isOk()) {
            return response;
        }
        return new ToolResponse(project(response.body(), paths), response.outcome(), response.truncated());
    }

    public ToolResponse projectCapped(ToolResponse response, int max, String... paths) {
        if (!response.isOk()) {
            return response;
        }
        return new ToolResponse(projectCapped(response.body(), max, paths), response.outcome(), response.truncated());
    }

    /** Convenience: cap a {@link ToolResponse}'s top-level array in place. */
    public ToolResponse cap(ToolResponse response, int max) {
        if (!response.isOk()) {
            return response;
        }
        return new ToolResponse(cap(response.body(), max), response.outcome(), response.truncated());
    }
}
