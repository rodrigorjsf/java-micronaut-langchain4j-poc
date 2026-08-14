package io.github.rodrigorjsf.agenticchat.tools.brazil;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.github.rodrigorjsf.agenticchat.skills.SkillTools;
import io.github.rodrigorjsf.agenticchat.tools.http.ToolHttpClient;
import io.github.rodrigorjsf.agenticchat.tools.http.ToolJson;
import jakarta.inject.Singleton;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Year;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Tools over Brazilian public registries, disclosed by the
 * {@code brazil-civic-data} skill.
 *
 * <p>House style for every tool in this project, visible here:
 * <ul>
 *   <li>The {@code @Tool} description says <em>when to reach for it</em>, not what
 *       it technically does. "Look up a Brazilian postal code (CEP)" is what the
 *       method name already says; naming the inputs and the returned fields is what
 *       actually improves selection.</li>
 *   <li>Arguments are validated here, not at the HTTP layer, and a bad argument
 *       comes back as text the model can act on. A model that gets "expected 8
 *       digits" fixes its own call; a model that gets a 400 usually gives up.</li>
 *   <li>Nothing throws. Every path returns a string, so LangChain4j never falls
 *       back to its default tool-error handling, which feeds
 *       {@code Throwable.getMessage()} straight into the prompt.</li>
 * </ul>
 *
 * <p>Three registries sit behind these tools rather than one, and the split is
 * deliberate: BrasilAPI is a community gateway in front of many upstreams, ViaCEP
 * is the older postal service, and IBGE is the statistics agency. When one of them
 * is down the others still answer, which is why {@code lookup_cep_fallback} exists
 * as a separate tool the model can reach for instead of a hidden retry.
 *
 * <p>Several of these endpoints answer {@code 200} with an empty result rather than
 * a {@code 404} — ViaCEP returns {@code {"erro": true}}, IBGE returns {@code []}.
 * That never reaches {@link ToolHttpClient}'s status mapping, so it is turned into
 * an explicit "no result" here; an empty object handed to the model is something it
 * will try to interpret.
 */
@Singleton
public class BrazilCivicDataTools implements SkillTools {

    private static final String API = "brasilapi";
    // Separate catalogue entries, not a whim: each has its own byte budget because
    // the NCM tariff table answers in megabytes where the rest of BrasilAPI answers
    // in kilobytes, and one budget cannot be right for both.
    private static final String NCM = "brasilapi-ncm";
    private static final String VIACEP = "viacep";
    private static final String IBGE = "ibge-servicodados";
    private static final String IBGE_PLACES = "ibge-localidades";

    private static final Set<String> UFS = Set.of(
            "AC", "AL", "AP", "AM", "BA", "CE", "DF", "ES", "GO", "MA", "MT", "MS", "MG",
            "PA", "PB", "PR", "PE", "PI", "RJ", "RN", "RS", "RO", "RR", "SC", "SP", "SE", "TO");

    private final ToolHttpClient http;
    private final ToolJson json;

