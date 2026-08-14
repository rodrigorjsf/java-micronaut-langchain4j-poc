---
name: authoring-agent-tools
description: Turn "we need a tool for X" into a tool contract before any of it is built. Use when someone asks for a tool that does not exist yet, when deciding what parameters a proposed tool takes, when a tool request arrives as an implementation rather than a requirement, or when working out whether one proposed tool is really two.
---

# Authoring an agent tool

**"Add a tool that looks up an order"** names an implementation somebody already
pictured. Nobody has said what question a user asks, what the model answers
without it, or what a wrong answer costs. Built as stated, a tool comes out
shaped like the API it wraps.

What a parameter may accept, what a result may cost, what the model sees when a
call fails — those boundary questions have their own answers. This is the pass
*before* them: an **interview** of four questions, answered on paper, whose
answers are the contract. One request runs through every section below.

## The interview

### 1. What does the user actually say?

Collect the **sentence list**: real sentences in the words a person types, from
support tickets, agent transcripts, or the logged turns where the agent answers
this badly today. Sentences written by whoever asked for the tool describe the
tool that person already pictured. Fewer than three from a real source means the
tool has no traffic, and a schema is paid for on every turn regardless.

```
"where's my order"    "did 88231 ship yet"    "my package hasn't arrived"
```

One question: *where is this thing now*. None of them asks for line items, a
billing address or the discount applied — the result's field list has started
narrowing and no API doc has been opened yet.

### 2. What does the model answer without it?

**Probe it.** Send the sentence list to the agent as it stands today, a dozen
runs — a smoke test, not a statistic. What comes back decides four things:

| The unaided model is… | Decision |
|---|---|
| right every time | do not build it — the schema taxes every turn for a capability you have |
| confidently wrong: it invents a tracking number | build it, and keep that transcript — it is eval case one |
| right but unverifiable: plausible, and it cannot know today's truth | build it **for freshness, not knowledge** |
| wrong for a reason a tool does not fix | not a tool. Stale-but-static content is retrieval; a phrasing it keeps getting wrong is the standing prompt; a fixed multi-call sequence is a skill |

Row three gets skipped, and it changes the design rather than approving it: a
freshness tool returns *when* its answer was true and *where* it came from, and
its description tells the model to say so. More fields is the wrong fix.

Then the cheapest kill: does an existing tool already answer these sentences? Two
tools answering one question turn routing into a coin flip. On row one or four,
hand back the sentence list and the probe transcript naming the row — a finished
answer, not a refusal to work.

### 3. What does a wrong answer cost?

Not how accurate the source is — what happens to the person when it is wrong. A
sentence the user will correct is **tier one**: read-only, cache freely, partial
data is fine, and that is our lookup. A wasted trip or a support ticket is **tier
two**: the result carries its provenance and the description tells the model to
hedge with it. An effect the user must undo, or money, is **tier three**, where
the contract is **two calls** — the first returns what would happen plus a token,
the second takes only that token — so the model can show the user what it is
about to do, the only moment anyone can stop it. Choosing the tier deliberately
closes **Tool Misuse & Exploitation (ASI02)** in the OWASP Top 10 for Agentic
Applications 2026: the ranked risk is not that a model calls a tool, it is that a
tool can do more than the question needed.

Above tier one, ask what a *second identical call* costs — a timeout makes the
model call again. **When the tool changes anything, or when a boolean parameter
survives the buckets below, read [`WRITES-AND-FLAGS.md`](WRITES-AND-FLAGS.md).**

### 4. Whose data is it, and who is asking?

The answer belongs to one customer, so `customerId` is **not** a parameter. The
model fills parameters by reading the conversation, and the conversation is where
an attacker gets to write. Identity comes from the authenticated session,
injected by the tool layer, absent from the schema the model sees.

The general form: **a value that widens who or what a call can reach is set by
the system; a value the model sets may only narrow inside a scope the system
already fixed.** That removes **Agent Identity & Privilege Abuse (ASI03)** rather
than filtering for it — no argument exists to smuggle an account number through.

## From the answers to the parameter set

Sort every candidate into three buckets. **From the user** — it appears in the
sentence list, so the model fills it by reading the conversation. **From the
system** — identity, tenant, locale, the catalogue key: injected, never in the
schema. **Invented** — nothing in the conversation or the session supplies it, so
the model guesses, and a guessed argument is a fabrication with a function call
around it. Either the user is asked, or it is not a parameter.

```
BAD   list_orders(customerId, page, pageSize, includeArchived, locale, status)

GOOD  find_orders(query)          // "the blue one", "tuesday"
      get_order_status(orderId)
```

`customerId` is session; `page`, `pageSize`, `includeArchived` and `locale` are
configuration, mentioned in no sentence. `status` survives only as an enum
(`open | shipped | delivered`), because two sentences narrow by it — free text
there earns a retry loop the first time a model sends `"in transit"`.

The reverse check finds what the buckets miss: **for each survivor, name the
sentence that determines it.** One you cannot name gets hallucinated on every
call. Past roughly five survivors, the tool answers more than one question. Once
it is live, the **argument-error rate per parameter** names the description that
failed — each miss costs two round trips and an apology.

## Write the description from the sentence list

The description is what routing runs on, so it is derived, not composed — the
house style of the prose itself is a boundary question. Three derivations:

