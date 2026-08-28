package io.github.rodrigorjsf.agenticchat.agent;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The arithmetic appendix, loaded once from the classpath.
 *
 * <h2>What goes wrong when this document does not reach the model</h2>
 * <p>
 * Nothing visible. A model that never reads the appendix does not refuse, does not
 * error and does not fall silent — it adds the numbers itself and writes the total
 * into the sentence, and a wrong total is exactly as fluent as a right one. There is
 * no exception to catch, no tool span in Langfuse to be missing (the tool was never
 * called, so there is nothing whose absence stands out in a trace that otherwise
 * looks normal), and no log line anywhere in this application that records the
 * event. The first report is a user who added the invoice up by hand. That is the
 * whole reason {@link #presentIn(String)} exists and the whole reason
 * {@link SystemPromptBuilder} refuses to start without it: the only affordable place
 * to detect this failure is before the process serves its first request.
 *
 * <h2>Why it is in the system prompt and not behind a skill</h2>
 * <p>
 * The same argument the voice document makes. A skill discloses something needed on
 * <em>some</em> turns; arithmetic can turn up in any turn, and the turn where the
 * model does not notice that it qualifies is indistinguishable from the turn that
 * genuinely needed no calculation. Routing this behind {@code activate_skill} would
 * put the rule behind the very judgement the rule exists to remove.
 *
 * <h2>Why it is a resource and not a Java text block</h2>
 * <p>
 * It is prose the model reads, revised by whoever tunes the assistant's behaviour,
 * and it is paid for on every turn of every conversation. As a file it is diffed as
 * prose and its size is measurable by a test. Nothing in Java knows what it says.
 *
 * <p><b>Standing constraint on the file: 2 600 characters, and it is nearly spent.</b>
 * Every later edit is net-neutral or a cut — a paragraph added here is a paragraph
 * charged to every request the service will ever serve.
 * {@code CalculationPolicyTest} asserts the ceiling in characters, so an edit that
 * grows it goes red rather than quietly getting more expensive.
 */
@Singleton
public class CalculationPolicy {

    private static final Logger LOG = LoggerFactory.getLogger(CalculationPolicy.class);

    private final String document;

    public CalculationPolicy(
            @Value("${agentic.prompt.calculation-document:prompt/CALCULATION.md}") String resource) {
        this.document = read(resource);
        LOG.info("Arithmetic appendix '{}' loaded: {} chars, ~{} tokens",
                resource, document.length(), document.length() / 4);
    }

    /**
     * The document, verbatim. Trailing whitespace is trimmed so the containment check
     * in {@link #presentIn(String)} is not defeated by an editor adding a newline.
     */
    public String document() {
        return document;
    }

    /**
     * Whether the assembled prompt really carries this document.
     *
     * <p>The activation guarantee. See the class javadoc for what the failure looks
     * like: confident mental arithmetic, in the right voice, that no log line records.
     */
    public boolean presentIn(String prompt) {
        return prompt != null && prompt.contains(document);
    }

    private static String read(String resource) {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Arithmetic appendix not found on the classpath: " + resource);
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            if (text.isBlank()) {
                throw new IllegalStateException("Arithmetic appendix is empty: " + resource);
            }
            return text;
        } catch (IOException cannotRead) {
            throw new IllegalStateException("Arithmetic appendix could not be read: " + resource, cannotRead);
        }
    }
}
