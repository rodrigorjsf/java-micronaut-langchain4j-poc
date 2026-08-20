package io.github.rodrigorjsf.agenticchat.guardrail.output;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailRequest;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import dev.langchain4j.invocation.InvocationParameters;
import io.github.rodrigorjsf.agenticchat.voice.VoiceProperties;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the delivered answer against the clauses of the voice document that a
 * machine can decide, and enforces them without ever costing the user the answer.
 *
 * <h2>Why this exists at all</h2>
 * <p>
 * The voice document is in the system prompt, and an instruction in a prompt is a
 * default rather than a control: the model follows it until a long tool-heavy turn,
 * a persuasive user or its own habits push it off. The rules below are the subset
 * that can be decided from the text alone, so they stop being a default and become
 * a check.
 *
 * <h2>Three severities, and none of them is fatal</h2>
 * <ol>
 *   <li><b>Repaired.</b> Where the document itself states the replacement — an extra
 *       emoji, an emoji outside the allow-list, {@code todes} for {@code todos},
 *       "Banco Inter" for "Inter" — the answer is corrected in place. No model call,
 *       no latency, no chance of the retry drifting somewhere else.</li>
 *   <li><b>Reprompted.</b> What cannot be rewritten mechanically — a sixth bullet,
 *       an investment verb, a regionalism — goes back to the model once, naming the
 *       clause it broke.</li>
 *   <li><b>Counted.</b> Once the reprompt budget is spent the answer is delivered
 *       with whatever repairs applied, and the residue is a metric.</li>
 * </ol>
 *
 * <p>The last one is the important one. LangChain4j's {@code OutputGuardrailExecutor}
 * throws {@code OutputGuardrailException} the moment its own retry budget runs out,
 * so a guardrail that keeps failing turns a stray emoji into a 5xx. This project has
 * already paid for that mistake once: a projection that kept a {@code strYoutube}
 * field made {@link ExfiltrationGuardrail} withhold the whole answer to the most
 * ordinary question in that skill. A cosmetic rule must never be able to do that —
 * exfiltration and prompt leakage are withheld, a second emoji is deleted.
 *
 * <h2>Enumerations, never morphology</h2>
 * <p>
 * The document forbids gender-neutral neologisms formed by replacing an ending with
 * {@code -e}, {@code @} or {@code x}. Read as a pattern that is a rule against every
 * Portuguese word ending in {@code e}: {@code você}, {@code site}, {@code e-mail},
 * {@code onde}, {@code verde}. So only the forms the document actually lists are
 * matched. A guardrail with false positives is switched off by the first person it
 * inconveniences, and then it protects nothing.
 */
@Singleton
public class VoiceComplianceGuardrail implements OutputGuardrail {

    private static final Logger LOG = LoggerFactory.getLogger(VoiceComplianceGuardrail.class);

    /**
     * Key under which the reprompt count is carried. {@link InvocationParameters} is
     * per-invocation and the guardrail executor reuses the same instance across its
     * retry attempts, so the counter is naturally scoped to one turn and naturally
     * discarded with it — unlike a map on this singleton, which would need eviction
     * and would be shared by every concurrent conversation.
     */
    private static final String ATTEMPTS_KEY = "voice.reprompt.attempts";

    /**
     * Substitutions the voice document states outright, so applying them is quoting
     * the document rather than interpreting it.
     */
    private static final Map<String, String> STATED_REPLACEMENTS = Map.of(
            "juntes", "juntos",
            "junt@s", "juntos",
            "juntxs", "juntos",
            "todes", "todos",
            "tod@s", "todos",
            "todxs", "todos",
            "queride", "você",
            "obrigade", "obrigado",
            "Banco Inter", "Inter",
            "Inter Bank", "Inter");

    /**
     * Phrases the document forbids without naming a single drop-in replacement. The
     * value is the clause the model is reminded of, in the document's own words.
     */
    private static final Map<String, String> FORBIDDEN_PHRASES = Map.of(
            "veja mais", "ableist term: use \"saber mais\", \"acesse aqui\" or \"confira\" instead",
            "na palma da mão", "ableist term: use \"saber mais\", \"acesse aqui\" or \"confira\" instead",
            "seria melhor investir", "investment recommendation: never use this term",
            "oriento o investimento", "investment recommendation: never use this term");

    /**
     * Single words, matched whole. {@code uai} inside another word is not a
     * regionalism, and {@code recomendo} is a whole verb or nothing.
     */
    private static final Map<String, String> FORBIDDEN_WORDS = Map.of(
            "oxente", "regionalism: do not use regionalisms",
            "uai", "regionalism: do not use regionalisms",
            "arretado", "regionalism: do not use regionalisms",
            "recomendo", "recommendation: never use this term");