- **The sentence list becomes the "use when" clause,** in the user's words: the
  phrasings you collected are the phrasings the model matches against.
- **The sentence that determines a parameter becomes that parameter's example.**
  `"did 88231 ship yet"` is where `orderId`'s `e.g. 88231` comes from.
- **The handoff a split creates becomes the second tool's precondition,** stating
  the id form and where it comes from: `"Requires an orderId — use find_orders
  first."` Unstated, the model passes the label it just read to the user.

## Write the error taxonomy before the happy path

Name every way the call fails to return the answer, as a closed set: `not_found`,
`ambiguous`, `invalid_input`, `not_permitted`, `unavailable`, `too_large`,
`already_done`. Enumerating failures changes the design, and it can only change
the design while the design is on paper.

**It finds the shape that leaks.** *No such order* and *that order is not yours*
look different and must be identical on the wire. Distinguish them and the tool
is an oracle — anyone who can talk to the agent enumerates valid references by
watching which reply comes back, and no rate limit hides it. Five minutes on
paper; roughly never found while implementing a 200 path.

**It finds the answer that is not one answer.** *The reference matches three
orders* forces a question the happy path never raises: does this tool return a
list, or silently pick one? Answer it here and the tool is right; answer it in a
bug report and it has shipped confident wrong orders for a month.

Each entry that can occur diagnoses something unfinished:

| Outcome | What it says about the design |
|---|---|
| `too_large` can occur | the result shape is wrong, and a budget will not fix a shape |
| `not_found` is common across the sentence list | a search tool is missing in front of this one |
| `not_permitted` is reachable by changing a parameter | identity is in the wrong place; move it to the session |
| an outcome you cannot name | that is the one the model receives as an exception |

Fewer than six entries — unreachable, slow, malformed, empty, ambiguous,
forbidden — means the list was enumerated from the vendor's 200 example.

## Size the result from a probe

Nobody guesses result size right, and the guess is always low. **Probe it.** Call
the real upstream twenty times with the arguments the sentence list produces,
against the worst account you can reach rather than the demo one, and record
serialized size at p50 and at max. The endpoint returning twelve rows for a test
user returns four hundred for a reseller, and that account is in production today.

Then subtract rather than cap. Write the **answer sentence** — what the model
will say to the user — and keep only the fields in it or in the obvious
follow-up. *"Your order shipped Tuesday and arrives Friday, tracking AB123"*
needs five fields; the upstream order object has sixty. A cap over a fat payload
slices an object in half; a projection is small because it was designed small.

The arithmetic is illustrative — run yours. Divide what one turn may spend on
tool output by the calls a turn makes: 6,000 tokens across three calls is ~2,000
per result. A projection that will not fit means question 1 was answered too
loosely; four hundred honest rows means the tool takes a page argument and
reports the true total. Keep the fattest payload as a fixture.

## One tool, or two

**Write the sentence the tool returns when it cannot decide. If that sentence is
a question addressed to the user, the tool is two.**

*"Three orders match that reference — which one did you mean?"* is a question
asked by a component with no way to ask anything. So `find_orders(reference)`
returning short summaries, then `get_order_status(orderId)`.

**Then design the handoff, or the split only moves the ambiguity.** Each summary
carries the id in the form the second tool's parameter accepts. Return `"blue
lamp, ordered Tuesday"` and the model calls `get_order_status("blue lamp")` — the
invented argument the buckets just killed, reintroduced between two tools. Return
`"#88231 — blue lamp, ordered Tuesday"` and it cannot.

The cannot-decide sentence catches most splits. **When it does not fire and the
tool still feels like two — a read and a write behind one name, two questions
sharing a verb, a fixed sequence you are tempted to split — read
[`SPLIT-TRIGGERS.md`](SPLIT-TRIGGERS.md).**

## The tests that gate the ship

**One per taxonomy branch**, asserting the returned **text** — not an exception
type or a status code, because that text is the next prompt the model reads. It
gates because it proves the enumeration closed: an outcome you cannot construct a
case for is imaginary and gets deleted, and a result landing outside every
outcome means the taxonomy is still open.

- **Routing** — every sentence in the list reaches this tool, asserted against
  the description clause derived above; then a sentence belonging to the
  **nearest sibling by name**, asserted not to. That negative half fails the day
  someone edits that sibling's description into your territory.
- **Budget** — the fattest fixture stays under the ceiling after shaping. The day
  upstream adds a field, the build fails instead of the bill.
- **Refusal** — a conversation asking for another account's data is refused
  because the account came from the session, not because a filter caught it.

These artefacts make the *next* change safe. A new parameter must be determined
by a sentence in the list, or it is invented. A narrowed return re-runs the answer
sentence. A renamed outcome fails the taxonomy test — correctly, because the old
text is what the model's behaviour was conditioned on.

## Before it ships

1. The sentence list came from real traffic, and every result field traces to one of its sentences.
2. Every parameter traces to the user or to the system, and none can widen scope.
3. The cost tier was chosen deliberately; above tier one the contract is two calls.
4. The taxonomy preceded the happy path, and *not found* and *not yours* return identical text.
5. The cap came from a probe against a worst-case account, and that payload is a fixture.
6. The cannot-decide sentence was written; if it asked the user a question, the tool was split and the handoff carries the id.
