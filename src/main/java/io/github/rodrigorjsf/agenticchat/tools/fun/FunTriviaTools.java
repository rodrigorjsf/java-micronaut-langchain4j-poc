package io.github.rodrigorjsf.agenticchat.tools.fun;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.github.rodrigorjsf.agenticchat.skills.SkillTools;
import io.github.rodrigorjsf.agenticchat.tools.http.ToolHttpClient;
import io.github.rodrigorjsf.agenticchat.tools.http.ToolJson;
import jakarta.inject.Singleton;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Cards, creature stats, jokes and trivia, disclosed by the {@code fun-and-trivia}
 * skill.
 *
 * <p>This is not a joke drawer. A chat assistant is asked light questions
 * constantly — deal me a hand, how strong is Charizard, tell me something I don't
 * know — and the failure mode of answering them from memory is quiet: an invented
 * base stat and a real one are stated with exactly the same confidence, and nobody
 * checks a fun fact. Every tool here replaces a guess with a lookup, which is the
 * same reason the package-registry tools exist in {@code developer-tools}.
 *
 * <p><b>Every tool here would naturally take no arguments, and none of them does.</b>
 * A zero-argument {@code @Tool} makes {@code ToolSpecifications} emit
 * {@code parameters() == null}: legal, but it hands the model a button with no
 * steering. Each tool instead carries the optional knob its own upstream already
 * supports — a breed for the dog photo, a maximum length for the cat fact, a
 * category for either joke source, a topic for the advice search, a count for the
 * quotes. None of these were invented to satisfy the rule; each one was probed
 * against the live service before it was written.
 *
 * <p>Three properties of these sources shaped the code:
 *
 * <ul>
 *   <li><b>"Nothing matched" arrives as a {@code 200}.</b> An impossible
 *       {@code max_length} answers {@code {}}, an advice search with no hits answers
 *       a {@code message} envelope with no {@code slips}, and JokeAPI answers
 *       {@code {"error":true,…}} — all with a {@code 200}, so none of them reach
 *       {@link ToolHttpClient}'s status mapping. Projected, each collapses to
 *       {@code {}}, which reads to a model as a real answer with no fields. They are
 *       turned into an explicit sentence here instead.</li>
 *   <li><b>PokeAPI has no field filter and answers in hundreds of kilobytes.</b>
 *       Measured on this machine: 290 KB for Pikachu, 427 KB for Mewtwo, 664 KB for
 *       Mew — the move list of a species that learns almost everything. The byte
 *       budget in configuration is a transport ceiling and must sit above that,
 *       because a cut mid-object leaves {@link ToolJson} nothing to parse; the
 *       projection below is what actually keeps the context small, at roughly one
 *       kilobyte.</li>
 *   <li><b>Two payloads keep their picture links on purpose.</b> A drawn card and a
 *       dog photograph <em>are</em> images — stripping the link would leave the tool
 *       with nothing to return. Card objects are nested inside {@code cards}, which
 *       {@link ToolJson} cannot element-project, so the count cap is the only lever
 *       and it is set low deliberately.</li>
 * </ul>
 *
 * <p>The deck is the one piece of state in this class, and it does not live here:
 * {@code new_shuffled_deck} returns an id that the card service owns, and the
 * conversation is what carries it between turns. That is why dealing is two tools
 * rather than one — a single {@code deal(n)} would have to hold a deck per
 * conversation on this side, and a game where the cards silently reset is worse than
 * no game.
 */
@Singleton
public class FunTriviaTools implements SkillTools {

    private static final String DECK_OF_CARDS = "deckofcards";
    private static final String POKEAPI = "pokeapi";
    private static final String RICK_AND_MORTY = "rickandmorty";
    private static final String DOG_CEO = "dog-ceo";
    private static final String CAT_FACT = "catfact";
    private static final String CHUCK_NORRIS = "chucknorris";
    private static final String JOKE_API = "jokeapi";
    private static final String USELESS_FACTS = "uselessfacts";
    private static final String ADVICE_SLIP = "adviceslip";
    private static final String QUOTES = "dummyjson-quotes";