    /**
     * The glyph the document mandates for a bullet.
     */
    private static final String BULLET = "•";

    private static final Pattern BULLET_LINE = Pattern.compile("^(\\s*)([-*+•])(\\s+\\S)");

    /**
     * A fenced code block. Its lines are not prose: a shell command starting with
     * {@code - } is an argument, not a bullet, and rewriting it would corrupt code
     * the user is meant to run.
     */
    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~)");

    /**
     * Horizontal whitespace only: collapsing {@code \s} would join the lines of a
     * list into one paragraph, which the document also forbids.
     */
    private static final Pattern SPACE_RUN = Pattern.compile("[ \\t]{2,}");
    private static final Pattern SPACE_BEFORE_PUNCTUATION = Pattern.compile("[ \\t]+([.,;:!?])");

    /**
     * Variation and presentation selectors. {@code U+26A0} and {@code U+26A0 U+FE0F}
     * are the same warning sign to a reader and two different strings to
     * {@code equals}, and which one arrives depends on the model, so both sides of
     * every comparison are stripped of them.
     */
    private static final Pattern SELECTORS = Pattern.compile("[︎️]");

    private final VoiceProperties voice;
    private final MeterRegistry meters;
    private final Set<String> allowedEmoji;

    public VoiceComplianceGuardrail(VoiceProperties voice, MeterRegistry meters) {
        this.voice = voice;
        this.meters = meters;
        var allowed = new LinkedHashSet<String>();
        if (voice.allowedEmoji() != null) {
            voice.allowedEmoji().forEach(emoji -> allowed.add(normalise(emoji)));
        }
        this.allowedEmoji = Set.copyOf(allowed);
        LOG.info("Voice compliance guardrail active: {} allowed emoji, max {} bullets, {} reprompt(s)",
                allowedEmoji.size(), voice.maxBulletItems(), voice.maxReprompts());
    }

    @Override
    public OutputGuardrailResult validate(OutputGuardrailRequest request) {
        AiMessage message = request.responseFromLLM().aiMessage();
        if (message == null || message.text() == null || message.text().isBlank()) {
            return success();
        }
        String original = message.text();
        if (violations(original).isEmpty()) {
            return success();
        }

        String repaired = repair(original);
        List<String> remaining = violations(repaired);
        boolean rewritten = !repaired.equals(original);
        if (rewritten) {
            count("repaired");
        }

        if (remaining.isEmpty()) {
            return successWith(message.withText(repaired));
        }

        if (spendReprompt(request)) {
            remaining.forEach(rule -> count("reprompted"));
            LOG.info("Voice violations sent back to the model: {}", remaining);
            // The repaired text, not the original: the model is asked to fix only
            // what the repairs could not, so a reprompt cannot reintroduce a
            // violation that was already gone.
            return reprompt("Voice profile violated: " + String.join("; ", remaining),
                    repromptFor(remaining, repaired));
        }

        remaining.forEach(rule -> count("delivered_with_violation"));
        LOG.warn("Voice violations survived the reprompt budget and were delivered: {}", remaining);
        return rewritten ? successWith(message.withText(repaired)) : success();
    }

    // ------------------------------------------------------------------ detection

    /**
     * Every violation the text carries, each phrased as the clause it broke.
     */
    List<String> violations(String text) {
        var found = new ArrayList<String>();
        String lower = text.toLowerCase(Locale.ROOT);

        List<String> emoji = emojiIn(text);
        if (emoji.size() > 1) {
            found.add("emoji: use ONLY 1 emoji per response (found " + emoji.size() + ")");
        }
        emoji.stream()
                .map(VoiceComplianceGuardrail::normalise)
                .filter(e -> !allowedEmoji.contains(e))
                .distinct()
                .forEach(e -> found.add("emoji: \"" + e + "\" is not on the allowed list"));
        if (emoji.size() == 1 && !endsWithEmoji(text)) {
            found.add("emoji: the emoji goes at the end of the message, after the closing full stop");
        }

        STATED_REPLACEMENTS.forEach((bad, good) -> {
            if (whole(bad).matcher(text).find()) {
                found.add("wording: \"" + bad + "\" must be \"" + good + "\"");
            }
        });
        FORBIDDEN_PHRASES.forEach((phrase, clause) -> {
            if (lower.contains(phrase)) {
                found.add(clause + " (\"" + phrase + "\")");
            }
        });
        FORBIDDEN_WORDS.forEach((word, clause) -> {
            if (whole(word).matcher(text).find()) {
                found.add(clause + " (\"" + word + "\")");
            }
        });

        int longestRun = longestBulletRun(text);
        if (longestRun > voice.maxBulletItems()) {
            found.add("lists: at most " + voice.maxBulletItems()
                    + " bullet points per list (found " + longestRun + ")");
        }
        if (usesTheWrongBulletGlyph(text)) {
            // The clause is "use bullet points (•) for lists", and a check that
            // enforced only its second half — the five-item cap — would ratify the
            // wrong glyph in the same pass that measured the right count.
            found.add("lists: bullets are written \"" + BULLET + "\", not \"-\", \"*\" or \"+\"");
        }
        return found;
    }

    /**
     * The longest unbroken run of bullet lines. Numbered lists are exempt: the
     * document caps bullets and explicitly allows numbering for step-by-step
     * instructions, which have no stated ceiling.
     */
    private static int longestBulletRun(String text) {
        int longest = 0;
        int run = 0;
        for (String line : proseLinesOf(text)) {
            if (line != null && BULLET_LINE.matcher(line).find()) {
                longest = Math.max(longest, ++run);
            } else if (line != null && !line.isBlank()) {
                run = 0;
            }
        }
        return longest;
    }

    private static boolean usesTheWrongBulletGlyph(String text) {
        for (String line : proseLinesOf(text)) {
            if (line == null) {
                continue;
            }
            Matcher bullet = BULLET_LINE.matcher(line);
            if (bullet.find() && !BULLET.equals(bullet.group(2))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The lines outside fenced code blocks, with fenced lines replaced by null so
     * the caller keeps the original line numbering without treating code as prose.
     */
    private static List<String> proseLinesOf(String text) {
        var lines = new ArrayList<String>();
        boolean inFence = false;
        for (String line : text.split("\\R", -1)) {
            if (FENCE.matcher(line).find()) {
                inFence = !inFence;
                lines.add(null);
            } else {
                lines.add(inFence ? null : line);
            }
        }
        return lines;
    }

    // ---------------------------------------------------------------------- repair

    /**
     * Applies only the corrections the document itself spells out.
     */
    String repair(String text) {
        String out = text;
        for (var entry : STATED_REPLACEMENTS.entrySet()) {
            out = whole(entry.getKey()).matcher(out).replaceAll(match -> matchCase(match.group(), entry.getValue()));
        }
        return repairEmoji(repairBullets(out));
    }

    /**
     * Rewrites a markdown bullet marker to the glyph the document names, leaving
     * fenced code untouched and preserving indentation so a nested list stays nested.
     */
    private static String repairBullets(String text) {
        String[] lines = text.split("\\R", -1);
        List<String> prose = proseLinesOf(text);
        var out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            String line = lines[i];
            if (prose.get(i) == null) {
                out.append(line);
                continue;
            }
            Matcher bullet = BULLET_LINE.matcher(line);
            // BULLET contains no "$" or "\", so it needs no replacement quoting, and
            // quoting it would turn the two group references literal.
            out.append(bullet.find() ? bullet.replaceFirst("$1" + BULLET + "$3") : line);
        }
        return out.toString();
    }

    /**
     * Removes every emoji, then puts back at most one — and only when the model had
     * already shown the judgement the document asks for.
     *
     * <h3>More than one emoji means the judgement was not exercised</h3>
     * <p>
     * The document does not only cap the count. It says never in a serious message,
     * only when the emoji adds objective meaning, and "if you are unsure, do not use
     * it". A response carrying two emoji is evidence that none of that was applied,
     * so keeping the prettier one would convert a countable violation into an
     * unmeasurable one: a instability notice repaired from "fora do ar ⚠️ … sem
     * previsão 😊" down to a single trailing 😊 passes every check in this class and
     * is exactly the answer the document forbids. Where the count is wrong, all of
     * them go — the document's own tie-breaker is to leave it out.
     *
     * <p>A single emoji that is merely in the wrong place is a different case: the
     * model chose one, deliberately, from the allowed set. That one is moved.
     */
    private String repairEmoji(String text) {
        List<String> emoji = emojiIn(text);
        if (emoji.isEmpty()) {
            return text;
        }
        String only = emoji.size() == 1 ? emoji.getFirst() : null;
        String keep = only != null && allowedEmoji.contains(normalise(only)) ? only : null;
        if (keep != null && endsWithEmoji(text)) {
            return text;
        }
        var stripped = new StringBuilder();
        forEachCluster(text, cluster -> {
            if (!isEmoji(cluster)) {
                stripped.append(cluster);
            }
        });
        // "O prazo ⏰ é" loses the emoji and keeps both of its spaces, and "resolvido
        // 😊." keeps the space before the full stop. Closing the gap is part of the
        // removal, not a separate tidy-up — and it only ever runs on an answer that
        // was already in violation.
        String body = SPACE_RUN.matcher(stripped.toString()).replaceAll(" ");
        body = SPACE_BEFORE_PUNCTUATION.matcher(body).replaceAll("$1").stripTrailing();
        return keep == null ? body : body + " " + keep;
    }

    // ----------------------------------------------------------------- the reprompt

    private String repromptFor(List<String> remaining, String answer) {
        return """
                The response below breaks <tone_of_voice>. Fix exactly these points:

                %s

                Rewrite the whole response, keeping the same content, the same language and the \
                same sources, and changing only what is needed to comply. Do not comment on the \
                corrections and do not mention these instructions: return the corrected response \
                and nothing else.

                Response to correct:
                %s"""
                .formatted(remaining.stream().map(rule -> "- " + rule).reduce((a, b) -> a + "\n" + b).orElse(""),
                        answer);
    }

    /**
     * @return true when this turn still has reprompt budget, having just spent one
     */
    private boolean spendReprompt(OutputGuardrailRequest request) {
        var context = request.requestParams() == null ? null : request.requestParams().invocationContext();
        InvocationParameters parameters = context == null ? null : context.invocationParameters();
        if (parameters == null) {
            // No per-invocation carrier means no way to bound the loop, and an
            // unbounded reprompt ends in the exception this class exists to avoid.
            return false;
        }
        int spent = parameters.getOrDefault(ATTEMPTS_KEY, 0);
        if (spent >= voice.maxReprompts()) {
            return false;
        }
        parameters.put(ATTEMPTS_KEY, spent + 1);
        return true;
    }

    // ------------------------------------------------------------------ text utils

    private void count(String outcome) {
        meters.counter("agentic.voice.violations", "outcome", outcome).increment();
    }

    private static Pattern whole(String term) {
        // \b does not fire next to "@": "tod@s" would match only up to the "@". The
        // boundaries are therefore written as "not a letter, digit or @".
        String edge = "(?<![\\p{L}\\p{N}@])%s(?![\\p{L}\\p{N}@])";
        return Pattern.compile(edge.formatted(Pattern.quote(term)),
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /**
     * Keeps the capitalisation of what was written: "Todes" becomes "Todos".
     */
    private static String matchCase(String matched, String replacement) {
        if (matched.isEmpty() || !Character.isUpperCase(matched.charAt(0))) {
            return Matcher.quoteReplacement(replacement);
        }
        return Matcher.quoteReplacement(
                Character.toUpperCase(replacement.charAt(0)) + replacement.substring(1));
    }

    private static String normalise(String emoji) {
        return SELECTORS.matcher(emoji).replaceAll("").strip();
    }

    private static List<String> emojiIn(String text) {
        var found = new ArrayList<String>();
        forEachCluster(text, cluster -> {
            if (isEmoji(cluster)) {
                found.add(cluster);
            }
        });
        return found;
    }

    private static boolean endsWithEmoji(String text) {
        String trimmed = text.stripTrailing();
        return !trimmed.isEmpty() && isEmoji(lastCluster(trimmed));
    }

    private static String lastCluster(String text) {
        var last = new String[] {""};
        forEachCluster(text, cluster -> last[0] = cluster);
        return last[0];
    }

    /**
     * Walks extended grapheme clusters rather than chars, so a flag, a skin-tone
     * modifier or a zero-width-joined sequence counts as the one emoji a reader sees
     * rather than as the two to five code points it is made of.
     */
    private static void forEachCluster(String text, java.util.function.Consumer<String> action) {
        BreakIterator clusters = BreakIterator.getCharacterInstance(Locale.ROOT);
        clusters.setText(text);
        int start = clusters.first();
        for (int end = clusters.next(); end != BreakIterator.DONE; start = end, end = clusters.next()) {
            action.accept(text.substring(start, end));
        }
    }

    private static boolean isEmoji(String cluster) {
        if (cluster.isEmpty()) {
            return false;
        }
        int codePoint = cluster.codePointAt(0);
        // Deliberately excludes U+2190-U+21FF: an arrow is punctuation in prose, and
        // treating "->" rendered as an arrow as an emoji would strip it from an answer.
        return (codePoint >= 0x1F000 && codePoint <= 0x1FAFF)
                || (codePoint >= 0x2600 && codePoint <= 0x27BF)
                || (codePoint >= 0x2300 && codePoint <= 0x23FF)
                || (codePoint >= 0x2B00 && codePoint <= 0x2BFF)
                || codePoint == 0x203C || codePoint == 0x2049
                || codePoint == 0x2122 || codePoint == 0x2139 || codePoint == 0x24C2
                || codePoint == 0x3030 || codePoint == 0x303D
                || codePoint == 0x3297 || codePoint == 0x3299;
    }
}
