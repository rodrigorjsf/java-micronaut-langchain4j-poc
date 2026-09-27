---
name: agentic-tool-boundary
description: Use when adding or reviewing agent tools, when a tool takes a URL or a path, when tool output is large or unbounded, when tool failures reach the model as exceptions, when a tool writes, sends, pays or deletes, when an upstream can answer with a redirect, or when the model picks the wrong tool from a set small enough to read in one sitting. From ten tools up, use progressive-tool-disclosure.
---

# The tool boundary

Every tool is a hole an attacker's text can reach through, and a place tokens
leak out of. One class owns the boundary; individual tools own only their
arguments and their meaning.

The four rules below are what that class enforces. They compose: each removes a
whole category of failure rather than patching an instance.

## 1. A tool never names a destination

**A tool takes a catalogue key and a path. It never takes a URL, a hostname, a
file path, a database name, or a shell command.**

```
BAD   fetch(url: string)
      read_file(path: string)
      query(connectionString: string, sql: string)

GOOD  fetch(source: "brasilapi", path: "/cep/v2/01310100")
      read_document(documentId: string)
      query(dataset: "orders", filters: {...})
```

The catalogue is configuration. A destination absent from it cannot be reached,
whatever the model asks for.

This is **structural**, not a filter. A validator on a URL parameter is a race
between your parser and the attacker's encoding; a parameter that does not
accept a destination has nothing to win. Cloud metadata endpoints, internal
services, `file://`, DNS rebinding — all of them need somewhere to put a
destination, and there is nowhere.

The test that proves it: pass `http://169.254.169.254` as the catalogue key and
assert the call is refused because that key is not configured.

### A redirect is a destination too

The catalogue decides the **first** request. It says nothing about the second:
a catalogued host that answers `302 Location: http://169.254.169.254/...` hands
the client a destination nobody configured, and an HTTP client that follows
redirects on its own requests it before any of your code sees the `302`. The
parameter had nowhere to put a destination; the response did.

MCP's security guidance names this exact chain — "Normal-looking URLs that
redirect to internal resources" — and says clients "SHOULD apply the same URL
validation to redirect targets": "Do not blindly follow redirects to internal
resources", and "Consider disabling automatic redirect following and validating
each hop" [sourced — Model Context Protocol, *Security Best Practices*, version
2026-07-28, section on Server-Side Request Forgery, read 2026-09-27,
https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/docs/docs/2026-07-28/tutorials/security/security_best_practices.mdx].
That section is written about OAuth discovery URLs; the mechanism is the same
for any server-side client that fetches on a model's behalf.

So the door owns the redirects:

1. **Turn automatic following off** in the tool door's own client — not the
   shared one, where the same switch silently changes every other caller. Most
   clients follow by default; Micronaut's followed a `302` to an off-catalogue
   host in this repository's red test until its tool client set
   `follow-redirects: false` [sourced — `ToolHttpClientTest`, run in a ticket
   session rather than the main thread].
2. **Follow each hop yourself**, and only to a host **and port** a catalogue
   entry names **exactly**. Exact host, not registrable domain: a link is read
   by a person, a request is made by your process, and a sibling subdomain of a
   catalogued API is a host nobody reviewed. The port matters for the same
   reason: `localhost:6379` is not the service the catalogue reviewed.
3. **Never downgrade** `https` to `http`, and **cap the hops** (three is
   plenty; a longer chain is a loop or someone shopping for a host).
4. **Share one deadline** across the hops, so a chain cannot multiply the
   endpoint's timeout.
5. **A refused hop is a value, not an exception** — and never retried. It is
   deterministic: asking again gets the same `Location`. Log the target for
   operators; keep the address out of the model's text, which can repeat it.

Keep the hops that stay on the host. Refusing every `3xx` is the easy fix and a
silent regression: an upstream that moved a route answers `301`, and the answer
is still there.

The test that proves it needs something observable, because nothing listens on
`169.254.169.254` in a test. Point a stub at `302` to the same stub server
under a host the catalogue does not hold (`127.0.0.1` when the catalogue says
`localhost`), and assert the landing route was called **zero** times. Then
assert a `302` to `169.254.169.254` fails fast, as a refused redirect rather
than as a connection that timed out — a client that followed would also fail
there, just more slowly, so "the call did not succeed" proves nothing.

What this does not cover: a catalogued hostname whose DNS answer changes to a
private address between validation and use. Pinning resolved addresses, or an
egress proxy that blocks private ranges, is the layer for that.

