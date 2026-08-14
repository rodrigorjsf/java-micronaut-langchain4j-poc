---
name: agentic-tool-boundary
description: Design the boundary between an LLM agent and the outside world. Use when adding or reviewing agent tools, when a tool takes a URL or a path, when tool output is large or unbounded, when tool failures reach the model as exceptions, or when the model keeps picking the wrong tool.
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

## 4. Retries are bounded and idempotent-only

Retry idempotent reads, on timeouts and 5xx only. Never on 4xx, never on 429.

The model already retries by calling the tool again. A retry underneath it
multiplies: three model attempts × three transport retries is nine requests to a
service that is already struggling — and if it is a free public API, that is how
you get blocked.

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
   matters until that is fixed.
2. What is the largest result this tool can return, and what happens then?
3. What does the model see when this tool throws? Read the framework's default
   handler; do not assume.
4. Does the description say when to use it, or only what it is?
5. Does a retry here stack on top of the model's own retry?
