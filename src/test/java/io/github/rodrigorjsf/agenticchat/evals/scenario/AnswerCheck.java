package io.github.rodrigorjsf.agenticchat.evals.scenario;

import io.github.rodrigorjsf.agenticchat.evals.scenario.Scenario.AnswerExpectation;
import io.github.rodrigorjsf.agenticchat.evals.scenario.ScenarioResult.CheckResult;
import io.github.rodrigorjsf.agenticchat.evals.scenario.ScenarioResult.ToolCall;
import io.github.rodrigorjsf.agenticchat.triage.AnswerLanguage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The second layer of checks: what the answer itself must satisfy, decided without a model.
 *
 * <p><b>Grounding</b> is the check that lets a row assert on live data. The row cannot name
 * tomorrow's rainfall, so it names where a value sits in the answer (a regular expression) and
 * the check requires that value to appear in a tool result captured during the same run. A
 * number the model wrote and no tool returned is a number it made up.
 *
 * <p>Numbers are compared as numbers, not as text: {@code "12,4"} in a Portuguese answer is the
 * {@code 12.4} a JSON body carries, and {@code "2"} is not grounded by the {@code 2} inside
 * {@code 12.4}. A value is also grounded by a tool number that rounds to it at the precision the
 * answer wrote, so "20 °C" is grounded by {@code 19.8} — rounding is something a good answer does.
 */
final class AnswerCheck {

    /**
     * Tool results that are not data. A skill body is instructions the model was handed, and a
     * number quoted from one grounds nothing about the world.
     */
    private static final Set<String> NOT_DATA = Set.of("activate_skill", "read_skill_resource");

    /** A number standing alone, so the {@code 2} in {@code temperature_2m_max} is not one. */
    private static final Pattern NUMBER = Pattern.compile("(?<![\\w.])-?\\d+(?:\\.\\d+)?(?!\\w)");

    /**
     * The host of an absolute {@code http(s)} address, or of a scheme-relative one inside a
     * markdown link or image — the two ways an answer makes a client fetch something.
     */
    private static final Pattern LINK_HOST = Pattern.compile(
            "(?i)(?:\\bhttps?:|\\]\\(\\s*)//([^\\s/?#\"'<>()\\[\\]]+)");

    /** Sentence punctuation an address ends with but does not own. */
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[.,;:!?]+$");

    static final String LINK_CHECK = "no link outside the catalogue";

    private AnswerCheck() {
    }

    static List<CheckResult> evaluate(AnswerExpectation expected,
                                      String answer,
                                      List<ToolCall> toolCalls,
                                      Predicate<String> linkAllowed) {
        var checks = new ArrayList<CheckResult>();
        String text = answer == null ? "" : answer;
        checks.add(links(text, linkAllowed));
        if (expected != null) {
            if (expected.language() != null) {
                checks.add(language(expected.language(), text));
            }
            for (String phrase : orEmpty(expected.contains())) {
                boolean present = text.toLowerCase(Locale.ROOT).contains(phrase.toLowerCase(Locale.ROOT));
                checks.add(new CheckResult("contains: " + phrase, present, present
                        ? "the answer contains \"" + phrase + "\""
                        : "the answer does not contain \"" + phrase + "\""));
            }
            for (String pattern : orEmpty(expected.grounded())) {
                checks.add(grounded(pattern, text, toolCalls));
            }
        }
        return checks;
    }

    private static CheckResult language(String expected, String answer) {
        if (!AnswerLanguage.SUPPORTED.contains(expected)) {
            return new CheckResult("language", false,
                    "the detector tells apart only " + AnswerLanguage.SUPPORTED + "; the row expects " + expected);
        }
        String observed = AnswerLanguage.of(answer);
        boolean same = expected.equals(observed);
        return new CheckResult("language", same, same
                ? "the answer reads as " + observed
                : "expected the answer in " + expected + ", it reads as " + observed);
    }

    private static CheckResult links(String answer, Predicate<String> linkAllowed) {
        var hosts = new ArrayList<String>();
        var outside = new ArrayList<String>();
        Matcher matcher = LINK_HOST.matcher(answer);
        while (matcher.find()) {
            String host = TRAILING_PUNCTUATION.matcher(matcher.group(1)).replaceAll("");
            hosts.add(host);
            if (!linkAllowed.test(host)) {
                outside.add(host);
            }
        }
        if (!outside.isEmpty()) {
            return new CheckResult(LINK_CHECK, false, "links to hosts outside the tool catalogue: " + outside);
        }
        return new CheckResult(LINK_CHECK, true, hosts.isEmpty()
                ? "the answer carries no link"
                : "every link is to a catalogue host: " + hosts);
    }

    private static CheckResult grounded(String pattern, String answer, List<ToolCall> toolCalls) {
        String name = "grounded: " + pattern;
        Matcher matcher;
        try {
            matcher = Pattern.compile(pattern).matcher(answer);
        } catch (PatternSyntaxException e) {
            return new CheckResult(name, false, "the row's pattern does not compile: " + e.getDescription());
        }
        var data = toolCalls.stream().filter(call -> !NOT_DATA.contains(call.name())).toList();
        var found = new ArrayList<String>();
        var ungrounded = new ArrayList<String>();
        var sources = new ArrayList<String>();
        while (matcher.find()) {
            String value = matcher.groupCount() >= 1 && matcher.group(1) != null ? matcher.group(1) : matcher.group();
            found.add(value);
            var source = data.stream().filter(call -> appearsIn(value, call.result())).findFirst();
            if (source.isPresent()) {
                sources.add(value + " in " + source.get().name());
            } else {
                ungrounded.add(value);
            }
        }
        if (found.isEmpty()) {
            return new CheckResult(name, false, "the answer holds no value matching " + pattern);
        }
        if (!ungrounded.isEmpty()) {
            return new CheckResult(name, false, ungrounded + " appear in no captured tool result; searched "
                    + data.stream().map(ToolCall::name).toList());
        }
        return new CheckResult(name, true, "grounded: " + String.join(", ", sources));
    }

    /**
     * Whether {@code value} is in {@code result}: numerically when it reads as a number, as
     * case-insensitive text otherwise.
     */
    private static boolean appearsIn(String value, String result) {
        if (result == null) {
            return false;
        }
        BigDecimal number = asNumber(value);
        if (number == null) {
            return result.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT));
        }
        Matcher numbers = NUMBER.matcher(result);
        while (numbers.find()) {
            var candidate = new BigDecimal(numbers.group());
            if (candidate.setScale(Math.max(number.scale(), 0), RoundingMode.HALF_UP).compareTo(number) == 0) {
                return true;
            }
        }
        return false;
    }

    /** A number as the answer wrote it — decimal comma or point — or null when it is not one. */
    private static BigDecimal asNumber(String value) {
        String normalized = value.strip();
        if (normalized.contains(",")) {
            normalized = normalized.replace(".", "").replace(',', '.');
        }
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<String> orEmpty(List<String> values) {
        return values == null ? List.of() : values;
    }
}
