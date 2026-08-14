package io.github.rodrigorjsf.agenticchat.tools.markets;

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
 * Cryptocurrency quotes and market rankings, disclosed by the
 * {@code crypto-and-markets} skill.
 *
 * <p>Every tool here returns a number that was true at a moment and is already
 * stale by the time the model reads it. That shapes two decisions:
 *
 * <ul>
 *   <li><b>Every quote carries its own timestamp.</b> {@code get_crypto_price}
 *       asks CoinGecko for {@code include_last_updated_at} even though the brief's
 *       URL does not, because a price with no time attached is a number the model
 *       will present as "current" hours later. {@code get_binance_ticker} carries
 *       {@code closeTime} for the same reason. {@code get_binance_price} is the one
 *       tool that cannot carry one — its description says so, and the skill tells
 *       the model to prefer the ticker whenever the number reaches the user.</li>
 *   <li><b>Two providers, deliberately.</b> CoinGecko sits behind Cloudflare and
 *       throttles the free tier aggressively; Binance is geo-restricted in some
 *       regions and answers {@code 451} there. Neither is reliable alone, and they
 *       fail for unrelated reasons, so the pair covers what one would not. Only
 *       CoinGecko can answer market-capitalisation questions; only Binance quotes an
 *       exchange pair directly.</li>
 * </ul>
 *
 * <p>CoinGecko answers an unknown coin id with {@code 200} and an empty object
 * rather than a {@code 404}, so "not found" never reaches
 * {@link ToolHttpClient}'s status mapping. It is turned into an explicit sentence
 * here, and that sentence names the actual mistake — a ticker symbol passed where
 * a CoinGecko id belongs, which is the failure this tool sees most.
 */
@Singleton
public class CryptoMarketsTools implements SkillTools {

    private static final String COINGECKO = "coingecko";
    private static final String BINANCE = "binance";

    /**
     * CoinGecko ids per call. Five coins is a comparison; more is a report.
     */
    private static final int MAX_COIN_IDS = 5;
    private static final int MAX_CURRENCIES = 3;

    private final ToolHttpClient http;
    private final ToolJson json;

