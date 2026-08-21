package io.github.rodrigorjsf.agenticchat.tools.knowledge;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scholarly tools, run for real against bodies shaped like the ones their
 * upstreams document.
 *
 * <p>This is the test the issue's first option describes, and it exists because
 * the unit tests of the two halves both pass while the pair is broken:
 * {@code ToolJsonTest} proves the projector keeps what it was told to keep, and
 * {@code GuardrailChainTest} proves the guardrail blocks a host outside the
 * catalogue. Neither notices that the field the projection was told to keep
 * <em>is</em> such a host. The failure only appears where a real body meets a real
 * projection, so that is what runs here — the same tool method, the same
 * {@code ToolJson} call, the same {@code ToolHttpClient}.
 *
 * <p>The assertions name hosts rather than calling the allow-list, on purpose.
 * Pointing {@code crossref} and {@code openalex} at the stub removes
 * {@code api.crossref.org} and {@code api.openalex.org} from the derived allow-list
 * in <em>this</em> context, so an allow-list-driven assertion here would be grading
 * a list this test just edited. {@code openalex.org} is restored explicitly below
 * for the one assertion that needs it. The allow-list's own both-directions check
 * lives in {@code ToolLinkPolicyTest}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KnowledgeResearchToolsLinkTest {

    private EmbeddedServer stub;
    private ApplicationContext context;
    private KnowledgeResearchTools tools;

    @BeforeAll
    void startStub() {
        stub = ApplicationContext.run(EmbeddedServer.class, Map.of(
                "stub.api.enabled", "true",
                "micronaut.server.port", -1));

        var base = "http://localhost:" + stub.getPort() + "/stub";
        // Only base-url is overridden: the @EachProperty entries in application.yml
        // merge into every test context, so crossref and openalex keep their real
        // timeouts and byte budgets. The Crossref budget is 16 KB and the stub body
        // is a few hundred, which matters — a body over the budget would be truncated
        // and the projection would answer TOO_MUCH_DATA instead of the fields, and
        // this test would go red for the wrong reason.
        Map<String, Object> config = new HashMap<>(Map.of(
                "agentic.tools.apis.crossref.base-url", base + "/crossref",
                "agentic.tools.apis.openalex.base-url", base + "/openalex",
                "agentic.guardrails.output.extra-allowed-link-hosts",
                java.util.List.of("gov.br", "openalex.org")));
        context = ApplicationContext.run(config);
        tools = context.getBean(KnowledgeResearchTools.class);
    }

    @AfterAll
    void stop() {
        if (context != null) {
            context.close();
        }
        if (stub != null) {
            stub.close();
        }
    }

    @Test
    @DisplayName("a Crossref record's own URL field is a doi.org address, and doi.org is not in the catalogue")
    void aSinglePaperCarriesNoLinkTheAnswerCouldNotQuote() {
        var answer = tools.get_paper_by_doi("10.1145/3292500.3330701");

        assertThat(answer)
                .as("the whole assistant turn is withheld if the answer quotes this")
                .doesNotContain("doi.org");
        assertThat(answer)
                .as("an author's ORCID is a URL on a host no tool in this catalogue calls")
                .doesNotContain("orcid.org");
        // The reverse failure the issue names: strip what the answer needed and the
        // model stops citing. The identifier itself is not a URL and must survive.
        assertThat(answer).contains("10.1145/3292500.3330701");
        assertThat(answer).contains("Applying Deep Learning to Airbnb Search");
    }

    @Test
    @DisplayName("a Crossref search keeps whole author objects, and an author object carries an ORCID URL")
    void aPaperSearchCarriesNoLinkTheAnswerCouldNotQuote() {
        var answer = tools.search_papers_crossref("applying deep learning to airbnb search", "3");

        assertThat(answer).doesNotContain("orcid.org", "doi.org");
        assertThat(answer).contains("Haldar", "10.1145/3292500.3330701");
    }

    @Test
    @DisplayName("OpenAlex answers a doi.org URL beside an openalex.org one — only the second may survive")
    void anOpenAlexSearchKeepsTheCatalogueLinkAndDropsTheOther() {
        var answer = tools.search_openalex("open access", "3");

        assertThat(answer).doesNotContain("doi.org");
        // The negative control. Removing every URL-valued field would pass the
        // assertion above and break the tool: openalex.org is in the catalogue and an
        // answer is allowed — and expected — to cite it.
        assertThat(answer).contains("https://openalex.org/W2741809807");
        assertThat(answer).contains("The state of OA", "935");
    }
}
