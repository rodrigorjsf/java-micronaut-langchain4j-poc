package io.github.rodrigorjsf.agenticchat.tools.books;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.github.rodrigorjsf.agenticchat.skills.SkillTools;
import io.github.rodrigorjsf.agenticchat.tools.http.ToolHttpClient;
import io.github.rodrigorjsf.agenticchat.tools.http.ToolJson;
import jakarta.inject.Singleton;

import java.util.LinkedHashMap;
import java.util.Locale;

/**
 * Book and bibliographic lookup, disclosed by the {@code books-and-library} skill.
 *
 * <p>Two registries sit behind four tools, and the split is the interesting part.
 * Open Library is the international catalogue and the only one that answers a
 * free-text search; the Brazilian ISBN registry answers for national editions that
 * Open Library often does not carry at all. Neither is a superset of the other, so
 * both ISBN tools exist as separate tools the model chooses between rather than as
 * a hidden fallback chain — the choice depends on where the book was published,
 * which is something the model knows and the tool layer does not.
 *
 * <p>Searching and identifying are deliberately different tools. A title is
 * ambiguous — a dozen editions of "Clean Code" exist — while an ISBN identifies one
 * physical edition. {@code search_books} finds candidates, and the ISBN tools
 * describe one edition; asking either to do the other's job is what produces
 * confidently wrong page counts.
 *
 * <p>Response size is the constraint that shapes every call here. Open Library's
 * search route accepts a server-side {@code fields=} whitelist that takes three
 * results from kilobytes to 552 bytes, so it is always sent; its ISBN route accepts
 * no such filter and answers with the full edition record — table of contents,
 * excerpts and notes included — which is why that one is projected here and its
 * byte budget is sized for the raw body.
 */
@Singleton
public class BooksLibraryTools implements SkillTools {

    private static final String OPEN_LIBRARY = "openlibrary";
    private static final String BRASILAPI = "brasilapi";

    /**
     * Server-side projection, not cosmetics: the default search response carries
     * every edition, every cover id and every subject for each hit. This whitelist
     * is what keeps a three-result search inside a few hundred bytes.
     */
    private static final String SEARCH_FIELDS = "title,author_name,first_publish_year,key";

    private final ToolHttpClient http;
    private final ToolJson json;

