package io.github.rodrigorjsf.agenticchat.tools.world;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.github.rodrigorjsf.agenticchat.skills.SkillTools;
import io.github.rodrigorjsf.agenticchat.tools.http.ToolHttpClient;
import io.github.rodrigorjsf.agenticchat.tools.http.ToolJson;
import jakarta.inject.Singleton;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;

/**
 * Country facts, World Bank indicators and world currency rates, disclosed by the
 * {@code countries-and-economy} skill.
 *
 * <p>Three sources sit behind these tools and the split between them is deliberate:
 * REST Countries answers "what is this country", the World Bank answers "how did
 * this number move over time", and Frankfurter answers "what is this worth in
 * another currency". A single "country info" tool would have to guess which of the
 * three the user meant.
 *
 * <p>Projection happens in two different places, for two different reasons. Where
 * the upstream can project server-side it is asked to — REST Countries with
 * {@code fields=} returns 261 bytes instead of a multi-kilobyte record, and the
 * World Bank's {@code mrv} bounds the series to the most recent values. Where it
 * cannot, {@link ToolJson} does it here: {@code get_fx_rates_broad} would otherwise
 * return roughly 160 currencies (~3 KB) when the question named two.
 *
 * <p>Every argument that ends up in a URL path is validated against a pattern
 * before the call. An unvalidated path segment can produce an illegal URI, and an
 * illegal URI fails before the request is attempted — which would leave the tool
 * throwing instead of returning.
 */
@Singleton
public class CountriesEconomyTools implements SkillTools {

    private static final String COUNTRIES_API = "restcountries";
    private static final String WORLDBANK_API = "worldbank";
    private static final String FRANKFURTER_API = "frankfurter";
    private static final String BROAD_FX_API = "exchangerate-api";

    /** ECB reference rates, which Frankfurter serves, start in 1999. */
    private static final int FIRST_FX_YEAR = 1999;

    private static final String ISO_CODE = "[A-Za-z]{2,3}";
    private static final String CURRENCY_CODE = "[A-Za-z]{3}";
    /** World Bank indicator ids look like NY.GDP.MKTP.CD or SP.POP.TOTL. */
    private static final String INDICATOR_CODE = "[A-Za-z0-9][A-Za-z0-9._-]{2,39}";

    private final ToolHttpClient http;
    private final ToolJson json;

