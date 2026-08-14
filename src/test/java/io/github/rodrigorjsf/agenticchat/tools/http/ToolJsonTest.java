package io.github.rodrigorjsf.agenticchat.tools.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolJsonTest {

    private final ToolJson json = new ToolJson();

    @Test
    void keepsOnlyTheRequestedFields() {
        var body = """
                {"cep":"01310100","state":"SP","city":"São Paulo","service":"open-cep",
                 "timezoneName":"America/Sao_Paulo"}""";

        var projected = json.project(body, "cep", "state", "city");

        assertThat(projected).contains("01310100", "SP", "São Paulo");
        assertThat(projected).doesNotContain("open-cep", "timezoneName");
    }

    @Test
    @DisplayName("a nested path is flattened to its leaf name")
    void flattensNestedPaths() {
        var body = """
                {"cep":"01310100","location":{"type":"Point",
                 "coordinates":{"latitude":"-23.56","longitude":"-46.65"}}}""";

        var projected = json.project(body, "cep", "location.coordinates.latitude");

        assertThat(projected).contains("\"latitude\"", "-23.56");
        assertThat(projected).doesNotContain("\"type\"", "Point");
    }

    @Test
    void projectsEveryElementOfAnArray() {
        var body = """
                [{"nome":"Selic","valor":14,"extra":"drop me"},
                 {"nome":"CDI","valor":13.9,"extra":"drop me"}]""";

        var projected = json.project(body, "nome", "valor");

        assertThat(projected).contains("Selic", "CDI", "13.9");
        assertThat(projected).doesNotContain("drop me");
    }

    @Test
    @DisplayName("a capped array says how many were dropped, so the model does not claim completeness")
    void capsAnArrayAndSaysSo() {
        var body = "[{\"n\":1},{\"n\":2},{\"n\":3},{\"n\":4},{\"n\":5}]";

        var capped = json.projectCapped(body, 2, "n");

        assertThat(capped).contains("_more", "3");
        assertThat(capped).doesNotContain("\"n\":4");
    }

    @Test
    void capKeepsTheElementShape() {
        var body = "[{\"a\":1,\"b\":2},{\"a\":3,\"b\":4},{\"a\":5,\"b\":6}]";

        var capped = json.cap(body, 2);

        assertThat(capped).contains("\"b\":2");
        assertThat(capped).contains("_more");
    }

    @Test
    void missingFieldsAreOmittedRatherThanNulled() {
        var projected = json.project("{\"a\":1}", "a", "b", "c");

        assertThat(projected).isEqualTo("{\"a\":1}");
    }

    @Test
    @DisplayName("unparseable input degrades to itself rather than throwing")
    void unparseableInputIsReturnedUnchanged() {
        assertThat(json.project("not json at all", "a")).isEqualTo("not json at all");
        assertThat(json.project("", "a")).isEmpty();
        assertThat(json.project((String) null, "a")).isNull();
    }

    @Test
    @DisplayName("a truncated body that will not parse is refused, never passed on as a fragment")
    void aTruncatedFragmentIsNeverHandedToTheModel() {
        // Truncation runs BEFORE projection. A 438 KB body cut at 32 KB ends
        // mid-object, projection cannot parse it, and the string overload returns
        // the input unchanged — so without this rule the model would receive 32 KB
        // of mangled JSON in place of the 131 bytes it asked for.
        var fat = new StringBuilder("[");
        for (int i = 0; i < 3_000; i++) {
            fat.append(i == 0 ? "" : ",")
               .append("{\"id\":").append(i).append(",\"nome\":\"Municipio ").append(i)
               .append("\",\"noise\":\"").append("y".repeat(100)).append("\"}");
        }
        fat.append("]");
        var cut = ToolResponse.ok(fat.substring(0, 32_768), true);

        var result = json.projectCapped(cut, 50, "nome");

        assertThat(result.isOk()).isFalse();
        assertThat(result.outcome()).isEqualTo(ToolResponse.Outcome.INVALID_REQUEST);
        assertThat(result.toModelText()).contains("Narrow it");
        assertThat(result.body()).doesNotContain("Municipio 0");
    }

    @Test
    @DisplayName("a complete body that happens to be marked truncated still projects")
    void aParseableTruncatedBodyIsStillProjected() {
        // Truncation on a code-point boundary can leave valid JSON. Refusing that
        // would throw away a usable answer.
        var response = ToolResponse.ok("[{\"nome\":\"a\",\"noise\":\"x\"}]", true);

        var result = json.projectCapped(response, 5, "nome");

        assertThat(result.isOk()).isTrue();
        assertThat(result.body()).contains("nome").doesNotContain("noise");
    }

    @Test
    void aFailedToolResponseIsLeftAlone() {
        var failure = ToolResponse.failure(ToolResponse.Outcome.NOT_FOUND, "nothing here");

        assertThat(json.project(failure, "a")).isSameAs(failure);
        assertThat(json.cap(failure, 3)).isSameAs(failure);
    }

    @Test
    @DisplayName("projection is what keeps a fat response well-formed")
    void projectionShrinksAFatResponseWithoutBreakingIt() {
        var fat = new StringBuilder("[");
        for (int i = 0; i < 2_000; i++) {
            fat.append(i == 0 ? "" : ",")
               .append("{\"id\":").append(i)
               .append(",\"nome\":\"Municipio ").append(i)
               .append("\",\"microrregiao\":{\"id\":1,\"nome\":\"x\"},\"noise\":\"")
               .append("y".repeat(100)).append("\"}");
        }
        fat.append("]");

        var projected = json.projectCapped(fat.toString(), 50, "nome");

        assertThat(projected.length()).isLessThan(fat.length() / 50);
        assertThat(projected).contains("_more", "1950");
        // Still valid JSON — which byte truncation would not have been.
        assertThat(projected).startsWith("[").endsWith("]");
    }
}
