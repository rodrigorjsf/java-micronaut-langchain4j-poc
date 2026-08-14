package io.github.rodrigorjsf.agenticchat.tools.knowledge;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.github.rodrigorjsf.agenticchat.skills.SkillTools;
import io.github.rodrigorjsf.agenticchat.tools.http.ToolHttpClient;
import io.github.rodrigorjsf.agenticchat.tools.http.ToolJson;
import jakarta.inject.Singleton;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;

/**
 * Encyclopedic and scholarly lookup, disclosed by the {@code knowledge-and-research}
 * skill.
 *
 * <p>Two families of tool live here, and the split matters when choosing between
 * them. Wikipedia and Wikidata answer <em>what is this thing</em> — prose for a
 * person, a place or a concept, and machine-readable identifiers for the same. The
 * literature tools answer <em>who has published on this</em>, and their results are
 * citations, not facts: a paper title in a result set is evidence that the paper
 * exists, never evidence that its claim is true.
 *
 * <p>Three details of this class are not stylistic:
 *
 * <ul>
 *   <li><b>The Wikipedia language is a catalogue key, not a hostname fragment.</b>
 *       Wikipedia's per-language editions live on different hosts, so a naive
 *       implementation would build {@code https://<lang>.wikipedia.org} from a model
 *       argument — a parameter that decides where a request goes, which is exactly
 *       the SSRF shape the tool boundary exists to remove. The language is instead
 *       matched against a closed set and mapped to one of two configured catalogue
 *       entries; an unknown language reaches no host at all.</li>
 *   <li><b>Article titles are percent-encoded before they touch a path.</b> Titles
 *       carry spaces, accents and parentheses, and an unencoded one makes URI
 *       parsing throw before the request is built — an exception escaping a tool,
 *       which is the one thing this layer must never do.</li>
 *   <li><b>The Wikimedia hosts, Crossref included, need an identifying User-Agent</b>
 *       and answer {@code 403} without one. That header is configuration, on the
 *       catalogue entry, so it cannot be forgotten per call.</li>
 * </ul>
 *
 * <p>Two catalogued endpoints are deliberately absent. The Wikidata SPARQL endpoint
 * would take a query language string straight from the model and forward it to a
 * public service — an injection surface with no useful validation short of writing a
 * SPARQL parser. arXiv answers Atom XML rather than JSON, which this layer's
 * projector cannot narrow, leaving ~9 KB of unprojectable markup per call; Crossref
 * and OpenAlex both index preprints anyway.
 */
@Singleton
public class KnowledgeResearchTools implements SkillTools {

    private static final String WIKIPEDIA_PT = "wikipedia-pt";
    private static final String WIKIPEDIA_EN = "wikipedia-en";
    private static final String WIKIDATA = "wikidata";
    private static final String CROSSREF = "crossref";
    private static final String OPENALEX = "openalex";
    private static final String NCBI = "ncbi-eutils";

    /** The MediaWiki action API, shared by every Wikipedia and Wikidata tool below. */
    private static final String MEDIAWIKI_API = "/w/api.php";

    /**
     * Crossref, OpenAlex and NCBI all run a faster, more forgiving "polite pool" for
     * callers who identify themselves. It costs one query parameter and buys
     * measurably better availability under load.
     */
    private static final String CONTACT_EMAIL = "rodrigo_rjsf@hotmail.com";
    private static final String TOOL_NAME = "micronaut-l4j-poc";

    /**
     * Crossref has no {@code select} on the single-work route, so a record arrives
     * whole — reference lists included — and only then is projected. Asking for more
     * than a handful of works at once is what makes the search route expensive.
     */
    private static final int MAX_PAPERS = 3;
    private static final int MAX_WIKI_RESULTS = 5;

    private final ToolHttpClient http;
    private final ToolJson json;

