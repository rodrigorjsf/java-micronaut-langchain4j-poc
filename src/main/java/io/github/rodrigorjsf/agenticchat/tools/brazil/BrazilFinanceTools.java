package io.github.rodrigorjsf.agenticchat.tools.brazil;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.github.rodrigorjsf.agenticchat.skills.SkillTools;
import io.github.rodrigorjsf.agenticchat.tools.http.ToolHttpClient;
import io.github.rodrigorjsf.agenticchat.tools.http.ToolJson;
import io.github.rodrigorjsf.agenticchat.tools.http.ToolResponse;
import jakarta.inject.Singleton;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Brazilian monetary, banking and price-table data, disclosed by the
 * {@code brazil-finance} skill.
 *
 * <p>Same house rules as the rest of the tool layer: descriptions say when to reach
 * for a tool, arguments are validated here in language the model can act on, and
 * nothing throws — every path returns a string.
 *
 * <p>Two things are specific to financial sources and shape the code below.
 *
 * <p><b>An empty answer is not an error.</b> Banco Central publishes no quotation on
 * weekends and national holidays, and answers {@code 200} with an empty array rather
 * than a {@code 404}. That never reaches {@link ToolHttpClient}'s status mapping, so
 * it is turned into an explicit "the market was closed" here. Handed the raw empty
 * array, a model will either report a rate of zero or quietly invent one.
 *
 * <p><b>Rate tables are large and the model needs a handful of rows.</b> The FIPE
 * model list for a single carmaker measured 35 KB, which is roughly 9k tokens that
 * would be replayed into every later prompt of the conversation. Everything wide is
 * capped through {@link ToolJson}, and the cap is announced so the model says "and
 * 340 others" instead of believing it has the whole table.
 */
@Singleton
public class BrazilFinanceTools implements SkillTools {

    private static final String API = "brasilapi";
    private static final String FIPE = "brasilapi-fipe";
    private static final String SGS = "bcb-sgs";
    private static final String PTAX = "bcb-olinda-ptax";
    private static final String FX = "awesomeapi-economia";
    private static final String IBGE = "ibge-servicodados";

    private static final Set<String> RATES = Set.of("SELIC", "CDI", "IPCA");
    private static final Set<String> VEHICLE_TYPES = Set.of("carros", "motos", "caminhoes");

    private final ToolHttpClient http;
    private final ToolJson json;

