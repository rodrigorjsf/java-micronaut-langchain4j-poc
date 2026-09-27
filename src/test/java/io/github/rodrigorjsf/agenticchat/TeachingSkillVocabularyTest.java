package io.github.rodrigorjsf.agenticchat;

import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.service.AiServices;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The teaching skills under {@code .claude/skills/} and the chapters under {@code docs/} are
 * what readers of this repository copy. Two kinds of drift in them compile, pass every other
 * test, and mislead the reader silently.
 *
 * <p><b>OWASP ASI titles.</b> The same item spelled three ways ("Tool Misuse", "Tool Misuse
 * &amp; Exploitation", "Tool Misuse and Exploitation") reads as three items, and a reader
 * searching for one spelling misses the other two. The canonical list below is the entry
 * headings of the primary document — <i>OWASP Top 10 for Agentic Applications 2026</i>,
 * OWASP GenAI Security Project, December 2025, pp. 9–36 [sourced —
 * https://genai.owasp.org/download/52117, read 2026-09-27]. Its
 * at-a-glance page and appendices abbreviate some of them with {@code &amp;}; the entry
 * headings are the document's own titles, so they win.
 *
 * <p>A title is checked wherever it is attached to an identifier: after it
 * ({@code ASI02 Tool Misuse and Exploitation}, {@code ASI02: …}, {@code ASI02 – …},
 * {@code ASI02 (…)}, {@code | ASI02 | …}) or before it ({@code Tool Misuse and Exploitation
 * (ASI02)}). A capitalised word in either position is read as a title and must be the
 * canonical one, and nothing capitalised may extend it on either side; lower-case prose
 * ({@code ASI07 in full}) is not a title.
 *
 * <p><b>LangChain4j knobs.</b> A skill that stays framework-generic still names the
 * LangChain4j setting in one clause, so a reader can find it. Each name is resolved by
 * reflection against the LangChain4j on this build's classpath, so a version bump that
 * renames a knob fails here rather than in a reader's IDE.
 */
class TeachingSkillVocabularyTest {

    private static final Path REPO = Path.of("").toAbsolutePath();
    private static final Path SKILLS = REPO.resolve(".claude/skills");
    private static final Path DOCS = REPO.resolve("docs");

    static final Map<String, String> CANONICAL_ASI_TITLES = Map.ofEntries(
            Map.entry("ASI01", "Agent Goal Hijack"),
            Map.entry("ASI02", "Tool Misuse and Exploitation"),
            Map.entry("ASI03", "Identity and Privilege Abuse"),
            Map.entry("ASI04", "Agentic Supply Chain Vulnerabilities"),
            Map.entry("ASI05", "Unexpected Code Execution (RCE)"),
            Map.entry("ASI06", "Memory & Context Poisoning"),
            Map.entry("ASI07", "Insecure Inter-Agent Communication"),
            Map.entry("ASI08", "Cascading Failures"),
            Map.entry("ASI09", "Human-Agent Trust Exploitation"),
            Map.entry("ASI10", "Rogue Agents"));

    private static final String ID = "ASI(?:0[1-9]|10)";
    private static final Pattern ID_THEN_TEXT = Pattern.compile(
            "\\b(" + ID + ")\\b(?:\\s*[:–—,|(]\\s*|\\s+-\\s+|\\s+)(?=\\S)");
    private static final Pattern TEXT_THEN_ID = Pattern.compile("\\s\\((" + ID + ")\\)");

    @Test
    @DisplayName("every ASI identifier carries its one canonical title, in skills and docs")
    void everyAsiTitleIsTheCanonicalOne() {
        List<String> violations = new ArrayList<>();
        for (Path file : markdownUnder(SKILLS, DOCS)) {
            String text = normalised(file);
            String where = REPO.relativize(file).toString();

            Matcher after = ID_THEN_TEXT.matcher(text);
            while (after.find()) {
                String id = after.group(1);
                String rest = text.substring(after.end());
                if (!startsLikeATitle(rest) || rest.startsWith("ASI")) {
                    continue;
                }
                String canonical = CANONICAL_ASI_TITLES.get(id);
                if (!rest.startsWith(canonical)
                        || startsLikeATitle(rest.substring(canonical.length()).stripLeading())) {
                    violations.add(where + ": " + id + " followed by \"" + excerpt(rest) + "\"");
                }
            }

            Matcher before = TEXT_THEN_ID.matcher(text);
            while (before.find()) {
                String id = before.group(1);
                String head = text.substring(0, before.start());
                String lastWord = head.substring(head.lastIndexOf(' ') + 1).replaceFirst("^\\(", "");
                if (!startsLikeATitle(lastWord)) {
                    continue;
                }
                String canonical = CANONICAL_ASI_TITLES.get(id);
                String beforeTitle = head.endsWith(canonical)
                        ? head.substring(0, head.length() - canonical.length()).stripTrailing()
                        : null;
                String wordBefore = beforeTitle == null
                        ? ""
                        : beforeTitle.substring(beforeTitle.lastIndexOf(' ') + 1);
                if (beforeTitle == null || startsLikeATitle(wordBefore)) {
                    violations.add(where + ": \"" + tail(head) + "\" before (" + id + ")");
                }
            }
        }
        assertThat(violations)
                .as("non-canonical OWASP ASI titles; the canonical list is "
                        + "TeachingSkillVocabularyTest.CANONICAL_ASI_TITLES")
                .isEmpty();
    }

    static Stream<Arguments> knobs() {
        return Stream.of(
                Arguments.of(AiServices.class, "storeRetrievedContentInChatMemory",
                        "storeRetrievedContentInChatMemory(false)", "retrieval-that-earns-its-place/SKILL.md"),
                Arguments.of(AiServices.class, "toolExecutionErrorHandler",
                        "`toolExecutionErrorHandler`", "agentic-tool-boundary/SKILL.md"),
                Arguments.of(AiServices.class, "toolArgumentsErrorHandler",
                        "`toolArgumentsErrorHandler`", "agentic-tool-boundary/SKILL.md"),
                Arguments.of(AiServices.class, "toolExecutionErrorHandler",
                        "`toolExecutionErrorHandler`", "agentic-codebase-audit/SEAM-PROBES.md"),
                Arguments.of(AiServices.class, "hallucinatedToolNameStrategy",
                        "`hallucinatedToolNameStrategy`", "agentic-service-composition/SKILL.md"),
                Arguments.of(AiServices.class, "hallucinatedToolNameStrategy",
                        "`hallucinatedToolNameStrategy`", "agentic-codebase-audit/SEAM-PROBES.md"),
                Arguments.of(DocumentSplitters.class, "recursive",
                        "`DocumentSplitters.recursive(", "writing-retrievable-knowledge/SKILL.md"));
    }

    @ParameterizedTest(name = "{0}.{1} is named in {3} and exists in this LangChain4j")
    @MethodSource("knobs")
    void aKnobASkillNamesResolvesInTheLangChain4jInUse(
            Class<?> owner, String method, String reference, String skill) {
        boolean resolves = Arrays.stream(owner.getMethods())
                .map(Method::getName)
                .anyMatch(method::equals);
        assertThat(resolves)
                .as("%s has no public method %s in the LangChain4j on this classpath",
                        owner.getSimpleName(), method)
                .isTrue();

        assertThat(read(SKILLS.resolve(skill)))
                .as("%s should name the LangChain4j knob %s", skill, reference)
                .contains(reference);
    }

    private static boolean startsLikeATitle(String s) {
        return !s.isEmpty() && Character.isUpperCase(s.charAt(0));
    }

    /** One line, single spaces, no emphasis or code marks: a title wrapped or bolded is still a title. */
    private static String normalised(Path file) {
        return read(file).replaceAll("[*`_]", "").replaceAll("\\s+", " ");
    }

    private static String excerpt(String s) {
        return s.substring(0, Math.min(45, s.length()));
    }

    private static String tail(String s) {
        return s.substring(Math.max(0, s.length() - 45));
    }

    private static List<Path> markdownUnder(Path... roots) {
        List<Path> files = new ArrayList<>();
        for (Path root : roots) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> p.toString().endsWith(".md")).sorted().forEach(files::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        assertThat(files).as("markdown under %s", Arrays.toString(roots)).isNotEmpty();
        return files;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
