---
name: authoring-agent-tools
description: Decide whether "we need a tool for X" needs a tool at all, and turn the ones that do into a contract before any of it is built. Use when someone asks for a tool that does not exist yet, when deciding what parameters a proposed tool takes, when a tool request arrives as an implementation rather than a requirement, or when working out whether one proposed tool is really two. For a tool that already exists, use agentic-tool-boundary; for grouping several behind one activation, authoring-agent-skills.
---

# Authoring an agent tool

**"Add a tool that looks up an order"** names an implementation someone pictured.
Nobody has said what question a user asks, what the model answers without it, or
what a wrong answer costs; built as stated, it is shaped like the API it wraps.
What a finished tool looks like is a boundary question — this is the pass
*before* it, an **interview** whose answers are the contract, or whose answer is
that there is no tool. A fourth question, *what decision would this tool hide
from the model?*, waits for §4 and a shape to ask it of.

## 1. What does the user actually say?

Collect the **sentence list**: real sentences in the words a person types, from
support tickets, agent transcripts, or the turns the agent answers badly today.
Sentences written by whoever asked for the tool describe the tool that person
already pictured. Illustratively eight to fifteen of them; under about six you
cannot tell a tool from an anecdote.

**Seal a third of them unread, at collection.** Sort the raw pull by a checksum
of each line so the order is nobody's judgement, move every third line into a
`sealed` file, and leave it closed until the routing set is built — only this
pass stands early enough to seal them before a description exists to contaminate
them. Everything below runs on **the open sentences**, the other two-thirds; a
sealed sentence needing a parameter you never derived is a scope finding, caught
before ship rather than in the first week of logs.

```
"where's my order"        "did 88231 ship yet"    "my package hasn't arrived"
"anything still open?"    "has everything from tuesday arrived yet"
"where's the blue lamp I ordered"
```

One question — *where is this thing now* — and none of them asks for line items or
a billing address. The field list narrows here, before an API doc is opened.

**Two exits are already available and both are free.** *Already built*: an
existing tool answers these sentences, and a second makes routing a coin flip.
*No traffic*: you had to invent the list, and a schema is paid for on every turn
whether anyone asks or not. Either ends the interview before the probe spends a call.

## 2. What does the model answer without it?

**Probe it.** Run the open sentences against today's agent a dozen times over — a
smoke test, not a statistic; illustratively five to ten open sentences at twelve
answers each is 60–120 calls. **The unit the table counts is one sentence's
twelve answers**: variance is per phrasing, and a list-wide average hides the
sentence that is wrong every time. **Read top to bottom, stop at the first row
that matches** — a cause outranks a count, so a sentence the model gets wrong
because it misreads the phrasing is row one even inside an otherwise good batch.

| The unaided model is… | Decision |
|---|---|
| wrong for a reason a tool does not fix | not a tool. Stale-but-static content is retrieval (`retrieval-that-earns-its-place`); a phrasing it keeps getting wrong is the standing prompt; a fixed multi-call sequence is a skill (`authoring-agent-skills`) |
| unable to know today's truth, because the fact moves under it | build **for freshness, not knowledge** — the field list does not grow, the answer only has to be current. §3 still sets the tier, on its own question: a stale unread count is tier one, a stale price that feeds a charge is tier three |
| wrong at least once, and a wrong answer is indistinguishable from a right one | **build.** This is the usual outcome, and one failure is enough — the count only sizes the urgency, because nobody reading an answer can tell which one they got, so the right ones do not help them. Eleven right out of twelve, with one invented tracking number, is the same decision |
| wrong in none of the twelve, on facts that do not move | do not build it — the schema taxes every turn for a capability you already have |

One sentence in a build row is enough to build; how many others join it is how
wide the tool is. The probe's output is the verdict **plus every transcript where
the model was wrong** — first eval cases, free while the tool does not exist.

**Four outcomes end the interview with no tool** — the two free exits in §1, and
this table's first and last rows. Each hands back a finished result: sentence
list, probe output, which outcome. Never a refusal.

## 3. What does a wrong answer cost?

Not how accurate the source is — what happens to the person when it is wrong.
**Write the wrong-answer-cost sentence** beside the tool: who pays, what they pay,
and whether they pay before they can correct it — that is what names the tier.