    /**
     * The service's own categories, minus {@code explicit}. A comma-separated string
     * rather than a set because a static field holding a collection is shared mutable
     * state whatever the declared type promises; {@link #canonical} splits it.
     */
    private static final String SAFE_CHUCK_CATEGORIES =
            "animal,career,celebrity,dev,fashion,food,history,money,movie,music,"
                    + "political,religion,science,sport,travel";

    /**
     * JokeAPI also serves {@code Dark}, {@code Spooky} and {@code Christmas}. Only
     * these three are offered: the first is not something a general assistant should
     * be able to reach for, and the seasonal ones answer nothing for eleven months.
     */
    private static final String JOKE_CATEGORIES = "Programming,Misc,Pun";

    /**
     * Belt and braces on the same guarantee, because they fail differently.
     * {@code safe-mode} is the documented switch but is presence-based upstream, and
     * this client drops a parameter with a blank value — so it is sent with a value,
     * and the explicit flag blacklist is sent alongside it in case that value is not
     * what the switch expects.
     */
    private static final String BLOCKED_JOKE_FLAGS =
            "nsfw,religious,political,racist,sexist,explicit";

    /** Ten cards is already ~2.4 KB, because each card carries three picture links. */
    private static final int MAX_CARDS = 10;

    /** Six decks is what the service allows, and more than any table game needs. */
    private static final int MAX_DECKS = 6;

    private final ToolHttpClient http;
    private final ToolJson json;