    public BrazilCivicDataTools(ToolHttpClient http, ToolJson json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public String skillName() {
        return "brazil-civic-data";
    }

    // ------------------------------------------------------------------
    // Addresses and postal codes
    // ------------------------------------------------------------------

    @Tool("""
            Resolve a Brazilian postal code (CEP) to its street, neighborhood, city, \
            state, IBGE municipal code and coordinates. Use whenever the user gives a \
            CEP or asks which address a CEP belongs to.""")
    public String lookup_cep(
            @P("The 8-digit Brazilian postal code. Punctuation is ignored, e.g. 01310-100 or 01310100.")
            String cep) {
        String digits = digitsOf(cep);
        if (digits.length() != 8) {
            return "Invalid argument: a CEP has exactly 8 digits, got " + digits.length()
                    + ". Ask the user to confirm the postal code.";
        }
        return http.get(API, "/cep/v2/" + digits).toModelText();
    }

    @Tool("""
            Resolve a postal code at a second, independent provider. Use when \
            lookup_cep found nothing or was unavailable, or when you specifically \
            need the telephone area code (DDD) and the 7-digit IBGE municipal code, \
            which lookup_cep does not return. Try lookup_cep first.""")
    public String lookup_cep_fallback(
            @P("The 8-digit Brazilian postal code. Punctuation is ignored, e.g. 01310-100 or 01310100.")
            String cep) {
        String digits = digitsOf(cep);
        if (digits.length() != 8) {
            return "Invalid argument: a CEP has exactly 8 digits, got " + digits.length()
                    + ". Ask the user to confirm the postal code.";
        }
        var response = json.project(http.get(VIACEP, "/" + digits + "/json/"),
                "cep", "logradouro", "bairro", "localidade", "uf", "ddd", "ibge");
        return orNotFound(response.toModelText(), "no address is registered for CEP " + digits + ".");
    }

    @Tool("""
            Find the postal codes (CEPs) that match a street name inside one city. \
            Use when the user has an address but not its CEP. Returns at most ten \
            candidates, so a distinctive street fragment narrows it better than a \
            common one.""")
    public String search_cep_by_address(
            @P("The two-letter state code, e.g. SP, RJ or MG.")
            String uf,
            @P("The city name, spelled out and accented as usual, e.g. 'São Paulo'.")
            String city,
            @P("Part of the street name, at least 3 characters, e.g. 'Paulista'. Do not include a house number.")
            String street) {
        String state = uf == null ? "" : uf.strip().toUpperCase(Locale.ROOT);
        if (!UFS.contains(state)) {
            return "Invalid argument: expected a two-letter Brazilian state code such as SP, got '"
                    + uf + "'.";
        }
        if (city == null || city.strip().length() < 3) {
            return "Invalid argument: the city name needs at least 3 characters, got '" + city + "'.";
        }
        if (street == null || street.strip().length() < 3) {
            return "Invalid argument: the street fragment needs at least 3 characters, got '"
                    + street + "'. Ask the user for more of the street name.";
        }
        String path = "/" + state + "/" + segment(city.strip()) + "/" + segment(street.strip()) + "/json/";
        var raw = http.get(VIACEP, path);
        if (raw.truncated()) {
            return "That street fragment matches too many addresses to return. Ask the user for "
                    + "more of the street name and search again.";
        }
        var response = json.projectCapped(raw, 10, "cep", "logradouro", "bairro", "localidade", "uf");
        return orNotFound(response.toModelText(),
                "no street matching '" + street.strip() + "' was found in " + city.strip() + "/" + state + ".");
    }

    // ------------------------------------------------------------------
    // Phone codes, holidays and companies
    // ------------------------------------------------------------------

    @Tool("""
            List the cities served by a Brazilian telephone area code (DDD), with the \
            state. Use when the user gives a 2-digit area code or asks which region a \
            phone number belongs to.""")
    public String lookup_ddd(
            @P("The 2-digit area code, e.g. 11 for São Paulo or 48 for Florianópolis.")
            String ddd) {
        String digits = digitsOf(ddd);
        if (digits.length() != 2) {
            return "Invalid argument: a DDD has exactly 2 digits, got '" + ddd + "'.";
        }
        return http.get(API, "/ddd/v1/" + digits).toModelText();
    }

    @Tool("""
            List Brazil's federal public holidays for a year, with dates and names. \
            Covers national holidays only, not state or municipal ones.""")
    public String list_national_holidays(
            @P("The four-digit year, e.g. 2026.")
            String year) {
        String digits = digitsOf(year);
        if (digits.length() != 4) {
            return "Invalid argument: expected a four-digit year, got '" + year + "'.";
        }
        int value = Integer.parseInt(digits);
        int current = Year.now().getValue();
        if (value < current - 50 || value > current + 10) {
            return "Invalid argument: the holiday registry only covers years near the present, got "
                    + value + ".";
        }
        return http.get(API, "/feriados/v1/" + value).toModelText();
    }

    @Tool("""
            Look up a Brazilian company by its CNPJ: legal name, trade name, address, \
            main activity and registration status. Use when the user gives a CNPJ or \
            asks about a registered Brazilian company.""")
    public String lookup_company_by_cnpj(
            @P("The 14-digit CNPJ. Punctuation is ignored, e.g. 19.131.243/0001-97.")
            String cnpj) {
        String digits = digitsOf(cnpj);
        if (digits.length() != 14) {
            return "Invalid argument: a CNPJ has exactly 14 digits, got " + digits.length() + ".";
        }
        // The full record carries the shareholder list and every secondary activity
        // code. That is several kilobytes the model never uses, and it would be
        // replayed into every later prompt in the conversation.
        return json.project(http.get(API, "/cnpj/v1/" + digits),
                "razao_social", "nome_fantasia", "cnae_fiscal_descricao",
                "descricao_situacao_cadastral", "data_inicio_atividade",
                "municipio", "uf", "capital_social").toModelText();
    }

    // ------------------------------------------------------------------
    // IBGE territory and census reference data
    // ------------------------------------------------------------------

    @Tool("""
            List Brazilian federative units with their IBGE numeric code, two-letter \
            acronym and name, for the whole country or for one macro-region. Use when \
            you need the numeric state code that list_municipalities_of_state and the \
            census tools require.""")
    public String list_brazil_states(
            @P("Optional IBGE macro-region code: 1 North, 2 Northeast, 3 Southeast, 4 South, 5 Central-West. Leave empty for all 27 states.")
            String regionCode) {
        String digits = digitsOf(regionCode);
        if (!digits.isEmpty() && !digits.matches("[1-5]")) {
            return "Invalid argument: the macro-region code is 1 to 5 (1 North, 2 Northeast, "
                    + "3 Southeast, 4 South, 5 Central-West), got '" + regionCode
                    + "'. Leave it empty for the whole country.";
        }
        String path = digits.isEmpty() ? "/estados" : "/regioes/" + digits + "/estados";
        return json.projectCapped(http.get(IBGE_PLACES, path), 30, "id", "sigla", "nome").toModelText();
    }

    @Tool("""
            List the municipalities of one Brazilian state. Requires the numeric IBGE \
            state code, not the two-letter acronym — call list_brazil_states first if \
            you only have 'SP'. The full list is long, so only the first 50 names are \
            returned along with a count of the rest.""")
    public String list_municipalities_of_state(
            @P("The numeric IBGE state code, e.g. 35 for São Paulo or 33 for Rio de Janeiro.")
            String stateCode) {
        String digits = digitsOf(stateCode);
        if (digits.length() != 2) {
            return "Invalid argument: expected a 2-digit IBGE state code such as 35 for São Paulo, got '"
                    + stateCode + "'. Call list_brazil_states to find it.";
        }
        // 382 KB for Minas Gerais, the largest state by municipality count. The byte
        // budget on this endpoint is sized for that; if it is ever exceeded the body
        // is no longer parseable JSON, and half a list is worse than no list.
        var raw = http.get(IBGE_PLACES, "/estados/" + digits + "/municipios");
        if (raw.truncated()) {
            return "The municipality list for that state was too large to read. Tell the user this "
                    + "state's full list is unavailable; do not answer from a partial list.";
        }
        var response = json.projectCapped(raw, 50, "id", "nome");
        return orNotFound(response.toModelText(),
                "there is no Brazilian state with IBGE code " + digits + ".");
    }

    @Tool("""
            Show how often a given first name was registered in Brazil, decade by \
            decade, from the national census. Use for questions about how popular or \
            how dated a Brazilian first name is.""")
    public String census_name_stats(
            @P("A single first name, without surname and without accents if unsure, e.g. 'Rodrigo'.")
            String name) {
        if (name == null || name.isBlank()) {
            return "Invalid argument: a first name is required.";
        }
        String clean = name.strip();
        if (clean.length() > 40 || clean.contains(" ")) {
            return "Invalid argument: pass one first name only, not a full name, got '" + clean + "'.";
        }
        var response = http.get(IBGE, "/v2/censos/nomes/" + segment(clean));
        return orNotFound(response.toModelText(),
                "the census has no record of the name '" + clean + "'.");
    }

    @Tool("""
            List the most common first names in Brazil, nationally or in one state, \
            with their census frequency and rank. Use for "most popular names" \
            questions rather than to check a single name.""")
    public String census_name_ranking(
            @P("Optional numeric IBGE state code to restrict the ranking, e.g. 33 for Rio de Janeiro. Leave empty for Brazil as a whole.")
            String stateCode) {
        String digits = digitsOf(stateCode);
        if (!digits.isEmpty() && digits.length() != 2) {
            return "Invalid argument: expected a 2-digit IBGE state code such as 33, got '"
                    + stateCode + "'. Call list_brazil_states to find it, or leave it empty for all of Brazil.";
        }
        return http.get(IBGE, "/v2/censos/nomes/ranking",
                digits.isEmpty() ? Map.of() : Map.of("localidade", digits)).toModelText();
    }

    @Tool("""
            Read the latest press releases and statistics announcements published by \
            IBGE, Brazil's official statistics agency. Use for "what did IBGE just \
            publish" questions, not to look up a specific indicator value.""")
    public String ibge_news(
            @P("How many recent releases to return, 1 to 5. Defaults to 3.")
            String count) {
        return json.project(http.get(IBGE, "/v3/noticias/",
                Map.of("qtd", Integer.toString(clamp(count, 1, 5, 3)))), "items").toModelText();
    }

    // ------------------------------------------------------------------
    // Other federal registries
    // ------------------------------------------------------------------

    @Tool("""
            Check whether a .br internet domain is registered, and when it expires. \
            Only .br domains are covered — for .com or any other suffix, say the \
            registry does not cover it rather than guessing.""")
    public String check_br_domain(
            @P("The domain name including its .br suffix, e.g. globo.com.br or exemplo.br.")
            String domain) {
        if (domain == null || domain.isBlank()) {
            return "Invalid argument: a domain name is required, e.g. globo.com.br.";
        }
        String clean = domain.strip().toLowerCase(Locale.ROOT);
        if (!clean.endsWith(".br")) {
            return "Invalid argument: this registry only covers .br domains, got '" + clean
                    + "'. Tell the user that other suffixes cannot be checked here.";
        }
        if (!clean.matches("[a-z0-9.-]{4,80}")) {
            return "Invalid argument: '" + clean + "' is not a well-formed domain name.";
        }
        return json.project(http.get(API, "/registrobr/v1/" + clean),
                "status_code", "status", "fqdn", "hosts", "expires-at").toModelText();
    }

    @Tool("""
            Find the Brazilian NCM tariff codes that match a product description. Use \
            when a question is about importing, exporting or classifying goods. A \
            search term is required — the full table is far too large to list.""")
    public String search_ncm(
            @P("A product word or short phrase in Portuguese, e.g. 'cafe' or 'notebook'.")
            String query) {
        if (query == null || query.strip().length() < 3) {
            return "Invalid argument: the search term needs at least 3 characters, got '" + query + "'.";
        }
        // A one-letter search returns 2.3 MB of this table, and even "ferro" returns
        // 33 KB. The search term is mandatory for that reason, and a body that still
        // overruns the budget is refused rather than passed on as a JSON fragment.
        var raw = http.get(NCM, "/v1", Map.of("search", query.strip()));
        if (raw.truncated()) {
            return "That search term matches too much of the tariff table to return. Ask the user "
                    + "for a more specific product and search again.";
        }
        var response = json.projectCapped(raw, 8, "codigo", "descricao");
        return orNotFound(response.toModelText(),
                "no NCM code matches '" + query.strip() + "'.");
    }

    // ------------------------------------------------------------------

    private static String digitsOf(String raw) {
        return raw == null ? "" : raw.replaceAll("\\D", "");
    }

    /**
     * Percent-encodes one path segment. {@code URLEncoder} is form encoding, where a
     * space becomes {@code +}; inside a path that is a literal plus sign, not a
     * space, so it is rewritten. Without this, a city name such as "São Paulo" makes
     * {@code URI.create} throw before the request is ever built.
     */
    private static String segment(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8).replace("+", "%20");
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

    /**
     * ViaCEP answers {@code 200} with {@code {"erro": true}} and IBGE answers
     * {@code 200} with {@code []} for things they do not know, so "not found" never
     * reaches the HTTP status mapping. After projection both collapse to an empty
     * object or array, which is worse than an error: the model reads it as a real
     * answer with no fields.
     */
    private static String orNotFound(String text, String what) {
        if (text == null || text.isBlank() || text.equals("{}") || text.equals("[]")) {
            return "No result: " + what + " Tell the user nothing was found; do not guess a value.";
        }
        return text;
    }
}