A sentence the user will correct is **tier one** — a catalogue description they are
reading right now: read-only, cache freely, partial data fine. A wasted trip or a
support ticket is **tier two**, and the order lookup lands here, not in tier one:
*a wrong "delivered" sends someone to an empty porch or opens a ticket, both paid
before the user sees anything to correct.* Its contract is a **provenance field in
the projection** — `as_of` and where the value came from, returned in the result and
not logged — plus a description instructing the model to say it out loud: *"shipped
as of 09:12 today, per the carrier"*. With no stamp the model has nothing to hedge
with, and drops the hedge or invents one. Money, or an effect the user must undo, is
**tier three**, whose contract is **two calls** — one returns what *would* happen
plus a token, the second takes only that token, so the model can show the user what
it is about to do, the only moment anyone can stop it. Above tier one, ask what a
*second identical call* costs: `get_order_status` served from cache after its `as_of`
window has passed hands back a stamp that is no longer true, so it caches for that
window and not past it.

Choosing the tier deliberately closes **Tool Misuse & Exploitation (ASI02)** of
the OWASP Top 10 for Agentic Applications 2026: the ranked risk is not that a
model calls a tool, it is that a tool exists which can do more than the question
needed. The two-call contract closes **Human-Agent Trust Exploitation (ASI09)**.
**Excessive agency** has no row of its own on that list; here it is removed by
the tier and by the second call, never by a rule telling the model to be careful.
**When the tool changes anything, read [`WRITES-AND-FLAGS.md`](WRITES-AND-FLAGS.md)**
for the token, its replay, the key that replaces it, and what ASI09 looks like.

## From the answers to the parameter set

Sort every candidate into three buckets under one rule: **a value that widens
what a call can reach is set by the system; a value the model sets may only
narrow inside a scope the system already fixed** — the model fills parameters
from the conversation, and the conversation is where an attacker writes.

**From the user**: it appears in the open sentences. **From the system**:
identity, tenant, locale, the catalogue key — injected by the tool layer, absent
from the schema. The answer belongs to one customer, so `customerId` is not a
parameter and no argument exists to smuggle an account number through, which
removes **Identity & Privilege Abuse (ASI03)** rather than filtering for it.
**Invented**: nothing in the conversation or session supplies it, so the model
guesses — a fabrication with a function call around it. Either the user is asked,
or it is not a parameter.

```
BAD   list_orders(customerId, page, pageSize, includeArchived, locale, status)

GOOD  find_orders(query, status?)   // query "blue lamp" · status open|shipped|delivered
      get_order_status(orderId)
```

`page`, `pageSize`, `includeArchived` and `locale` are configuration, in no
sentence. `query` traces to *"where's the blue lamp I ordered"* and is free text on
purpose: its value **is** the user's own words, matched against your data, and no
enum lists what people call their own things. `status` traces to *"anything still
open?"* and *"has everything from tuesday arrived yet"* and survives **only as an
enum**, because its value names a state the domain already enumerates — free text
there earns a retry loop the first time a model sends `"in transit"`, and is why
`shipped` is in the value set with no sentence of its own. That is the line: free
text where the user's phrasing is the input, an enum where the parameter picks
among states the system defines. Then name, per survivor, the sentence that
determines it — one you cannot name gets hallucinated on every call; past about
five survivors, illustratively, the tool is answering more than one question.

**When a boolean survives all three buckets, read the flag trichotomy in
[`WRITES-AND-FLAGS.md`](WRITES-AND-FLAGS.md)** — one shape is a capability, not a parameter.

## Write the description from the open sentences

Routing runs on the description, so it is derived, not composed. **The open
sentences become the "use when" clause**, in the user's words, because the
phrasings you collected are the phrasings the model matches against. **The
sentence that determines a parameter becomes that parameter's example** — `"did
88231 ship yet"` is where `orderId`'s documented `e.g. 88231` comes from, and §4
hands the id over in exactly that form. **A split creates a precondition the
second tool's description states**: which id it takes, and which tool produces it.

## Write the error taxonomy before the happy path

Name every way the call fails to return the answer, as a closed set: `not_found`,
`ambiguous`, `invalid_input`, `not_permitted`, `unavailable`, `too_large`,
`already_done`. Enumerating failures changes the design, and can only change it while the design is on paper.

**It finds the shape that leaks.** *No such order* and *that order is not yours*
must be identical on the wire, or the tool is an oracle: anyone who can talk to
the agent enumerates valid references by watching which reply comes back, and no
rate limit hides it.

**It finds the answer that is not one answer.** *What the user said matches three
orders* is a case the happy path never raises; naming it here is what sends §4
looking for the second tool underneath it, rather than a bug report a month after
the first one silently picked.

