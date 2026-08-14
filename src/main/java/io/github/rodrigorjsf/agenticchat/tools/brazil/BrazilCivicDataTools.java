package io.github.rodrigorjsf.agenticchat.tools.brazil;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.github.rodrigorjsf.agenticchat.skills.SkillTools;
import io.github.rodrigorjsf.agenticchat.tools.http.ToolHttpClient;
import jakarta.inject.Singleton;

import java.time.Year;

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
 */
@Singleton
public class BrazilCivicDataTools implements SkillTools {

    private static final String API = "brasilapi";

    private final ToolHttpClient http;

    public BrazilCivicDataTools(ToolHttpClient http) {
        this.http = http;
    }

    @Override
    public String skillName() {
        return "brazil-civic-data";
    }

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
        return http.get(API, "/cnpj/v1/" + digits).toModelText();
    }

    private static String digitsOf(String raw) {
        return raw == null ? "" : raw.replaceAll("\\D", "");
    }
}
