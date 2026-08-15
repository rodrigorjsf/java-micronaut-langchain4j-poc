---
name: authoring-agent-tools
description: Turn "we need a tool for X" into a tool contract before any of it is built. Use when someone asks for a tool that does not exist yet, when deciding what parameters a proposed tool takes, when a tool request arrives as an implementation rather than a requirement, or when working out whether one proposed tool is really two. For a tool that already exists, use agentic-tool-boundary.
---

# Authoring an agent tool

**"Add a tool that looks up an order"** names an implementation someone
pictured. Nobody has said what question a user asks, what the model answers
without it, or what a wrong answer costs. Built as stated, it is shaped like the
API it wraps. What a finished tool must look like is a boundary question; this is
the pass *before* it — an **interview** whose answers are the contract. Three
questions follow, and a fourth — *what decision would this tool hide from the
model?* — waits until §4, once there is a shape to ask it of.

## 1. What does the user actually say?

Collect the **sentence list**: real sentences in the words a person types, from
support tickets, agent transcripts, or the turns the agent answers badly today.
Sentences written by whoever asked for the tool describe the tool that person
already pictured.

```
"where's my order"    "did 88231 ship yet"    "my package hasn't arrived"
```

One question — *where is this thing now* — and not one of them asks for line
items or a billing address. The field list starts narrowing here, before an API
doc is opened.

## 2. What does the model answer without it?

**Probe it.** Send the sentence list to the agent as it stands today, a dozen
runs — a smoke test, not a statistic. **Read the runs by their worst outcome,
not their average**, or the rows below stop being exclusive: a run that is right
eight times is also wrong four.

| The unaided model is… | Decision |
|---|---|
| right twelve times out of twelve, on facts that do not move | do not build it — the schema taxes every turn for a capability you already have |
| right eight of twelve, and the four wrong answers read exactly like the eight | **build.** This is the usual outcome and the strongest case for building: nobody reading an answer can tell which four they got, so the eight do not help them. Illustratively, one confident fabrication — an invented tracking number — in a dozen runs already clears the bar |
| right but unverifiable, and it cannot know today's truth | build it **for freshness, not knowledge**, which lands it at tier two in §3 — that tier's contract is what makes freshness usable. More fields is the wrong fix |
| wrong for a reason a tool does not fix | not a tool. Stale-but-static content is retrieval; a phrasing it keeps getting wrong is the standing prompt; a fixed multi-call sequence is a skill |

The probe's output is the verdict **plus every transcript where the model was
wrong** — the tool's first eval cases, free only while the tool does not exist.

**Four outcomes end the interview without a tool**, each handing back a finished
result — the sentence list, the probe output, which of the four — not a refusal.

1. **Already built** — an existing tool answers these sentences. The cheapest of
   the four and the only one you can run before the probe: two tools answering
   one question make routing a coin flip.
2. **No traffic** — fewer than three sentences from a real source. A schema is
   paid for on every turn whether or not anyone asks.
3. **Already answered** — the probe came back right every time.
4. **Not a tool** — the probe was wrong for a reason a tool does not fix.

## 3. What does a wrong answer cost?

Not how accurate the source is — what happens to the person when it is wrong. A
sentence the user will correct is **tier one** — our lookup: read-only, cache
freely, partial data fine. A wasted trip or a support ticket is **tier two**: the
result carries its provenance and the description tells the model to hedge with
it. Money, or an effect the user must undo, is **tier three**, whose contract is
**two calls** — one returns what *would* happen plus a token, the second takes
only that token, so the model can show the user what it is about to do, the only
moment anyone can stop it.

Choosing the tier deliberately closes **Tool Misuse & Exploitation (ASI02)** in
the OWASP Top 10 for Agentic Applications 2026: the ranked risk is not that a
model calls a tool, it is that a tool can do more than the question needed. The
two-call contract closes a second item — **Human-Agent Trust Exploitation
(ASI09)**, the excessive agency of an agent acting past what the turn authorised
because the user approved something described in the abstract and learned what it
actually did afterwards.
Above tier one, ask what a *second identical call* costs — a timeout makes the
model call again. **When the tool changes anything, or when a boolean parameter
survives the buckets below, read [`WRITES-AND-FLAGS.md`](WRITES-AND-FLAGS.md).**

## From the answers to the parameter set

Sort every candidate into three buckets under one rule: **a value that widens
what a call can reach is set by the system; a value the model sets may only
narrow inside a scope the system already fixed** — the model fills parameters
from the conversation, and the conversation is where an attacker writes.

**From the user**: it appears in the sentence list. **From the system**:
identity, tenant, locale, the catalogue key — injected by the tool layer, absent
from the schema. The answer belongs to one customer, so `customerId` is not a
parameter and no argument exists to smuggle an account number through, which
removes **Agent Identity & Privilege Abuse (ASI03)** rather than filtering for
it. **Invented**: nothing in the conversation or the session supplies it, so the
model guesses, and a guessed argument is a fabrication with a function call
around it — either the user is asked, or it is not a parameter.

```
BAD   list_orders(customerId, page, pageSize, includeArchived, locale, status)

GOOD  find_orders(query)          // "the blue one", "tuesday"
      get_order_status(orderId)
```

`page`, `pageSize`, `includeArchived` and `locale` are configuration, in no
sentence. `status` survives only as an enum (`open | shipped | delivered`),
because two sentences narrow by it — free text there earns a retry loop the
first time a model sends `"in transit"`. Then name, for each survivor, the
sentence that determines it: one you cannot name gets hallucinated on every
call. Past roughly five survivors, the tool answers more than one question.

## Write the description from the sentence list

