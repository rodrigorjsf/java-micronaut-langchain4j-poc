# 3 · Security

The OWASP Top 10 for Agentic Applications 2026, mapped to the class that answers
each item — and, where nothing answers it, said so plainly.

**Provenance.** The landing page at
<https://genai.owasp.org/resource/owasp-top-10-for-agentic-applications-for-2026/>
is promotional; the ten entries live in the PDF at
<https://genai.owasp.org/download/52117>, published 9 December 2025 by the OWASP
GenAI Security Project. The identifiers are **`ASI01:2026` … `ASI10:2026`** — ASI
for the Agentic Security Initiative series. Anything citing `AAI01` has the prefix
wrong.

## The seams everything hangs off

Before the list, the five interception points this framework actually offers,
because the whole map is determined by them.

```mermaid
flowchart LR
    classDef hook fill:#2f7d4f,stroke:#9ae6b4,color:#ffffff
    classDef gap fill:#8a3b3b,stroke:#ffb4b4,color:#ffffff
    classDef flow fill:#2d6cdf,stroke:#9ec1ff,color:#ffffff

    IN["user message"] --> IG["InputGuardrail<br/>sees RAG too"]
    IG --> LOOP["tool loop<br/>runs many round trips"]
    LOOP --> OG["OutputGuardrail"]
    OG --> OUT["reply"]
    LOOP --> TE["ToolExecutor<br/>the ONLY hook inside the loop"]

    class IG,OG,TE hook
    class LOOP gap
    class IN,OUT flow
```

**Guardrails run once per invocation and never see tool results.** The input chain
runs, then the entire multi-round-trip tool loop, then the output chain. A
`ToolExecutionResultMessage` is produced strictly between the two and is reachable
from neither — there is no accessor for it on either request type.

RAG content *is* reachable from an input guardrail, via
`requestParams().augmentationResult()`. Tool output is not.

**That asymmetry is the spine of this threat model.** Indirect prompt injection —
instructions planted in data the agent fetches — arrives through tool output, so
`ToolGuardProvider` decorating `ToolExecutor` is the only place it can be caught.

Two traps in building that decorator, both of which fail silently:

- **Decorate `executeWithContext`, not `execute`.** The framework only ever calls
  the former, and LangChain4j's own skill executors throw from `execute`
  deliberately. A decorator on the wrong method never runs *and* crashes the
  skills.
- **A hallucinated tool name never reaches any executor.** The framework's
  `executor == null` branch bypasses every decorator, so that path needs its own
  strategy — and the default one throws, turning a model typo into a 500.

## The ten

| ID | Title | Control here | Test |
|---|---|---|---|
| **ASI01** | Agent Goal Hijack | Input guardrail chain: normalize → deterministic score → gray-zone classifier. System prompt is assembled from config at startup and never derived from a request or from memory. | 71 corpus cases, half of them benign traffic that looks like an attack |
| **ASI02** | Tool Misuse and Exploitation | Tools name a catalogue key, never a URL. Arguments screened for credential shapes. Results screened for injection. `maxToolCallingRoundTrips(6)`. Per-endpoint timeouts and byte budgets (a transport bound, applied before link scrubbing). Addresses outside the catalogue are removed from a tool body before the model reads it. | SSRF refusal via an unconfigured catalogue key; credential-shaped argument refused; injected result neutralised |
| **ASI03** | Identity and Privilege Abuse | `ConversationId` is validated before it builds a storage key in either store — the memory-isolation boundary. No tool parameter may be named like a credential. | 13 `ConversationId` cases naming each rejected shape; the parameter-name test over every tool |
| **ASI04** | Agentic Supply Chain | All tools are compiled `@Tool` methods in this repository. There is no runtime tool registry, no MCP mount, no dynamic descriptor. | ArchUnit: tools may not open their own connections |
| **ASI05** | Unexpected Code Execution | There is no code-execution tool, no `eval`, no template engine reachable from a prompt. The applicable slice is deserialization hygiene on anything persisted. | ArchUnit no-cycles and the tool-boundary rules |
| **ASI06** | Memory & Context Poisoning | Output guardrails use `fatalWithMessageRemoval`, so a poisoned assistant message is deleted from memory rather than replayed. Compaction preserves activations together with the messages that requested them, and empties a failed trajectory's payload without removing the message — a result whose request was summarised away is a list the provider rejects. | Leaked-canary message removal; the compaction invariant test |
| **ASI07** | Insecure Inter-Agent Communication | Sub-agents are in-process objects. **There is no wire between them to protect.** | — see below |
| **ASI08** | Cascading Failures | Bounded round trips, bounded retries (never on 4xx or 429), per-endpoint timeouts, and a triage gate that fails *open* so a classifier outage degrades rather than stops. | Per-endpoint timeout test; the retry-once test |
| **ASI09** | Human-Agent Trust Exploitation | Refusals never explain which rule fired. The assistant is instructed to say what it does not know and never to present a tool failure as an answer. | Rejection-message test asserting no rule name leaks |
| **ASI10** | Rogue Agents | Every tool execution is bounded and observable: per-role metrics, per-tool counters, and structured logs on every block. | Metrics assertions in the cost tests |

