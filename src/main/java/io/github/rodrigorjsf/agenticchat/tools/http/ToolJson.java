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
 * <h2>Truncation runs BEFORE projection, and that ordering has teeth</h2>
 * <p>
 * {@link ToolHttpClient} caps the transport at the endpoint's byte budget, and only
 * then does a tool project. If the raw body exceeded that budget, what arrives here
 * is a fragment ending mid-object — unparseable, so the string overloads return it
 * unchanged.
 *
 * <p>That is correct for a String in isolation and catastrophic for a tool result:
 * the model would receive 32 KB of mangled JSON in place of the 131 bytes it asked
 * for, which is the exact failure this class exists to prevent. The
 * {@link ToolResponse} overloads therefore refuse: a truncated body that will not
 * parse becomes an explicit "narrow your query", never a fragment.
 *
 * <p>The other half of the fix is configuration — {@code max-response-bytes} on a
 * projecting endpoint must sit above the RAW body, because it is a transport
 * ceiling and the projection is the context ceiling.
 */
@Singleton
public class ToolJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Marker appended when an array was capped, so the model knows the list is partial.
     */
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

    /**
     * Projects the elements of an array <em>nested inside</em> an object, and caps it.
     *
     * <p>{@link #project} takes dotted paths, so it is easy to believe that naming the
     * array projects through it. It does not: a path that lands on a container keeps
     * that container <em>whole</em>. {@code project(body, "meals")} on
     * {@code {"meals":[…]}} returns every field of every meal, which is the opposite
     * of what the call reads like — and it is how third-party URLs, thumbnails and
     * six translations of an instruction sheet reach the model.
     *
     * <p>So the nested case gets its own method with the array named separately from
     * the fields:
     *
     * <pre>{@code
     * projectList(body, "meals", 3, "strMeal", "strCategory", "strInstructions")
     * // {"meals":[{"strMeal":…,"strCategory":…,"strInstructions":…}, …, {"_more":11}]}
     * }</pre>
     *
     * <p>Everything outside the named array is dropped. If the key is absent or is not
     * an array, the body comes back unchanged — a missing key is how these APIs report
     * "no result", and turning that into an empty envelope would hide it.
     */
    public String projectList(String json, String arrayField, int max, String... paths) {
        if (json == null || json.isBlank()) {
            return json;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode array = root == null ? null : root.get(arrayField);
            if (array == null || !array.isArray()) {
                return json;
            }
            ObjectNode out = MAPPER.createObjectNode();
            out.set(arrayField, projectArray((ArrayNode) array, max, paths));
            return MAPPER.writeValueAsString(out);
        } catch (Exception cannotParse) {
            return json;
        }
    }

    /**
     * Caps a top-level array without touching the shape of its elements.
     */
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

    /**
     * Told to the model when a truncated body cannot be projected. Actionable on
     * purpose: "narrow it" is something the model can actually do, unlike "error".
     */
    static final String TOO_MUCH_DATA =
            "That query returned more data than this tool can read. Narrow it — a more specific "
                    + "term, a smaller range, or fewer results — and try again.";

    /**
     * Convenience for the common shape: project a {@link ToolResponse} in place.
     */
    public ToolResponse project(ToolResponse response, String... paths) {
        return reshape(response, body -> projectCapped(body, Integer.MAX_VALUE, paths));
    }

    public ToolResponse projectCapped(ToolResponse response, int max, String... paths) {
        return reshape(response, body -> projectCapped(body, max, paths));
    }

    /**
     * Convenience: cap a {@link ToolResponse}'s top-level array in place.
     */
    public ToolResponse cap(ToolResponse response, int max) {
        return reshape(response, body -> cap(body, max));
    }

    /**
     * Convenience for the nested case: see {@link #projectList(String, String, int, String...)}.
     */
    public ToolResponse projectList(ToolResponse response, String arrayField, int max, String... paths) {
        return reshape(response, body -> projectList(body, arrayField, max, paths));
    }

    /**
     * The one place the truncated-fragment rule lives.
     *
     * <p>A body that came back truncated and still will not parse is a fragment. It
     * is never passed on: the model cannot tell a fragment from a complete answer,
     * and it will confidently interpret one.
     */
    private ToolResponse reshape(ToolResponse response, java.util.function.UnaryOperator<String> shaper) {
        if (!response.isOk()) {
            return response;
        }
        if (response.truncated() && !isParseable(response.body())) {
            return ToolResponse.failure(ToolResponse.Outcome.INVALID_REQUEST, TOO_MUCH_DATA);
        }
        return new ToolResponse(shaper.apply(response.body()), response.outcome(), response.truncated());
    }

    private static boolean isParseable(String json) {
        if (json == null || json.isBlank()) {
            return false;
        }
        try {
            MAPPER.readTree(json);
            return true;
        } catch (Exception cannotParse) {
            return false;
        }
    }
}
