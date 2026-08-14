package io.github.rodrigorjsf.agenticchat.tools.science;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.github.rodrigorjsf.agenticchat.skills.SkillTools;
import io.github.rodrigorjsf.agenticchat.tools.http.ToolHttpClient;
import io.github.rodrigorjsf.agenticchat.tools.http.ToolJson;
import jakarta.inject.Singleton;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;

/**
 * Space and Earth-science observations, disclosed by the {@code science-and-space}
 * skill.
 *
 * <p>Five sources, one per tool, and none of them interchangeable: NASA for the
 * daily astronomy image, USGS for seismology, wheretheiss.at for the station's
 * orbit, Open Notify for its crew, and Launch Library for the flight manifest. That
 * is why there is no "space" tool that dispatches internally — a model choosing
 * between five named questions picks better than a model filling in a mode
 * parameter, and an outage in one source then costs one tool rather than the skill.
 *
 * <p>Every result here is <em>live</em>. The ISS moves about 7.7 km every second and
 * the earthquake feed changes by the minute, so nothing in this class is cacheable
 * for long, and the timestamp each source returns is part of the answer rather than
 * decoration.
 *
 * <p>Two endpoints from the source catalogue are deliberately absent, and the reason
 * is the same for both: their payload nests the list that would have to be capped
 * inside an object, and {@link ToolJson} caps only a top-level array. NASA's
 * near-Earth-object feed keys its asteroid list by date and answers ~7 KB for a
 * single day, and OpenSky's aircraft states are positional arrays with no field
 * names at all. Shipping either would have meant an uncapped multi-thousand-token
 * result replayed into every later prompt of the conversation.
 */
@Singleton
public class ScienceSpaceTools implements SkillTools {

    private static final String NASA = "nasa";
    private static final String USGS = "usgs-earthquake";
    private static final String ISS = "wheretheiss";
    private static final String LAUNCH_LIBRARY = "launch-library";

    /**
     * NASA's published shared demo key. It is not a secret — it is the value the
     * api.nasa.gov front page hands out — but it is throttled per calling IP (about
     * 30 requests an hour), and that quota is shared with everyone else behind the
     * same egress address. A free personal key lifts it to 1000/hour and is the
     * upgrade path if the picture-of-the-day tool starts coming back rate limited.
     */
    private static final String NASA_KEY = "DEMO_KEY";

    /**
     * The first Astronomy Picture of the Day; there is nothing to fetch before it.
     */
    private static final LocalDate APOD_EPOCH = LocalDate.of(1995, 6, 16);

    /**
     * Launch Library allows roughly fifteen anonymous requests an hour, so this tool
     * is one of the cheapest in the catalogue to exhaust. Three launches is enough
     * to answer "what is next" and keeps a single turn to a single request.
     */
    private static final int MAX_LAUNCHES = 3;

    /**
     * Six events already cost ~4 KB of GeoJSON; more is a fat result, not a better answer.
     */
    private static final int MAX_EARTHQUAKES = 6;

    private final ToolHttpClient http;
    private final ToolJson json;