    public FunTriviaTools(ToolHttpClient http, ToolJson json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public String skillName() {
        return "fun-and-trivia";
    }

    // ------------------------------------------------------------------
    // Playing cards — two tools because the deck is state the model must carry
    // ------------------------------------------------------------------

    @Tool("""
            Shuffle a fresh deck of playing cards and get the deck id that identifies \
            it. Use when the user wants to play a card game, be dealt a hand, pick a \
            card at random or settle something by drawing. No cards come back from \
            this — the deck lives on the card service and only its id is returned, so \
            keep that id and pass it to draw_cards, which is what actually deals.""")
    public String new_shuffled_deck(
            @P("How many 52-card decks to shuffle together, 1 to 6. Defaults to 1. Blackjack tables use 6.")
            String deckCount) {
        var query = Map.of("deck_count", Integer.toString(clamp(deckCount, 1, MAX_DECKS, 1)));
        // success is a constant on the happy path and shuffled is always true on this
        // route; the id and the count are the whole answer.
        return json.project(http.get(DECK_OF_CARDS, "/new/shuffle/", query),
                "deck_id", "remaining").toModelText();
    }

    @Tool("""
            Deal cards off a deck and get each one's value, suit and picture, plus how \
            many cards are left. Requires a deck id from new_shuffled_deck — call that \
            first unless the conversation already produced an id you can reuse. Cards \
            are not put back, so a second draw from the same deck deals different \
            cards, which is what makes a real hand possible.""")
    public String draw_cards(
            @P("The deck id returned by new_shuffled_deck, a short lowercase code, e.g. 5f65ecm2q8a8.")
            String deckId,
            @P("How many cards to deal, 1 to 10. Defaults to 5.")
            String count) {
        String deck = deckId == null ? "" : deckId.strip().toLowerCase(Locale.ROOT);
        if (!deck.matches("[a-z0-9]{6,32}")) {
            return "Invalid argument: a deck id is a short code of letters and digits such as "
                    + "5f65ecm2q8a8, got '" + deckId + "'. Call new_shuffled_deck to get one.";
        }
        var query = Map.of("count", Integer.toString(clamp(count, 1, MAX_CARDS, 5)));
        // cards is a nested array, so its elements keep their image links. That is the
        // point of a card and the cap on count is what bounds the cost.
        return json.project(http.get(DECK_OF_CARDS, "/" + deck + "/draw/", query),
                "deck_id", "remaining", "cards").toModelText();
    }

    // ------------------------------------------------------------------
    // Characters and creatures
    // ------------------------------------------------------------------

    @Tool("""
            Get a Pokémon's national number, types, height, weight and its six base \
            stats. Use whenever a user names a Pokémon and asks how strong it is, how \
            big, which type, or how two of them compare. Takes the English name in \
            lowercase or the Pokédex number. Height comes back in decimetres and \
            weight in hectograms — convert both before quoting them.""")
    public String get_pokemon(
            @P("The Pokémon's English name in lowercase, e.g. 'pikachu' or 'mr-mime', or its Pokédex number, e.g. '25'.")
            String pokemon) {
        String name = pokemon == null ? "" : pokemon.strip().toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z0-9][a-z0-9-]{0,29}")) {
            return "Invalid argument: expected a Pokémon name in lowercase such as 'pikachu', or a "
                    + "Pokédex number such as '25', got '" + pokemon + "'. Spaces are written as "
                    + "hyphens, as in 'mr-mime'.";
        }
        // The raw record runs from 160 KB to 664 KB: every move the species can learn,
        // every sprite in every game, every version-group entry. Six fields answer the
        // question that was asked.
        return json.project(http.get(POKEAPI, "/pokemon/" + name),
                "id", "name", "height", "weight", "types", "stats").toModelText();
    }

    @Tool("""
            Get a Rick and Morty character's name, species, gender, alive-or-dead \
            status, origin dimension and last known location. Use when the user asks \
            about someone from the show. Takes the character's number, not a name: \
            Rick is 1, Morty 2, Summer 3, Beth 4, Jerry 5, and the skill instructions \
            list more. An unrecognised number returns no result rather than a guess.""")
    public String get_rickandmorty_character(
            @P("The character's number in the show's catalogue, 1 or higher, e.g. 1 for Rick Sanchez.")
            String characterId) {
        Integer id = positiveInt(characterId);
        if (id == null) {
            return "Invalid argument: expected a character number such as 1 for Rick or 2 for Morty, "
                    + "got '" + characterId + "'. This tool cannot look a character up by name.";
        }
        // origin and location are kept whole rather than projected to origin.name and
        // location.name: a projected path is stored under its LAST segment, so both
        // would land on the key "name" and overwrite the character's own name.
        // The extra cost is two link fields; the alternative is a wrong answer.
        return json.project(http.get(RICK_AND_MORTY, "/character/" + id),
                "id", "name", "status", "species", "gender", "origin", "location").toModelText();
    }

    @Tool("""
            Get the link to a photograph of a dog, optionally of one breed. Use when \
            the user asks for a dog picture, wants cheering up, or is curious what a \
            breed actually looks like. Leave the breed empty for any dog. Breeds are \
            single lowercase words — 'beagle', 'pug', 'corgi' — and a sub-breed is \
            written master first, as in 'hound/afghan'.""")
    public String get_random_dog_image(
            @P("The breed in lowercase, e.g. 'beagle', or 'hound/afghan' for a sub-breed. Leave empty for any dog.")
            String breed) {
        if (breed == null || breed.isBlank()) {
            return http.get(DOG_CEO, "/breeds/image/random").toModelText();
        }
        String clean = breed.strip().toLowerCase(Locale.ROOT).replace(' ', '/');
        if (!clean.matches("[a-z]{2,20}(/[a-z]{2,20})?")) {
            return "Invalid argument: expected one lowercase breed such as 'beagle', or "
                    + "'hound/afghan' for a sub-breed, got '" + breed + "'. This directory has no "
                    + "mixed breeds and no breed descriptions.";
        }
        // Small enough to return whole: one link and a status word, 94 bytes measured.
        return http.get(DOG_CEO, "/breed/" + clean + "/images/random").toModelText();
    }

    // ------------------------------------------------------------------
    // Jokes, facts, advice and quotes
    // ------------------------------------------------------------------

    @Tool("""
            Get a short factual statement about cats. Use when the user asks for a cat \
            fact, animal trivia, or something small to fill a moment. The optional \
            maximum length keeps the fact short enough for a caption or a message; \
            leave it empty for any length. Cats only — there is no equivalent for \
            other animals here.""")
    public String get_cat_fact(
            @P("Longest fact to accept, in characters, 20 to 400. Leave empty for any length.")
            String maxLength) {
        var query = new LinkedHashMap<String, String>();
        if (maxLength != null && !maxLength.isBlank()) {
            Integer max = positiveInt(maxLength);
            if (max == null || max < 20 || max > 400) {
                return "Invalid argument: the maximum length must be a number between 20 and 400 "
                        + "characters, got '" + maxLength + "'.";
            }
            query.put("max_length", Integer.toString(max));
        }
        return orNotFound(http.get(CAT_FACT, "/fact", query).toModelText(),
                "no cat fact is short enough for that limit. Raise the maximum length, or leave it "
                        + "empty, and ask again.");
    }

    @Tool("""
            Get a Chuck Norris one-liner: the deadpan, absurd kind about him being \
            unstoppable. Use when the user asks for one by name, or wants a quick \
            silly line and this style fits the conversation. The optional category \
            steers the subject — 'dev' for programming ones, 'food', 'movie'. For an \
            ordinary joke rather than this genre, use get_programming_joke.""")
    public String get_chuck_norris_joke(
            @P("Subject to draw from, e.g. 'dev', 'food', 'movie', 'science', 'sport'. Leave empty for any subject.")
            String category) {
        var query = new LinkedHashMap<String, String>();
        if (category != null && !category.isBlank()) {
            String chosen = canonical(SAFE_CHUCK_CATEGORIES, category);
            if (chosen == null) {
                return "Invalid argument: '" + category + "' is not one of the available subjects. "
                        + "Choose one of " + SAFE_CHUCK_CATEGORIES
                        + ", or leave it empty for any subject.";
            }
            query.put("category", chosen);
        }
        return orNotFound(json.project(http.get(CHUCK_NORRIS, "/jokes/random", query), "value")
                        .toModelText(),
                "the joke service returned nothing for that subject. Try another subject, or leave "
                        + "it empty.");
    }

    @Tool("""
            Get a clean one-line joke, about programming by default. Use when the user \
            asks for a joke, a laugh or something funny and there is no reason to \
            prefer the Chuck Norris style. The category picks the flavour: \
            'Programming' for developer humour, 'Pun' for wordplay, 'Misc' for general \
            jokes. Offensive material is filtered out and cannot be requested.""")
    public String get_programming_joke(
            @P("Which flavour of joke: 'Programming', 'Pun' or 'Misc'. Defaults to 'Programming'.")
            String category) {
        String chosen = category == null || category.isBlank()
                ? "Programming"
                : canonical(JOKE_CATEGORIES, category);
        if (chosen == null) {
            return "Invalid argument: '" + category + "' is not an available flavour. Choose "
                    + JOKE_CATEGORIES + ", or leave it empty for a programming joke.";
        }
        var query = new LinkedHashMap<String, String>();
        query.put("safe-mode", "true");
        query.put("blacklistFlags", BLOCKED_JOKE_FLAGS);
        // Two-part jokes arrive as separate setup and delivery fields, which the
        // projection below would silently drop; asking for single-line ones keeps the
        // answer whole.
        query.put("type", "single");
        // "Nothing matched" is a 200 here, carrying {"error":true,…} and no joke at
        // all, so the projection empties and the not-found branch speaks.
        return orNotFound(json.project(http.get(JOKE_API, "/joke/" + chosen, query), "joke")
                        .toModelText(),
                "every joke in that category was filtered out or none matched. Try another flavour.");
    }

    @Tool("""
            Get a random piece of trivia — a strange, true and entirely useless fact. \
            Use when the user asks for a fun fact, a random fact, or something \
            interesting they did not know. It returns whatever the collection offers \
            and cannot be pointed at a subject: for a fact about a particular topic, \
            use the knowledge-and-research skill instead.""")
    public String get_useless_fact(
            @P("Language of the fact: 'en' for English or 'de' for German. Defaults to 'en'.")
            String language) {
        String lang = language == null || language.isBlank()
                ? "en"
                : language.strip().toLowerCase(Locale.ROOT);
        if (!lang.equals("en") && !lang.equals("de")) {
            return "Invalid argument: this collection exists only in English ('en') and German "
                    + "('de'), got '" + language + "'. There is no Portuguese edition — translate "
                    + "the English fact instead.";
        }
        return orNotFound(json.project(http.get(USELESS_FACTS, "/facts/random",
                        Map.of("language", lang)), "text").toModelText(),
                "the trivia collection returned nothing this time.");
    }

    @Tool("""
            Get a short piece of everyday life advice. Use when the user asks for \
            advice in a light way, wants a nudge, or a thought for the day. An \
            optional one-word topic searches the collection — 'money', 'sleep', \
            'work'. This is a small set of aphorisms and not counselling: never answer \
            a real health, money or relationship problem from it.""")
    public String get_random_advice(
            @P("A single lowercase word to search for, e.g. 'money' or 'love'. Leave empty for one random piece of advice.")
            String topic) {
        if (topic == null || topic.isBlank()) {
            return orNotFound(json.project(http.get(ADVICE_SLIP, "/advice"), "slip.advice").toModelText(),
                    "the advice collection returned nothing this time.");
        }
        String word = topic.strip().toLowerCase(Locale.ROOT);
        if (!word.matches("[a-z]{3,20}")) {
            return "Invalid argument: the search takes one plain word of 3 to 20 letters, e.g. "
                    + "'money', got '" + topic + "'. Pick the single most important word, or leave "
                    + "it empty for a random piece of advice.";
        }
        // A search with no hits answers 200 with a notice envelope and no slips, which
        // projects away to nothing — the not-found branch is the only signal there is.
        return orNotFound(json.project(http.get(ADVICE_SLIP, "/advice/search/" + word),
                        "total_results", "slips").toModelText(),
                "no advice in this collection mentions '" + word + "'. It holds only a couple of "
                        + "hundred lines, so a miss is ordinary; offer a random one instead.");
    }

    @Tool("""
            Get quotations with their authors. Use when the user asks for a quote, an \
            inspiring line, or something to open a talk or a post with. Ask for \
            several when they want to choose. The collection is fixed and modest, and \
            it cannot be searched by topic or by author — for the words of one \
            particular person, use the knowledge-and-research skill.""")
    public String get_random_quote(
            @P("How many quotations to return, 1 to 5. Defaults to 1.")
            String count) {
        int wanted = clamp(count, 1, 5, 1);
        // A top-level array, so the cap applies to the elements directly and the id
        // each quotation carries is dropped with it.
        return orNotFound(json.projectCapped(http.get(QUOTES, "/random/" + wanted), wanted,
                        "quote", "author").toModelText(),
                "the quotation collection returned nothing this time.");
    }

    // ------------------------------------------------------------------

    /**
     * Matches {@code raw} against a comma-separated allow-list, ignoring case, and
     * returns the entry in the spelling the upstream expects. JokeAPI's categories
     * are capitalised and a model will send {@code programming}; normalising here
     * turns an avoidable 404 into a working call.
     *
     * @return the canonical entry, or {@code null} when it is not on the list
     */
    private static String canonical(String allowed, String raw) {
        String needle = raw.strip();
        for (String candidate : allowed.split(",")) {
            if (candidate.equalsIgnoreCase(needle)) {
                return candidate;
            }
        }
        return null;
    }

    private static Integer positiveInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int value = Integer.parseInt(raw.strip());
            return value >= 1 ? value : null;
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

    /**
     * Most sources behind this skill answer {@code 200} with an empty envelope when
     * nothing matched, so "not found" never reaches the HTTP status mapping. An empty
     * object handed to the model is worse than an error: it reads as a real answer
     * with no fields, and a model that has just been asked for a fun fact will
     * cheerfully supply one of its own.
     */
    private static String orNotFound(String text, String what) {
        if (text == null || text.isBlank() || text.equals("{}") || text.equals("[]")) {
            return "No result: " + what + " Tell the user nothing was found; do not invent one.";
        }
        return text;
    }
}
