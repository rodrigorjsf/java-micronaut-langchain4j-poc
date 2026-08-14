# Architecture Decision Records

One file per decision that was hard to reverse, surprising, or paid for with a
measurement. Each record states the decision, the evidence behind it, what it
costs, and what would make us revisit it.

Records marked **[measured]** carry numbers produced on the development machine
by a command run in the main session, quoted verbatim. Records marked
**[sourced]** rest on official documentation or on library source read directly.

| # | Decision | Status |
|---|---|---|
| [0001](0001-java-25-micronaut-5-single-module.md) | Java 25, Micronaut 5.1.0, one Maven module | Accepted |
| [0002](0002-langchain4j-directly-not-the-micronaut-integration.md) | Use `dev.langchain4j` directly, not `io.micronaut.langchain4j` | Accepted |
| [0003](0003-no-local-model.md) | No local model; hosted chat + in-process embeddings | Accepted |
| [0004](0004-floci-as-the-aws-and-cache-substrate.md) | floci for DynamoDB and Valkey | Accepted |
| [0005](0005-progressive-tool-disclosure-through-skills.md) | Disclose tools through skills, not tool search | Accepted |
| [0006](0006-llm-as-judge-triage.md) | A separate, cheaper model triages every turn | Accepted |
| [0007](0007-write-through-conversation-memory.md) | Valkey in front of DynamoDB for chat memory | Accepted |
| [0008](0008-layered-prompt-injection-defence.md) | Normalize, then score, then ask a model | Accepted |
| [0009](0009-tool-http-catalogue.md) | Tools name a catalogue key, never a URL | Accepted |
| [0010](0010-rag-over-the-assistants-own-documentation.md) | Retrieve the assistant's own docs, routed by skill hint | Accepted |