## The tool layer, specifically

Six attacks, and what stops each.

| Attack | Stopped by |
|---|---|
| **SSRF via a URL-taking tool** | No tool takes a URL. A catalogue key is the only way to name a destination, and one that is not configured is refused. This removes the category rather than filtering it. |
| **Secret exfiltration via tool arguments** | Credential shapes are refused before the request leaves the process, and no tool parameter may be *named* like a credential. |
| **Indirect injection via tool results or RAG** | `ToolGuardProvider` screens every result, with its own rule set — see below. RAG documents are the repository's own reviewed Markdown, not third-party text. |
| **Tool-name hallucination** | A non-throwing strategy returns guidance instead of a 500, telling the model to activate the right skill first. |
| **Excessive agency / unbounded loops** | Six round trips per turn, and a bounded tool set that only widens on explicit activation. |
| **Cost-exhaustion DoS** | Per-endpoint timeouts, no retry on 429, a triage gate that answers most refusals with a small model, and per-role cost metrics that make an anomaly visible. |

### A tool result is not a sentence, and the rules cannot be the same

The screen on a tool result started as the screen on a user's turn, and three of
its structural rules turned out to measure the machine rather than the message.

Compact JSON contains no whitespace, so a rule that blocks on a 400-character run
without a space blocks on any result over 400 bytes. Measured 2026-08-20, the
central bank's twelve-month SELIC series is 457 characters with a longest run of
457 — and the model was told *"this source returned content that looks like an
attempt to give you instructions"* instead of receiving the series. Most of this
catalogue returns compact JSON, so most of it was reachable and unusable.

`scoreToolResult` keeps only what is about **instructions**, which is the only
thing indirect injection can be: role delimiters, a fence claiming a privileged
role, a data URI, override and probe phrases, invisible characters, a decodable
base64 blob. It drops the length rule — `max-response-bytes` is already that
bound, at the transport, where it belongs — the long-token rule, and the
`<s>`/`</s>` delimiter, which is a chat-template leak in a user's own sentence and
ordinary HTML strikethrough in an article a tool fetched.

The general shape: **a detector inherited from another surface is a detector that
has not been evaluated on this one.**

### The catalogue entry that was rejected

Worth recording, because a rejected source leaves no trace in the code and the
next person re-adds it.

`ip-api.com` is the better IP-geolocation source of the two that were evaluated —
richer record, same latency — and it serves the free tier over **plain HTTP
only**. Measured 2026-08-14: `https://ip-api.com/json/8.8.8.8` answered `403` in
1.16 s, `http://ip-api.com/json/8.8.8.8` answered `200` in 0.27 s. TLS is a paid
feature there.

The argument that tool takes is a **user-supplied IP address**, so the plaintext
version puts a value out of the user's message on the wire in clear, to buy a few
hundred bytes of extra fields. `ipwho.is` answers the same question over TLS, so
the catalogue carries that one and `ip-api` is absent by decision. Every base URL
in `agentic.tools.apis` is `https://`.

