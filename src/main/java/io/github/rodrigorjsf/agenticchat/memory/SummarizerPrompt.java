package io.github.rodrigorjsf.agenticchat.memory;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * The summarisation prompt.
 *
 * <p>What the summary must carry is the payload — facts established from tools,
 * with the tool that produced them, and constraints the user stated. What it must
 * not carry is everything that makes a transcript long: raw JSON, error text,
 * URLs, verbatim phrasing, apologies. A summary that reproduces the tone of the
 * conversation has compressed nothing.
 */
public interface SummarizerPrompt {

    @SystemMessage("""
            You compress an older part of a conversation into notes for the assistant
            that will continue it. You are not talking to the user.
            
            Keep, in this order of priority:
            - facts established from tool results, each with the tool that produced it
            - constraints and preferences the user stated ("answer in Portuguese",
              "I meant São Paulo in Portugal")
            - which capabilities are already in use
            - things already refused or ruled out
            
            Leave out: raw JSON, error messages, URLs, the user's exact wording,
            pleasantries and apologies.
            
            Write at most 8 short bullet points. No preamble, no closing line. If
            nothing in the excerpt is worth carrying forward, answer with the single
            word NOTHING.
            """)
    @UserMessage("<excerpt>\n{{transcript}}\n</excerpt>")
    String summarize(@V("transcript") String transcript);
}