    public BrazilFinanceTools(ToolHttpClient http, ToolJson json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public String skillName() {
        return "brazil-finance";
    }

    // ------------------------------------------------------------------
    // Interest rates and inflation
    // ------------------------------------------------------------------

    @Tool("""
            Get Brazil's current reference rates — SELIC, CDI and IPCA — either all \
            three at once or a single named one. Use for "what is the SELIC", "how \
            much is inflation in Brazil" and any question about the current cost of \
            money in Brazil. For a rate on a past date use get_bcb_time_series.""")
    public String get_brazil_interest_rates(
            @P("Optional rate name: SELIC, CDI or IPCA. Leave empty to get all three.")
            String rateName) {
        if (rateName == null || rateName.isBlank()) {
            return http.get(API, "/taxas/v1").toModelText();
        }
        String rate = rateName.strip().toUpperCase(Locale.ROOT);
        if (!RATES.contains(rate)) {
            return "Invalid argument: this registry publishes SELIC, CDI and IPCA, got '"
                    + rateName + "'. Leave the argument empty to get all three.";
        }
        return http.get(API, "/taxas/v1/" + rate).toModelText();
    }

    @Tool("""
            Read the last observations of a Banco Central time series (SGS) by its \
            numeric code, for history rather than today's value. Useful codes: 432 \
            SELIC target, 12 CDI daily, 433 IPCA monthly, 1 USD/BRL daily, 4390 \
            monthly SELIC. Requires the series code — do not guess one you have not \
            been given.""")
    public String get_bcb_time_series(
            @P("The numeric SGS series code, e.g. 432 for the SELIC target.")
            String seriesCode,
            @P("How many of the most recent observations to return, 1 to 12. Defaults to 6.")
            String observations) {
        String code = digitsOf(seriesCode);
        if (code.isEmpty() || code.length() > 6) {
            return "Invalid argument: the SGS series code is a number such as 432, got '"
                    + seriesCode + "'.";
        }
        int last = clamp(observations, 1, 12, 6);
        return http.get(SGS, "/bcdata.sgs." + code + "/dados/ultimos/" + last,
                Map.of("formato", "json")).toModelText();
    }

    @Tool("""
            Get the official IBGE value of a statistical indicator, such as the IPCA \
            inflation variation, for the latest or a specific month. Requires the \
            aggregate and variable ids — call describe_ibge_aggregate first if you do \
            not have them. Aggregate 7060 with variable 63 is the monthly IPCA.""")
    public String get_ibge_indicator(
            @P("The IBGE aggregate id, e.g. 7060 for the IPCA.")
            String aggregate,
            @P("The variable id inside that aggregate, e.g. 63 for the monthly variation.")
            String variable,
            @P("The period: '-1' for the latest, '-6' for the last six, or a month as YYYYMM such as 202601.")
            String period) {
        String agg = digitsOf(aggregate);
        String var = digitsOf(variable);
        if (agg.isEmpty() || var.isEmpty()) {
            return "Invalid argument: both the aggregate id and the variable id are numeric, got '"
                    + aggregate + "' and '" + variable + "'. Call describe_ibge_aggregate to find them.";
        }
        String when = period == null || period.isBlank() ? "-1" : period.strip();
        if (!when.matches("-\\d{1,2}|\\d{6}")) {
            return "Invalid argument: the period is '-1' for the latest, '-N' for the last N, "
                    + "or YYYYMM such as 202601, got '" + period + "'.";
        }
        // The square brackets in localidades=N1[all] must reach the wire percent-encoded;
        // passing them as a query value is what guarantees that.
        return http.get(IBGE, "/v3/agregados/" + agg + "/periodos/" + when + "/variaveis/" + var,
                Map.of("localidades", "N1[all]")).toModelText();
    }

    @Tool("""
            List the variables and the valid period range of an IBGE statistical \
            aggregate. Use this before get_ibge_indicator whenever you do not already \
            know which variable id carries the number you want.""")
    public String describe_ibge_aggregate(
            @P("The IBGE aggregate id, e.g. 1705 for the IPCA-15 or 7060 for the IPCA.")
            String aggregate) {
        String agg = digitsOf(aggregate);
        if (agg.isEmpty()) {
            return "Invalid argument: the aggregate id is a number such as 7060, got '"
                    + aggregate + "'.";
        }
        // 32 KB of metadata, of which the model needs three fields; the territorial
        // levels and classification trees are the rest and are never used here.
        var raw = http.get(IBGE, "/v3/agregados/" + agg + "/metadados");
        if (raw.truncated()) {
            return "The metadata for aggregate " + agg + " was too large to read. Ask the user "
                    + "which indicator they want and try a more specific aggregate.";
        }
        return json.project(raw, "nome", "periodicidade", "variaveis").toModelText();
    }

    // ------------------------------------------------------------------
    // Exchange rates
    // ------------------------------------------------------------------

    @Tool("""
            Get live market quotes in Brazilian reais for currency pairs such as \
            USD-BRL or EUR-BRL, with the day's high, low and percentage change. Use \
            for "how much is the dollar today". For the Banco Central's official \
            closing rate on a past date, use get_bcb_fx_rate instead.""")
    public String get_fx_quote_brl(
            @P("One or more pairs separated by commas, at most five, e.g. 'USD-BRL' or 'USD-BRL,EUR-BRL,BTC-BRL'.")
            String pairs) {
        if (pairs == null || pairs.isBlank()) {
            return "Invalid argument: at least one currency pair is required, e.g. USD-BRL.";
        }
        String[] parts = pairs.strip().toUpperCase(Locale.ROOT).split(",");
        if (parts.length > 5) {
            return "Invalid argument: ask for at most five pairs at a time, got " + parts.length + ".";
        }
        for (String part : parts) {
            if (!part.strip().matches("[A-Z]{3}-[A-Z]{3}")) {
                return "Invalid argument: each pair is two 3-letter codes joined by a hyphen, "
                        + "e.g. USD-BRL, got '" + part.strip() + "'.";
            }
        }
        return http.get(FX, "/json/last/" + String.join(",", trimAll(parts))).toModelText();
    }

    @Tool("""
            Get the daily closing series for one currency pair, to answer "how has the \
            dollar moved this week" or to chart a trend. Returns one closing quote per \
            day, most recent first. For a single official rate on one date, use \
            get_bcb_fx_rate.""")
    public String get_fx_history_brl(
            @P("The currency pair, e.g. 'USD-BRL' or 'EUR-BRL'.")
            String pair,
            @P("How many days back, 1 to 30. Defaults to 7.")
            String days) {
        if (pair == null || !pair.strip().toUpperCase(Locale.ROOT).matches("[A-Z]{3}-[A-Z]{3}")) {
            return "Invalid argument: the pair is two 3-letter codes joined by a hyphen, "
                    + "e.g. USD-BRL, got '" + pair + "'.";
        }
        int window = clamp(days, 1, 30, 7);
        return json.projectCapped(
                http.get(FX, "/json/daily/" + pair.strip().toUpperCase(Locale.ROOT) + "/" + window),
                30, "bid", "timestamp", "create_date").toModelText();
    }

    @Tool("""
            Get the Banco Central's official closing rate for a currency against the \
            real on a specific date. Use when the question needs the official number \
            for accounting, a contract or a past date, rather than the live market \
            price. There is no quotation on weekends and holidays.""")
    public String get_bcb_fx_rate(
            @P("The three-letter currency code, e.g. USD, EUR or GBP. Call list_bcb_currencies if unsure.")
            String currency,
            @P("The date as YYYY-MM-DD, e.g. 2026-08-13. Must be a business day.")
            String date) {
        String code = currency == null ? "" : currency.strip().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z]{3}")) {
            return "Invalid argument: expected a three-letter currency code such as USD, got '"
                    + currency + "'.";
        }
        String day = isoDate(date);
        if (day == null) {
            return "Invalid argument: the date must be written as YYYY-MM-DD, e.g. 2026-08-13, got '"
                    + date + "'.";
        }
        var response = http.get(API, "/cambio/v1/cotacao/" + code + "/" + day);
        return orMarketClosed(response, "\"cotacoes\":[]", code + " on " + day);
    }

    @Tool("""
            Get the official PTAX dollar rate — the buy and sell reference the Banco \
            Central publishes for the day, and the number Brazilian contracts and tax \
            filings cite. Use when the question says PTAX or asks for the official \
            dollar rate rather than the market price.""")
    public String get_ptax_usd(
            @P("The date as YYYY-MM-DD, e.g. 2026-08-13. Must be a business day.")
            String date) {
        String day = isoDate(date);
        if (day == null) {
            return "Invalid argument: the date must be written as YYYY-MM-DD, e.g. 2026-08-13, got '"
                    + date + "'.";
        }
        // The OData route takes the date as MM-DD-YYYY inside single quotes, which is
        // neither ISO nor the Brazilian order; the model should never have to know that.
        LocalDate parsed = LocalDate.parse(day);
        String odata = "'%02d-%02d-%04d'".formatted(
                parsed.getMonthValue(), parsed.getDayOfMonth(), parsed.getYear());
        var response = http.get(PTAX, "/CotacaoDolarDia(dataCotacao=@dataCotacao)",
                Map.of("@dataCotacao", odata, "$format", "json"));
        return orMarketClosed(response, "\"value\":[]", "the dollar on " + day);
    }

    @Tool("""
            List the currencies the Banco Central quotes against the real, with their \
            three-letter codes. Use when a currency code is uncertain before calling \
            get_bcb_fx_rate, not as an answer in itself.""")
    public String list_bcb_currencies(
            @P("How many currencies to list, 1 to 30. Defaults to 30; there are about twenty in total.")
            String maxResults) {
        return json.cap(http.get(API, "/cambio/v1/moedas"), clamp(maxResults, 1, 30, 30)).toModelText();
    }

    // ------------------------------------------------------------------
    // Banks
    // ------------------------------------------------------------------

    @Tool("""
            Identify a Brazilian bank from the three-digit COMPE code that appears on \
            a transfer, a boleto or a bank slip, returning its name, full legal name \
            and ISPB. Use when the user gives a bank number such as 001, 341 or 260 \
            and wants to know which institution it is.""")
    public String lookup_bank(
            @P("The COMPE bank code, 1 to 3 digits, e.g. 1 for Banco do Brasil or 341 for Itaú.")
            String bankCode) {
        String code = digitsOf(bankCode);
        if (code.isEmpty() || code.length() > 3) {
            return "Invalid argument: a COMPE bank code has 1 to 3 digits, e.g. 341, got '"
                    + bankCode + "'.";
        }
        return http.get(API, "/banks/v1/" + Integer.parseInt(code)).toModelText();
    }

    // ------------------------------------------------------------------
    // FIPE vehicle price tables
    // ------------------------------------------------------------------

    @Tool("""
            List the vehicle makes in the FIPE price table, with the brand code the \
            other FIPE tools need. Start here for any question about what a used car, \
            motorcycle or truck is worth in Brazil.""")
    public String list_fipe_brands(
            @P("The vehicle type: 'carros' for cars, 'motos' for motorcycles, 'caminhoes' for trucks.")
            String vehicleType) {
        String type = normaliseVehicleType(vehicleType);
        if (type == null) {
            return "Invalid argument: the vehicle type is 'carros', 'motos' or 'caminhoes', got '"
                    + vehicleType + "'.";
        }
        return json.projectCapped(http.get(FIPE, "/marcas/v1/" + type), 40, "nome", "valor").toModelText();
    }

    @Tool("""
            List the models a FIPE brand sells, with the model code. Requires the \
            brand code from list_fipe_brands — a brand name will not work. The list is \
            long, so only the first 25 models come back with a count of the rest; say \
            so rather than implying the range is complete.""")
    public String list_fipe_models(
            @P("The vehicle type: 'carros', 'motos' or 'caminhoes'. Must match the type used in list_fipe_brands.")
            String vehicleType,
            @P("The numeric brand code returned by list_fipe_brands in the 'valor' field, e.g. 21 for Fiat.")
            String brandCode) {
        String type = normaliseVehicleType(vehicleType);
        if (type == null) {
            return "Invalid argument: the vehicle type is 'carros', 'motos' or 'caminhoes', got '"
                    + vehicleType + "'.";
        }
        String brand = digitsOf(brandCode);
        if (brand.isEmpty() || brand.length() > 5) {
            return "Invalid argument: the brand code is the number in the 'valor' field of "
                    + "list_fipe_brands, e.g. 21, got '" + brandCode + "'.";
        }
        // The largest carmaker measured 35 KB of model names. The catalogue budget is
        // sized above that; a body past it is no longer parseable, and a fragment of
        // a price table is the kind of half-answer a model reports as complete.
        var raw = http.get(FIPE, "/veiculos/v1/" + type + "/" + brand);
        if (raw.truncated()) {
            return "That brand has more models than this tool can return. Ask the user which model "
                    + "they mean; do not answer from a partial list.";
        }
        return json.projectCapped(raw, 25, "modelo", "valor").toModelText();
    }

    // ------------------------------------------------------------------

    private static String digitsOf(String raw) {
        return raw == null ? "" : raw.replaceAll("\\D", "");
    }

    private static String[] trimAll(String[] parts) {
        String[] out = new String[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = parts[i].strip();
        }
        return out;
    }

    private static String normaliseVehicleType(String raw) {
        if (raw == null) {
            return null;
        }
        String type = raw.strip().toLowerCase(Locale.ROOT);
        return VEHICLE_TYPES.contains(type) ? type : null;
    }

    /** @return the date as {@code YYYY-MM-DD}, or {@code null} when it is not a real date */
    private static String isoDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.strip()).toString();
        } catch (DateTimeParseException notADate) {
            return null;
        }
    }

    /**
     * Banco Central answers {@code 200} with an empty array on weekends and national
     * holidays. Left alone that reads as a successful call with no data, and the
     * model either reports zero or fills the gap itself.
     */
    private static String orMarketClosed(ToolResponse response, String emptyMarker, String what) {
        if (response.isOk() && response.body().replace(" ", "").contains(emptyMarker)) {
            return "No quotation for " + what + ": Banco Central publishes none on weekends and "
                    + "national holidays. Ask again for the previous business day; do not estimate a rate.";
        }
        return response.toModelText();
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
