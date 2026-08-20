package io.github.rodrigorjsf.agenticchat.guardrail.output;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailRequest;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import dev.langchain4j.guardrail.config.OutputGuardrailsConfig;
import dev.langchain4j.invocation.InvocationParameters;
import io.github.rodrigorjsf.agenticchat.voice.VoiceProperties;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
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
 *   <li><b>Repaired.</b> Where the document itself states the replacement — an emoji
 *       outside the allow-list, {@code todes} for {@code todos}, "Banco Inter" for
 *       "Inter", a markdown dash where the document names {@code •} — the answer is
 *       corrected in place. No model call, no latency, no chance of the retry
 *       drifting somewhere else.</li>
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
 * exfiltration and prompt leakage are withheld, a stray emoji is deleted.
 *
 * <h2>The failure that actually matters here is the false positive</h2>
 * <p>
 * A guardrail that damages a compliant answer is switched off by the first person it
 * inconveniences, and then none of its real rules are enforced either. Three
 * decisions below exist only because of that, each after a measurement:
 *
 * <ul>
 *   <li><b>Emoji are identified by the Unicode {@code Emoji_Presentation} property,
 *       never by code-point ranges.</b> Ranges chosen by eye put {@code ✓}, {@code ✗},
 *       {@code ★}, {@code ❶}, {@code ➡}, {@code ⌘} and {@code ™} in the same class as
 *       {@code 😊}. "Pagamento ✓ confirmado. 😊" then read as two emoji, and the repair
 *       deleted the one the brand actually allows. Measured on this JDK:
 *       {@code Emoji_Presentation} is true for every emoji in the allow-list bar one,
 *       and false for all of those typographic symbols.</li>
 *   <li><b>Only the enumerated neologisms, and not all of them.</b> The document's
 *       rule is against endings replaced by {@code -e}, {@code @} or {@code x}, which
 *       as a pattern is a rule against {@code você}, {@code site}, {@code onde} and
 *       {@code verde}. {@code juntes} is enumerated by the document and is also the
 *       standard tu-subjunctive of <i>juntar</i>, so "peço que tu juntes os
 *       comprovantes" came back as "peço que tu juntos os comprovantes". The
 *       unambiguous {@code junt@s} and {@code juntxs} are matched; the bare form is
 *       left to the model, which still has the clause in front of it.</li>
 *   <li><b>A list is two or more consecutive bullet lines.</b> One line beginning
 *       with a dash is a footnote, a line of dialogue, or an amount — and rewriting
 *       "* Valores sujeitos a alteração pelo Bacen." into a bullet, or counting it as
 *       the sixth item of the list above it, is damage either way.</li>
 * </ul>
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
     * the document rather than interpreting it. Ordered, because the violation list
     * becomes the reprompt and {@code Map.of} iterates differently on every JVM.
     *
     * <p>Every term here and in {@link #FORBIDDEN_TERMS} must appear in the shipped
     * document, and a test asserts it. A term the document does not carry is a rule
     * the model was never told about, enforced on its output — which is how
     * "Banco Inter" survived here after the brand left the document, ready to rewrite
     * the name of a real company returned by a CNPJ lookup.
     */
    private static final Map<String, String> STATED_REPLACEMENTS = new LinkedHashMap<>(Map.ofEntries(
            Map.entry("junt@s", "juntos"),
            Map.entry("juntxs", "juntos"),
            Map.entry("todes", "todos"),
            Map.entry("tod@s", "todos"),
            Map.entry("todxs", "todos"),
            Map.entry("queride", "você"),
            Map.entry("obrigade", "obrigado")));

    /**
     * Terms the document forbids without naming a single drop-in replacement. The
     * value is the clause the model is reminded of.
     */
    private static final Map<String, String> FORBIDDEN_TERMS = new LinkedHashMap<>(Map.ofEntries(
            Map.entry("veja mais", "ableist term: use \"saber mais\", \"acesse aqui\" or \"confira\" instead"),
            Map.entry("na palma da mão", "ableist term: use \"saber mais\", \"acesse aqui\" or \"confira\" instead"),
            Map.entry("oxente", "regionalism: do not use regionalisms"),
            Map.entry("uai", "regionalism: do not use regionalisms"),
            Map.entry("arretado", "regionalism: do not use regionalisms"),
            Map.entry("recomendo", "recommendation: never use this term"),
            Map.entry("seria melhor investir", "investment recommendation: never use this term"),
            Map.entry("oriento o investimento", "investment recommendation: never use this term")));

    /**
     * The glyph the document mandates for a bullet.
     */
    private static final String BULLET = "•";

    /**
     * Leading whitespace, marker, then at least one space and a non-space. The
     * leading group is captured because indentation decides whether a line is an item
     * of the list or a sub-item of one.
     */
    private static final Pattern BULLET_LINE = Pattern.compile("^([ \\t]*)([-*+•])([ \\t]+\\S)");

    /**
     * A markdown thematic break — {@code ***}, {@code - - -}, {@code ___}. It starts
     * with a bullet marker and is a horizontal rule, and rewriting {@code * * *} into
     * {@code • * *} is the sort of damage that gets a guardrail switched off.
     */
    private static final Pattern THEMATIC_BREAK = Pattern.compile("^[ \\t]*([-*_])([ \\t]*\\1){2,}[ \\t]*$");

    private static final Pattern FENCE = Pattern.compile("^[ \\t]*(```|~~~)");

    /**
     * Interior runs of horizontal whitespace. Applied after a line's own indentation,
     * never to it: collapsing the leading run reindents a four-space code block, and
     * in Python or YAML that changes what the code means.
     */
    private static final Pattern INDENT = Pattern.compile("^[ \\t]*");
    private static final Pattern SPACE_RUN = Pattern.compile("[ \\t]{2,}");
    private static final Pattern SPACE_BEFORE_PUNCTUATION = Pattern.compile("[ \\t]+([.,;:!?])");

    /**
     * Variation and presentation selectors. {@code U+26A0} and {@code U+26A0 U+FE0F}
     * are the same warning sign to a reader and two different strings to
     * {@code equals}, and which one arrives depends on the model, so both sides of
     * every comparison are stripped of them.
     */
    private static final Pattern SELECTORS = Pattern.compile("[︎️]");

    /**
     * Default emoji presentation, per the Unicode emoji properties the JDK exposes.
     * A character with {@code Emoji=Yes} but {@code Emoji_Presentation=No} — {@code ✔},
     * {@code ➡}, {@code ™}, {@code ⚠} — renders as text unless followed by U+FE0F, so
     * it is an emoji only when the cluster carries that selector.
     */
    /**
     * The Portuguese enclitic pronouns, as they attach after a hyphen. Needed so the
     * compound-word exclusion in {@link #whole(String)} does not swallow
     * {@code Recomendo-lhe}.
     */
    private static final String ENCLITIC =
            "(?:me|te|se|lhes?|nos?|nas?|los?|las?|os?|as?)(?![\\p{L}\\p{N}-])";

    private static final Pattern EMOJI_PRESENTATION = Pattern.compile("\\p{IsEmoji_Presentation}");
    private static final Pattern EMOJI_ELIGIBLE = Pattern.compile("\\p{IsEmoji}");

    private final VoiceProperties voice;
    private final MeterRegistry meters;
    private final Set<String> allowedEmoji;
    private final List<Replacement> replacements;
    private final List<TermRule> forbidden;
    private final int maxReprompts;

    private record Replacement(Pattern pattern, String to) { }

    private record TermRule(Pattern pattern, String term, String clause) { }

    public VoiceComplianceGuardrail(VoiceProperties voice, MeterRegistry meters) {
        this.voice = voice;
        this.meters = meters;

        var allowed = new LinkedHashSet<String>();
        if (voice.allowedEmoji() != null) {
            voice.allowedEmoji().forEach(emoji -> allowed.add(normalise(emoji)));
        }
        this.allowedEmoji = Set.copyOf(allowed);

        // Compiled once. Built per call this was ~35 Pattern.compile per turn, for
        // patterns that never change.
        this.replacements = STATED_REPLACEMENTS.entrySet().stream()
                .map(entry -> new Replacement(whole(entry.getKey()), entry.getValue()))
                .toList();
        this.forbidden = FORBIDDEN_TERMS.entrySet().stream()
                .map(entry -> new TermRule(whole(entry.getKey()), entry.getKey(), entry.getValue()))
                .toList();

        // The executor throws once ITS budget is spent, and its budget counts the
        // first attempt. Asking for as many reprompts as it allows attempts means the
        // last failure has nowhere to go but the exception — a word choice becoming a
        // 5xx, which is the failure this class exists to prevent. Clamped rather than
        // rejected: refusing to start over a cosmetic setting is its own outage.
        int ceiling = OutputGuardrailsConfig.MAX_RETRIES_DEFAULT - 1;
        this.maxReprompts = Math.clamp(voice.maxReprompts(), 0, ceiling);
        if (this.maxReprompts != voice.maxReprompts()) {
            LOG.warn("agentic.voice.max-reprompts={} exceeds what the guardrail executor allows; clamped to {}",
                    voice.maxReprompts(), this.maxReprompts);
        }
        LOG.info("Voice compliance guardrail active: {} allowed emoji, max {} bullets, {} reprompt(s)",
                allowedEmoji.size(), voice.maxBulletItems(), maxReprompts);
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

        // Prose only. An emoji or a dash inside a fenced code block is part of the
        // sample, and reporting it would raise a violation the repair is not allowed
        // to fix — which spends the reprompt budget every turn and never converges.
        Lines lines = Lines.of(text);
        String prose = lines.prose();

        List<String> emoji = emojiIn(prose);
        if (emoji.size() > 1) {
            found.add("emoji: use ONLY 1 emoji per response (found " + emoji.size() + ")");
        }
        emoji.stream()
                .map(VoiceComplianceGuardrail::normalise)
                .filter(e -> !allowedEmoji.contains(e))
                .distinct()
                .forEach(e -> found.add("emoji: \"" + e + "\" is not on the allowed list"));
        if (emoji.size() == 1 && !endsWithEmoji(prose)) {
            found.add("emoji: the emoji goes at the end of the message, after the closing full stop");
        }

        for (Replacement replacement : replacements) {
            Matcher match = replacement.pattern().matcher(prose);
            if (match.find()) {
                found.add("wording: \"" + match.group() + "\" must be \"" + replacement.to() + "\"");
            }
        }
        for (TermRule rule : forbidden) {
            if (rule.pattern().matcher(prose).find()) {
                found.add(rule.clause() + " (\"" + rule.term() + "\")");
            }
        }

        List<List<Integer>> lists = lines.bulletLists();
        int longest = lists.stream().mapToInt(list -> lines.itemsIn(list)).max().orElse(0);
        if (longest > voice.maxBulletItems()) {
            found.add("lists: at most " + voice.maxBulletItems()
                    + " bullet points per list (found " + longest + ")");
        }
        if (lines.usesTheWrongGlyph(lists)) {
            // The clause is "use bullet points (•) for lists - max. 5 items", and a
            // check that enforced only its second half would ratify the wrong glyph
            // in the same pass that measured the right count.
            found.add("lists: bullets are written \"" + BULLET + "\", not \"-\", \"*\" or \"+\"");
        }
        return found;
    }

    // ---------------------------------------------------------------------- repair

    /**
     * Applies only the corrections the document itself spells out.
     */
    String repair(String text) {
        // Prose lines only, and for the same reason violations() reads prose only:
        // where the two disagree about which lines count, one of them wins silently.
        // Running the replacements over the whole answer rewrote a code sample —
        // "var tod@s = lista;" became "var todos = lista;" — for a violation that
        // was never reported, and shipped it as a success.
        String out = text;
        // Repairs interact: removing "😀 " from the head of "😀 - item um" turns a line
        // the glyph fixer had already walked past into a bullet, so one pass left a
        // list with mixed markers and a violation the model was then asked to fix.
        // Iterating to a fixed point is what makes repair(repair(x)) == repair(x),
        // and that identity is what keeps the reprompt for real violations only.
        for (int pass = 0; pass < 3; pass++) {
            String next = repairOnce(out);
            if (next.equals(out)) {
                return out;
            }
            out = next;
        }
        return out;
    }

    private String repairOnce(String text) {
        String out = Lines.of(text).mapProse(line -> {
            String replaced = line;
            for (Replacement replacement : replacements) {
                replaced = replacement.pattern().matcher(replaced)
                        .replaceAll(match -> matchCase(match.group(), replacement.to()));
            }
            return replaced;
        });
        return Lines.of(repairEmoji(out)).withBulletGlyphFixed();
    }

    /**
     * Removes every emoji from the prose, then puts back at most one — and only when
     * the model had already shown the judgement the document asks for.
     *
     * <h3>More than one emoji means the judgement was not exercised</h3>
     * <p>
     * The document does not only cap the count. It says never in a serious message,
     * only when the emoji adds objective meaning, and "if you are unsure, do not use
     * it". A response carrying two emoji is evidence that none of that was applied,
     * so keeping the prettier one would convert a countable violation into an
     * unmeasurable one: an instability notice repaired from "fora do ar ⚠️ … sem
     * previsão 😊" down to a single trailing 😊 passes every check in this class and
     * is exactly the answer the document forbids. Where the count is wrong, all of
     * them go — the document's own tie-breaker is to leave it out.
     *
     * <p>A single emoji that is merely in the wrong place is a different case: the
     * model chose one, deliberately, from the allowed set. That one is moved — unless
     * the answer ends inside a code block, where appending it would run the emoji
     * onto the closing fence and render the rest of the message as code.
     */
    private String repairEmoji(String text) {
        Lines lines = Lines.of(text);
        List<String> emoji = emojiIn(lines.prose());
        if (emoji.isEmpty()) {
            return text;
        }
        String only = emoji.size() == 1 ? emoji.getFirst() : null;
        String keep = only != null && allowedEmoji.contains(normalise(only)) ? only : null;
        if (keep != null && endsWithEmoji(lines.prose())) {
            return text;
        }
        if (!lines.endsInAppendableProse()) {
            keep = null;
        }
        return lines.stripEmojiFromProse(this::isEmoji) + (keep == null ? "" : " " + keep);
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
                .formatted(String.join("\n", remaining.stream().map(rule -> "- " + rule).toList()), answer);
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
        if (spent >= maxReprompts) {
            return false;
        }
        parameters.put(ATTEMPTS_KEY, spent + 1);
        return true;
    }

    // -------------------------------------------------------------------- the lines

    /**
     * One answer, split into lines and classified once.
     *
     * <p>Every rule here is about what the assistant <em>says</em>, and a fenced code
     * block is not something it says. Doing the classification in one place is what
     * keeps {@code violations} and {@code repair} agreeing about which lines are
     * prose — and where they disagree, a violation is reported that repair may not
     * touch, the reprompt budget is spent on it every turn, and it never converges.
     */
    private record Lines(List<String> all, boolean[] isProse) {

        static Lines of(String text) {
            String[] split = text.split("\\R", -1);
            int fences = 0;
            for (String line : split) {
                if (FENCE.matcher(line).find()) {
                    fences++;
                }
            }
            // An odd number of fence markers means one is unpaired, and honouring it
            // would classify the whole tail as code — silently disabling every rule
            // below it. An unbalanced answer is treated as prose throughout.
            boolean honourFences = fences % 2 == 0;

            var prose = new boolean[split.length];
            boolean inFence = false;
            for (int i = 0; i < split.length; i++) {
                boolean isFenceMarker = honourFences && FENCE.matcher(split[i]).find();
                if (isFenceMarker) {
                    inFence = !inFence;
                }
                prose[i] = !isFenceMarker && !inFence;
            }

            // The other markdown code block: four spaces of indent after a blank
            // line. Fences were handled and this form was not, so a YAML or Python
            // sample written this way had its dashes rewritten into bullets, its
            // "tod@s" rewritten into "todos", and its six lines counted against the
            // five-item cap — a violation repair could not clear, spending a reprompt
            // on every turn that carried one.
            //
            // A block only opens after a blank line and never under a bullet, because
            // four spaces below a bullet is that item's continuation, not code.
            boolean inIndentedCode = false;
            boolean previousWasBlank = true;
            boolean previousWasBullet = false;
            for (int i = 0; i < split.length; i++) {
                if (!prose[i]) {
                    inIndentedCode = false;
                    continue;
                }
                String line = split[i];
                if (line.isBlank()) {
                    previousWasBlank = true;
                    continue;
                }
                boolean deep = indentWidthOf(line) >= 4;
                if (inIndentedCode && deep) {
                    prose[i] = false;
                    previousWasBlank = false;
                    continue;
                }
                inIndentedCode = deep && previousWasBlank && !previousWasBullet;
                if (inIndentedCode) {
                    prose[i] = false;
                }
                previousWasBlank = false;
                previousWasBullet = isBullet(line);
            }
            return new Lines(List.of(split), prose);
        }

        /**
         * Applies a rewrite to the prose lines and copies the code lines through.
         */
        String mapProse(java.util.function.UnaryOperator<String> rewrite) {
            var out = new StringBuilder();
            for (int i = 0; i < all.size(); i++) {
                if (i > 0) {
                    out.append('\n');
                }
                out.append(isProse[i] ? rewrite.apply(all.get(i)) : all.get(i));
            }
            return out.toString();
        }

        String prose() {
            var out = new StringBuilder();
            for (int i = 0; i < all.size(); i++) {
                if (isProse[i]) {
                    if (!out.isEmpty()) {
                        out.append('\n');
                    }
                    out.append(all.get(i));
                }
            }
            return out.toString();
        }

        /**
         * True when the answer ends on a line an emoji can be appended to.
         *
         * <p>Not merely "ends in prose": a closing fence, a table row, a bullet and a
         * heading are all places where " 😊" lands inside a structure rather than at
         * the end of a sentence. The closing fence was the visible one — it destroyed
         * the fence and rendered the rest of the answer as code — and a table row is
         * the same defect one step quieter.
         */
        boolean endsInAppendableProse() {
            for (int i = all.size() - 1; i >= 0; i--) {
                String line = all.get(i);
                if (line.isBlank()) {
                    continue;
                }
                return isProse[i]
                        && !isBullet(line)
                        && !line.stripLeading().startsWith("|")
                        && !line.stripLeading().startsWith("#")
                        && indentWidthOf(line) < 4;
            }
            return true;
        }

        /**
         * The line indices of each bullet list, where a list is two or more
         * consecutive bullet lines. A single line opening with a dash is a footnote,
         * a line of dialogue or an amount, and treating it as a list both rewrites it
         * and pushes the list above it over the item cap.
         */
        List<List<Integer>> bulletLists() {
            var lists = new ArrayList<List<Integer>>();
            var run = new ArrayList<Integer>();
            int blanks = 0;
            for (int i = 0; i < all.size(); i++) {
                if (!isProse[i]) {
                    blanks = flush(lists, run, blanks);
                    continue;
                }
                String line = all.get(i);
                if (line.isBlank()) {
                    blanks++;
                    continue;
                }
                if (isBullet(line)) {
                    // ONE blank line between items is a loose list — the shape this
                    // document pushes the model toward, since it mandates a blank line
                    // "between paragraphs, lists, headings and content". Reading it as
                    // the end of the list let ten dash items past both bullet rules.
                    // TWO ends it, and so does any paragraph in between.
                    //
                    // Across a blank line the marker also has to match, which is
                    // markdown's own rule and the one that keeps "* Trazer os
                    // originais." below a "•" list a footnote rather than its sixth
                    // item. Adjacent lines are matched marker-blind on purpose: that
                    // is where a genuinely mixed-glyph list shows up, and it should be
                    // reported.
                    boolean newList = blanks >= 2
                            || (blanks == 1 && !run.isEmpty()
                                && !markerOf(all.get(run.getFirst())).equals(markerOf(line)));
                    if (newList) {
                        flush(lists, run, blanks);
                    }
                    run.add(i);
                    blanks = 0;
                    continue;
                }
                // An indented line straight after an item is that item wrapping, not
                // the end of the list.
                if (!run.isEmpty() && blanks == 0 && indentWidthOf(line) > 0) {
                    continue;
                }
                blanks = flush(lists, run, blanks);
            }
            flush(lists, run, blanks);
            return lists;
        }

        private static int flush(List<List<Integer>> lists, List<Integer> run, int blanks) {
            if (run.size() > 1) {
                lists.add(List.copyOf(run));
            }
            run.clear();
            return 0;
        }

        /**
         * The items of one list: the lines at its shallowest indentation. Deeper lines
         * are sub-items of an item already counted, and the document caps items.
         */
        int itemsIn(List<Integer> list) {
            int base = list.stream().mapToInt(i -> indentOf(all.get(i))).min().orElse(0);
            return (int) list.stream().filter(i -> indentOf(all.get(i)) == base).count();
        }

        boolean usesTheWrongGlyph(List<List<Integer>> lists) {
            return lists.stream().flatMap(List::stream)
                    .anyMatch(i -> !BULLET.equals(markerOf(all.get(i))));
        }

        String withBulletGlyphFixed() {
            var rewrite = new LinkedHashSet<Integer>();
            bulletLists().forEach(rewrite::addAll);
            var out = new StringBuilder();
            for (int i = 0; i < all.size(); i++) {
                if (i > 0) {
                    out.append('\n');
                }
                String line = all.get(i);
                out.append(rewrite.contains(i)
                        ? BULLET_LINE.matcher(line).replaceFirst("$1" + BULLET + "$3")
                        : line);
            }
            return out.toString();
        }

        /**
         * Drops every emoji from the prose lines and closes the gap each one leaves,
         * copying code lines through byte for byte.
         */
        String stripEmojiFromProse(java.util.function.Predicate<String> isEmoji) {
            var out = new StringBuilder();
            for (int i = 0; i < all.size(); i++) {
                if (i > 0) {
                    out.append('\n');
                }
                String line = all.get(i);
                if (!isProse[i]) {
                    out.append(line);
                    continue;
                }
                var kept = new StringBuilder();
                forEachCluster(line, cluster -> {
                    if (!isEmoji.test(cluster)) {
                        kept.append(cluster);
                    }
                });
                // Closing the gap is part of REMOVING an emoji, so it runs only on the
                // lines that lost one. Applied to every prose line it reflowed the
                // padding of markdown tables and aligned text that had no emoji in it
                // at all.
                if (kept.length() == line.length()) {
                    out.append(line);
                    continue;
                }
                // The indentation is preserved and only the interior is collapsed:
                // "O prazo ⏰ é" must lose its double space, and a four-space block
                // must not lose its four.
                Matcher indent = INDENT.matcher(kept.toString());
                String leading = indent.find() ? indent.group() : "";
                String body = kept.substring(leading.length());
                body = SPACE_RUN.matcher(body).replaceAll(" ");
                out.append(leading).append(SPACE_BEFORE_PUNCTUATION.matcher(body).replaceAll("$1"));
            }
            return out.toString().stripTrailing();
        }

        private static boolean isBullet(String line) {
            return BULLET_LINE.matcher(line).find() && !THEMATIC_BREAK.matcher(line).find();
        }

        private static String markerOf(String line) {
            Matcher bullet = BULLET_LINE.matcher(line);
            return bullet.find() ? bullet.group(2) : "";
        }

        private static int indentWidthOf(String line) {
            Matcher indent = INDENT.matcher(line);
            if (!indent.find()) {
                return 0;
            }
            int width = 0;
            for (char c : indent.group().toCharArray()) {
                width += c == '\t' ? 4 : 1;
            }
            return width;
        }

        private static int indentOf(String line) {
            Matcher bullet = BULLET_LINE.matcher(line);
            return bullet.find() ? bullet.group(1).length() : 0;
        }
    }

    /**
     * Every Portuguese term this class matches on. Exposed so a test can assert the
     * catalogue runs in both directions: a term here that the document does not carry
     * is a rule the model was never told about, enforced on its output.
     */
    static Set<String> enforcedTerms() {
        var terms = new LinkedHashSet<>(STATED_REPLACEMENTS.keySet());
        terms.addAll(STATED_REPLACEMENTS.values());
        terms.addAll(FORBIDDEN_TERMS.keySet());
        return Set.copyOf(terms);
    }

    // ------------------------------------------------------------------ text utils

    private void count(String outcome) {
        meters.counter("agentic.voice.violations", "outcome", outcome).increment();
    }

    /**
     * Matches a term only when it stands alone.
     *
     * <p>{@code \b} is not enough twice over. It does not fire next to {@code @}, so
     * {@code tod@s} would match only up to the sign; and it treats {@code -},
     * {@code _} and {@code /} as boundaries, so {@code uai-minas},
     * {@code total_uai_mensal} and {@code /docs/uai/relatorio.pdf} all counted as the
     * regionalism.
     *
     * <p>Two of the three exclusions then need an exception carved back out, because
     * closing a hole opened a worse one. A full stop is a boundary only when a
     * sentence ends there — {@code recomendo.} must match and {@code uai.com} must
     * not. And a hyphen is part of a compound only when what follows is not an
     * enclitic pronoun: {@code Uai-Minas} is a proper noun, while
     * {@code Recomendo-lhe esse fundo} is standard formal Portuguese and is precisely
     * the sentence this rule exists to catch.
     *
     * <p>The third exclusion was withdrawn outright. Treating {@code _} and a trailing
     * {@code /} as identifier characters protected {@code total_uai_mensal} and cost
     * every rule in the map: {@code _Recomendo_ esse fundo} and
     * {@code Recomendo/sugiro} both went unmatched, and underscore emphasis is
     * something a model emits by habit. The trade is deliberate and asymmetric — a
     * snake_case identifier that happens to contain a listed term now costs one
     * reprompt, where the alternative shipped investment advice.
     */
    private static Pattern whole(String term) {
        return Pattern.compile(
                "(?<![\\p{L}\\p{N}@./-])" + Pattern.quote(term)
                        + "(?![\\p{L}\\p{N}@])"             // recomendos, tod@s
                        + "(?!\\.[\\p{L}\\p{N}])"         // uai.com, but "recomendo." still matches
                        + "(?!-(?!" + ENCLITIC + ")[\\p{L}\\p{N}])", // Uai-Minas, but "Recomendo-lhe" matches
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /**
     * Keeps the capitalisation of what was written: "Todes" becomes "Todos", and
     * "TODES" in a heading becomes "TODOS" rather than "Todos".
     */
    private static String matchCase(String matched, String replacement) {
        if (matched.isEmpty()) {
            return Matcher.quoteReplacement(replacement);
        }
        if (matched.length() > 1 && matched.equals(matched.toUpperCase(Locale.ROOT))
                && !matched.equals(matched.toLowerCase(Locale.ROOT))) {
            return Matcher.quoteReplacement(replacement.toUpperCase(Locale.ROOT));
        }
        if (Character.isUpperCase(matched.charAt(0))) {
            return Matcher.quoteReplacement(
                    Character.toUpperCase(replacement.charAt(0)) + replacement.substring(1));
        }
        return Matcher.quoteReplacement(replacement);
    }

    private static String normalise(String emoji) {
        return SELECTORS.matcher(emoji).replaceAll("").strip();
    }

    private List<String> emojiIn(String text) {
        var found = new ArrayList<String>();
        forEachCluster(text, cluster -> {
            if (isEmoji(cluster)) {
                found.add(cluster);
            }
        });
        return found;
    }

    private boolean endsWithEmoji(String text) {
        String trimmed = text.stripTrailing();
        if (trimmed.isEmpty()) {
            return false;
        }
        var last = new String[] {""};
        forEachCluster(trimmed, cluster -> last[0] = cluster);
        return isEmoji(last[0]);
    }

    /**
     * Whether a grapheme cluster is an emoji as a reader would see it.
     *
     * <p>Three ways to qualify, in the order they matter: the cluster defaults to
     * emoji presentation; or it is emoji-eligible and carries the U+FE0F selector that
     * asks for that presentation; or it is one of the emoji this deployment allows,
     * which catches {@code ⚠} — emoji-eligible, text by default, and on the list.
     *
     * <p>What this deliberately does not do is match {@code \p{IsEmoji}} alone: that
     * property is true of {@code #}, {@code *} and every digit, because each can be
     * the base of a keycap sequence.
     */
    private boolean isEmoji(String cluster) {
        if (cluster.isEmpty()) {
            return false;
        }
        String first = new String(Character.toChars(cluster.codePointAt(0)));
        if (EMOJI_PRESENTATION.matcher(first).find()) {
            return true;
        }
        if (cluster.indexOf('️') >= 0 && EMOJI_ELIGIBLE.matcher(first).find()) {
            return true;
        }
        return allowedEmoji.contains(normalise(cluster));
    }

    /**
     * Walks extended grapheme clusters rather than chars, so a flag, a skin-tone
     * modifier or a zero-width-joined sequence counts as the one emoji a reader sees
     * rather than as the two to five code points it is made of.
     */
    private static void forEachCluster(String text, Consumer<String> action) {
        BreakIterator clusters = BreakIterator.getCharacterInstance(Locale.ROOT);
        clusters.setText(text);
        int start = clusters.first();
        for (int end = clusters.next(); end != BreakIterator.DONE; start = end, end = clusters.next()) {
            action.accept(text.substring(start, end));
        }
    }
}