    public CryptoMarketsTools(ToolHttpClient http, ToolJson json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public String skillName() {
        return "crypto-and-markets";
    }

    @Tool("""
            Get the current price and 24-hour change of one or more cryptocurrencies, \
            in US dollars, Brazilian reais or any other fiat currency. Use for "how \
            much is bitcoin worth" and for converting an amount of a coin into money. \
            Takes CoinGecko coin ids such as bitcoin or ethereum, never ticker symbols \
            like BTC. The result carries last_updated_at, which must be quoted with \
            the price.""")
    public String get_crypto_price(
            @P("One to five CoinGecko coin ids, comma separated, lowercase, e.g. 'bitcoin' or 'bitcoin,ethereum,solana'. These are full names, not tickers: 'bitcoin', not 'BTC'.")
            String coinIds,
            @P("One to three fiat currency codes, comma separated, e.g. 'usd' or 'usd,brl'. Leave empty for 'usd,brl'.")
            String currencies) {
        if (coinIds == null || coinIds.isBlank()) {
            return "Invalid argument: at least one CoinGecko coin id is required, e.g. 'bitcoin'. "
                    + "Ask the user which coin they mean if it is not clear.";
        }
        String ids = normalisedList(coinIds, MAX_COIN_IDS, "[a-z0-9][a-z0-9-]{1,40}");
        if (ids == null) {
            return "Invalid argument: expected up to " + MAX_COIN_IDS + " comma-separated CoinGecko "
                    + "coin ids in lowercase, e.g. 'bitcoin,ethereum', got '" + coinIds
                    + "'. Ticker symbols such as BTC are not accepted here.";
        }
        String fiats = currencies == null || currencies.isBlank()
                ? "usd,brl"
                : normalisedList(currencies, MAX_CURRENCIES, "[a-z]{3}");
        if (fiats == null) {
            return "Invalid argument: expected up to " + MAX_CURRENCIES + " comma-separated "
                    + "three-letter currency codes, e.g. 'usd,brl', got '" + currencies + "'.";
        }

        var query = new LinkedHashMap<String, String>();
        query.put("ids", ids);
        query.put("vs_currencies", fiats);
        query.put("include_24hr_change", "true");
        // Not decoration: without it the answer is a bare number the model will
        // happily call "current" on a later turn, hours after it was true.
        query.put("include_last_updated_at", "true");
        // The whole body is ~230 bytes and its keys are the coin ids themselves,
        // so there is no fixed field list to project against.
        var response = http.get(COINGECKO, "/simple/price", query);
        return orNotFound(response.toModelText(),
                "CoinGecko knows no coin with the id '" + ids + "'. These are ids, not tickers — "
                        + "'bitcoin' rather than 'BTC', 'ethereum' rather than 'ETH'.");
    }

    @Tool("""
            List the largest cryptocurrencies by market capitalisation, with their \
            price, rank and 24-hour change in a chosen currency. Use for "top coins", \
            "biggest cryptocurrencies" or a market overview, not to price a single \
            coin the user already named — get_crypto_price does that in one call.""")
    public String get_crypto_market_ranking(
            @P("The fiat currency the prices are quoted in, three letters, e.g. 'usd' or 'brl'. Leave empty for 'usd'.")
            String currency,
            @P("How many coins to list, 1 to 5. Defaults to 5.")
            String count) {
        String fiat = currency == null || currency.isBlank()
                ? "usd"
                : currency.strip().toLowerCase(Locale.ROOT);
        if (!fiat.matches("[a-z]{3}")) {
            return "Invalid argument: expected a three-letter currency code such as 'usd' or 'brl', got '"
                    + currency + "'.";
        }
        int size = clamp(count, 1, 5, 5);

        var query = new LinkedHashMap<String, String>();
        query.put("vs_currency", fiat);
        query.put("per_page", Integer.toString(size));
        query.put("page", "1");
        // The raw rows carry an image URL, a 7-day sparkline and the whole
        // all-time-high block — several kilobytes per coin that no answer quotes,
        // and that would be replayed into every later prompt in the conversation.
        var response = json.projectCapped(http.get(COINGECKO, "/coins/markets", query), size,
                "id", "symbol", "name", "current_price", "market_cap", "market_cap_rank",
                "price_change_percentage_24h", "last_updated");
        return orNotFound(response.toModelText(),
                "CoinGecko has no ranking priced in '" + fiat + "'. Try 'usd' or 'brl'.");
    }

    @Tool("""
            Get the 24-hour trading statistics of one Binance pair: last price, \
            percentage change, high, low and traded volume. Use when the user names an \
            exchange pair such as BTCUSDT, when they ask for the day's high or low, or \
            as a second source when get_crypto_price is rate limited. Returns \
            closeTime, the timestamp to quote with the price.""")
    public String get_binance_ticker(
            @P("The trading pair written without a separator, e.g. BTCUSDT, ETHUSDT or BTCBRL. Case is ignored.")
            String symbol) {
        String pair = normalisedSymbol(symbol);
        if (pair == null) {
            return symbolComplaint(symbol);
        }
        return json.project(http.get(BINANCE, "/ticker/24hr", Map.of("symbol", pair)),
                "symbol", "lastPrice", "priceChangePercent", "highPrice", "lowPrice",
                "volume", "closeTime").toModelText();
    }

    @Tool("""
            Get only the last traded price of a Binance pair — the cheapest quote in \
            this skill. Use when one number is enough and the pair is already known. It \
            carries no timestamp, so when the price will be shown to the user call \
            get_binance_ticker instead and quote its closeTime.""")
    public String get_binance_price(
            @P("The trading pair written without a separator, e.g. BTCUSDT or ETHUSDT. Case is ignored.")
            String symbol) {
        String pair = normalisedSymbol(symbol);
        if (pair == null) {
            return symbolComplaint(symbol);
        }
        // 44 bytes: two fields, nothing worth projecting away.
        return http.get(BINANCE, "/ticker/price", java.util.Map.of("symbol", pair)).toModelText();
    }

    // ------------------------------------------------------------------

    /**
     * Binance writes pairs as one word. Models reliably produce {@code BTC/USDT} or
     * {@code btc-usdt}, and both name the pair the user meant, so they are repaired
     * rather than refused — a rejected call the model has to fix costs a round trip
     * to say something the tool already knew.
     */
    private static String normalisedSymbol(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String pair = raw.strip().toUpperCase(Locale.ROOT).replaceAll("[\\s/_-]", "");
        return pair.matches("[A-Z0-9]{5,20}") ? pair : null;
    }

    private static String symbolComplaint(String symbol) {
        return "Invalid argument: a Binance pair is written as one word, e.g. BTCUSDT, not BTC/USDT, got '"
                + symbol + "'. Ask the user which pair they mean, or use get_crypto_price with a "
                + "CoinGecko coin id instead.";
    }

    /**
     * Normalises a comma-separated argument to lowercase and validates each element
     * against {@code element}, returning {@code null} when anything fails. Doing it
     * here rather than at the HTTP layer means a bad list comes back as a sentence
     * the model can act on instead of an upstream 400 it usually gives up on.
     */
    private static String normalisedList(String raw, int max, String element) {
        String[] parts = raw.strip().toLowerCase(Locale.ROOT).split(",");
        if (parts.length > max) {
            return null;
        }
        var out = new StringBuilder();
        for (String part : parts) {
            String clean = part.strip();
            if (!clean.matches(element)) {
                return null;
            }
            if (!out.isEmpty()) {
                out.append(',');
            }
            out.append(clean);
        }
        return out.isEmpty() ? null : out.toString();
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
     * CoinGecko answers {@code 200} with {@code {}} for a coin id it does not know,
     * so "not found" never reaches the HTTP status mapping. An empty object handed
     * to the model is worse than an error: it reads as a real answer with no fields.
     */
    private static String orNotFound(String text, String what) {
        if (text == null || text.isBlank() || text.equals("{}") || text.equals("[]")) {
            return "No result: " + what + " Tell the user nothing was found; do not guess a price.";
        }
        return text;
    }
}
