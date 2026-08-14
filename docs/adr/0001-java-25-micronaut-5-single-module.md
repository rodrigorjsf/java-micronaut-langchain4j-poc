# 0001 — Java 25, Micronaut 5.1.0, one Maven module

**Status:** Accepted · **[measured]**

## Context

The brief specified Micronaut 5.x and Java 25. Both were unproven on this machine
and both are load-bearing: Micronaut is annotation-processor-heavy, and a new JDK
is exactly where annotation processors break.

## Decision

**Java 25 on Micronaut 5.1.0, in a single Maven module.**

Java 25 is not a gamble here. Micronaut Launch offers exactly one JDK for 5.1.0:

```
$ curl -s https://launch.micronaut.io/select-options | ...
jdkVersion => ['JDK_25']
```

`micronaut-parent:5.1.0` declares `<jdk.version>17</jdk.version>` as a *default*,
which is easy to misread as a ceiling. It is not; the generated project overrides
it to 25.

Proven before any application code was written:

1. A Launch-generated skeleton, untouched: `./mvnw -B -q test` → exit 0.
2. The same skeleton plus `langchain4j-bom:1.18.1` and the beta modules
   (`skills`, `agentic`, `guardrails`, `embeddings-all-minilm-l6-v2-q`), with a
   class using `@Tool`, `@SystemMessage`, `@MemoryId`, `dev.langchain4j.agentic.Agent`,
   `Skills`, `PatternBasedPromptInjectionGuardrail` and `@Singleton` together:
   `./mvnw -B -q compile` → exit 0.

Micronaut's annotation processing and LangChain4j's GA and beta modules coexist
on Java 25.

**The Maven wrapper, not the Maven on `PATH`.** The `mvn` on this machine is
3.8.6 (2022) and lives on a Windows drive mount; the wrapper is 3.9.16 and its
local repository is on ext4. Every build command in this repository uses
`./mvnw`.

**One module, not several.** The seams this project needs are between bounded
contexts — triage, guardrails, skills, tools, memory, agent — and packages
express those. Multi-module under `micronaut-parent` adds per-module annotation
processor configuration and `micronaut.processing.group` bookkeeping for no
boundary a package cannot draw. Package boundaries are enforced by ArchUnit,
which is executable and therefore a real constraint rather than a convention.

## Consequences

**Gained.** Records, pattern matching, virtual threads under `@ExecuteOn(BLOCKING)`,
and `Math.clamp` — all used. One `pom.xml`, one reactor, fast builds.

**Given up.** No compile-time guarantee that a package boundary is not crossed;
ArchUnit catches it at test time instead. Splitting later means moving files,
which is cheap, and re-deriving the processor configuration, which is not — but
it is a cost paid only if the split is ever needed.

## Revisit if

The tool catalogue becomes independently deployable, or a second application
needs to depend on the domain without dragging in the HTTP layer.
