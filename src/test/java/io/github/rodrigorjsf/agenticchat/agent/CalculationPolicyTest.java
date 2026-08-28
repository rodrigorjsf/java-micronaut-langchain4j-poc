package io.github.rodrigorjsf.agenticchat.agent;

import io.github.rodrigorjsf.agenticchat.tools.calc.CalculatorTools;
import io.github.rodrigorjsf.agenticchat.voice.VoiceProfile;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The activation guarantee for the arithmetic appendix, asserted on the real wiring.
 *
 * <p>Same shape as {@code VoiceProfileTest}, and for the same reason: an appendix that
 * fails to reach the model raises nothing. The model adds the numbers itself, writes
 * the total into a fluent sentence in the correct voice, and no exception, metric,
 * span or log line marks the turn. So the check is not "did we call something" but
 * "is the document, byte for byte, in the prompt the model is sent, in the right
 * place".
 */
@MicronautTest(startApplication = false)
class CalculationPolicyTest {

    @Inject
    CalculationPolicy calculation;

    @Inject
    VoiceProfile voice;

    @Inject
    SystemPromptBuilder systemPrompt;

    @Test
    @DisplayName("the shipped appendix is loaded verbatim from the classpath")
    void theAppendixIsLoaded() {
        assertThat(calculation.document())
                .startsWith("# Arithmetic")
                .endsWith("never answer with your own arithmetic instead.");
    }

    @Test
    @DisplayName("the whole appendix reaches the model, not merely its heading")
    void theWholeAppendixIsInTheAssembledPrompt() {
        assertThat(calculation.presentIn(systemPrompt.prompt())).isTrue();

        // A spot check per required element of the document, because containment of
        // the whole string still passes on a file silently truncated to its heading,
        // and each of these is a rule the model gets wrong when it is missing. Each
        // string sits inside one source line, so re-wrapping the prose does not turn
        // these into false failures.
        assertThat(systemPrompt.prompt()).contains(
                "Non-negotiable rule 1 covers numbers too",
                "`15` means 15%",
                "`PERCENT_OF` is `[percent, base]`",
                "`[base, percent]`: \"200 mais 15%\"",
                "never `R$ 1.234,56`, `1.234,56` or",
                "Chain a step rather than pre-computing part of one",
                "`CALCULATION FAILED` names the step");
    }

    @Test
    @DisplayName("the appendix sits after # Skills and before the voice document")
    void theAppendixIsPositionedBetweenSkillsAndVoice() {
        String prompt = systemPrompt.prompt();
        int appendixAt = prompt.indexOf(calculation.document());

        // Position is the one thing the two presentIn checks cannot see. Both of them
        // pass whichever way round the two documents are interpolated into the text
        // block, so swapping two adjacent %s arguments would leave the whole suite
        // green while the voice document stopped being last. Only an index ordering
        // catches that, which is why this is a test and not a comment on the builder.
        assertThat(appendixAt).isGreaterThan(prompt.indexOf("# Skills"));
        assertThat(appendixAt).isLessThan(prompt.indexOf("# How to answer"));
        assertThat(appendixAt).isLessThan(prompt.indexOf(voice.document()));
    }

    @Test
    @DisplayName("a missing appendix stops the process instead of degrading to mental arithmetic")
    void anAbsentAppendixFailsFast() {
        assertThatThrownBy(() -> new CalculationPolicy("prompt/DOES-NOT-EXIST.md"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found on the classpath");
    }

    @Test
    @DisplayName("the appendix has a size gate of its own that would actually fire")
    void theAppendixStaysInsideItsBudget() {
        // The system-prompt gate in VoiceProfileTest is 23_000 characters and has more
        // than a kilobyte of slack, so it would not notice this file doubling. This is
        // the gate that would: the appendix is paid on every turn of every
        // conversation, its budget was settled at 2 600 characters, and it is nearly
        // spent — every later edit is net-neutral or a cut.
        //
        // Characters, not bytes. `wc -c` reads 2599 on this file because the em dashes
        // and the arrows are multi-byte in UTF-8; the string the model is charged for
        // is 2577 long. Reading the byte count as the budget would leave the next
        // editor believing there is one character of headroom when there are 23.
        assertThat(calculation.document().length())
                .as("arithmetic appendix characters, paid on every turn of every conversation")
                .isLessThan(2_600);
    }

    @Test
    @DisplayName("the calculator is wired into the assistant's construction — "
            + "this does NOT prove the model is offered it on every turn")
    void theCalculatorIsDeclaredOnTheAssistant() {
        // What this proves: AiServiceFactory cannot build a ChatAssistant without a
        // CalculatorTools, so removing the .tools(calculator) line and its parameter
        // together is a red test rather than a silent loss of the tool.
        //
        // What it does not prove: that the static specification reaches the provider
        // on every turn. That is a property of LangChain4j's ToolService, measured in
        // the main session against the 1.18.1 sources rather than asserted here —
        // createContextFromStaticToolsAndProviders merges the static specs into
        // effectiveTools alongside whatever the dynamic provider returns. Asserting it
        // here would mean building the real assistant, which needs a chat model, which
        // needs an API key, which would move this test out of the default build.
        Method factoryMethod = Arrays.stream(AiServiceFactory.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("chatAssistant"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("AiServiceFactory no longer builds a ChatAssistant"));

        assertThat(factoryMethod.getParameterTypes())
                .as("the assistant factory takes the calculator, so .tools(calculator) has something to declare")
                .contains(CalculatorTools.class);
    }
}
