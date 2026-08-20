---
name: prompt-injection-layers
description: Build a prompt-injection defence that survives production. Use when adding input or output guardrails, when a regex blocklist is the only defence, when a detector is producing false positives, or when deciding what a rejected request should be told. For where a guardrail attaches in the runtime so that it actually runs, use agentic-service-composition.
---

# Layered injection defence

A regex list is a speed bump: fullwidth characters, zero-width joiners, base64
and a rephrasing all walk past it. A pure LLM classifier is the opposite failure —
accurate enough, and a model call on every single turn.

Four layers, ordered so each makes the next one's job smaller.

```
INPUT   1 normalize    rewrites, never blocks
        2 score        structural certainties block; hints only score
        3 classify     one model call, gray zone only
OUTPUT  4 canary + exfiltration channels
```

## 1. Normalization is the lever, and it never blocks

Canonicalise the text before any rule reads it, so no rule has to be written
twice — once for the plain form and once for the fullwidth form of the same
attack.

- **NFKC**, which folds fullwidth `Ｉｇｎｏｒｅ`, mathematical-bold `𝗜𝗴𝗻𝗼𝗿𝗲` and
  ligatures onto plain ASCII ([UAX #15](https://www.unicode.org/reports/tr15/)).
- Strip **invisible characters**: zero-width space and joiners, BOM, bidi
  overrides, soft hyphen, word joiner.
- Strip **C0/C1 controls**, keeping tab, newline, carriage return.
- Collapse **padding runs** — long stretches of blank lines or spaces used to push
  a system prompt out of the model's attention.

Run it first. If your framework feeds each guardrail's output to the next, a
first-position normalizer defends everything downstream at once — and the
rewritten text is what reaches the model.

**Do not fold homoglyphs.** Cyrillic `а` U+0430 and Latin `a` U+0061 look
identical, and confusables folding
([UTS #39](https://www.unicode.org/reports/tr39/)) is a false-positive minefield
in an application that is legitimately multilingual. Count a high proportion of
non-Latin letters as a **signal** instead.

**Count invisible characters before removing them.** After normalization there
are none left to see, and their presence is one of the strongest signals you get.

## 2. Structural certainties block; statistical hints score

This split is what decides whether a detection layer survives contact with
production.

| Blocks on its own | Adds to a score |
|---|---|
| chat-template delimiters (`<\|im_start\|>`, `[INST]`, `<<SYS>>`) | invisible characters found before normalization |
| a code fence labelled `system` / `assistant` / `developer` | a high proportion of non-Latin letters |
| `data:…;base64,` URIs | a single "probe" phrase |
| a base64 run that **decodes to mostly printable text** | |
| absurd length, or an unbroken 400-character token | |
| an explicit instruction-override phrase | |

A chat-template delimiter is never legitimate user content. A high proportion of
Cyrillic is also just what a Russian speaker looks like.

**Decode before believing base64.** Random identifiers and hashes are
base64-shaped too and decode to noise; a smuggled instruction decodes to mostly
printable ASCII. The decode is the tell.

## Three false-positive controls, and they are the whole game

A rule set without these gets switched off by the first person it annoys.

**Fold accents for matching.** Users type "instrucoes" as often as "instruções".
A rule that only catches the accented spelling catches only the polite attacker.
Fold for matching; never send the folded text to the model.

**Skip phrase rules inside code fences.** A developer pasting an injection
payload to ask about it is not attacking you. Fences do **not** exempt the
delimiter rules — a fence *labelled* `system` is itself the attack.

**Require corroboration for phrases that are ordinary language.** "Act as", "aja
como", "pretend that" appear in normal sentences. Only score them when a role
noun follows within ~40 characters. That is what keeps "finja que está tudo bem"
out of the score.

Two real false positives found by a corpus test, both from rules that looked
obviously safe:

- `show me the …rules` matched **"Show me the rules of the game Truco"**.
  Narrowed to `your …prompt` and `the system prompt`.
- The Portuguese equivalent matched "mostre as regras do jogo". Now requires a
  possessive or an explicit "de sistema".

**Build the corpus half benign.** A detector that only proves recall is the kind
that gets switched off in week two. Fill the benign half with sentences that look
like attacks to a naive regex — "pode ignorar o que eu disse antes", "esqueça o
que eu pedi, vamos começar de novo".

## 3. The model sees only the gray zone

Three bands: clean passes with no model call; structural certainty blocks with no
model call; only the middle pays for one opinion.

Block only above a **high confidence** threshold — 0.8 is a reasonable start. An
unsure classifier lets a real user through.

If your framework gives an input guardrail only pass-or-kill, the heuristics and
the classifier must live in **one** component. There is no channel to hand a
score to the next guardrail, so "score here, decide there" is not expressible.

Give the classifier no tools, no memory, and no guardrails of its own — the last
one would call back into the guardrail that calls it.

## 4. Output side: a canary, and the two exfiltration channels

**System-prompt leakage.** Phrase-matching your own prompt does not work: the
model paraphrases, and will happily describe its instructions without reproducing
a sentence. Embed a **random per-process canary** in the prompt and match on
that. It catches verbatim dumps with zero false positives.

Paraphrase leakage is not solved by a control. It is solved by putting nothing
secret in the prompt. Treating a system prompt as a credential is what makes
extracting it worth the effort; treating it as a published behaviour spec means a
leak costs nothing.

**Exfiltration** has two channels worth closing:

- a **markdown image or link to an attacker host** fires the moment the answer
  renders, with no click. Allow-list the hosts your application has any reason to
  link to;
- **credential-shaped strings**, matched narrowly enough not to eat ordinary
  base64.

**Remove the offending message from memory, do not merely block it.** If your
framework has a "fail and delete the message" outcome, use it. Leaving the
message in the conversation replays the leaked text into every later prompt, so
one successful extraction keeps leaking for the rest of the session.

## What the user is told

**Nothing.** "I can't help with that request." — no rule name, no score, no
category.

A detector that explains which rule fired is a detector the attacker iterates
against, one request at a time. Log everything, including the rule ids; return
nothing.

## Rolling out a rule

Score and log, never block, until you have false-positive numbers from real
traffic. Promote a rule to blocking on evidence. Log the rule id on every hit so
that evidence exists at all.
