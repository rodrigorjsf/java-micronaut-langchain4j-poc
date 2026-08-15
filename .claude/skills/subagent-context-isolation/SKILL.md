---
name: subagent-context-isolation
description: Spend a sub-agent only where the context it discards is worth the extra model calls. Use when work is being split across several agents, when an agent pipeline is being put on the default path of every request, when fan-out or recursion has no call budget, or when a sub-agent's output is spliced into the parent's prompt.
---

# Sub-agent context isolation

A **sub-agent** is a disposable context: its own prompt, its own tool loop, its
own message list — all thrown away when it returns. One return value survives.
That **discard** is the entire product.

## 1. The discard test

**Spawn a sub-agent for a subtask whose intermediate output the main conversation
will never reference again.** Everything below is consequence.

Which module sets the retry policy? Forty files are opened, thirty-nine
discarded, and the answer is three paths. The file bodies are the *evidence*, the
paths are the *finding*, and a follow-up — "open the second one" — acts on the
finding. The evidence has a discard point.

Now the case that looks identical and is not. A child reads a contract and
returns eight sentences. The user's next message is "what does it say about
termination?", and the parent holds eight sentences that never mention it plus a
model that will answer from them anyway. The material *was* the subject of the
conversation, not scaffolding for one answer. Nothing was discarded, so that
child is a prompt section with a network round trip in front of it.

In one question: **when the next turn asks a follow-up, does the parent need what
the sub-agent read, or only what it concluded?**

**An org chart is not a discard point.** "Researcher, writer, critic, editor"
cuts along job titles, and job titles rarely land on discard points — the writer
needs what the researcher found, the critic what the writer meant, the same
material retold at every hop in the lossy medium of a model's prose. The bill is
the loud failure; the quiet one is that **errors compound instead of surfacing**,
a fact the researcher got slightly wrong returning from the writer with more
confidence and fewer hedges. Two roles that need the same material are one agent.

## 2. The briefing is a contract, like the return

A child has no user, no history and one shot: **it cannot come back for
clarification.** An ambiguity the parent left open never surfaces as a question —
it returns as a confident, well-cited finding about the wrong thing, and nothing
in the return marks it as wrong. So the brief carries five parts:

| In the brief | The failure without it |
|---|---|
| the question, in one sentence | the child treats the topic as the task and returns a survey |
| what a successful answer looks like | right facts, wrong grain — a paragraph where the parent needed a list |
| the return schema (§6) | the parent is back to parsing prose, and every control in §6 rests on that schema |
| the budget, in round trips and seconds | the child spends until something else stops it, and the stop looks like a finding |
| what to do when the material is not there | it answers from pretraining, fluently, and the parent cannot tell |

**Give the child what it needs to do the work and nothing that anchors it on the
parent's conclusion.** A critic handed the reasoning that produced the mistake
agrees with it — an expensive way to hear yes.

The brief is assembled from a conversation that may already carry planted text: a
retrieved document, an earlier tool result, a quoted email. The down-leg is a
hijack path of its own, and the child is the one holding tools — so quoted
material in the brief gets the provenance wrapper §6 puts on the return.

## 3. The budget is in model calls, and in seconds

**Convention: count model calls, including the parent's consumption call** — the
child's answer has to be read by something. A **round trip** is one model call
plus the tool execution it asks for. N children behind one tool the agent chose
cost **N + 2**; a parent fanning out to three children needing four round trips
each is `1 + (3 × 4) + 1 = 14` model calls for one user message. **Parallelism
buys latency, not calls — 14 stays 14.**

Isolation has a **fixed floor**: a child pays its own system prompt and its own
tool schemas on *every one of its own round trips*. Illustrative — a 1200-token
preamble over two round trips spends 2400 tokens to avoid replaying perhaps 3000.
Caching discounts that replay and moves the threshold a long way, so measure with
caching configured as it runs in production.

**Allow depth 1, and go deeper only when you can name the second level's discard
point.** A bounded-*looking* "each agent may spawn three" is 3 + 9 + 27 = 39
agents and, at four round trips each, 156 model calls from one "hi, can you check
something". Hold the budget as **a counter the parent owns and decrements across
the whole tree**: per-agent limits that each new agent receives a fresh copy of
multiply, reaching Cascading Failures (ASI08) of the OWASP Top 10 for Agentic
Applications 2026 with no upstream failure at all. And never call a
composition-level `retry()` that carries no counter — it re-enters the whole
workflow and recurses until the stack ends, a different mechanism from a bounded
transport retry underneath one tool. *Test:* script a child that tries to spawn a
child, and assert the spawn is refused rather than counted.

**A round-trip cap does not catch a child that is slow rather than looping.** Give
every child a wall-clock deadline and make a miss return `budget_exhausted` (§6),
not an exception. Time also decides whether a fan-out ships: **a sub-agent turn
cannot stream**, so the user watches nothing happen for the whole tree, and a
time-to-first-token going from under a second to twenty is the change users
actually report — invisible in every model-call sum above. *Test:* hold one
branch past the deadline and assert the turn ends with a partial answer naming
the missing branch.

## 4. The four shapes, and the combine step

```
map        A ─▶ B B B ─▶ combine   one prompt, many inputs
best-of-N  A ─▶ B B B ─▶ check     many attempts at one input
pipeline   A ─▶ B ─▶ C             each stage narrows
critic     A ─▶ B ─▶ A             fresh eyes on one artefact
router     A ─▶ (B | C | D)        exactly one child applies
```

