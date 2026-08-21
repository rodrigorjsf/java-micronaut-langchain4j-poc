package io.github.rodrigorjsf.agenticchat.tools.http;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which hosts this application may link to, and how a tool result is made to obey
 * that before the model ever reads it.
 *
 * <p><b>Derived, not listed.</b> The set is every host in the tool catalogue plus
 * anything explicitly added in configuration. A hand-written list beside a growing
 * catalogue drifts in the direction that hurts: a new data source is added, its
 * host is not, and the assistant starts refusing to cite the source it just used.
 * Deriving it means the two cannot disagree.
 *
 * <p>Registrable-domain matching, not exact-host: a tool calling
 * {@code api.open-meteo.com} makes {@code open-meteo.com} and its subdomains
 * linkable, because a source's documentation and its API rarely live on the same
 * hostname. It is also what keeps {@code images.dog.ceo} usable from a catalogue
 * entry of {@code dog.ceo}.
 *
 * <h2>Why the derivation lives in the tool package</h2>
 * <p>
 * The same list has two users. {@code ExfiltrationGuardrail} applies it to the
 * finished answer — see {@code guardrail.output.LinkAllowList}, which is now a
 * façade over this class. {@link ToolHttpClient} applies it to every body coming
 * in, because a link the answer may not carry is a link the model should never
 * have been shown.
 *
 * <p>It could not live on the guardrail side. {@code guardrail} already depends on
 * {@code tools} for {@link ApiEndpointProperties}, so a {@code tools → guardrail}
 * edge would close a package cycle that {@code ArchitectureTest#noPackageCycles}
 * fails on. The derivation therefore sits at the bottom, where both layers can
 * reach it.
 *
 * <h2>What the scrub is, and what it is not</h2>
 * <p>
 * It is <b>not</b> "strip URL-valued fields". That version of the fix is wrong in a
 * way that is quiet: {@code get_random_dog_image} returns nothing but a link, a
 * drawn card <em>is</em> a picture, and {@code search_openalex} answers an
 * {@code openalex.org} address the user is entitled to be given. Every one of those
 * hosts is in the catalogue and every one of them survives here.
 *
 * <p>It is "remove the links this application could not have shown anyway". The
 * decision is the allow-list's, not a field name's, so the tool layer and the
 * output guardrail cannot disagree about a given host — and a projection written
 * next year cannot re-admit one by keeping a field whose name nobody recognised.
 *
 * <p>The alternative was leaving them in and letting the guardrail fire. That costs
 * the whole answer: {@code ExfiltrationGuardrail} returns
 * {@code fatalWithMessageRemoval}, so an ordinary question about a paper is
 * answered "The response was withheld by the output policy." with nothing
 * connecting it to a tool result. Removing the link degrades one field; leaving it
 * loses the turn.
 */
@Singleton
public class LinkPolicy {

    private static final Logger LOG = LoggerFactory.getLogger(LinkPolicy.class);

    /**
     * What replaces a link that may not be shown.
     *
     * <p>Bracketed meta-text, the shape {@link ToolResponse#toModelText()} already
     * uses for {@code [truncated: …]}, so the model reads it as a note about the
     * result rather than as data. It carries no host: a bare hostname is still an
     * exfiltration channel — an attacker registers
     * {@code <secret>.attacker.example} and reads it out of DNS — and some clients
     * turn a bare domain into a link anyway.
     */
    static final String REMOVED = "[link removed: outside the tool catalogue]";

    /**
     * An absolute {@code http(s)} address, tolerating JSON-escaped slashes.
     *
     * <p>{@code https:\/\/www.themealdb.com\/…} is what a PHP-backed API sends —
     * {@code json_encode} escapes forward slashes by default — and a pattern
     * anchored on a literal {@code //} walks straight past it while the model, which
     * reads the decoded string, does not.
     *
     * <p>Group 1 is the authority, up to the first delimiter; group 2 is everything
     * after it that is not whitespace or a JSON string terminator, so the whole
     * address goes rather than just its head.
     */
    private static final Pattern ABSOLUTE_URL = Pattern.compile(
            "(?i)\\bhttps?:(?:\\\\?/){2}([^\\s\"'<>/?#\\\\]+)([^\\s\"'<>]*)");

    /**
     * A scheme-relative address at the start of a JSON string value: MediaWiki
     * answers {@code "url":"//www.wikidata.org/wiki/Q155"}, and a browser fetches
     * that over the page's own scheme with no click.
     *
     * <p>The lookbehind is the whole safety margin. Without it the pattern matches
     * {@code //} anywhere — inside a comment, inside a path, inside prose — and a
     * control that mangles ordinary text is a control that gets turned off.
     */
    private static final Pattern JSON_SCHEME_RELATIVE = Pattern.compile(
            "(?<=\")(?:\\\\?/){2}([^\\s\"'<>/?#\\\\]+)([^\\s\"'<>]*)");

    /**
     * Sentence punctuation a bare-host address ends with but does not own.
     *
     * <p>Not JSON's problem — there a value ends at its quote — but these bodies also
     * carry prose: an abstract, a Wikipedia extract, a licence notice. "Fonte:
     * https://api.crossref.org." captures {@code api.crossref.org.} with the full
     * stop attached, that string ends with no allowed suffix, and a source the
     * catalogue DOES contain would be deleted from the one field quoting it.
     * {@code ExfiltrationGuardrail} learned this on the other side of the wire.
     *
     * <p>It does two jobs, and they are separate: on the <em>host</em> it decides
     * whether the address is allowed, and on the <em>whole match</em> it decides how
     * much text the replacement is entitled to consume. Doing only the first trims
     * the stop off the decision and then deletes it from the sentence anyway —
     * "Fonte: [link removed: …] Consultado hoje." — which is a control quietly
     * editing prose it was never asked to touch.
     */
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[.,;:!?'\"\\)\\]}]+$");

    private final Set<String> allowed;

    public LinkPolicy(List<ApiEndpointProperties> endpoints,
                      @Value("${agentic.guardrails.output.extra-allowed-link-hosts:}") List<String> extra) {
        var hosts = new LinkedHashSet<String>();
        for (ApiEndpointProperties endpoint : endpoints) {
            String host = hostOf(endpoint.baseUrl());
            if (host != null) {
                hosts.add(registrableDomain(host));
            }
        }
        if (extra != null) {
            extra.stream()
                    .filter(host -> host != null && !host.isBlank())
                    .map(host -> host.toLowerCase(Locale.ROOT).strip())
                    .forEach(hosts::add);
        }
        this.allowed = Set.copyOf(hosts);
        LOG.info("Responses may link to {} domains, derived from the tool catalogue", allowed.size());
    }

    public boolean allows(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String bare = host.toLowerCase(Locale.ROOT);
        bare = bare.contains("@") ? bare.substring(bare.indexOf('@') + 1) : bare;
        bare = bare.split(":")[0];
        for (String domain : allowed) {
            if (bare.equals(domain) || bare.endsWith("." + domain)) {
                return true;
            }
        }
        return false;
    }

    public Set<String> domains() {
        return allowed;
    }

    /**
     * Replaces every link to a host outside the catalogue with {@link #REMOVED},
     * and leaves every other byte of {@code body} exactly as it was.
     *
     * @return {@code body} itself when nothing had to change, so the common case
     *         allocates nothing
     */
    public String scrub(String body) {
        if (body == null || body.isEmpty()) {
            return body;
        }
        // Most bodies carry no address at all, and the two patterns below are the
        // expensive part of a call that otherwise touches no memory.
        if (body.indexOf("http") < 0 && body.indexOf("\"//") < 0 && body.indexOf("\"\\/") < 0) {
            return body;
        }
        return removeDisallowed(removeDisallowed(body, ABSOLUTE_URL), JSON_SCHEME_RELATIVE);
    }

    private String removeDisallowed(String body, Pattern pattern) {
        Matcher matcher = pattern.matcher(body);
        StringBuilder out = null;
        while (matcher.find()) {
            String host = TRAILING_PUNCTUATION.matcher(matcher.group(1)).replaceAll("");
            if (allows(host)) {
                continue;
            }
            if (out == null) {
                out = new StringBuilder(body.length());
            }
            LOG.debug("Removed a tool-result link to {}: outside the tool catalogue", host);
            // The stop, comma or bracket the address ended on belongs to the sentence
            // around it, not to the address, so it is put back after the marker. In
            // JSON there is nothing to put back — the value ended at its quote and the
            // match stopped there.
            matcher.appendReplacement(out, Matcher.quoteReplacement(REMOVED + sentenceTailOf(matcher.group())));
        }
        if (out == null) {
            return body;
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** The punctuation at the end of {@code matched} that the sentence owns, if any. */
    private static String sentenceTailOf(String matched) {
        Matcher stop = TRAILING_PUNCTUATION.matcher(matched);
        return stop.find() ? matched.substring(stop.start()) : "";
    }

    private static String hostOf(String baseUrl) {
        try {
            return URI.create(baseUrl).getHost();
        } catch (RuntimeException notAUri) {
            return null;
        }
    }

    /**
     * Last two labels, which is right for {@code api.open-meteo.com} and wrong for
     * a multi-label public suffix such as {@code .com.br}. Three labels are kept
     * when the second-to-last is one of the handful of suffixes this catalogue
     * actually contains — a full public-suffix list is a dependency and a download
     * for a problem this size.
     */
    private static String registrableDomain(String host) {
        String[] labels = host.toLowerCase(Locale.ROOT).split("\\.");
        if (labels.length <= 2) {
            return host.toLowerCase(Locale.ROOT);
        }
        String secondLast = labels[labels.length - 2];
        boolean multiLabelSuffix = Set.of("com", "gov", "org", "net", "edu").contains(secondLast)
                && labels[labels.length - 1].length() == 2;
        int keep = multiLabelSuffix ? 3 : 2;
        return String.join(".", Arrays.copyOfRange(labels, labels.length - keep, labels.length));
    }
}
