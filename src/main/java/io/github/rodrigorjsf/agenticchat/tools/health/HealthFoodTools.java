package io.github.rodrigorjsf.agenticchat.tools.health;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.github.rodrigorjsf.agenticchat.skills.SkillTools;
import io.github.rodrigorjsf.agenticchat.tools.http.ToolHttpClient;
import io.github.rodrigorjsf.agenticchat.tools.http.ToolJson;
import io.github.rodrigorjsf.agenticchat.tools.http.ToolResponse;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Packaged-food nutrition, recipes and drinks, disclosed by the
 * {@code health-and-food} skill.
 *
 * <p>Everything here returns <em>data about a product or a dish</em>. Nothing here
 * evaluates a diet, and the class is shaped so the model cannot mistake one for the
 * other: a Nutri-Score letter and a nutriment table arrive as recorded values, never
 * as a judgement, and the skill document forbids turning either into advice.
 *
 * <p>Two properties of Open Food Facts drive the food tools, and both were confirmed
 * against live responses rather than assumed:
 *
 * <ul>
 *   <li><b>An unknown barcode is a {@code 200}, not a {@code 404}.</b> The body is
 *       {@code {"code":"…","status":0,"status_verbose":"…"}} with no {@code product}
 *       key at all, so "not found" never reaches {@link ToolHttpClient}'s status
 *       mapping. Projecting it yields {@code {}}, which reads to a model as a real
 *       answer with no fields — so it is turned into an explicit sentence here.</li>
 *   <li><b>A product that exists may carry no nutrition at all.</b> One probed
 *       barcode came back {@code status: 1} with a populated name and brand,
 *       {@code nutriscore_grade: "unknown"} and a {@code nutriments} object holding
 *       none of the per-100 g fields. That is a found product with unrecorded data,
 *       which is a different answer from "not found", so it deliberately falls
 *       through as a partial record and the skill teaches how to read it.</li>
 * </ul>
 *
 * <p>The two food tools deliberately sit on different Open Food Facts hosts. The
 * legacy {@code cgi/search.pl} route answered {@code 503} on eight of nine attempts
 * while it was being measured, so name search runs on the project's current search
 * service instead, which answered {@code 200} in 0.66 s on five consecutive calls
 * and honours the same {@code fields} whitelist. Splitting the catalogue key is what
 * lets that move be a configuration change: the barcode route and the search route
 * can now be pointed, budgeted and retried independently.
 *
 * <p>The two recipe sources are unbounded at the source: their search routes accept
 * no result limit, and an ordinary food word answers with everything that matches.
 * Measured 2026-08-14: pie 68 935 B, cake 63 514 B, chicken 59 786 B, soup 54 843 B,
 * bread 57 439 B — against pasta at 2 247 B. So the bound is applied here, with
 * {@link ToolJson#projectList}: the nested list is capped at three and each element
 * keeps only the fields an answer quotes.
 *
 * <p>That keep-list is also a security control, which is the part worth stating
 * plainly. A recipe record carries {@code strSource}, {@code strYoutube} and
 * {@code strMealThumb}; a cocktail carries {@code strImageSource}. Those are
 * third-party URLs, and a response that quotes one is withheld <em>whole</em> by the
 * output link policy — so the user asking the most ordinary question in this skill
 * would get nothing at all, with no clue why. Enumerating what to keep removes the
 * category; a deny-list would have to be right about every field the sources add
 * next.
 */
@Singleton
public class HealthFoodTools implements SkillTools {

    private static final String OPEN_FOOD_FACTS = "openfoodfacts";
    private static final String OPEN_FOOD_FACTS_SEARCH = "openfoodfacts-search";
    private static final String THEMEALDB = "themealdb";
    private static final String THECOCKTAILDB = "thecocktaildb";
    private static final String OPEN_BREWERY_DB = "openbrewerydb";

    /**
     * Mandatory, not an optimisation: the full Open Food Facts record for a single
     * product runs to roughly 100 KB — every photo, every contributor edit and every
     * derived score. With this whitelist the same lookup answered 1 269 bytes.
     */
    private static final String PRODUCT_FIELDS =
            "product_name,brands,quantity,nutriscore_grade,ingredients_text,allergens_tags,nutriments";

    /**
     * The search service has its own whitelist; a hit needs only enough to be chosen
     * between, and its {@code code} is what the barcode tool then takes.
     */
    private static final String SEARCH_FIELDS = "product_name,brands,nutriscore_grade,code";

    /** Three is enough to say "there are several"; a fourth is never quoted in an answer. */
    private static final int MAX_RECIPES = 3;

    /**
     * The fields a recipe answer quotes, built per call rather than held in a static
     * array — a static field of array type is shared mutable state whatever it holds,
     * and forty strings cost nothing beside an HTTP round trip.
     */
    private static String[] recipeFields() {
        var fields = new ArrayList<String>(List.of("strMeal", "strCategory", "strArea", "strInstructions"));
        for (int slot = 1; slot <= 20; slot++) {
            fields.add("strIngredient" + slot);
            fields.add("strMeasure" + slot);
        }
        return fields.toArray(String[]::new);
    }

    /** As {@link #recipeFields()}; a cocktail has fifteen ingredient slots, not twenty. */
    private static String[] cocktailFields() {
        var fields = new ArrayList<String>(
                List.of("strDrink", "strCategory", "strAlcoholic", "strGlass", "strInstructions"));
        for (int slot = 1; slot <= 15; slot++) {
            fields.add("strIngredient" + slot);
            fields.add("strMeasure" + slot);
        }
        return fields.toArray(String[]::new);
    }

    private final ToolHttpClient http;
    private final ToolJson json;

    public HealthFoodTools(ToolHttpClient http, ToolJson json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public String skillName() {
        return "health-and-food";
    }

    @Tool("""
            Look up a packaged food by its barcode and get the recorded product name, \
            brand, pack size, ingredients, declared allergens, Nutri-Score letter and \
            the energy, fat, saturates, carbohydrate, sugar, protein and salt per 100 \
            grams. Use when the user reads out the number under the barcode of \
            something they bought. Requires the barcode — use search_food_products \
            when they only know the product name. The data is contributed by \
            volunteers and any field may simply be missing.""")
    public String get_food_product_by_barcode(
            @P("The number printed under the bar lines, 8 to 14 digits. Spaces and hyphens are ignored, e.g. 7891000315507 or 3017620422003.")
            String barcode) {
        String digits = barcode == null ? "" : barcode.replaceAll("[^0-9]", "");
        if (digits.length() < 8 || digits.length() > 14) {
            return "Invalid argument: a product barcode has 8 to 14 digits, got '" + barcode
                    + "'. Ask the user to read the number under the bar lines again, or use "
                    + "search_food_products if they only know the product name.";
        }
        var response = json.project(
                http.get(OPEN_FOOD_FACTS, "/product/" + digits + ".json", Map.of("fields", PRODUCT_FIELDS)),
                "product.product_name",
                "product.brands",
                "product.quantity",
                "product.nutriscore_grade",
                "product.ingredients_text",
                "product.allergens_tags",
                // The per-100 g keys, chosen to match a printed nutrition label. The
                // raw nutriments object also carries per-serving values, unit markers
                // and estimated-from-ingredients variants of each one.
                "product.nutriments.energy-kcal_100g",
                "product.nutriments.fat_100g",
                "product.nutriments.saturated-fat_100g",
                "product.nutriments.carbohydrates_100g",
                "product.nutriments.sugars_100g",
                "product.nutriments.proteins_100g",
                "product.nutriments.salt_100g");
        return orNotFound(response.toModelText(),
                "Open Food Facts holds no product with barcode " + digits
                        + ". The database is volunteer-contributed and far from complete, so this "
                        + "means the product was never added, not that it does not exist.");
    }

    @Tool("""
            Search packaged foods by product or brand name and get each match with its \
            brand, Nutri-Score letter and barcode. Use when the user names something \
            they bought but cannot read out a barcode; then take the barcode from the \
            best match and call get_food_product_by_barcode for the full nutrition \
            table. Returns candidates to choose between, not a nutrition table.""")
    public String search_food_products(
            @P("Words from the product or brand name, e.g. 'nutella' or 'leite condensado'. At least 2 characters.")
            String query,
            @P("How many products to return, 1 to 5. Defaults to 5.")
            String limit) {
        if (query == null || query.strip().length() < 2) {
            return "Invalid argument: the search needs at least 2 characters, got '" + query
                    + "'. Ask the user which product or brand they mean.";
        }
        String clean = query.strip();
        if (clean.length() > 80) {
            return "Invalid argument: that search text is too long to be a product name. "
                    + "Give the brand and the product, e.g. 'nutella' or 'leite moça'.";
        }

        var params = new LinkedHashMap<String, String>();
        params.put("q", clean);
        params.put("page_size", Integer.toString(clamp(limit, 1, 5, 5)));
        params.put("fields", SEARCH_FIELDS);

        var raw = http.get(OPEN_FOOD_FACTS_SEARCH, "/search", params);
        if (raw.truncated()) {
            return "That search returned more than this tool can read. Ask the user for the brand "
                    + "as well as the product name and search once more.";
        }
        // count alongside the hits: "631 products match, showing 5" is a different
        // answer from "5 products match", and the model cannot tell them apart from
        // the list alone. Everything else in the envelope — aggregations, facets,
        // charts, timings — describes the query rather than the food.
        var response = json.project(raw, "count", "hits");
        String text = response.toModelText();
        if (response.isOk() && (text.contains("\"hits\":[]") || !text.contains("\"hits\""))) {
            return "No result: no packaged food is catalogued under '" + clean
                    + "'. Tell the user nothing was found; do not guess a nutrition value.";
        }
        return text;
    }

    @Tool("""
            Find a cooking recipe by dish name and get its category, cuisine, \
            ingredient list with quantities and step-by-step instructions. Leave the \
            dish empty for a random recipe when the user wants a suggestion, says \
            "surprise me" or asks what to cook. Give a dish name and not an \
            ingredient: 'lasagne' names a dish, while 'chicken' matches dozens of \
            recipes and comes back too large to read.""")
    public String search_recipe(
            @P("The dish name, e.g. 'arrabiata' or 'lasagne'. Leave empty to get one random recipe as a suggestion.")
            String dish) {
        if (dish == null || dish.isBlank()) {
            // No arguments to validate and one meal to return: the random route is
            // ~1.6 KB and cannot overrun the budget.
            return recipeText(http.get(THEMEALDB, "/random.php"),
                    "the recipe service returned no dish this time. Try again or ask the user "
                            + "for a cuisine they like.");
        }
        String clean = dish.strip();
        if (clean.length() > 60) {
            return "Invalid argument: that text is too long to be a dish name. Give the name of "
                    + "one dish, e.g. 'arrabiata'.";
        }
        var raw = http.get(THEMEALDB, "/search.php", Map.of("s", clean));
        if (raw.truncated()) {
            // The budget clears every measured search, so reaching here means the
            // catalogue grew past it. Say that, and do not diagnose the user's word:
            // an earlier version of this message told them "chicken" was an ingredient
            // rather than a dish, and then said the same of soup, pie and cake.
            return "'" + clean + "' returned more recipe data than this tool can read back. Ask the "
                    + "user for a more specific dish name and try once more. Do not answer from a "
                    + "partial recipe.";
        }
        return recipeText(raw, "no recipe is catalogued under '" + clean
                + "'. This catalogue is small and Anglo-centric, so a missing dish is normal; "
                + "do not invent a recipe.");
    }

    @Tool("""
            Find a cocktail recipe by name and get its glass, ingredient list with \
            measures and mixing instructions. Use when the user names a drink — \
            'caipirinha', 'negroni' — or asks how one is made. Requires a name; it \
            cannot suggest a random drink. A very common name such as 'margarita' \
            returns several variants, so present one and say the others exist.""")
    public String search_cocktail(
            @P("The cocktail name, e.g. 'caipirinha' or 'negroni'. At least 3 characters.")
            String name) {
        if (name == null || name.strip().length() < 3) {
            return "Invalid argument: a cocktail name needs at least 3 characters, got '" + name
                    + "'. Ask the user which drink they mean.";
        }
        String clean = name.strip();
        if (clean.length() > 60) {
            return "Invalid argument: that text is too long to be a cocktail name. Give the name "
                    + "of one drink, e.g. 'negroni'.";
        }
        var raw = http.get(THECOCKTAILDB, "/search.php", Map.of("s", clean));
        if (raw.truncated()) {
            return "'" + clean + "' returned more drink data than this tool can read back. Ask the "
                    + "user for the full name of the variant they mean, e.g. \"Tommy's Margarita\" "
                    + "rather than \"margarita\". Do not answer from a partial recipe.";
        }
        return listOrNothing(json.projectList(raw, "drinks", MAX_RECIPES, cocktailFields()), "drinks",
                "no cocktail is catalogued under '" + clean + "'. Do not invent a recipe for a drink "
                        + "the catalogue does not have.");
    }

    @Tool("""
            List breweries in a city with their type, the region they are in and the \
            country. Use for "breweries in X", "where is there a brewpub" and \
            beer-tourism questions. Coverage is strongest in the United States and \
            thin elsewhere, so an empty list means nothing is catalogued for that \
            city, not that the city has no breweries.""")
    public String search_breweries(
            @P("The city name, e.g. 'san diego', 'portland' or 'dublin'. Spaces are handled for you.")
            String city,
            @P("How many breweries to list, 1 to 5. Defaults to 3.")
            String limit) {
        if (city == null || city.strip().length() < 2) {
            return "Invalid argument: a city name needs at least 2 characters, got '" + city
                    + "'. Ask the user which city they mean.";
        }
        String clean = city.strip().toLowerCase(Locale.ROOT);
        if (clean.length() > 60 || !clean.matches("[\\p{L}][\\p{L} .'-]*")) {
            return "Invalid argument: expected a plain city name such as 'san diego', got '" + city
                    + "'. A state or a country is not accepted here.";
        }
        int size = clamp(limit, 1, 5, 3);
        var params = new LinkedHashMap<String, String>();
        // The API matches on underscore-joined city names.
        params.put("by_city", clean.replaceAll("[\\s.']+", "_"));
        params.put("per_page", Integer.toString(size));

        // A top-level array, so the cap applies directly. state_province duplicates
        // state on every probed record, and latitude, longitude, phone and the four
        // address lines are not part of any answer this skill gives.
        //
        // website_url is left out deliberately. Probed 2026-08-14, san_diego returns
        // 10barrel.com, www.2kidsBrewing.com and 42northbrewing.com — none of them a
        // host the tool catalogue reaches, so an answer quoting one is withheld whole
        // by the output link policy and the user gets a blank refusal for "breweries
        // in San Diego". A brewery's name is what the user searches for anyway.
        var response = json.projectCapped(http.get(OPEN_BREWERY_DB, "/breweries", params), size,
                "name", "brewery_type", "city", "state", "country");
        return orNotFound(response.toModelText(),
                "no brewery is catalogued in '" + city.strip() + "'. Coverage outside the United "
                        + "States is sparse; say the directory has nothing rather than implying the "
                        + "city has no breweries.");
    }

    // ------------------------------------------------------------------

    /**
     * Both recipe routes answer the same envelope, so they share the projection and
     * the empty check. A dish the catalogue does not hold comes back as
     * {@code {"meals":null}} rather than as a {@code 404}.
     */
    private String recipeText(ToolResponse raw, String nothingFound) {
        return listOrNothing(json.projectList(raw, "meals", MAX_RECIPES, recipeFields()),
                "meals", nothingFound);
    }

    /**
     * Both recipe sources report "nothing found" as a {@code null} list inside a
     * {@code 200}. {@link ToolJson#projectList} leaves a body it cannot treat as a
     * list untouched, so that null arrives here intact — which is the point. Turning
     * it into an empty envelope would hide it, and a model handed {@code {"meals":[]}}
     * reaches for what it remembers about the dish.
     */
    private static String listOrNothing(ToolResponse response, String field, String nothingFound) {
        if (!response.isOk()) {
            return response.toModelText();
        }
        String text = response.toModelText();
        String bare = text.replace(" ", "");
        if (bare.contains("\"" + field + "\":null") || bare.contains("\"" + field + "\":[]")) {
            return "No result: " + nothingFound + " Tell the user nothing was found; do not guess a value.";
        }
        return orNotFound(text, nothingFound);
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
     * Every source behind this skill answers {@code 200} with an empty envelope for
     * something it does not hold, so "not found" never reaches the HTTP status
     * mapping. An empty object or array handed to the model is worse than an error:
     * it reads as a real answer with no fields, and the model fills the gaps from
     * memory — which, for a nutrition table, means inventing numbers about food.
     */
    private static String orNotFound(String text, String what) {
        if (text == null || text.isBlank() || text.equals("{}") || text.equals("[]")) {
            return "No result: " + what + " Tell the user nothing was found; do not guess a value.";
        }
        return text;
    }
}
