# 0009 — Tools name a catalogue key, never a URL

**Status:** Accepted

## Context

The tool layer makes outbound HTTP calls to roughly sixty public APIs, with
arguments chosen by a language model. That is the classic SSRF setup: if any tool
parameter can influence a hostname, a model that has read an attacker's text can
be talked into fetching `http://169.254.169.254/latest/meta-data/`.

## Decision

**No tool ever receives or constructs a URL.** A tool passes a catalogue key and
a path; `ToolHttpClient` resolves the base URL from `agentic.tools.apis` in
configuration.

This removes SSRF as a *category* rather than filtering for it. There is no
parameter that accepts a host, so there is nothing for a crafted argument to
occupy. The test that pins it passes `http://169.254.169.254` as the API name and
asserts the call is refused because that key is not in the catalogue.

Four more responsibilities live in the same class rather than in each of sixty
tools:

**Bounded output.** Every response is capped at a per-endpoint byte budget
(32 KB default — roughly 8k tokens, already more than any single tool result
should cost). Truncation is *reported* to the model, so it can narrow the query
instead of answering from a fragment it believes is complete.

The cap is a *transport* bound and it is enforced first. Link scrubbing runs after
it, and the removal marker is 42 characters, so replacing an address shorter than
that pushes a body marginally over the budget. Rare, bounded by the number of
addresses in the body, and stated here rather than left as a claim the code
falsifies.

**Allow-listed links, at the door.** A tool body is scrubbed of any address outside
the catalogue before the model reads it — see ADR 0008.

**Failures are values, never exceptions.** LangChain4j's default tool-error
handler feeds `Throwable.getMessage()` to the model — its own javadoc names this
as a path for stack traces, file paths, upstream URLs, downstream response bodies
and credentials to reach the prompt, the chat history, the observability pipeline
and the provider's logs. `ToolResponse` shapes each outcome into text that says
what happened and what to do next. A test asserts that a 5xx body containing
`/var/secrets/api-key.txt` never reaches the model.

The shaping matters as much as the redaction. `NOT_FOUND` ends with "do not guess
a value"; `RATE_LIMITED` with "do not retry this tool now"; `INVALID_REQUEST`
with "correct the arguments and call the tool again". A tool result that only
says "error" makes a model retry the same call or invent an answer.

**Bounded retries.** Idempotent GETs only, on timeouts and 5xx only, never on 4xx
or 429. The model already retries by calling the tool again; retrying underneath
it multiplies load on a free public API that is doing us a favour.

Per-endpoint timeouts go through Reactor rather than `toBlocking()`, which has
only a client-wide timeout. Tool endpoints have very different latency profiles
and one slow public API must not be able to hold a conversation open for the
global timeout.

## Consequences

**Gained.** SSRF is structurally impossible. Context cost per tool call is
bounded and visible. Adding an API is a config entry; adding a tool is a method.
Every tool inherits the same timeout, retry, truncation and redaction behaviour
without repeating it.

**Given up.** A tool cannot call an API that is not in the catalogue, so a
genuinely dynamic source needs a deliberate config change and a review. One
`HttpClient` bean is shared across all endpoints, so connection-pool tuning is
global; per-endpoint pools would need per-endpoint clients.

## Revisit if

An API needs a request body or non-GET semantics — the same catalogue applies,
but retry safety has to be reconsidered per method.