A rejection written only in prose is a convention, and conventions lose. So the
rule is executable: `./scripts/check-tool-catalogue.py` fails on any `base-url`
that is not `https://`, with `localhost` and `127.0.0.1` exempt because test
fixtures serve over plain HTTP.

```
PLAINTEXT BASE URL — a tool's arguments would travel unencrypted:
  ipwhois                          http://ipwho.is
```

That is the check firing against a deliberately downgraded entry — the negative
control, because a gate nobody has seen fail is not known to work.

## What is *not* built, and why

The distinction that matters: everything above is code plus tests in this
repository. Everything below is an **absent control with a written reason** — it
is not mitigated, and nothing here should be read as implying otherwise.

- **ASI07 in full** — mTLS, message signing, nonces, registry attestation.
  Sub-agents are in-process objects reached by a method call. Building crypto
  between them would be theatre. The moment an A2A client or an MCP tool provider
  appears, there *is* a wire, and this decision has to be re-opened.
- **ASI04 runtime supply chain** — SBOM/AIBOM attestation, signed tool
  descriptors, registry pinning. The runtime composition surface OWASP describes
  does not exist here; all tools are compiled into the artefact.
- **ASI09 UI safeguards** — risk badges, manipulation-pattern reminders, adaptive
  trust calibration. This is a headless backend. The server-side half is built;
  the visual layer is out of scope and stays out.
- **ASI05 sandboxing** — containers, syscall limits, safe interpreters. There is
  nothing to sandbox.
- **ASI10 cryptographic identity attestation** — signed behavioural manifests,
  HSM-mediated signing. Requires identity infrastructure this project does not
  have.
- **Per-principal authorization.** There is one anonymous user. The seam exists —
  `ConversationId` scopes memory — but the entitlement matrix is degenerate, so
  no policy is enforced. A real deployment puts authentication at the edge and
  passes a principal through `InvocationParameters`.
- **HTTP rate limiting.** Deliberately at the gateway in a real deployment, and
  absent here. The per-turn bounds cap what one *request* can cost; they do not
  cap how many requests one caller can make.
- **Per-endpoint transport limits.** The heap a tool call can occupy is bounded
  globally — `micronaut.http.client.max-content-length` is 3 MB and at most twelve
  requests are in flight — but it is bounded by *one* number for sixty endpoints,
  and that number is chosen with a margin rather than derived from the catalogue.
  The version that would derive it gives `ToolHttpClient` a client configured per
  entry, or streams the body and cancels past the budget. Not built, because no
  upstream here is hostile and the exposure is bounded either way; recorded because
  the reasoning belongs somewhere a reader will find it. See issue #1.

  **The trap in the obvious version**, written down because it looks like the fix
  and is the opposite: the transport floor is the largest **raw** body an endpoint
  can return, not the largest `max-response-bytes`. Those differ by an order of
  magnitude — themealdb's ceiling is 262 144 and its one-letter search measured
  2.3 MB. Set the transport under the raw body and `truncate()` never runs: the
  client refuses the response, the retry loop retries a deterministic failure, and
  the explicit "narrow your query" the model can act on becomes "the request did
  not complete". Raising `max-response-bytes` to compensate is worse again — it
  raises the buffered ceiling too. Pinned by
  `ToolHttpClientTest#aTransportCeilingBelowTheBodyIsNotABudget`.

## The one that generalises

Read the framework's defaults before trusting them. Three in this stack are
actively dangerous and all three are on by default:

1. The tool-execution error handler feeds `Throwable.getMessage()` to the model —
   stack traces, upstream URLs, response bodies and credentials, into the prompt,
   the history and the provider's logs.
2. The hallucinated-tool-name strategy throws, turning a model typo into a 500.
3. Retrieved content is written into persisted chat memory, so a chunk fetched on
   turn three is still being paid for on turn twenty.

None of them announces itself. Each was found by reading the library's source, not
its documentation.
