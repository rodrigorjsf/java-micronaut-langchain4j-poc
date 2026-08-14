package io.github.rodrigorjsf.agenticchat.tools.geo;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.github.rodrigorjsf.agenticchat.skills.SkillTools;
import io.github.rodrigorjsf.agenticchat.tools.http.ToolHttpClient;
import io.github.rodrigorjsf.agenticchat.tools.http.ToolJson;
import jakarta.inject.Singleton;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;

/**
 * Place lookup, addresses and weather, disclosed by the {@code geo-and-weather}
 * skill.
 *
 * <p>{@code get_weather} takes coordinates and not a place name, deliberately. A
 * single "weather in X" tool would have to geocode internally, which hides the
 * ambiguity — there is a São Paulo in Brazil and one in Portugal — and leaves the
 * model unable to ask which one the user meant. Splitting the two puts the
 * disambiguation where it can actually be resolved. Every tool below that needs a
 * position follows the same rule: coordinates in, never a name.
 *
 * <p>Two things this class does on every network tool, and the reason for each:
 *
 * <ul>
 *   <li><b>Arguments are validated here.</b> A path segment built from an
 *       unvalidated argument can be an illegal URI, and an illegal URI throws
 *       before the request is even attempted — which would escape the tool as an
 *       exception. Every value that reaches a path is checked against a pattern
 *       first, and a rejected value comes back as a sentence the model can act on.</li>
 *   <li><b>Responses are projected.</b> Open-Meteo answers carry
 *       {@code generationtime_ms}, elevation and unit blocks the model never reads,
 *       and a tool result is replayed into every later prompt in the conversation.
 *       {@link ToolJson} keeps the fields the answer needs and drops the rest.</li>
 * </ul>
 */
@Singleton
public class GeoWeatherTools implements SkillTools {

    private static final String GEOCODING_API = "open-meteo-geocoding";
    private static final String FORECAST_API = "open-meteo-forecast";
    private static final String ARCHIVE_API = "open-meteo-archive";
    private static final String AIR_QUALITY_API = "open-meteo-air-quality";
    private static final String MARINE_API = "open-meteo-marine";
    private static final String NOMINATIM_API = "nominatim";
    private static final String SUN_API = "sunrise-sunset";
    private static final String POSTAL_API = "zippopotam";

    /**
     * Requested explicitly rather than taking the API's defaults: the default
     * response carries far more fields than a chat answer needs, and every extra
     * field is context the model pays for on this turn and every later one.
     */
    private static final String CURRENT_FIELDS =
            "temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m";
    private static final String DAILY_FIELDS =
            "weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum";
    private static final String ARCHIVE_DAILY_FIELDS =
            "temperature_2m_max,temperature_2m_min,precipitation_sum";

    /** The longest range the archive tool will ask for, in days, inclusive. */
    private static final int MAX_ARCHIVE_SPAN_DAYS = 31;

    private final ToolHttpClient http;
    private final ToolJson json;