    public CountriesEconomyTools(ToolHttpClient http, ToolJson json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public String skillName() {
        return "countries-and-economy";
    }

    @Tool("""
            Get the profile of a country from its name: capital, population, official \
            currencies, languages, region and flag. Use for "what is the capital of", \
            "how many people live in" or any single-country fact. Partial names match, \
            so several countries can come back — say which one you answered about.""")
    public String get_country_by_name(
            @P("The country name in English, e.g. 'brazil', 'south africa' or 'united kingdom'.")
            String name) {
        if (name == null || name.isBlank()) {
            return "Invalid argument: a country name is required.";
        }
        String trimmed = name.strip();
        if (!trimmed.matches("[A-Za-z .'()-]{2,60}")) {
            return "Invalid argument: use the English country name in plain Latin letters, e.g. 'ivory coast', "
                    + "or call get_country_by_code with its ISO code. Got '" + name + "'.";
        }
        var query = new LinkedHashMap<String, String>();
        query.put("fields", "name,capital,population,currencies,languages,region,flag");
        // Space is the only character the pattern above admits that a URI path
        // rejects, so encoding it is enough to keep the path legal.
        var response = http.get(COUNTRIES_API, "/v3.1/name/" + trimmed.replace(" ", "%20"), query);
        // A partial name such as "united" matches several countries; five is enough
        // for the model to pick one and ask, and the cap announces what it dropped.
        return json.cap(response, 5).toModelText();
    }

    @Tool("""
            Get the profile of a country from its ISO code — capital, population, \
            currencies and region. Use when you already hold a 2- or 3-letter code, \
            typically one another tool returned, and want the exact country rather than \
            a name match. Prefer get_country_by_name when the user gave you a name.""")
    public String get_country_by_code(
            @P("ISO-3166 alpha-2 or alpha-3 country code, e.g. 'BR', 'BRA', 'US' or 'DEU'.")
            String countryCode) {
        String code = normalise(countryCode);
        if (!code.matches(ISO_CODE)) {
            return "Invalid argument: expected a 2- or 3-letter ISO country code such as 'BR' or 'BRA', got '"
                    + countryCode + "'.";
        }
        var query = new LinkedHashMap<String, String>();
        query.put("fields", "name,capital,population,currencies,region");
        return http.get(COUNTRIES_API, "/v3.1/alpha/" + code, query).toModelText();
    }

    @Tool("""
            Get the recent values of a World Bank development indicator for one \
            country — GDP, inflation, life expectancy, unemployment, population. Use \
            for "how has X changed" and for comparing countries on the same measure. \
            Needs the indicator code, e.g. NY.GDP.MKTP.CD for GDP in current US \
            dollars; the skill guide lists the common ones.""")
    public String get_worldbank_indicator(
            @P("ISO-3166 alpha-2 or alpha-3 country code, e.g. 'BRA', 'USA' or 'DE'.")
            String countryCode,
            @P("World Bank indicator code, e.g. 'NY.GDP.MKTP.CD', 'FP.CPI.TOTL.ZG' or 'SP.DYN.LE00.IN'.")
            String indicator,
            @P("How many of the most recent years to return, 1 to 10. Defaults to 5.")
            String years) {
        String code = normalise(countryCode);
        if (!code.matches(ISO_CODE)) {
            return "Invalid argument: expected a 2- or 3-letter ISO country code such as 'BRA', got '"
                    + countryCode + "'.";
        }
        String series = indicator == null ? "" : indicator.strip().toUpperCase(Locale.ROOT);
        if (!series.matches(INDICATOR_CODE)) {
            return "Invalid argument: an indicator code looks like NY.GDP.MKTP.CD, got '" + indicator
                    + "'. Ask the user which measure they mean if you are unsure.";
        }
        String recent = Integer.toString(clamp(years, 1, 10, 5));

        var query = new LinkedHashMap<String, String>();
        query.put("format", "json");
        // mrv = most recent values. Without it the series runs back to 1960 and the
        // answer never uses more than the last handful of years.
        query.put("mrv", recent);
        query.put("per_page", recent);
        // The payload is [paging-metadata, rows]; that envelope is not addressable
        // as a field path, and mrv already bounds it to about a kilobyte.
        return http.get(WORLDBANK_API, "/country/" + code + "/indicator/" + series, query).toModelText();
    }

    @Tool("""
            Get how the World Bank classifies a country: region, income group, lending \
            category, capital city and coordinates. Use for "is this a high-income \
            country", for grouping countries by region, and to confirm a country code \
            before asking for an indicator.""")
    public String get_worldbank_country_meta(
            @P("ISO-3166 alpha-2 or alpha-3 country code, e.g. 'BRA' or 'BR'.")
            String countryCode) {
        String code = normalise(countryCode);
        if (!code.matches(ISO_CODE)) {
            return "Invalid argument: expected a 2- or 3-letter ISO country code such as 'BRA', got '"
                    + countryCode + "'.";
        }
        var query = new LinkedHashMap<String, String>();
        query.put("format", "json");
        return http.get(WORLDBANK_API, "/country/" + code, query).toModelText();
    }

    @Tool("""
            Get today's exchange rate between two world currencies, from the European \
            Central Bank reference rates. Use for any "how much is X worth in Y" money \
            question. Returns the rate for one unit of the base currency: multiply the \
            user's amount by it yourself, and quote the date the rate carries.""")
    public String convert_currency(
            @P("Currency to convert FROM, ISO-4217 code, e.g. 'USD'.")
            String from,
            @P("Currency or currencies to convert TO, comma-separated ISO-4217 codes, e.g. 'BRL' or 'BRL,EUR'.")
            String to) {
        String base = normalise(from);
        if (!base.matches(CURRENCY_CODE)) {
            return "Invalid argument: expected a 3-letter currency code such as 'USD', got '" + from + "'.";
        }
        String symbols = currencyList(to);
        if (symbols == null) {
            return "Invalid argument: expected up to 8 comma-separated 3-letter currency codes, e.g. 'BRL,EUR', got '"
                    + to + "'. Call list_supported_currencies if you are unsure of a code.";
        }
        var query = new LinkedHashMap<String, String>();
        query.put("base", base);
        query.put("symbols", symbols);
        return http.get(FRANKFURTER_API, "/latest", query).toModelText();
    }

    @Tool("""
            Get the exchange rate between two currencies on a past date, from European \
            Central Bank reference rates going back to 1999. Use for "what was the \
            dollar worth in January 2020" and for comparing a rate then and now. \
            Weekends and holidays have no fixing, so the answer may carry the previous \
            business day's date.""")
    public String get_historical_fx_rate(
            @P("The day of the rate, ISO date, e.g. 2025-01-02.")
            String date,
            @P("Currency to convert FROM, ISO-4217 code, e.g. 'USD'.")
            String from,
            @P("Currency or currencies to convert TO, comma-separated ISO-4217 codes, e.g. 'BRL'.")
            String to) {
        LocalDate day = parseDate(date);
        if (day == null) {
            return "Invalid argument: the date must be an ISO date like 2025-01-02, got '" + date + "'.";
        }
        if (day.getYear() < FIRST_FX_YEAR) {
            return "Invalid argument: these reference rates start in " + FIRST_FX_YEAR + ", so " + day
                    + " is out of range. Tell the user the series does not go back that far.";
        }
        if (day.isAfter(LocalDate.now())) {
            return "Invalid argument: " + day + " is in the future. Use convert_currency for today's rate.";
        }
        String base = normalise(from);
        if (!base.matches(CURRENCY_CODE)) {
            return "Invalid argument: expected a 3-letter currency code such as 'USD', got '" + from + "'.";
        }
        String symbols = currencyList(to);
        if (symbols == null) {
            return "Invalid argument: expected up to 8 comma-separated 3-letter currency codes, e.g. 'BRL', got '"
                    + to + "'.";
        }
        var query = new LinkedHashMap<String, String>();
        query.put("base", base);
        query.put("symbols", symbols);
        return http.get(FRANKFURTER_API, "/" + day, query).toModelText();
    }

    @Tool("""
            List the currency codes the exchange-rate tools accept, with the name of \
            each currency, or check whether particular codes are among them. Use when a \
            code was rejected, when the user names a currency you have no code for, or \
            before promising a conversion you are not sure is covered.""")
    public String list_supported_currencies(
            @P("Optional codes to check, comma-separated, e.g. 'BRL,ARS'. Leave empty to list every currency.")
            String codes) {
        // Validate before calling: a malformed filter is the model's mistake to fix,
        // and spending a request on it teaches it nothing.
        String wanted = codes == null || codes.isBlank() ? null : currencyList(codes);
        if (codes != null && !codes.isBlank() && wanted == null) {
            return "Invalid argument: expected up to 8 comma-separated 3-letter codes, e.g. 'BRL,ARS', got '"
                    + codes + "'. Leave the argument empty to see the whole list.";
        }
        var response = http.get(FRANKFURTER_API, "/currencies");
        if (wanted == null) {
            return response.toModelText();
        }
        // The payload is a flat {code: name} map, so asking for the codes by name is
        // the whole filter. A code missing from the answer is a code not covered.
        return json.project(response, wanted.split(",")).toModelText();
    }

    @Tool("""
            Get exchange rates for currencies the European Central Bank does not \
            publish — most Latin American, African and Asian currencies. Use only after \
            convert_currency came back without the currency you needed. Rates here \
            update about once a day, so quote them as approximate and give the update \
            time returned with them.""")
    public String get_fx_rates_broad(
            @P("Base currency, ISO-4217 code, e.g. 'USD'.")
            String base,
            @P("Currencies to read, comma-separated ISO-4217 codes, at most 8, e.g. 'ARS,CLP,COP'.")
            String symbols) {
        String from = normalise(base);
        if (!from.matches(CURRENCY_CODE)) {
            return "Invalid argument: expected a 3-letter base currency code such as 'USD', got '" + base + "'.";
        }
        String wanted = currencyList(symbols);
        if (wanted == null) {
            return "Invalid argument: name up to 8 currencies you want, e.g. 'ARS,CLP'. This source quotes about "
                    + "160 currencies and returning them all would flood the answer.";
        }

        // Field paths built from the requested codes, so the result carries the two
        // or three rates the question asked for instead of every currency on Earth.
        var paths = new ArrayList<String>();
        paths.add("base_code");
        paths.add("time_last_update_utc");
        for (String code : wanted.split(",")) {
            paths.add("rates." + code);
        }
        return json.project(http.get(BROAD_FX_API, "/latest/" + from),
                paths.toArray(String[]::new)).toModelText();
    }

    /**
     * @return the codes uppercased and comma-joined, or {@code null} when the input
     *         is empty, too long, or holds something that is not a currency code
     */
    private static String currencyList(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.strip().split("\\s*,\\s*");
        if (parts.length > 8) {
            return null;
        }
        var codes = new ArrayList<String>(parts.length);
        for (String part : parts) {
            String code = part.strip().toUpperCase(Locale.ROOT);
            if (!code.matches(CURRENCY_CODE)) {
                return null;
            }
            codes.add(code);
        }
        return String.join(",", codes);
    }

    private static String normalise(String raw) {
        return raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.strip());
        } catch (DateTimeParseException notADate) {
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