| Shape | Use it for | The trap |
|---|---|---|
| **Map** | screening 200 records, reading 40 files | none inherent — independence buys parallel wall-clock and a deterministic combine |
| **Best-of-N** | a task with a checkable answer | without a deterministic checker it is N times the cost for a coin flip; "pick the best one", asked of another model, is an eleventh call you can audit no better than the ten |
| **Pipeline** | extract → verify → format | stage two sees stage one's output and not its evidence, so a wrong extraction gets formatted beautifully; every stage boundary must be a discard point of its own, or that stage belongs inside the previous one |
| **Critic** | judging an artefact against requirements | it earns its call only when it sees something the parent cannot, which means withholding the parent's reasoning (§2) |
| **Router** | mutually exclusive branches | the routing decision is itself a model call — route + child + consume is 3, against the 1–2 of one agent holding both tool sets |

**Combine in code wherever code can.** A model call to merge N results makes it
N+1, and the merge is where one branch's fabricated item gets blended into a list
that reads as uniform. Decide per branch whether a failure is fatal or merely a
missing field, and return the missing field when the others answered.

## 5. Expose it as one tool the model chooses

**A sub-agent workflow is a tool with a description, not a stage every request
passes through.** On the default path, "thanks, that helped" pays the whole
fan-out: at 10% of turns needing the deep work, the default path pays roughly ten
times over, on every turn, forever. As a tool, a failed child is a value the
parent routes around while still answering the turn; as a stage, its failure is
the turn's failure and its latency is everyone's.

Write the description around the **shape of the subtask**, and say plainly what
does not come back:

```
BAD   "Runs the research sub-agent."
GOOD  "Answer a question that requires reading many documents. Returns a short
       finding plus the identifier of each document it came from. The documents
       themselves do not come back, so ask for everything you need in one call.
       For a single known document, read it directly instead."
```

Without the third sentence the model calls the tool, receives the finding, then
asks a follow-up as though the raw material were in front of it. Without the
fourth it spends fourteen model calls on "what is in section 2?".

## 6. Isolation destroys the evidence, so the return is untrusted

**A finding the sub-agent read and a finding it invented are byte-identical at
the parent, by construction** — the one thing that distinguished them was
discarded on purpose, and the more fluent the child, the more solid the
fabrication looks.

**So make citations a return-value contract, not a request in the prompt.** Every
claim comes back with the identifier of what it came from — document id, file
path, record key — and that identifier must be one the parent can hand straight
to a tool. *Test:* resolve every identifier in a return through the tool the
parent would use, and fail the case on one that does not resolve. Fabrication
becomes a boolean rather than a question of tone.

**A sub-agent fails fluently.** It does not throw — it returns four well-formed
sentences saying it found nothing specific, followed by general knowledge, and
the parent reads that as content. Give the return an outcome field (`found` /
`not_found` / `budget_exhausted`). *Test:* feed a bare-prose return to the parser
and assert it maps to `not_found`. A budget-exhausted scan that reads as `found`
is how a partial sweep gets reported to the user as an exhaustive one.

**The context is discarded from the prompt, not from the trace.** Write the
child's message list, its tool calls and its raw reads under the parent's turn
id. That correlation key costs nothing at inference time and is the difference
between "the sub-agent was wrong" and "the sub-agent read this document, and this
is what it said"; without it a fabricated finding is unattributable and the
spot-check above has nothing to check against. An agent whose trajectory is
unobserved has its tool set as its blast radius, so the trace is what makes Tool
Misuse (ASI02) detectable rather than only preventable.

Four seams stay open after that, each with one control:

| Seam | The control, and the failure it removes |
|---|---|
| the child holds tools while reading attacker-reachable text | Where a framework's default is to inherit the parent's set, the child that only needs to read a calendar carries every write, send and purchase tool the parent has. Give each child the narrowest set and keep every irreversible call with the parent: per-child allowlists remove Identity and Privilege Abuse (ASI03) from the delegation path instead of detecting it afterwards. *Test:* assert at boot that each child's tool set is a strict subset of the parent's and holds nothing irreversible. |
| the return enters the parent's prompt | The parent's input guardrails ran on the *user's* message and never see this seam. Wrapping the return as data to consider rather than instructions to follow closes Agent Goal Hijack (ASI01) here; `prompt-injection-layers` owns how that wrapper is written. |
| the return is written to memory | Run the parent's **output** guardrail before the write. Memory & Context Poisoning (ASI06) is cured by *removing* the offending message rather than refusing it, because a poisoned finding stored on turn three is replayed into every prompt until the conversation ends. |
| a hop crosses a process boundary | While every hop is in-process there is no wire to protect. A remote agent or a mounted tool provider makes the return a network response from a party you must authenticate, and Insecure Inter-Agent Communication (ASI07) is reopened rather than inherited. |

A classifier's verdict is the same hazard in an easier form: an enum can be
replaced with a safe default (`llm-triage-gate` has that list). A child returns
prose, so replacement is unavailable and the controls above are what you have.

## Reviewing an existing sub-agent

1. Name its discard point. If a plausible next turn needs what it read, it is a
   prompt section, not an agent.
2. Read its brief — question, answer shape, return schema, budget, what to do
   when the material is not there. Which of the five is missing?
3. Count the model calls at full fan-out and full depth, and the seconds before
   the user sees anything. Is either number enforced?
4. Default path, or a tool the model chose? What does a turn that did not need it
   pay?
5. Can the parent resolve one citation from the return without re-running it?
6. Which of the parent's tools does it hold that its subtask does not need?
7. What does it return when it finds nothing, and can the parent tell that from
   finding something?
