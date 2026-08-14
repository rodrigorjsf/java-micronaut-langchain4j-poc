package io.github.rodrigorjsf.agenticchat.tools.time;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.github.rodrigorjsf.agenticchat.skills.SkillTools;
import io.github.rodrigorjsf.agenticchat.tools.http.ToolHttpClient;
import io.github.rodrigorjsf.agenticchat.tools.http.ToolJson;
import jakarta.inject.Singleton;

import java.time.Year;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;

/**
 * Wall-clock time and public-holiday calendars, disclosed by the
 * {@code time-and-calendar} skill.
 *
 * <p>Two upstreams, split by what they know: Nager.Date holds the holiday calendar
 * of some 110 countries, and timeapi.io holds the current time in an IANA zone.
 * Neither knows anything about the other, so no tool here chains into another.
 *
 * <p>Three house-style points visible in this class:
 *
 * <ul>
 *   <li><b>The timezone is checked against the JVM's own IANA database before the
 *       call.</b> {@link ZoneId#getAvailableZoneIds()} costs nothing and turns a
 *       hallucinated zone such as "Brazil/Sao_Paulo" into a sentence the model can
 *       act on, instead of a round trip that comes back as an upstream error with
 *       no explanation.</li>
 *   <li><b>Arguments that reach a path segment are validated as patterns first.</b>
 *       A year and a two-letter country code are the only things that ever get
 *       concatenated into a URL path here, and both are checked before they do.</li>
 *   <li><b>Nothing throws.</b> Every branch returns text, so LangChain4j never
 *       falls back to its default tool-error handling.</li>
 * </ul>
 *
 * <p>Brazilian federal holidays are deliberately <em>not</em> re-implemented here:
 * {@code list_national_holidays} in the {@code brazil-civic-data} skill already
 * covers the same BrasilAPI endpoint, and {@code get_holidays} with country code
 * {@code BR} answers the same question from this skill. Two tools over one source
 * would only make the routing decision harder for no new capability.
 */
@Singleton
public class TimeCalendarTools implements SkillTools {

    private static final String HOLIDAYS_API = "nager-date";
    private static final String CLOCK_API = "timeapi";

    /**
     * How far from the present the holiday calendar is worth asking for. The
     * upstream carries a few decades either side; a year outside this window is
     * almost always a typo, and catching it here saves a round trip.
     */
    private static final int YEARS_BACK = 50;
    private static final int YEARS_AHEAD = 10;

    private final ToolHttpClient http;
    private final ToolJson json;

