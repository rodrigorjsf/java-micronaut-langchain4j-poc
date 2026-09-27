# Injection in tool results

Open this when the agent reads tool results, fetched pages, retrieved chunks or
sub-agent returns: where an input guardrail cannot see, and what each layer of
`SKILL.md` does with text a stranger wrote.

## Injection arrives in tool results too

The user is one author of the text the model reads. Every tool result, fetched
page, retrieved chunk and sub-agent return is another — a stranger's, read with
the same attention. OWASP calls this **indirect** prompt injection: it "occur[s]
when an LLM accepts input from external sources, such as websites or files"
[sourced — *LLM01:2025*, "Indirect Prompt Injections", read 2026-09-27,
https://github.com/OWASP/www-project-top-10-for-large-language-model-applications/blob/main/2_0_vulns/LLM01_PromptInjection.md]. Its scenario #2: a summarised web page carries hidden instructions to
insert an image whose URL exfiltrates the conversation — closed by layer 4's host
allow-list, never seen by an input-side rule.

**An input guardrail does not see it.** It runs once, on the user's turn, before
the tool loop; a tool result arrives inside the loop. Screen results where a
tool's output becomes a message — the executor, the tool door — and route
**every** tool through that point: a tool wired in by a second mechanism
(declared statically beside a dynamic provider, say) is one the screen never
sees. Where that hook lives is agentic-service-composition's question.

**A result needs its own rule set, not the user's.** Keep the rules about
*instructions* — chat-template delimiters, a fence labelled with a privileged
role, `data:` URIs, override and probe phrases, invisible characters, base64 that
decodes to text. Drop the rules about *sentences*. Compact JSON has no
whitespace, so an "unbroken 400-character token" rule fires on any result over
400 characters: a twelve-month interest-rate series of 457 characters was
reported to the model as an injection attempt [sourced — this repository's
tool-result scorer, whose comment records the measurement]. Length already has a
bound at the tool door.

**Neutralise the payload; keep the message.** Replace a flagged result's text
with a short neutral error the model can act on, and keep the result message —
its call id and its metadata. A tool call with no answer is a list providers
reject; why a stored history must keep that pair is
conversation-memory-and-compaction's.

**And assume the screen will miss.** It is layers 1–3 run on a different author,
and exactly as probabilistic. That is why layer 5 exists.