    public BooksLibraryTools(ToolHttpClient http, ToolJson json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public String skillName() {
        return "books-and-library";
    }

    @Tool("""
            Find books by title, subject or any free-text description and get their \
            author, first publication year and Open Library key. Use whenever the \
            user names a book but not an ISBN, or asks which books exist about a \
            topic. Returns candidates to choose between, not a single answer.""")
    public String search_books(
            @P("Words from the title or subject, e.g. 'clean code' or 'história do Brasil colonial'.")
            String query,
            @P("How many results to return, 1 to 5. Defaults to 3.")
            String limit) {
        if (query == null || query.strip().length() < 2) {
            return "Invalid argument: the search needs at least 2 characters, got '" + query
                    + "'. Ask the user which book or subject they mean.";
        }
        var params = new LinkedHashMap<String, String>();
        params.put("q", query.strip());
        params.put("fields", SEARCH_FIELDS);
        params.put("limit", Integer.toString(clamp(limit, 1, 5, 3)));
        return searchAndProject(params, "no book matches '" + query.strip() + "'.");
    }

    @Tool("""
            List the books catalogued under one author's name, with their first \
            publication year. Use for "what did X write" or "other books by this \
            author" questions. Give the author's name as written on a cover; \
            search_books is the better tool when the user is describing a book \
            rather than naming its author.""")
    public String list_books_by_author(
            @P("The author's name as printed on a book, e.g. 'Machado de Assis' or 'Robert C. Martin'.")
            String author,
            @P("How many titles to return, 1 to 5. Defaults to 5.")
            String limit) {
        if (author == null || author.strip().length() < 3) {
            return "Invalid argument: an author name needs at least 3 characters, got '" + author
                    + "'. Ask the user for the author's full name.";
        }
        String clean = luceneSafe(author);
        if (clean.length() < 3) {
            return "Invalid argument: '" + author + "' has no letters or digits to search for.";
        }
        var params = new LinkedHashMap<String, String>();
        // A fielded query on the search route rather than a second endpoint: the
        // quotes keep a two-word name as one phrase, and the punctuation stripped
        // above is what would otherwise be read as query syntax rather than a name.
        params.put("q", "author_name:\"" + clean + "\"");
        params.put("fields", SEARCH_FIELDS);
        params.put("limit", Integer.toString(clamp(limit, 1, 5, 5)));
        return searchAndProject(params, "the catalogue lists no books by '" + clean + "'.");
    }

    @Tool("""
            Describe one specific edition of a book from its ISBN: title, publisher, \
            publication date and page count, from the international catalogue. Use \
            when the user gives an ISBN of a book published outside Brazil, or when \
            get_book_by_isbn_br found nothing. Requires an ISBN — use search_books \
            when you only have a title.""")
    public String get_book_by_isbn_intl(
            @P("A 10- or 13-digit ISBN. Hyphens and spaces are ignored, e.g. 978-0-13-235088-4.")
            String isbn) {
        String normalised = normaliseIsbn(isbn);
        if (normalised == null) {
            return isbnComplaint(isbn);
        }
        // The full edition record runs to 32 KB — table of contents, excerpts, notes
        // and every identifier the catalogue holds. There is no server-side field
        // filter on this route, so the budget is sized for the raw body and the
        // projection is what actually reaches the model.
        var raw = http.get(OPEN_LIBRARY, "/isbn/" + normalised + ".json");
        if (raw.truncated()) {
            return "That edition record was too large to read. Tell the user the details for ISBN "
                    + normalised + " could not be retrieved; do not answer from a partial record.";
        }
        var response = json.project(raw, "title", "publishers", "publish_date", "number_of_pages");
        return orNotFound(response.toModelText(),
                "the international catalogue has no edition with ISBN " + normalised + ".");
    }

    @Tool("""
            Describe a book from its ISBN using the Brazilian registry: title, \
            subtitle, authors, publisher, year, format and page count. Use first for \
            any book published in Brazil or written in Portuguese, because the \
            international catalogue often does not carry national editions. Requires \
            an ISBN.""")
    public String get_book_by_isbn_br(
            @P("A 10- or 13-digit ISBN. Hyphens and spaces are ignored, e.g. 978-85-457-0287-0.")
            String isbn) {
        String normalised = normaliseIsbn(isbn);
        if (normalised == null) {
            return isbnComplaint(isbn);
        }
        // This route shares the general BrasilAPI budget rather than owning one, so
        // the guard stays even though the probed record is barely a kilobyte: the
        // budget belongs to other tools too and can be tightened without this file
        // ever changing.
        var raw = http.get(BRASILAPI, "/isbn/v1/" + normalised);
        if (raw.truncated()) {
            return "That record was too large to read. Tell the user the details for ISBN "
                    + normalised + " could not be retrieved; do not answer from a partial record.";
        }
        var response = json.project(raw,
                "title", "subtitle", "authors", "publisher", "year", "format", "page_count", "provider");
        return orNotFound(response.toModelText(),
                "the Brazilian ISBN registry has no record for " + normalised
                        + "; try get_book_by_isbn_intl.");
    }

    // ------------------------------------------------------------------

    /**
     * Both search tools hit one route with one shape of answer, so they share the
     * budget check and the projection. {@code numFound} is kept alongside the hits
     * because "12 editions, showing 3" is a different answer from "3 editions".
     */
    private String searchAndProject(LinkedHashMap<String, String> params, String nothingFound) {
        var raw = http.get(OPEN_LIBRARY, "/search.json", params);
        if (raw.truncated()) {
            return "That search returned more than this tool can read. Ask for a narrower title "
                    + "or subject and search again.";
        }
        var response = json.project(raw, "numFound", "docs");
        String text = response.toModelText();
        if (response.isOk() && (text.contains("\"docs\":[]") || !text.contains("\"docs\""))) {
            return "No result: " + nothingFound + " Tell the user nothing was found; do not guess a title.";
        }
        return text;
    }

    /**
     * @return the ISBN without punctuation, or {@code null} when it is not one
     */
    private static String normaliseIsbn(String raw) {
        if (raw == null) {
            return null;
        }
        String clean = raw.replaceAll("[^0-9Xx]", "").toUpperCase(Locale.ROOT);
        // ISBN-10 ends in a check character that may be X; ISBN-13 is all digits.
        return clean.matches("\\d{9}[\\dX]|\\d{13}") ? clean : null;
    }

    private static String isbnComplaint(String isbn) {
        return "Invalid argument: an ISBN has 10 or 13 digits, optionally with hyphens, got '"
                + isbn + "'. Ask the user to read the number under the barcode, or use search_books "
                + "if they only know the title.";
    }

    /**
     * Strips the characters Lucene reads as query syntax. Without this a name with
     * a colon or a stray quote is not a failed search but a <em>syntax error</em>
     * from the search backend, which reaches the model as an unhelpful upstream
     * error rather than as "no such author".
     */
    private static String luceneSafe(String raw) {
        return raw.replaceAll("[\\\\+\\-!(){}\\[\\]^\"~*?:/|&]", " ")
                .replaceAll("\\s+", " ")
                .strip();
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
     * Both registries answer {@code 200} with a nearly empty object for records they
     * hold no data for, so "not found" never reaches the HTTP status mapping. An
     * empty object handed to the model is worse than an error: it reads as a real
     * answer with no fields, and the model fills the gaps.
     */
    private static String orNotFound(String text, String what) {
        if (text == null || text.isBlank() || text.equals("{}") || text.equals("[]")) {
            return "No result: " + what + " Tell the user nothing was found; do not guess a value.";
        }
        return text;
    }
}