## 2. Results are bounded, and truncation is announced

Every result is capped at a per-source byte budget, and when it is cut the model
is **told**.

Public APIs return fat JSON. 32 KB is roughly 8k tokens — already more than any
single tool result should cost, and it lands in the conversation where it will
be replayed into every later prompt.

Silent truncation is worse than the tokens. A model handed half a list believes
it has the whole list and answers confidently from a fragment. Say so:

```
[truncated: the response was longer than this tool's budget.
 Narrow the query if you need the rest.]
```

## 3. Failures are values, never exceptions

Nothing at the tool boundary throws.

Most agent frameworks have a default error handler that feeds
`exception.getMessage()` back to the model. That is a data-exfiltration path:
stack traces, upstream URLs, response bodies, connection strings and credentials
land in the prompt, then in the conversation history, then in the provider's
logs, then in your observability pipeline.

Return a shaped result instead. Log the real cause where operators can see it.
In LangChain4j that means setting `toolExecutionErrorHandler` on the AI Service
builder — the async table below is why the default is not even one behaviour
[sourced — `AiServices` javadoc,
https://github.com/langchain4j/langchain4j/blob/1.20.1/langchain4j/src/main/java/dev/langchain4j/service/AiServices.java,
read 2026-09-27].

**And shape the text for recovery.** A result that only says "error" makes the
model retry the same call or invent an answer. Each outcome states what happened
*and what to do next*:

| Outcome | What the model is told |
|---|---|
| not found | "No result. Tell the user nothing was found; do not guess a value." |
| invalid arguments | "Correct the arguments and call the tool again, or ask the user for the missing detail." |
| rate limited | "Do not retry this tool now. Answer from what you already have." |
| upstream error | "Do not retry. Tell the user this source is temporarily unavailable." |

Note that two of the four say *do not retry*. Without that, a model burns its
whole round-trip budget on a source that is down.

**Set the handlers explicitly, because the defaults depend on the call mode.**
In LangChain4j 1.20.x an AI Service method that returns `CompletableFuture`,
`CompletionStage` or `Flow.Publisher` runs asynchronously, and two tool defaults
flip relative to the synchronous path `[sourced]`:

| | Synchronous / `TokenStream` | `CompletableFuture` / `Flow.Publisher` |
|---|---|---|
| Several tool calls in one response | run **sequentially** | run **concurrently** |
| Tool **execution** error | sent back to the model | **fails the invocation** |
| Tool **argument-parse** error | **fails the invocation** | sent back to the model |

So switching a method to async, with no other change, turns the "upstream error"
row above into a 5xx for the user, and makes every tool that shares state race
its siblings. An explicitly configured `toolExecutionErrorHandler` and
`toolArgumentsErrorHandler` are used by every mode — set both, returning the
shaped text from the table, and the failure stays a value on either path. For
tools that are not safe to run at once, pass
`executeToolsConcurrently(Executors.newSingleThreadExecutor())`.

