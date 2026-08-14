# 0002 — Use `dev.langchain4j` directly, not `io.micronaut.langchain4j`

**Status:** Accepted · **[sourced]**

## Context

`io.micronaut.langchain4j:micronaut-langchain4j-*` (2.2.0 in the Micronaut 5.1.0
platform) wires LangChain4j into Micronaut with annotation-driven configuration:
`@AiService` interfaces, configuration-property-driven model beans, and
`@Singleton` tools registered automatically. It is less code.

The brief asked for the `dev.langchain4j` dependency explicitly.

## Decision

**Depend on `dev.langchain4j` and wire everything by hand**, in
`AiServiceFactory`, `ChatModelRegistry` and `SkillCatalog`.

Beyond following the brief, the reason it is the right call for a study project:
this repository exists to show *how* an agentic backend is assembled. The
integration's value is hiding the assembly. Every choice that matters here —
guardrail ordering, the tool-execution error handler that stops
`Throwable.getMessage()` reaching the model, the message-window memory over a
custom store, the skills tool provider, the round-trip bound — is a line in a
factory a reader can see and a test can pin. Under the integration those live in
`application.yml` keys, or in defaults nobody reads.

There is also a version argument. The platform BOM pins
`langchain4j.version = 1.18.0` while the current release is **1.18.1**, and
`langchain4j.community.version = 1.16.0-beta26` against **1.18.0-beta28**.
Overriding one property moves LangChain4j independently of the integration's
release cadence:

```xml
<langchain4j.version>1.18.1</langchain4j.version>
```

`langchain4j-bom:1.18.1` then manages the GA modules at 1.18.1 and the beta ones
at 1.18.1-beta28 through its own internal properties, so no LangChain4j
dependency in this POM carries a version.

## Consequences

**Gained.** Every wiring decision is visible, greppable and testable. LangChain4j
moves on its own schedule. No hidden bean that a future reader has to discover by
reading someone else's starter.

**Given up.** More code in `AiServiceFactory`, and the responsibility for
lifecycle details the integration would have handled. Micronaut-specific
LangChain4j features — the Ollama test resource, the `@AiService` processor — are
unavailable, and adopting them later means partially rewriting the factory.

## Revisit if

The hand-written factory starts reimplementing things the integration already
does well, particularly around configuration binding for many model roles.