    public TimeCalendarTools(ToolHttpClient http, ToolJson json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public String skillName() {
        return "time-and-calendar";
    }

    @Tool("""
            List the official public holidays of one country for one year, with each \
            date, its local name and its English name. Use when the user asks whether \
            a date is a holiday, which holidays a country has, or how many working \
            days a period contains. Covers national holidays; regional ones are \
            marked with the counties they apply to.""")
    public String get_holidays(
            @P("The four-digit year, e.g. 2026.")
            String year,
            @P("ISO-3166 alpha-2 country code, e.g. BR for Brazil, PT for Portugal, US for the United States.")
            String countryCode) {
        String validYear = validYear(year);
        if (validYear == null) {
            return yearComplaint(year);
        }
        String country = countryCode(countryCode);
        if (country == null) {
            return countryComplaint(countryCode);
        }
        var response = json.projectCapped(
                http.get(HOLIDAYS_API, "/PublicHolidays/" + validYear + "/" + country),
                20, "date", "localName", "name", "counties");
        return orNotFound(response.toModelText(),
                "the holiday calendar has no entry for country '" + country + "' in " + validYear + ".");
    }

    @Tool("""
            List the next public holidays coming up in one country over the following \
            twelve months. Use for "when is the next holiday", "quando é o próximo \
            feriado" or planning questions about the near future, where the user does \
            not name a year.""")
    public String get_next_holidays(
            @P("ISO-3166 alpha-2 country code, e.g. BR for Brazil, PT for Portugal, US for the United States.")
            String countryCode) {
        String country = countryCode(countryCode);
        if (country == null) {
            return countryComplaint(countryCode);
        }
        var response = json.projectCapped(http.get(HOLIDAYS_API, "/NextPublicHolidays/" + country),
                5, "date", "localName", "name", "counties");
        return orNotFound(response.toModelText(),
                "the holiday calendar does not cover country '" + country + "'.");
    }

    @Tool("""
            Find the long weekends of one country in one year: the runs of consecutive \
            free days formed by a holiday next to a weekend, and whether a bridge day \
            has to be taken off work. Use for "quando posso viajar", holiday-planning \
            and time-off questions.""")
    public String get_long_weekends(
            @P("The four-digit year, e.g. 2026.")
            String year,
            @P("ISO-3166 alpha-2 country code, e.g. BR for Brazil, PT for Portugal, US for the United States.")
            String countryCode) {
        String validYear = validYear(year);
        if (validYear == null) {
            return yearComplaint(year);
        }
        String country = countryCode(countryCode);
        if (country == null) {
            return countryComplaint(countryCode);
        }
        var response = json.projectCapped(
                http.get(HOLIDAYS_API, "/LongWeekend/" + validYear + "/" + country),
                10, "startDate", "endDate", "dayCount", "needBridgeDay");
        return orNotFound(response.toModelText(),
                "no long weekend is recorded for country '" + country + "' in " + validYear + ".");
    }

    @Tool("""
            Get the current local date, time, day of the week and daylight-saving state \
            in one timezone. Use whenever the user asks what time it is somewhere, or \
            when you need "now" in a place before reasoning about a deadline or a \
            meeting. Requires an IANA timezone name such as America/Sao_Paulo — \
            find_place in the geo-and-weather skill returns one for a city.""")
    public String get_current_time(
            @P("IANA timezone name in Region/City form, e.g. America/Sao_Paulo, Europe/Lisbon or UTC.")
            String timeZone) {
        if (timeZone == null || timeZone.isBlank()) {
            return "Invalid argument: an IANA timezone name is required, e.g. America/Sao_Paulo.";
        }
        String zone = timeZone.strip();
        // Checked against the JVM's bundled IANA database rather than upstream: a
        // wrong zone is the most likely failure here, and the model can only fix it
        // if it is told that it is the zone that is wrong.
        if (!ZoneId.getAvailableZoneIds().contains(zone)) {
            return "Invalid argument: '" + zone + "' is not an IANA timezone name. Use Region/City "
                    + "form such as America/Sao_Paulo, Europe/Lisbon or UTC. If you only have a city "
                    + "name, find_place in the geo-and-weather skill returns its timezone.";
        }
        // ~231 bytes, every field of which the answer may quote: returned whole.
        return http.get(CLOCK_API, "/Time/current/zone", Map.of("timeZone", zone)).toModelText();
    }

    // ------------------------------------------------------------------

    /**
     * @return the year as a string when it is plausible, otherwise {@code null}
     */
    private static String validYear(String raw) {
        String digits = raw == null ? "" : raw.replaceAll("\\D", "");
        if (digits.length() != 4) {
            return null;
        }
        int value = Integer.parseInt(digits);
        int current = Year.now().getValue();
        return value < current - YEARS_BACK || value > current + YEARS_AHEAD ? null : digits;
    }

    /**
     * @return the upper-case alpha-2 code, or {@code null} when it is not one
     */
    private static String countryCode(String raw) {
        String clean = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        return clean.matches("[A-Z]{2}") ? clean : null;
    }

    private static String yearComplaint(String year) {
        int current = Year.now().getValue();
        return "Invalid argument: expected a four-digit year between " + (current - YEARS_BACK)
                + " and " + (current + YEARS_AHEAD) + ", got '" + year + "'.";
    }

    private static String countryComplaint(String countryCode) {
        return "Invalid argument: expected a two-letter ISO country code such as BR, PT or US, got '"
                + countryCode + "'. Ask the user which country they mean if it is not clear.";
    }

    /**
     * The holiday service answers {@code 200} with {@code []} for a country it has
     * no calendar for, which never reaches the HTTP status mapping. An empty array
     * handed to the model reads as "this country has no holidays".
     */
    private static String orNotFound(String text, String what) {
        if (text == null || text.isBlank() || text.equals("[]") || text.equals("{}")) {
            return "No result: " + what + " Tell the user nothing was found; do not guess a date.";
        }
        return text;
    }
}
