package io.github.rodrigorjsf.agenticchat.tools.devtools;

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
 * Software-ecosystem lookups, disclosed by the {@code developer-tools} skill.
 *
 * <p>Every tool here answers a question the model would otherwise answer from
 * memory, and answering a version question from memory is how a chat backend tells
 * a user to install a release that does not exist yet or was yanked last month.
 * That is the whole reason the package-registry tools exist: they are cheap, they
 * are authoritative, and the failure they prevent is invisible.
 *
 * <p><b>Rate limits shape this class more than latency does.</b> Unauthenticated
 * GitHub is 60 requests per hour <em>per IP</em>, shared by every user of this
 * deployment, and Stack Exchange is 300 per day on the same terms. Two consequences
 * are visible in the code: those catalogue entries carry no retries, because
 * {@code ToolHttpClient} maps a rate-limit {@code 403} to an upstream error and
 * would otherwise spend two of sixty requests on one exhausted call; and the
 * spending advice lives in the skill's markdown rather than in a
 * {@code @Tool} description, where it would be re-sent on every turn.
 *
 * <p>The registries answer with megabytes when asked plainly: PyPI returns every
 * release of every file ever published for a package (193 KB for {@code requests}),
 * crates.io returns every version of a crate (433 KB for {@code serde}). Neither
 * offers a server-side field filter, so their byte budgets are sized for the raw
 * body and {@link ToolJson} is what keeps the model's context small. Where a
 * server-side limit does exist — Algolia's {@code hitsPerPage}, Solr's {@code rows},
 * GitHub's {@code per_page} — it is always sent, because bytes not transferred are
 * strictly better than bytes discarded after the fact.
 */
@Singleton
public class DeveloperTools implements SkillTools {

    private static final String GITHUB = "github";
    private static final String HACKERNEWS = "hackernews";
    private static final String HN_SEARCH = "hn-search";
    private static final String STACKEXCHANGE = "stackexchange";
    private static final String NPM = "npm-registry";
    private static final String PYPI = "pypi";
    private static final String CRATES = "cratesio";
    private static final String MAVEN = "maven-central";

    /**
     * The front page carries roughly 500 story ids. Ten is already more than a
     * conversation can follow up on within its tool round-trip budget.
     */
    private static final int TOP_STORY_IDS = 10;

    private final ToolHttpClient http;
    private final ToolJson json;