| A reachable outcome | What it says about the design |
|---|---|
| `too_large` can occur | the result shape is wrong, and a budget will not fix a shape |
| `not_found` is common across the open sentences | a search tool is missing in front of this one |
| `not_permitted` is reachable by changing a parameter | identity is in the wrong bucket; move it to the system |
| an outcome you cannot name | that is the one the model receives as an exception |

A list naming only `not_found` and `invalid_input` was copied from the vendor's 200
example: `unavailable`, `ambiguous` and `not_permitted` are in no API doc's error
table and happen every week. Illustratively a healthy read tool carries those five
and no more — the other two belong to a write, and to a shape needing redesign.

## Shape the result before you budget it

Nobody guesses result size right, and the guess is always low — so subtract rather
than cap. Write the **answer sentence**, what the model will say to the user, and
keep only the fields in it or in the obvious follow-up. *"Your order shipped Tuesday
and arrives Friday, tracking AB123 — carrier status as of 09:12"* needs six: five the
answer names, plus the `as_of` stamp §3's tier put in the projection so that last
clause can exist. The upstream order object has sixty. A cap over a fat payload
slices an object in half; a **projection** is small because it was designed small.
**Before that shape is fixed, measure what the upstream returns** — the **size
sample**, its p50-versus-max split, the arithmetic and the fixture are in
[`RESULT-BUDGET.md`](RESULT-BUDGET.md).

## 4. What decision would this tool hide from the model?

*That* a tool hiding a decision gets split is the boundary rule; this pass owes it
a way to find the decision while the tool is on paper, and a handoff between the
halves. A tool decides silently when it cannot decide, so write the
**cannot-decide sentence** — what it returns in that case. **If that sentence is a
question addressed to the user, the tool is two.** *"Three orders match that
reference — which one did you mean?"* is a question asked by a component with no
way to ask anything; one tool answering it picks an order and never says so. Hence
`find_orders` returning short summaries, then `get_order_status(orderId)`.

**Then design the handoff, or the split only moves the ambiguity.** Each summary
carries the id in the exact form the second tool's documented parameter accepts.
Return `"blue lamp, ordered Tuesday"` and the model calls `get_order_status("blue
lamp")` — the invented argument the buckets just killed, reintroduced between two
tools; `"88231 — blue lamp, ordered Tuesday"` cannot be misread that way.

**When the cannot-decide sentence does not fire and the tool still feels like two —
a read and a write behind one name — read [`SPLIT-TRIGGERS.md`](SPLIT-TRIGGERS.md).**

## The tests that gate the ship

Three are deterministic and sit beside the code.

- **Taxonomy** — one case per branch, asserting the returned **text**, the next
  prompt the model reads. It also proves the set closed: an outcome you cannot
  construct a case for is imaginary and gets deleted, and a result landing outside
  every outcome means the taxonomy is still open.
- **Budget** — the fattest fixture, shaped by the projection, stays inside the
  per-result budget. The day upstream adds a field, the build fails, not the bill.
- **Refusal** — a conversation asking for another account's data is refused
  because the account came from the session, not because a filter caught it.

**Routing is the fourth, and not a unit test** — it runs the model, so the pass
threshold and the flaky-case rule are `agentic-evals` territory, which you can
invoke. Sourcing, sizing and scoring the set are `reviewing-agent-tools-and-skills`,
which is human-invoked only: read its `ROUTING-SET.md` directly, or ask the user to
run it. This pass owes it four kinds of case: **positives from the sealed third**,
which the description was never written from; a sentence belonging to the **nearest
sibling by name**, labelled with that sibling, which fails the day someone edits its
description into your territory; a turn nothing in the layer should take, labelled
**none**; and — because §4 just made a two-call sequence — one labelled with the
**ordered pair** `find_orders → get_order_status`.

## Before it ships

1. Sentence list came from real traffic; the sealed file exists, was written in one operation and has not been opened since; every result field traces to an open sentence.
2. Every candidate landed in the user or the system bucket; nothing invented.
3. The tier is written beside the tool with the wrong-answer-cost sentence that fixed it; a second identical call is safe; tier three is two calls.
4. The taxonomy preceded the happy path; *not found* and *not yours* read alike.
5. Projection sized against the worst account; above tier one the stamp is a field in it; shaped max under budget; fixture kept.
6. The cannot-decide sentence was written; a split's summaries carry the id.
7. Description derived from the open sentences; routing set carries sealed positives, the nearest sibling's sentence, a *none*, and the ordered pair.
8. A case per taxonomy branch, and a refusal that passes on session identity rather than on a filter.