    public ScienceSpaceTools(ToolHttpClient http, ToolJson json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public String skillName() {
        return "science-and-space";
    }

    @Tool("""
            Get NASA's Astronomy Picture of the Day: its title, the image link and \
            the astronomer's explanation of what is in it. Use when the user asks \
            for today's space image, or what NASA published on a particular day. \
            Some days carry a video instead of a photograph.""")
    public String get_astronomy_picture_of_day(
            @P("The day to fetch, as an ISO date like 2026-08-14. Leave empty for today. Nothing exists before 1995-06-16.")
            String date) {
        var query = new LinkedHashMap<String, String>();
        query.put("api_key", NASA_KEY);
        if (date != null && !date.isBlank()) {
            LocalDate day = parseDate(date);
            if (day == null) {
                return "Invalid argument: the date must be an ISO date like 2026-08-14, got '" + date + "'.";
            }
            if (day.isBefore(APOD_EPOCH)) {
                return "Invalid argument: the archive starts on " + APOD_EPOCH
                        + " and " + day + " is earlier. Ask the user for a later date.";
            }
            if (day.isAfter(LocalDate.now())) {
                return "Invalid argument: " + day + " is in the future and no image exists for it yet. "
                        + "Leave the date empty for today's image.";
            }
            query.put("date", day.toString());
        }
        // hdurl and service_version are dropped: a second copy of the same link and a
        // version marker the answer never quotes.
        return json.project(http.get(NASA, "/planetary/apod", query),
                "title", "date", "media_type", "url", "copyright", "explanation").toModelText();
    }

    @Tool("""
            List recent earthquakes worldwide above a given magnitude, with where \
            each one struck, how strong it was and when. Use for "was there an \
            earthquake in X", "how big was the quake" or "any recent seismic \
            activity" questions. Covers the whole planet, from USGS.""")
    public String get_recent_earthquakes(
            @P("Earliest day to include, as an ISO date like 2026-08-01. Leave empty for the last 7 days.")
            String since,
            @P("Smallest magnitude to report, 0 to 10, e.g. 5.5. Defaults to 4.5, which is roughly the level people feel.")
            String minMagnitude,
            @P("How many events to return, 1 to 6. Defaults to 5.")
            String limit) {
        LocalDate start = LocalDate.now().minusDays(7);
        if (since != null && !since.isBlank()) {
            start = parseDate(since);
            if (start == null) {
                return "Invalid argument: the start date must be an ISO date like 2026-08-01, got '"
                        + since + "'.";
            }
            if (start.isAfter(LocalDate.now())) {
                return "Invalid argument: " + start + " is in the future; this feed only holds "
                        + "earthquakes that have already happened.";
            }
        }
        Double magnitude = parseMagnitude(minMagnitude);
        if (magnitude == null) {
            return "Invalid argument: the minimum magnitude must be a number between 0 and 10, got '"
                    + minMagnitude + "'.";
        }

        var query = new LinkedHashMap<String, String>();
        query.put("format", "geojson");
        query.put("starttime", start.toString());
        query.put("minmagnitude", Double.toString(magnitude));
        query.put("limit", Integer.toString(clamp(limit, 1, MAX_EARTHQUAKES, 5)));
        // Largest first rather than newest first: "was there a big one" is the question
        // behind almost every phrasing of this, and a cap of six then keeps the six
        // that matter instead of six aftershocks.
        query.put("orderby", "magnitude");
        // The envelope carries a metadata block and a bounding box that describe the
        // query rather than the earthquakes; only the feature list is an answer.
        return json.project(http.get(USGS, "/fdsnws/event/1/query", query), "features").toModelText();
    }

    @Tool("""
            Get where the International Space Station is right now: latitude, \
            longitude, altitude in km, ground speed and whether it is in daylight or \
            eclipse. Use for "where is the ISS" questions. The position is only valid \
            for the second it was measured — the station crosses a country in minutes.""")
    public String get_iss_position() {
        return json.project(http.get(ISS, "/v1/satellites/25544"),
                "latitude", "longitude", "altitude", "velocity", "visibility", "timestamp").toModelText();
    }


    @Tool("""
            List the next scheduled orbital rocket launches: mission name, the \
            launch window, who is flying it, from which pad, and how firm the date \
            is. Use for "when is the next launch" or "what is SpaceX launching next" \
            questions. Costly upstream — call it at most once per turn.""")
    public String get_upcoming_launches(
            @P("How many upcoming launches to list, 1 to 3. Defaults to 3.")
            String count) {
        var query = new LinkedHashMap<String, String>();
        query.put("limit", Integer.toString(clamp(count, 1, MAX_LAUNCHES, MAX_LAUNCHES)));
        // The list serializer, not the full one: the detailed representation carries
        // vehicle histories and mission descriptions measured in tens of kilobytes.
        query.put("mode", "list");
        return json.project(http.get(LAUNCH_LIBRARY, "/2.3.0/launches/upcoming/", query),
                "results").toModelText();
    }

    // ------------------------------------------------------------------

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

    /**
     * An omitted magnitude takes the default; a malformed or out-of-range one is
     * rejected instead, because a threshold the model meant to be 6 and that
     * silently became 4.5 answers a different question from the one asked.
     */
    private static Double parseMagnitude(String raw) {
        if (raw == null || raw.isBlank()) {
            return 4.5;
        }
        try {
            double value = Double.parseDouble(raw.strip().replace(',', '.'));
            return value >= 0 && value <= 10 ? value : null;
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