Routing runs on the description, so it is derived, not composed. **The sentence
list becomes the "use when" clause,** in
the user's words, because the phrasings you collected are the phrasings the model
matches against. **The sentence that determines a parameter becomes that
parameter's example** — `"did 88231 ship yet"` is where `orderId`'s `e.g. 88231`
comes from. **A split creates a precondition on the second tool**: name which id
form it takes and which tool produces it. That there *is* one falls out of §4;
how the sentence is worded is the boundary's description style.

## Write the error taxonomy before the happy path

Name every way the call fails to return the answer, as a closed set:
`not_found`, `ambiguous`, `invalid_input`, `not_permitted`, `unavailable`,
`too_large`, `already_done`. Enumerating failures changes the design, and it can
only change the design while the design is on paper.

**It finds the shape that leaks.** *No such order* and *that order is not yours*
must be identical on the wire, or the tool is an oracle: anyone who can talk to
the agent enumerates valid references by watching which reply comes back, and no
rate limit hides it.

**It finds the answer that is not one answer.** *The reference matches three
orders* forces a question the happy path never raises: does this tool return a
list, or silently pick one? Answer it here and the tool is right; answer it in a
bug report and it has shipped confident wrong orders for a month.

| A reachable outcome | What it says about the design |
|---|---|
| `too_large` can occur | the result shape is wrong, and a budget will not fix a shape |
| `not_found` is common across the sentence list | a search tool is missing in front of this one |
| `not_permitted` is reachable by changing a parameter | identity is in the wrong bucket; move it to the system |
| an outcome you cannot name | that is the one the model receives as an exception |

A list naming only `not_found` and `invalid_input` was copied from the vendor's
200 example: `unavailable`, `ambiguous` and `not_permitted` appear in no API
doc's error table and happen every week. Illustratively, a healthy read tool
carries those five — `too_large` says the shape is wrong, and `already_done`
belongs to a write.

## Size the result from a probe

Nobody guesses result size right, and the guess is always low. **Probe it.** Call
the real upstream twenty times with the arguments the sentence list produces,
against the worst account you can reach, and record serialized size at p50 and
max: the endpoint returning twelve rows for the test user returns four hundred
for a reseller. **Max is the number the test gates; p50 is the number the bill is
forecast from** — p50 times the calls a turn makes is what this tool adds to an
average turn, and a forecast that looks fine beside a max ten times over is a
tool that fails on your largest customer.

Then subtract rather than cap. Write the **answer sentence** — what the model
will say to the user — and keep only the fields in it or in the obvious
follow-up. *"Your order shipped Tuesday and arrives Friday, tracking AB123"*
needs five fields; the upstream order object has sixty. A cap over a fat payload
slices an object in half; a **projection** is small because it was designed
small. The arithmetic is illustrative: divide what a turn may spend on tool
output by the calls it makes — 6,000 tokens across three calls is ~2,000 per
result, which is what the shaped **max** must sit under. A projection that will
not fit means §1 was answered too loosely; keep the fattest payload as a fixture.

## 4. What decision would this tool hide from the model?

The decision a tool hides is the one it makes silently when it cannot decide, so
that is where to go looking for it. **Write the sentence the tool returns when it
cannot decide. If that sentence is a question addressed to the user, the tool is
two.** *"Three orders match that reference — which one did you mean?"* is a
question asked by a component with no way to ask anything; one tool answering it
picks an order and never says it picked. So `find_orders(reference)` returning
short summaries, then `get_order_status(orderId)`.

**Then design the handoff, or the split only moves the ambiguity.** Each summary
carries the id in the form the second tool's parameter accepts. Return `"blue
lamp, ordered Tuesday"` and the model calls `get_order_status("blue lamp")` — the
invented argument the buckets just killed, reintroduced between two tools;
`"#88231 — blue lamp, ordered Tuesday"` cannot be misread that way.

**When that sentence does not fire and the tool still feels like two — a read and
a write behind one name — read [`SPLIT-TRIGGERS.md`](SPLIT-TRIGGERS.md).**

## The tests that gate the ship

Three are deterministic and sit beside the code.

- **Taxonomy** — one case per branch, asserting the returned **text**: it is the
  next prompt the model reads. It also proves the enumeration closed — an outcome
  you cannot construct a case for is imaginary and gets deleted, and a result
  landing outside every outcome means the taxonomy is still open.
- **Budget** — the fattest fixture, shaped by the projection, stays inside the
  per-result budget. The day upstream adds a field, the build fails, not the bill.
- **Refusal** — a conversation asking for another account's data is refused
  because the account came from the session, not because a filter caught it.

**Routing is the fourth, and it is not a unit test** — it runs the model, so it
needs a pass threshold and a rule for a flaky case, which is `agentic-evals`
territory. This pass owes it the cases: every sentence in the list as a positive,
and one sentence belonging to the **nearest sibling by name** as the negative,
which fails the day someone edits that sibling's description into your territory.

These artefacts make the *next* change safe: a new parameter must be determined
by a sentence in the list or it is invented, a narrowed return re-runs the answer
sentence, and a renamed outcome fails the taxonomy test — correctly, because the
old text is what the model's behaviour was conditioned on.

## Before it ships

1. Sentence list came from real traffic; every result field traces to a sentence.
2. Every candidate landed in the user or the system bucket; nothing invented
   survives, and no parameter widens scope.
3. The cost tier was chosen deliberately; above tier one a second identical call
   is safe, and at tier three the contract is two calls.
4. The taxonomy preceded the happy path; *not found* and *not yours* read alike.
5. The projection came from a probe against the worst account you could reach;
   shaped max sits under the per-result budget, and that payload is a fixture.
6. The cannot-decide sentence was written; a split's summaries carry the id.
