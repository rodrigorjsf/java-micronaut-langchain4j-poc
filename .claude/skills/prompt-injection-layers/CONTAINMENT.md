# Containment — the patterns, the limits, the cost

Read this when you are choosing *how* to stop untrusted text from choosing a
consequential action, or arguing about whether containment makes detection
unnecessary. The rule table itself is in the body's layer 5.

## The principle

> "once an LLM agent has ingested untrusted input, it must be constrained so that
> it is impossible for that input to trigger any consequential actions—that is,
> actions with negative side effects on the system or its environment"

[sourced — Beurer-Kellner et al., *Design Patterns for Securing LLM Agents
against Prompt Injections*, arXiv 2506.08837v3, 2025-06-27, §3, read 2026-09-27,
https://arxiv.org/abs/2506.08837]

## The six patterns, and which rule each one backs

All from §3.1 of the same paper. "Untrusted" means anything the user did not type.

| Pattern | What it constrains | Backs the rule |
|---|---|---|
| **Action-selector** | the model only maps a request onto predefined actions, and no action's output comes back to it — "an LLM-modulated 'switch' statement" | the strongest form of "consequential tools fixed before untrusted text enters" |
| **Plan-then-execute** | the plan (the list of tool calls) is fixed before any tool output is read; it "acts as a form of 'control flow integrity' protection" | "consequential tools fixed before untrusted text enters" |
| **LLM map-reduce** | one isolated model per untrusted item, with no harmful tools; its output is constrained ("a regex that ensures the output of map is a number") before anything else reads it | "a model that reads untrusted text has no tools, and returns a value code can check" |
| **Dual LLM** | a privileged model with tools never reads untrusted data; a quarantined model without tools does, and hands back symbolic variables that ordinary code dereferences at call time | the same row, in its general form |
| **Code-then-execute** | the model writes a program up front; untrusted data flows through the program, not through the model's planning | "consequential tools fixed…", with arguments still exposed |
| **Context-minimization** | the user's prompt is removed from the context once it has chosen the action, so it cannot steer the reply | the one pattern aimed at the *user's* prompt |

Plan-then-execute and code-then-execute both admit the gap the paper states
plainly: "we cannot prevent a prompt injection in the calendar data from altering
the content of the email sent to John". That gap is what the destination rule
closes.

## Why arguments need their own rule — CaMeL

CaMeL's running example is "Can you send Bob the document he requested in our
last meeting?". The plan is right and stays right; the attack is in the data:
"the prompt injection modifies the recipient's email address within a 'send
email' task", so the right tool sends a confidential document to the attacker
[sourced — Debenedetti et al., *Defeating Prompt Injections by Design*, arXiv
2503.18813v2, 2025-06-24, §3, read 2026-09-27, https://arxiv.org/abs/2503.18813].

Its answer is provenance checked at the call: every value carries capabilities
recording "the sources and allowed recipients of each value" (§2), and a security
policy runs when a tool is called. The banking suite's `send_money` policy
"requires the recipient and the amounts of the payment to have the user as a
source, as well as no other untrusted parent source in the dependency graph"
(§6). When a policy blocks a call, the user is "asked for explicit approval"
(§2) — the person-decides row.

You do not need CaMeL's interpreter to take the rule. A tool that checks its
recipient against the user's own messages, or looks it up from the caller's
identity, is enforcing the same policy for the one argument that matters.

## What containment does not cover

- **Text-to-text attacks.** CaMeL "cannot defend against text-to-text attacks
  which have no consequences on the data flow" — a summary that misstates the
  email, a phishing link presented as advice (§3.1). That is layer 4's link
  allow-list and the canary.
- **A malicious user.** CaMeL assumes "the user prompt is trusted" (§3), and
  plan-then-execute "does not prevent prompt injections contained in the user
  prompt" (arXiv 2506.08837v3, §3.1). Layers 1–3 stay for exactly that input.
- **The detectors themselves.** Containment bounds what a miss can *do*; it does
  not make a miss rarer. A leaked system prompt or a poisoned answer is still a
  detection problem.

## What it costs

In AgentDojo, CaMeL solved **77%** of tasks with provable security, against
**84%** for an undefended system [sourced — arXiv 2503.18813v2, abstract]. The
denials cluster where provenance is hardest to annotate: the paper reports more
blocked calls in its Slack and banking suites, where data comes from web pages or
the `send_money` policy is strict (§6). Spend containment on the tools that
write, send or pay, not on the ones that only read.
