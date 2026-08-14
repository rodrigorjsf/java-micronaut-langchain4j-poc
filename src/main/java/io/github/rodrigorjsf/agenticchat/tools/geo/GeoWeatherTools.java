package io.github.rodrigorjsf.agenticchat.tools.geo;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.github.rodrigorjsf.agenticchat.skills.SkillTools;
import io.github.rodrigorjsf.agenticchat.tools.http.ToolHttpClient;
import jakarta.inject.Singleton;

import java.util.LinkedHashMap;

/**
 * Place lookup and weather, disclosed by the {@code geo-and-weather} skill.
 *
 * <p>{@code get_weather} takes coordinates and not a place name, deliberately. A
 * single "weather in X" tool would have to geocode internally, which hides the
 * ambiguity — there is a São Paulo in Brazil and one in Portugal — and leaves the
 * model unable to ask which one the user meant. Splitting the two puts the
 * disambiguation where it can actually be resolved.
 */
@Singleton
public class GeoWeatherTools implements SkillTools {

    private static final String GEOCODING_API = "open-meteo-geocoding";
    private static final String FORECAST_API = "open-meteo-forecast";

    /**
     * Requested explicitly rather than taking the API's defaults: the default
     * response carries far more fields than a chat answer needs, and every extra
     * field is context the model pays for on this turn and every later one.
     */
    private static final String CURRENT_FIELDS =
            "temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m";
    private static final String DAILY_FIELDS =
            "weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum";

    private final ToolHttpClient http;

    public GeoWeatherTools(ToolHttpClient http) {
        this.http = http;
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