    public KnowledgeResearchTools(ToolHttpClient http, ToolJson json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public String skillName() {
        return "knowledge-and-research";
    }

    // ------------------------------------------------------------------
    // Wikipedia
    // ------------------------------------------------------------------

    @Tool("""
            Read the opening summary of a Wikipedia article: what the subject is, in \
            a paragraph or two, plus the link to the full article. Use for "who is", \
            "what is" and "tell me about" questions. Requires the exact article \
            title — run wikipedia_search or wikipedia_autocomplete first if you are \
            guessing at one.""")
    public String wikipedia_summary(
            @P("The exact article title, e.g. 'Machado de Assis'. Spaces and accents are fine; a wrong title returns nothing.")
            String title,
            @P("Which Wikipedia to read: 'pt' for Portuguese or 'en' for English. Defaults to 'pt'.")
            String language) {
        String api = wikipediaApi(language);
        if (api == null) {
            return unsupportedLanguage(language);
        }
        if (title == null || title.isBlank()) {
            return "Invalid argument: an article title is required.";
        }
        if (title.length() > 180) {
            return "Invalid argument: that is too long to be an article title. Use wikipedia_search "
                    + "with the user's phrasing instead.";
        }
        // thumbnail, originalimage and coordinates are dropped: three blocks of image
        // metadata that no textual answer quotes, on an endpoint that answers ~3 KB.
        return json.project(http.get(api, "/api/rest_v1/page/summary/" + segment(title.strip())),
                "title", "description", "extract", "content_urls.desktop.page").toModelText();
    }

    @Tool("""
            Search Wikipedia's full text and get back candidate article titles with a \
            matching snippet from each. Use when you know what the user means but not \
            which article covers it, or when wikipedia_summary found nothing. The \
            snippets are search context, not an answer — read the winning article \
            with wikipedia_summary.""")
    public String wikipedia_search(
            @P("What to search for, in the article's language, e.g. 'literatura brasileira século XIX'.")
            String query,
            @P("Which Wikipedia to search: 'pt' for Portuguese or 'en' for English. Defaults to 'pt'.")
            String language,
            @P("How many candidate titles to return, 1 to 5. Defaults to 3.")
            String limit) {
        String api = wikipediaApi(language);
        if (api == null) {
            return unsupportedLanguage(language);
        }
        if (query == null || query.isBlank()) {
            return "Invalid argument: something to search for is required.";
        }
        var params = new LinkedHashMap<String, String>();
        params.put("action", "query");
        params.put("list", "search");
        params.put("srsearch", query.strip());
        // Server-side field selection: without it every hit also carries its byte
        // size, word count and last-edit timestamp.
        params.put("srprop", "snippet");
        params.put("srlimit", Integer.toString(clamp(limit, 1, MAX_WIKI_RESULTS, 3)));
        params.put("format", "json");
        return json.project(http.get(api, MEDIAWIKI_API, params), "query.search").toModelText();
    }

    @Tool("""
            Turn a partly-typed or misspelled name into real Wikipedia article \
            titles. Use to pin down the exact title before calling \
            wikipedia_summary, or when the user's spelling of a person or place \
            looks uncertain. Returns titles only, with no description of any of \
            them.""")
    public String wikipedia_autocomplete(
            @P("The beginning of the title, e.g. 'micronau' or 'Guimaraes R'.")
            String prefix,
            @P("Which Wikipedia to query: 'pt' for Portuguese or 'en' for English. Defaults to 'pt'.")
            String language,
            @P("How many suggestions to return, 1 to 5. Defaults to 5.")
            String limit) {
        String api = wikipediaApi(language);
        if (api == null) {
            return unsupportedLanguage(language);
        }
        if (prefix == null || prefix.isBlank()) {
            return "Invalid argument: a title prefix is required.";
        }
        var params = new LinkedHashMap<String, String>();
        params.put("action", "opensearch");
        params.put("search", prefix.strip());
        params.put("limit", Integer.toString(clamp(limit, 1, MAX_WIKI_RESULTS, MAX_WIKI_RESULTS)));
        // Main namespace only, so the suggestions are articles rather than talk pages
        // and templates.
        params.put("namespace", "0");
        params.put("format", "json");
        // Returned whole, unprojected: OpenSearch answers a four-element array whose
        // shape is positional, and projecting or capping it would destroy the
        // envelope the titles live in. It is ~360 bytes.
        return http.get(api, MEDIAWIKI_API, params).toModelText();
    }

    @Tool("""
            Find Wikipedia articles about things physically near a point: monuments, \
            neighborhoods, buildings, parks. Use for "what is around here" or "what \
            is worth seeing near this address" questions. Requires coordinates — get \
            them from find_place or an address lookup in the geo-and-weather \
            skill.""")
    public String wikipedia_nearby(
            @P("Latitude in decimal degrees, -90 to 90, e.g. -23.5613.")
            String latitude,
            @P("Longitude in decimal degrees, -180 to 180, e.g. -46.6565.")
            String longitude,
            @P("Search radius in metres, 10 to 10000. Defaults to 1000.")
            String radiusMetres,
            @P("Which Wikipedia to search: 'pt' for Portuguese or 'en' for English. Defaults to 'pt'.")
            String language) {
        String api = wikipediaApi(language);
        if (api == null) {
            return unsupportedLanguage(language);
        }
        Double lat = parseCoordinate(latitude, 90);
        Double lon = parseCoordinate(longitude, 180);
        if (lat == null || lon == null) {
            return "Invalid argument: latitude must be between -90 and 90 and longitude between "
                    + "-180 and 180, got '" + latitude + "' and '" + longitude + "'. Use find_place "
                    + "in the geo-and-weather skill to get coordinates for a place name.";
        }
        var params = new LinkedHashMap<String, String>();
        params.put("action", "query");
        params.put("list", "geosearch");
        params.put("gscoord", lat + "|" + lon);
        params.put("gsradius", Integer.toString(clamp(radiusMetres, 10, 10000, 1000)));
        params.put("gslimit", Integer.toString(MAX_WIKI_RESULTS));
        params.put("format", "json");
        return json.project(http.get(api, MEDIAWIKI_API, params), "query.geosearch").toModelText();
    }

    // ------------------------------------------------------------------
    // Wikidata
    // ------------------------------------------------------------------

    @Tool("""
            Find the Wikidata identifier (a Q-number) for a person, place, company or \
            concept, with a one-line description of each candidate. Use to \
            disambiguate a name — several things share one — and to get the Q-number \
            that wikidata_entity_labels needs.""")
    public String wikidata_search_entity(
            @P("The name to look up, e.g. 'Brasil' or 'Ada Lovelace'.")
            String name,
            @P("Language for the labels and descriptions: 'pt' or 'en'. Defaults to 'pt'.")
            String language,
            @P("How many candidates to return, 1 to 5. Defaults to 3.")
            String limit) {
        if (name == null || name.isBlank()) {
            return "Invalid argument: a name to search for is required.";
        }
        String lang = normalisedLanguage(language);
        if (lang == null) {
            return unsupportedLanguage(language);
        }
        var params = new LinkedHashMap<String, String>();
        params.put("action", "wbsearchentities");
        params.put("search", name.strip());
        params.put("language", lang);
        params.put("uselang", lang);
        params.put("limit", Integer.toString(clamp(limit, 1, MAX_WIKI_RESULTS, 3)));
        params.put("format", "json");
        return json.project(http.get(WIKIDATA, MEDIAWIKI_API, params), "search").toModelText();
    }

    @Tool("""
            Read the official label and one-line description of a Wikidata item in \
            both Portuguese and English. Use to confirm that a Q-number is the thing \
            you think it is, or to get a subject's canonical name in the other \
            language. Requires a Q-number — use wikidata_search_entity first.""")
    public String wikidata_entity_labels(
            @P("The Wikidata item id, a Q followed by digits, e.g. Q155 for Brazil.")
            String entityId) {
        String id = entityId == null ? "" : entityId.strip().toUpperCase(Locale.ROOT);
        if (!id.matches("Q\\d{1,12}")) {
            return "Invalid argument: a Wikidata item id looks like Q155, got '" + entityId
                    + "'. Use wikidata_search_entity to find it.";
        }
        var params = new LinkedHashMap<String, String>();
        params.put("action", "wbgetentities");
        params.put("ids", id);
        // The whole item is over half a megabyte of statements and sitelinks. Asking
        // for two properties in two languages turns that into a few hundred bytes,
        // which is why there is no tool here that fetches an entity in full.
        params.put("props", "labels|descriptions");
        params.put("languages", "pt|en");
        params.put("format", "json");
        return http.get(WIKIDATA, MEDIAWIKI_API, params).toModelText();
    }

    // ------------------------------------------------------------------
    // Scholarly literature
    // ------------------------------------------------------------------

    @Tool("""
            Find published academic papers by title, topic or author and get their \
            DOI, authors, journal and year. Use whenever the user asks for papers, \
            studies, articles or a citation for a claim. Covers every discipline; \
            search_openalex is the alternative when you also want how often each \
            paper has been cited.""")
    public String search_papers_crossref(
            @P("What to search for: a title, a topic or an author, e.g. 'attention is all you need'.")
            String query,
            @P("How many papers to return, 1 to 3. Defaults to 3.")
            String rows) {
        if (query == null || query.isBlank()) {
            return "Invalid argument: something to search for is required — a paper title, a topic "
                    + "or an author name.";
        }
        var params = new LinkedHashMap<String, String>();
        params.put("query.bibliographic", query.strip());
        params.put("rows", Integer.toString(clamp(rows, 1, MAX_PAPERS, MAX_PAPERS)));
        // Server-side projection. Without it a Crossref work carries its full
        // reference list, licence terms and funder records.
        params.put("select", "DOI,title,author,issued,container-title");
        params.put("mailto", CONTACT_EMAIL);
        return json.project(http.get(CROSSREF, "/works", params), "message.items").toModelText();
    }

    @Tool("""
            Get the full record for one paper from its DOI: title, authors, journal, \
            year, abstract when the publisher deposited one, and how many later works \
            cite it. Use when the user gives a DOI, or after search_papers_crossref \
            to read one result properly.""")
    public String get_paper_by_doi(
            @P("The DOI, with or without a leading https://doi.org/, e.g. 10.1145/3292500.3330701.")
            String doi) {
        String clean = normaliseDoi(doi);
        if (clean == null) {
            return "Invalid argument: a DOI starts with '10.' followed by a slash and a suffix, "
                    + "e.g. 10.1145/3292500.3330701, got '" + doi + "'.";
        }
        // No select parameter exists on this route, so the whole record arrives —
        // reference lists included — and is narrowed here.
        return json.project(http.get(CROSSREF, "/works/" + clean),
                "message.title", "message.author", "message.issued", "message.container-title",
                "message.publisher", "message.abstract", "message.is-referenced-by-count",
                "message.URL").toModelText();
    }

    @Tool("""
            Search the open scholarly index for papers on a topic and get, for each, \
            how many times it has been cited. Use when the user wants the most \
            influential or most cited work on something, rather than the closest \
            title match that search_papers_crossref returns.""")
    public String search_openalex(
            @P("The topic to search for, e.g. 'retrieval augmented generation'.")
            String query,
            @P("How many papers to return, 1 to 3. Defaults to 3.")
            String perPage) {
        if (query == null || query.isBlank()) {
            return "Invalid argument: a topic to search for is required.";
        }
        var params = new LinkedHashMap<String, String>();
        params.put("search", query.strip());
        params.put("per-page", Integer.toString(clamp(perPage, 1, MAX_PAPERS, MAX_PAPERS)));
        // Mandatory, not an optimisation: the unprojected answer for two works is
        // ~31 KB, most of it concept scores and per-year citation histograms.
        params.put("select", "id,title,publication_year,cited_by_count,doi");
        params.put("mailto", CONTACT_EMAIL);
        return json.project(http.get(OPENALEX, "/works", params), "results").toModelText();
    }

    @Tool("""
            Search PubMed for biomedical and clinical literature and get back the \
            PubMed IDs of the best matches. Use for questions about medicine, \
            disease, drugs, public health or biology, where PubMed indexes far more \
            than the general scholarly tools. Returns identifiers only — read them \
            with get_pubmed_summaries.""")
    public String search_pubmed(
            @P("The biomedical query, in English, e.g. 'dengue vaccine efficacy brazil'.")
            String query,
            @P("How many PubMed IDs to return, 1 to 5. Defaults to 3.")
            String limit) {
        if (query == null || query.isBlank()) {
            return "Invalid argument: a biomedical topic to search for is required.";
        }
        var params = new LinkedHashMap<String, String>();
        params.put("db", "pubmed");
        params.put("term", query.strip());
        params.put("retmax", Integer.toString(clamp(limit, 1, MAX_WIKI_RESULTS, 3)));
        params.put("retmode", "json");
        params.put("sort", "relevance");
        params.put("tool", TOOL_NAME);
        params.put("email", CONTACT_EMAIL);
        // translationstack and querytranslation explain how the query was expanded —
        // useful to a librarian, noise to an answer.
        return json.project(http.get(NCBI, "/esearch.fcgi", params),
                "esearchresult.count", "esearchresult.idlist").toModelText();
    }

    @Tool("""
            Turn one PubMed ID into a readable reference: article title, journal, \
            publication date and first author. Use after search_pubmed, on the most \
            promising ids only. Takes a single id per call — resolve two or three of \
            them rather than the whole result list.""")
    public String get_pubmed_summaries(
            @P("A single PubMed ID, digits only, e.g. 39000000.")
            String pubmedId) {
        String id = pubmedId == null ? "" : pubmedId.strip();
        if (!id.matches("\\d{1,9}")) {
            return "Invalid argument: a PubMed ID is digits only, e.g. 39000000, got '" + pubmedId
                    + "'. Pass one id at a time, taken from search_pubmed.";
        }
        var params = new LinkedHashMap<String, String>();
        params.put("db", "pubmed");
        params.put("id", id);
        params.put("retmode", "json");
        params.put("tool", TOOL_NAME);
        params.put("email", CONTACT_EMAIL);
        // The record is keyed by the id itself, which is why this tool takes one id
        // and not a list: the projection paths have to name the key, and two records
        // would collide on the same leaf names. It also bounds the result — a paper
        // with forty authors carries all forty in its unprojected summary.
        return json.project(http.get(NCBI, "/esummary.fcgi", params),
                "result." + id + ".title",
                "result." + id + ".fulljournalname",
                "result." + id + ".pubdate",
                "result." + id + ".sortfirstauthor",
                "result." + id + ".elocationid").toModelText();
    }

    // ------------------------------------------------------------------

    /**
     * Maps a language to a catalogue entry through a closed switch, never by building
     * the key from the argument. A concatenated key would let a model argument steer
     * which host is chosen, and the point of the catalogue is that it cannot.
     */
    private static String wikipediaApi(String language) {
        String lang = normalisedLanguage(language);
        if (lang == null) {
            return null;
        }
        return switch (lang) {
            case "en" -> WIKIPEDIA_EN;
            default -> WIKIPEDIA_PT;
        };
    }

    private static String normalisedLanguage(String language) {
        if (language == null || language.isBlank()) {
            return "pt";
        }
        String lang = language.strip().toLowerCase(Locale.ROOT);
        return "pt".equals(lang) || "en".equals(lang) ? lang : null;
    }

    private static String unsupportedLanguage(String language) {
        return "Invalid argument: only 'pt' and 'en' are configured, got '" + language
                + "'. Ask in one of those two, and translate the answer for the user if needed.";
    }

    /**
     * Accepts the shapes a user actually pastes — a bare DOI, a doi.org URL, a
     * {@code doi:} prefix — and rejects anything outside a conservative character
     * set. The allow-list is load-bearing rather than cosmetic: the DOI becomes a
     * path segment, and a stray space or bracket there makes URI parsing throw
     * before the request is built.
     */
    private static String normaliseDoi(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String clean = raw.strip();
        int marker = clean.indexOf("10.");
        if (marker < 0) {
            return null;
        }
        clean = clean.substring(marker);
        // Slashes are left as slashes: Crossref resolves the DOI's own path structure,
        // and percent-encoding them is not reliably accepted there.
        return clean.matches("10\\.\\d{4,9}/[A-Za-z0-9._;()/:+-]{1,180}") ? clean : null;
    }

    /**
     * Percent-encodes one path segment. {@code URLEncoder} is form encoding, where a
     * space becomes {@code +}; inside a path that is a literal plus sign, not a
     * space, so it is rewritten.
     */
    private static String segment(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static Double parseCoordinate(String raw, double bound) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            double value = Double.parseDouble(raw.strip().replace(',', '.'));
            return Math.abs(value) <= bound ? value : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static int clamp(String raw, int min, int max, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Math.clamp(Integer.parseInt(raw.strip()), min, max);
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }
}