Sources, read 2026-09-27: LangChain4j 1.20.0 release notes
(https://github.com/langchain4j/langchain4j/releases/tag/1.20.0) — "multiple tool
calls run concurrently, a tool *execution* error fails the invocation rather than
being sent to the LLM, and a tool *argument-parse* error is sent to the LLM rather
than failing"; and the "Non-blocking and Reactive" tutorial, section "Defaults
that differ from the synchronous modes"
(https://docs.langchain4j.dev/tutorials/non-blocking) — "an explicitly configured
handler is used by every mode".

## 4. Retries are bounded and idempotent-only

Retry idempotent reads, on timeouts and 5xx only. Never on 4xx, never on 429.

The model already retries by calling the tool again. A retry underneath it
multiplies: three model attempts × three transport retries is nine requests to a
service that is already struggling — and if it is a free public API, that is how
you get blocked.

## Side effects wait for a person, between selection and invocation

The four rules above protect the outside world from the model's *reads*. A tool
that writes — sends a message, charges a card, deletes a record, deploys — needs
one more thing, and it is not in the door class: **the loop stops after the model
has chosen the call and before the call runs**, and a person approves or rejects
that exact call.

12-factor agents puts the requirement at the moment it matters: the ability "to
interrupt a working agent and resume later, ESPECIALLY between the moment of tool
**selection** and the moment of tool **invocation**" — without it, "there's no way
to review/approve the tool call before it runs" [sourced — HumanLayer, *12-Factor
Agents*, factor 8 "Own your control flow", read 2026-09-27,
https://github.com/humanlayer/12-factor-agents/blob/main/content/factor-08-own-your-control-flow.md].
The three alternatives it lists are the ones teams fall into: hold the task in
memory and lose it on a restart, restrict the agent to low-stakes reads, or "just
yolo hope it doesn't screw up".

OpenAI's Agents SDK is a concrete shape of it. A tool declares `needsApproval`
(`true`, or a function of the parsed arguments); when it fires, "the tool call
does not execute", the run pauses and returns the pending calls as
`interruptions`, each one is resolved with `state.approve(...)` or
`state.reject(...)`, and the same `RunState` is passed back to resume "from the
interrupted point". The state serialises (`toString()` / `RunState.fromString`),
so a pause can outlive the process [sourced — OpenAI Agents SDK for TypeScript,
*Human-in-the-loop* guide, read 2026-09-27,
https://github.com/openai/openai-agents-js/blob/main/docs/src/content/docs/guides/human-in-the-loop.mdx].

What to carry over, whatever the framework:

| Rule | Why |
|---|---|
| Decide approval per call, from the parsed arguments | "send 1 email to the requester" and "email all 40 000 customers" are one tool |
| Persist the paused state, not a thread waiting on it | an approval can take hours; a sleeping thread dies with the pod |
| Resume the *same* pending call, by its id | re-asking the model after approval gets a different call than the one approved |
| Keep the snapshot server-side; authenticate the approver on the server | the guide is explicit: deserialising "does not authenticate the snapshot or the person submitting it" |
| A rejection is a tool result the model reads | say what was refused and what to do next, as rule 3 does for failures |
| Unparseable arguments fail closed | the guide requests approval without evaluating the rule when arguments do not parse; never execute on a parse error |

The approval is a gate on **effects**, not a substitute for the door. An approved
call still goes through the catalogue, the byte budget and the redirect check.

A read-only tool layer — every tool an idempotent `GET` — has nothing to approve,
and adding a pause there buys latency and nothing else. The moment the first
writing tool is proposed, this section applies before it ships.

## The description house style

A tool description is the routing signal. With more than a handful of tools, the
model picks by reading descriptions, so drift in them is a behaviour regression
that no functional test catches.

**Say when to reach for the tool, not what it technically does.** The method name
already says what it does.

```
BAD   "Looks up a CEP."
      "Calls the weather API."

GOOD  "Resolve a Brazilian postal code (CEP) to its street, neighborhood, city,
       state, IBGE municipal code and coordinates. Use whenever the user gives a
       CEP or asks which address a CEP belongs to."

      "Get current conditions and a daily forecast for a latitude and longitude.
       Requires coordinates — use find_place first if you only have a place name."
```

The second example does the other job of a good description: it names its
**precondition**, which stops the model calling it with a city name and getting
an argument error it then has to recover from.

Every parameter documents its format, with an example. `"The 8-digit postal code.
Punctuation is ignored, e.g. 01310-100 or 01310100."` costs twelve tokens and
removes a whole class of retry.

**Split a tool when it hides a decision.** A single `weather(place)` that
geocodes internally hides the ambiguity between the Brazilian and the Portuguese
São Paulo, and leaves the model unable to ask which one the user meant.
`find_place` then `get_weather` puts the decision where it can be resolved.

Enforce the style with a test over every tool: name pattern, description length
bounds, and a non-blank description on every parameter.

## Validate arguments in the tool, in the model's language

Check arguments before the call and return text the model can act on.

```
GOOD  "Invalid argument: a CEP has exactly 8 digits, got 5.
       Ask the user to confirm the postal code."
```

A model that gets this fixes its own call. A model that gets an HTTP 400
usually gives up and apologises.

## Reviewing an existing tool layer

Ask, in order:

1. Can any parameter influence *where* a request goes? If yes, nothing else
   matters until that is fixed. Then: can a *response* — a `3xx` — change where
   the next request goes?
2. What is the largest result this tool can return, and what happens then?
3. What does the model see when this tool throws? Read the framework's default
   handler; do not assume.
4. Does the description say when to use it, or only what it is?
5. Does a retry here stack on top of the model's own retry?
6. Does this tool change anything outside the process? If yes, where does the
   run stop for approval, and what survives a restart while it waits?