    public DeveloperTools(ToolHttpClient http, ToolJson json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public String skillName() {
        return "developer-tools";
    }

    // ------------------------------------------------------------------
    // GitHub — a shared 60-requests-per-hour budget for the whole deployment
    // ------------------------------------------------------------------

    @Tool("""
            Get a GitHub repository's stars, forks, open issues, main language, \
            licence and last push date. Use when the user names a project as \
            owner/repo or asks how active or popular a repository is. Needs both the \
            owner and the repository name — this cannot search by description.""")
    public String get_github_repo(
            @P("The account that owns the repository, e.g. 'langchain4j' in langchain4j/langchain4j.")
            String owner,
            @P("The repository name, e.g. 'langchain4j' in langchain4j/langchain4j.")
            String repo) {
        if (!isGitHubName(owner) || !isGitHubName(repo)) {
            return "Invalid argument: expected an owner and a repository name as in "
                    + "'langchain4j/langchain4j', got '" + owner + "' and '" + repo
                    + "'. Ask the user for the repository URL if you are unsure.";
        }
        // The raw record carries around forty *_url template fields the model never
        // reads and would then replay into every later prompt in the conversation.
        return json.project(http.get(GITHUB, "/repos/" + owner.strip() + "/" + repo.strip()),
                "full_name", "description", "stargazers_count", "forks_count",
                "open_issues_count", "language", "license.spdx_id", "pushed_at",
                "html_url").toModelText();
    }

    @Tool("""
            Get the public profile of a GitHub user or organisation: real name, bio, \
            number of public repositories and followers. Use when the user asks who \
            maintains something or how large an organisation's presence is. Takes the \
            login handle, not a person's real name.""")
    public String get_github_user(
            @P("The GitHub login handle, without the @, e.g. 'langchain4j' or 'torvalds'.")
            String login) {
        if (!isGitHubName(login)) {
            return "Invalid argument: expected a GitHub login handle such as 'torvalds', got '"
                    + login + "'.";
        }
        return json.project(http.get(GITHUB, "/users/" + login.strip()),
                "login", "name", "bio", "public_repos", "followers", "html_url").toModelText();
    }

    // ------------------------------------------------------------------
    // Hacker News
    // ------------------------------------------------------------------

    @Tool("""
            Get the ids of the stories currently on the Hacker News front page. Each \
            id needs a separate get_hackernews_item call to become readable, so \
            prefer get_hackernews_front_page, which returns titles and scores in one \
            call. Use this only when you specifically need ranking position.""")
    public String get_hackernews_top(
            @P("How many ids to return, in rank order, 1 to 10. Defaults to 10.")
            String limit) {
        // A plain JSON array of some 500 ids, so the top-level cap applies directly
        // and the model is told how many it did not get.
        return json.cap(http.get(HACKERNEWS, "/topstories.json"),
                clamp(limit, 1, TOP_STORY_IDS, TOP_STORY_IDS)).toModelText();
    }

    @Tool("""
            Read one Hacker News story or comment by its numeric id: title, author, \
            score, comment count and the link it points to. Use to follow up on an id \
            you already have from get_hackernews_top. Requires an id — it cannot look \
            a story up by title.""")
    public String get_hackernews_item(
            @P("The numeric Hacker News item id, e.g. 8863.")
            String itemId) {
        String digits = itemId == null ? "" : itemId.replaceAll("\\D", "");
        if (digits.isEmpty() || digits.length() > 12) {
            return "Invalid argument: a Hacker News item id is a number, e.g. 8863, got '"
                    + itemId + "'. Use search_hackernews if you only have a topic.";
        }
        // `kids` is the full comment-id tree and can run to hundreds of entries.
        return json.project(http.get(HACKERNEWS, "/item/" + digits + ".json"),
                "title", "by", "score", "time", "url", "descendants").toModelText();
    }

    @Tool("""
            Search the whole Hacker News archive for stories about a topic, with \
            their score, comment count and date. Use when the user asks what the \
            community said about a technology, or wants discussion rather than \
            documentation. This is the right tool for any topic-shaped question.""")
    public String search_hackernews(
            @P("The topic to search for, e.g. 'micronaut graalvm' or 'rust async'.")
            String query,
            @P("How many stories to return, 1 to 3. Defaults to 3.")
            String limit) {
        if (query == null || query.strip().length() < 2) {
            return "Invalid argument: the search needs at least 2 characters, got '" + query + "'.";
        }
        var params = new LinkedHashMap<String, String>();
        params.put("query", query.strip());
        // Stories only: comment hits carry their full body text, which is both
        // unbounded and rarely what a "what was said about X" question wants.
        params.put("tags", "story");
        params.put("hitsPerPage", Integer.toString(clamp(limit, 1, 3, 3)));
        return hnSearch("/search", params, "no Hacker News story matches '" + query.strip() + "'.");
    }

    @Tool("""
            Get the Hacker News front page as readable stories — title, link, score \
            and comment count — in a single call. Use for "what is on Hacker News" or \
            "what is the tech news today". Prefer this over get_hackernews_top, which \
            returns only ids.""")
    public String get_hackernews_front_page(
            @P("How many stories to return, 1 to 3. Defaults to 3.")
            String limit) {
        var params = new LinkedHashMap<String, String>();
        params.put("tags", "front_page");
        params.put("hitsPerPage", Integer.toString(clamp(limit, 1, 3, 3)));
        return hnSearch("/search_by_date", params, "the front page came back empty.");
    }

    // ------------------------------------------------------------------
    // Stack Overflow
    // ------------------------------------------------------------------

    @Tool("""
            Find the highest-voted Stack Overflow questions about a programming \
            problem, with their score, tags and whether they have an accepted answer. \
            Use for error messages, "how do I" and configuration questions. Returns \
            question titles and links, not the answer text itself.""")
    public String search_stackoverflow(
            @P("The problem in a few words, as you would type it into a search box, e.g. 'micronaut graalvm reflection'.")
            String query,
            @P("How many questions to return, 1 to 3. Defaults to 3.")
            String limit) {
        if (query == null || query.strip().length() < 3) {
            return "Invalid argument: the search needs at least 3 characters, got '" + query
                    + "'. Ask the user for the error message or the exact problem.";
        }
        var params = new LinkedHashMap<String, String>();
        params.put("order", "desc");
        params.put("sort", "votes");
        params.put("q", query.strip());
        params.put("site", "stackoverflow");
        params.put("pagesize", Integer.toString(clamp(limit, 1, 3, 3)));
        params.put("filter", "default");
        var raw = http.get(STACKEXCHANGE, "/search/advanced", params);
        if (raw.truncated()) {
            return "That search returned more than this tool can read. Ask again with a narrower "
                    + "query, for example by including the exact error message.";
        }
        // quota_remaining is kept deliberately: this API allows 300 calls per day for
        // the whole deployment, and the model can see how close that is to running out.
        var response = json.project(raw, "quota_remaining", "items");
        String text = response.toModelText();
        if (response.isOk() && text.contains("\"items\":[]")) {
            return "No result: no Stack Overflow question matches '" + query.strip()
                    + "'. Suggest different wording; do not invent a question link.";
        }
        return text;
    }

    // ------------------------------------------------------------------
    // Package registries
    // ------------------------------------------------------------------

    @Tool("""
            Get the current published version, description and licence of an npm \
            package. Use whenever a question involves a JavaScript or TypeScript \
            library version, or "is this package still maintained". Never answer a \
            version question from memory — published versions change daily.""")
    public String get_npm_package(
            @P("The exact package name as published, e.g. 'express' or '@babel/core'.")
            String packageName) {
        String name = packageName == null ? "" : packageName.strip().toLowerCase(Locale.ROOT);
        if (!name.matches("(@[a-z0-9._-]{1,60}/)?[a-z0-9._-]{1,120}")) {
            return "Invalid argument: expected an npm package name such as 'express' or "
                    + "'@babel/core', got '" + packageName + "'.";
        }
        return json.project(http.get(NPM, "/" + name + "/latest"),
                "name", "version", "description", "license", "homepage").toModelText();
    }

    @Tool("""
            Get the current released version, summary, licence and required Python \
            version of a PyPI package. Use for any question about a Python library's \
            version, or whether it supports a given Python release. Never answer a \
            version question from memory.""")
    public String get_pypi_package(
            @P("The exact package name as published on PyPI, e.g. 'requests' or 'langchain-core'.")
            String packageName) {
        String name = packageName == null ? "" : packageName.strip();
        if (!name.matches("[A-Za-z0-9._-]{1,120}")) {
            return "Invalid argument: expected a PyPI package name such as 'requests', got '"
                    + packageName + "'.";
        }
        // 193 KB for `requests`: the record embeds every file of every release ever
        // published. The budget is sized for that, and only `info` survives here.
        var raw = http.get(PYPI, "/" + name + "/json");
        if (raw.truncated()) {
            return "That package's record was too large to read. Tell the user the version of "
                    + name + " could not be retrieved; do not answer from memory.";
        }
        return json.project(raw,
                "info.name", "info.version", "info.summary", "info.license",
                "info.home_page", "info.requires_python").toModelText();
    }

    @Tool("""
            Get the current stable version, description, repository and total \
            downloads of a Rust crate. Use for any question about a Rust library's \
            version or how widely it is used. Never answer a version question from \
            memory.""")
    public String get_crate(
            @P("The exact crate name as published on crates.io, e.g. 'serde' or 'tokio'.")
            String crateName) {
        String name = crateName == null ? "" : crateName.strip().toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z0-9._-]{1,64}")) {
            return "Invalid argument: expected a crate name such as 'serde', got '" + crateName + "'.";
        }
        // 433 KB for `serde`: the response embeds every published version. Everything
        // outside the `crate` object is discarded before the model ever sees it.
        var raw = http.get(CRATES, "/crates/" + name);
        if (raw.truncated()) {
            return "That crate's record was too large to read. Tell the user the version of "
                    + name + " could not be retrieved; do not answer from memory.";
        }
        return json.project(raw,
                "crate.name", "crate.max_stable_version", "crate.description",
                "crate.downloads", "crate.repository").toModelText();
    }

    @Tool("""
            Find a Java or Kotlin library on Maven Central and get its latest \
            published version and coordinates. Use for any question about a JVM \
            dependency version, or to find the group id that goes with an artifact \
            name. Search by group with 'g:dev.langchain4j' or by artifact with \
            'a:micronaut-core'.""")
    public String search_maven_artifact(
            @P("A Solr query: 'g:dev.langchain4j' for a group, 'a:micronaut-core' for an artifact, or plain words.")
            String query,
            @P("How many artifacts to return, 1 to 5. Defaults to 5.")
            String limit) {
        if (query == null || query.strip().length() < 2) {
            return "Invalid argument: a search term is required, e.g. 'g:dev.langchain4j' or "
                    + "'a:micronaut-core', got '" + query + "'.";
        }
        String clean = query.strip();
        if (!clean.matches("[A-Za-z0-9 .:_*-]{2,120}")) {
            return "Invalid argument: use letters, digits, dots and a single 'g:' or 'a:' prefix, got '"
                    + clean + "'.";
        }
        var params = new LinkedHashMap<String, String>();
        params.put("q", clean);
        params.put("rows", Integer.toString(clamp(limit, 1, 5, 5)));
        params.put("wt", "json");
        var raw = http.get(MAVEN, "/solrsearch/select", params);
        if (raw.truncated()) {
            return "That search matched too much of the repository to read. Narrow it with a group, "
                    + "for example 'g:dev.langchain4j'.";
        }
        var response = json.project(raw, "response.numFound", "response.docs");
        String text = response.toModelText();
        if (response.isOk() && text.contains("\"docs\":[]")) {
            return "No result: Maven Central has no artifact matching '" + clean
                    + "'. Check the spelling of the group or artifact id; do not guess a version.";
        }
        return text;
    }

    // ------------------------------------------------------------------

    /**
     * Both Algolia-backed tools share one answer shape, one budget check and one projection.
     */
    private String hnSearch(String path, Map<String, String> params, String nothingFound) {
        var raw = http.get(HN_SEARCH, path, params);
        if (raw.truncated()) {
            return "That search returned more than this tool can read. Ask for fewer stories or "
                    + "use a narrower topic.";
        }
        var response = json.project(raw, "nbHits", "hits");
        String text = response.toModelText();
        if (response.isOk() && text.contains("\"hits\":[]")) {
            return "No result: " + nothingFound + " Suggest different wording; do not invent a story.";
        }
        return text;
    }

    private static boolean isGitHubName(String value) {
        return value != null && value.strip().matches("[A-Za-z0-9._-]{1,100}");
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