    public GeoWeatherTools(ToolHttpClient http, ToolJson json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public String skillName() {
        return "geo-and-weather";
    }

    @Tool("""
            Find a place by name and return its coordinates, country, admin region, \
            population and timezone. Returns several candidates when the name is \
            ambiguous. Call this before get_weather unless you already have coordinates.""")
    public String find_place(
            @P("The place name, e.g. 'Florianópolis' or 'Porto'. City names work best; full street addresses do not.")
            String name,
            @P("Optional ISO-639-1 language for the result names, e.g. 'pt' or 'en'. Defaults to 'pt'.")
            String language) {
        if (name == null || name.isBlank()) {
            return "Invalid argument: a place name is required.";
        }
        if (name.length() > 120) {
            return "Invalid argument: that place name is too long to be a place name.";
        }
        var query = new LinkedHashMap<String, String>();
        query.put("name", name.strip());
        query.put("count", "5");
        query.put("language", language == null || language.isBlank() ? "pt" : language.strip());
        query.put("format", "json");
        return http.get(GEOCODING_API, "/v1/search", query).toModelText();
    }

    @Tool("""
            Get current conditions and a daily forecast for a latitude and longitude. \
            Temperatures are Celsius, wind km/h, precipitation mm, and weather_code is \
            a WMO code that must be translated for the user. Requires coordinates — \
            use find_place first if you only have a place name.""")
    public String get_weather(
            @P("Latitude in decimal degrees, -90 to 90, e.g. -23.55.")
            String latitude,
            @P("Longitude in decimal degrees, -180 to 180, e.g. -46.63.")
            String longitude,
            @P("How many days of forecast, 1 to 7. Defaults to 3.")
            String forecastDays) {
        Double lat = parseCoordinate(latitude, 90);
        Double lon = parseCoordinate(longitude, 180);
        if (lat == null) {
            return "Invalid argument: latitude must be a number between -90 and 90, got '" + latitude + "'.";
        }
        if (lon == null) {
            return "Invalid argument: longitude must be a number between -180 and 180, got '" + longitude + "'.";
        }

        var query = new LinkedHashMap<String, String>();
        query.put("latitude", Double.toString(lat));
        query.put("longitude", Double.toString(lon));
        query.put("current", CURRENT_FIELDS);
        query.put("daily", DAILY_FIELDS);
        query.put("forecast_days", Integer.toString(clampDays(forecastDays)));
        // "auto" makes the API answer in the target location's own timezone, so
        // "today" means today there rather than today on the server.
        query.put("timezone", "auto");
        return http.get(FORECAST_API, "/v1/forecast", query).toModelText();
    }

    @Tool("""
            Get recorded daily weather for a past date range: maximum and minimum \
            temperature in Celsius and total precipitation in mm for each day. Use for \
            "how hot was it last January" or "did it rain that week". Requires \
            coordinates — use find_place first — and a range of at most 31 days that \
            ends before today. get_weather covers today and the coming days instead.""")
    public String get_historical_weather(
            @P("Latitude in decimal degrees, -90 to 90, e.g. -23.55.")
            String latitude,
            @P("Longitude in decimal degrees, -180 to 180, e.g. -46.63.")
            String longitude,
            @P("First day of the range, ISO date, e.g. 2026-01-01.")
            String startDate,
            @P("Last day of the range, ISO date, at most 31 days after the first, e.g. 2026-01-31.")
            String endDate) {
        Double lat = parseCoordinate(latitude, 90);
        Double lon = parseCoordinate(longitude, 180);
        if (lat == null || lon == null) {
            return coordinateComplaint(latitude, longitude);
        }
        LocalDate start = parseDate(startDate);
        LocalDate end = parseDate(endDate);
        if (start == null || end == null) {
            return "Invalid argument: both dates must be ISO dates like 2026-01-31, got '"
                    + startDate + "' and '" + endDate + "'.";
        }
        if (start.isAfter(end)) {
            return "Invalid argument: the first date must not come after the last one.";
        }
        if (!end.isBefore(LocalDate.now())) {
            return "Invalid argument: the weather archive only holds past days, and " + end
                    + " is not in the past. Use get_weather for today and the forecast.";
        }
        long span = ChronoUnit.DAYS.between(start, end) + 1;
        if (span > MAX_ARCHIVE_SPAN_DAYS) {
            return "Invalid argument: that range is " + span + " days and the limit is "
                    + MAX_ARCHIVE_SPAN_DAYS + ". Ask for a shorter range, or call the tool once per month.";
        }

        var query = new LinkedHashMap<String, String>();
        query.put("latitude", Double.toString(lat));
        query.put("longitude", Double.toString(lon));
        query.put("start_date", start.toString());
        query.put("end_date", end.toString());
        query.put("daily", ARCHIVE_DAILY_FIELDS);
        query.put("timezone", "auto");
        // Parallel arrays: `time` and each measurement share an index. Everything
        // else in the payload is metadata the answer never quotes.
        return json.project(http.get(ARCHIVE_API, "/v1/archive", query),
                "timezone",
                "daily.time",
                "daily.temperature_2m_max",
                "daily.temperature_2m_min",
                "daily.precipitation_sum").toModelText();
    }

    @Tool("""
            Get the current air quality at a latitude and longitude: PM2.5 and PM10 in \
            micrograms per cubic metre plus the US AQI index. Use when the user asks \
            about pollution, smoke, haze or whether the air is safe to exercise in. \
            Requires coordinates — use find_place first.""")
    public String get_air_quality(
            @P("Latitude in decimal degrees, -90 to 90, e.g. -23.55.")
            String latitude,
            @P("Longitude in decimal degrees, -180 to 180, e.g. -46.63.")
            String longitude) {
        Double lat = parseCoordinate(latitude, 90);
        Double lon = parseCoordinate(longitude, 180);
        if (lat == null || lon == null) {
            return coordinateComplaint(latitude, longitude);
        }
        var query = new LinkedHashMap<String, String>();
        query.put("latitude", Double.toString(lat));
        query.put("longitude", Double.toString(lon));
        query.put("current", "pm10,pm2_5,us_aqi");
        query.put("timezone", "auto");
        return json.project(http.get(AIR_QUALITY_API, "/v1/air-quality", query),
                "current.time",
                "current.pm10",
                "current.pm2_5",
                "current.us_aqi").toModelText();
    }

    @Tool("""
            Get the current sea state at a coastal point: significant wave height in \
            metres. Use for surfing, sailing, diving or beach questions. Requires \
            coordinates of a point at sea or on the coast — an inland point comes back \
            with no wave_height at all, which means "not at sea", not "calm".""")
    public String get_marine_conditions(
            @P("Latitude in decimal degrees, -90 to 90, e.g. -23.98 for Santos.")
            String latitude,
            @P("Longitude in decimal degrees, -180 to 180, e.g. -46.33.")
            String longitude) {
        Double lat = parseCoordinate(latitude, 90);
        Double lon = parseCoordinate(longitude, 180);
        if (lat == null || lon == null) {
            return coordinateComplaint(latitude, longitude);
        }
        var query = new LinkedHashMap<String, String>();
        query.put("latitude", Double.toString(lat));
        query.put("longitude", Double.toString(lon));
        query.put("current", "wave_height");
        query.put("timezone", "auto");
        return json.project(http.get(MARINE_API, "/v1/marine", query),
                "current.time",
                "current.wave_height").toModelText();
    }

    @Tool("""
            Get the ground elevation in metres above sea level at a latitude and \
            longitude. Use for "how high is this place", altitude sickness, hiking and \
            cycling questions. Requires coordinates — use find_place first.""")
    public String get_elevation(
            @P("Latitude in decimal degrees, -90 to 90, e.g. -16.5.")
            String latitude,
            @P("Longitude in decimal degrees, -180 to 180, e.g. -68.15.")
            String longitude) {
        Double lat = parseCoordinate(latitude, 90);
        Double lon = parseCoordinate(longitude, 180);
        if (lat == null || lon == null) {
            return coordinateComplaint(latitude, longitude);
        }
        var query = new LinkedHashMap<String, String>();
        query.put("latitude", Double.toString(lat));
        query.put("longitude", Double.toString(lon));
        return http.get(FORECAST_API, "/v1/elevation", query).toModelText();
    }

    @Tool("""
            Search OpenStreetMap for a street address, a landmark or a point of \
            interest and get its coordinates. Use when the user gives something more \
            specific than a city — "Avenida Paulista 1578", "Museu do Ipiranga" — \
            because find_place only resolves place names. Costly upstream: call it at \
            most once per turn.""")
    public String search_address_osm(
            @P("The address or place to search, e.g. 'Avenida Paulista 1578, Sao Paulo'.")
            String query) {
        if (query == null || query.isBlank()) {
            return "Invalid argument: an address or place to search for is required.";
        }
        if (query.length() > 200) {
            return "Invalid argument: that search text is too long; give a street address or a landmark.";
        }
        var params = new LinkedHashMap<String, String>();
        params.put("q", query.strip());
        params.put("format", "jsonv2");
        params.put("limit", "3");
        return json.projectCapped(http.get(NOMINATIM_API, "/search", params), 3,
                "display_name", "lat", "lon", "type", "category").toModelText();
    }

    @Tool("""
            Turn a latitude and longitude into the nearest street address, with \
            neighborhood, city, state, postcode and country. Use when you have a point \
            — from find_place, a CEP lookup or a GPS reading — and the user wants to \
            know what is there. Costly upstream: call it at most once per turn.""")
    public String reverse_geocode_osm(
            @P("Latitude in decimal degrees, -90 to 90, e.g. -23.5613.")
            String latitude,
            @P("Longitude in decimal degrees, -180 to 180, e.g. -46.6565.")
            String longitude) {
        Double lat = parseCoordinate(latitude, 90);
        Double lon = parseCoordinate(longitude, 180);
        if (lat == null || lon == null) {
            return coordinateComplaint(latitude, longitude);
        }
        var query = new LinkedHashMap<String, String>();
        query.put("lat", Double.toString(lat));
        query.put("lon", Double.toString(lon));
        query.put("format", "jsonv2");
        return json.project(http.get(NOMINATIM_API, "/reverse", query),
                "display_name",
                "address.road",
                "address.suburb",
                "address.city",
                "address.state",
                "address.postcode",
                "address.country").toModelText();
    }

    @Tool("""
            Get sunrise, sunset, solar noon and day length for a place on a date. Use \
            for "what time does the sun set", golden-hour, fasting and prayer-time \
            questions. Requires coordinates — use find_place first. All times come back \
            in UTC and must be converted to the place's own timezone before answering.""")
    public String get_sun_times(
            @P("Latitude in decimal degrees, -90 to 90, e.g. -23.55.")
            String latitude,
            @P("Longitude in decimal degrees, -180 to 180, e.g. -46.63.")
            String longitude,
            @P("The day, as an ISO date like 2026-08-14. Leave empty for today.")
            String date) {
        Double lat = parseCoordinate(latitude, 90);
        Double lon = parseCoordinate(longitude, 180);
        if (lat == null || lon == null) {
            return coordinateComplaint(latitude, longitude);
        }
        String day = "today";
        if (date != null && !date.isBlank()) {
            LocalDate parsed = parseDate(date);
            if (parsed == null) {
                return "Invalid argument: the date must be an ISO date like 2026-08-14, got '" + date + "'.";
            }
            day = parsed.toString();
        }
        var query = new LinkedHashMap<String, String>();
        query.put("lat", Double.toString(lat));
        query.put("lng", Double.toString(lon));
        // formatted=0 asks for ISO-8601 UTC instead of a localized string that would
        // have to be re-parsed to be useful.
        query.put("formatted", "0");
        query.put("date", day);
        return json.project(http.get(SUN_API, "/json", query),
                "results.sunrise",
                "results.sunset",
                "results.solar_noon",
                "results.day_length").toModelText();
    }

    @Tool("""
            Resolve a postal code OUTSIDE Brazil to its town, region and coordinates — \
            a US ZIP, a Portuguese código postal, a German PLZ. Use when the user gives \
            a foreign postal code. Brazilian CEPs are not in this registry: for those \
            use lookup_cep in the brazil-civic-data skill instead.""")
    public String lookup_postal_code_intl(
            @P("ISO-3166 alpha-2 country code, e.g. 'us', 'pt' or 'de'.")
            String countryCode,
            @P("The postal code as written locally, e.g. '90210' or '1000-001'.")
            String postalCode) {
        String country = countryCode == null ? "" : countryCode.strip().toLowerCase(java.util.Locale.ROOT);
        if (!country.matches("[a-z]{2}")) {
            return "Invalid argument: expected a 2-letter country code such as 'us' or 'pt', got '"
                    + countryCode + "'.";
        }
        if ("br".equals(country)) {
            return "This registry has no Brazilian data — every Brazilian code returns not found. "
                    + "Use lookup_cep in the brazil-civic-data skill for a CEP.";
        }
        String postal = postalCode == null ? "" : postalCode.strip().replace(" ", "");
        if (!postal.matches("[A-Za-z0-9-]{3,10}")) {
            return "Invalid argument: a postal code here is 3 to 10 letters, digits or hyphens, got '"
                    + postalCode + "'.";
        }
        // Small enough to return whole: the probed record is ~225 bytes.
        return http.get(POSTAL_API, "/" + country + "/" + postal).toModelText();
    }

    private static String coordinateComplaint(String latitude, String longitude) {
        return "Invalid argument: latitude must be between -90 and 90 and longitude between -180 and 180, got '"
                + latitude + "' and '" + longitude + "'. Use find_place to get coordinates for a place name.";
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

    private static int clampDays(String raw) {
        if (raw == null || raw.isBlank()) {
            return 3;
        }
        try {
            return Math.clamp(Integer.parseInt(raw.strip()), 1, 7);
        } catch (NumberFormatException notANumber) {
            return 3;
        }
    }
}
