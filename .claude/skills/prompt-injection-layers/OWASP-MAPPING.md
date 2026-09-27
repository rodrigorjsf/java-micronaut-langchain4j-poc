# OWASP mapping

Read this when a threat model, an audit or a review asks which OWASP items this
defence answers. Key every table on the **identifier**, never the title: titles
get reworded between editions and between secondary sources, identifiers do not.

## LLM01:2025 Prompt Injection

Source: OWASP Top 10 for LLM Applications 2025, *LLM01:2025 Prompt Injection*
[sourced — read 2026-09-27,
https://github.com/OWASP/www-project-top-10-for-large-language-model-applications/blob/main/2_0_vulns/LLM01_PromptInjection.md].

It names two types. **Direct** injection: "a user's prompt input directly alters
the behavior of the model". **Indirect** injection: it "occur[s] when an LLM
accepts input from external sources, such as websites or files". It opens its
mitigations by conceding that "it is unclear if there are fool-proof methods of
prevention", then lists seven:

| LLM01:2025 mitigation | Where this skill answers it |
|---|---|
| 1 Constrain model behavior | the system prompt — reinforcement, never the control |
| 2 Define and validate expected output formats | a quarantined call returns a value code validates (layer 5) |
| 3 Implement input and output filtering | layers 1–4, and the tool-result screen |
| 4 Enforce privilege control and least privilege access | layer 5: the consequential tools a turn may reach are fixed before untrusted text arrives |
| 5 Require human approval for high-risk actions | layer 5's fallback when a policy cannot decide |
| 6 Segregate and identify external content | the tool-result section: a result is a stranger's text, screened by its own rules |
| 7 Conduct adversarial testing and attack simulations | the half-benign corpus, and the "detector lost" test |

The right-hand column is this skill's mapping, not OWASP's.

## OWASP Top 10 for Agentic Applications 2026

Source: OWASP GenAI Security Project, Agentic Security Initiative, *OWASP Top 10
For Agentic Applications 2026*, version 2026, December 2025 [sourced — PDF at
https://genai.owasp.org/download/52117, read 2026-09-27]. Titles below are the
document's own entry headings.

| Item | What OWASP says | Where this skill answers it |
|---|---|---|
| **ASI01 Agent Goal Hijack** (p. 9) | agents "cannot reliably distinguish instructions from related content", and attackers redirect them through "prompt-based manipulation, deceptive tool outputs, malicious artefacts, forged agent-to-agent messages, or poisoned external data". Its first mitigation routes every natural-language input "through the same input-validation and prompt-injection safeguards defined in LLM01:2025"; its second is "least privilege for agent tools and requiring human approval for high-impact or goal-changing actions" | layers 1–4 and the tool-result screen for the first; layer 5 for the second |
| **ASI02 Tool Misuse and Exploitation** (p. 12) | "Agents can misuse legitimate tools due to prompt injection […] leading to data exfiltration, tool output manipulation or workflow hijacking" | layer 5 — an injection that reaches a tool's arguments is this item |
| **ASI06 Memory & Context Poisoning** (p. 24) | context "excludes one-time input prompts covered under LLM01:2025"; the item is corrupted context "causing future reasoning, planning, or tool use to become biased, unsafe, or aid exfiltration" | layer 4's rule: remove a leaked or injected message from memory, never merely withhold it, or it replays into every later turn |

The document's own cross-map (Appendix A, p. 39) lists LLM01:2025 against
ASI01, ASI03, ASI05, ASI06, ASI08 and ASI09 — and **not** against ASI02, which it maps to
LLM06:2025 Excessive Agency. The ASI02 row above is therefore this skill's
reading: a tool misused *because of* an injection is where containment earns its
place, whichever list files it.
