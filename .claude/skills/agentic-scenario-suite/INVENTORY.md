# Inventory: artifacts, domains and real inputs

Open this for steps 1–3 of [`SKILL.md`](SKILL.md): finding every artifact that can
change an answer, grouping them into scenario domains, and sourcing the sentences
users really type. It is written for any LangChain4j backend, with skills or
without; the mapping for the repository this skill was written in is in
[`WORKED-EXAMPLE.md`](WORKED-EXAMPLE.md).

## The six kinds of artifact

An **artifact** is anything whose change can change what the model answers. A
row's `dependsOn` names artifacts; diff mode fingerprints them; the report lists
the ones no row names as **uncovered**. Give each one a stable identifier that a
reviewer can read without opening the code.

| Kind | Where it lives in a LangChain4j backend | Identifier convention | Why it changes answers |
|---|---|---|---|
| system-prompt section | `@SystemMessage` text or resource, a `systemMessageProvider`, prompt files the builder concatenates | `system-prompt#<heading-slug>` | it sets role, refusals, answer shape and language for every turn |
| tool | `@Tool` methods (description, parameter names, `@P` / `@Description` text), hand-built `ToolSpecification`s, MCP tools | the tool name the model sees — `get_weather` | the description decides *when* the model calls it; the schema decides *what* it sends |
| skill | a directory with `SKILL.md` loaded by a skill loader, plus its resources | the directory name | the description decides activation; the body steers every call made after it |
| sub-agent | `@Agent` interfaces, agentic workflows, an AI Service called as a tool | the agent name — `trip_briefer` | its own prompt and tools shape what it returns to the parent |
| catalogue entry | the configuration of each upstream a tool reaches: base URL, timeout, response ceiling | the configuration key | a different host, timeout or ceiling changes what the tool hands back |
| guardrail | `InputGuardrail` / `OutputGuardrail` implementations, a triage or scope classifier, a link or exfiltration filter | the class name, or `guardrail#<rule>` | it decides whether a turn is answered, refused or blocked, and rewrites answers |

**A fingerprint may be coarser than the artifact, never finer.** When a tool's
description and the helper code its class shares are hard to separate, fingerprint
the whole class: an edit then selects every tool of the class, which costs one
extra run. A fingerprint finer than the artifact lets a real change select nothing.

**Where you looked is part of the answer.** A backend spreads prompt text across
annotations, resource files and string builders; tools across annotated classes and
programmatic specifications. For each kind, write down the search you ran, so a
reviewer can tell an empty kind from an unsearched one.

## Deriving the scenario domains

A **scenario domain** is one flow a user can reach through the agent, named the way
a user asks for it. It is the unit of coverage: every domain gets its happy paths
and its bad paths, and the report counts rows per domain.

| Backend shape | Where domains come from | Example |
|---|---|---|
| skills group the tools | each skill's territory, split where one skill serves flows users ask for separately | a `geo-and-weather` skill yields *weather lookup* and *place lookup* |
| tools only, no skills | group tools by the sentences that reach them: tools a user reaches with the same sentence are one domain | `find_company` and `company_partners` → *company lookup* |
| sub-agents | each workflow a user can trigger, even when it spans several tools | a trip briefing that calls weather, holidays and places |
| a mix | all three; a tool reachable both bare and through a skill belongs to the domain the user's sentence names | — |

Three things are **kinds of scenario, not domains**: injection, out-of-scope
requests and multi-turn memory. They are tested *inside* real domain requests —
an injection hidden in a weather question, a follow-up that relies on the previous
turn — because that is where they arrive.

**A domain holds state** when a later turn can depend on an earlier one: a place
resolved in turn 1 and reused in turn 2, a selection the user refines. Those
domains owe a multi-turn row. Record the list of stateful domains beside the
dataset, so the coverage floor can be computed rather than remembered.

*Check:* every tool and sub-agent is reachable from at least one domain. A tool in
no domain is either dead or a domain you have not named yet.

## Sourcing inputs from real traffic

Synthetic sentences are written in the vocabulary of whoever wrote the artifacts.
Users write differently — shorter, misspelled, in their own nouns — and a scenario
set in the team's vocabulary passes while real turns fail. So before drafting,
ask the user for an export of real traffic per domain.

| Source | What to export | Where the sentences are |
|---|---|---|
| Langfuse | traces filtered by the tool or skill that served them, over a recent window | the trace input, or the first user message of the session |
| any other observability platform | spans of the chat endpoint with their input attribute | the recorded user message |
| application logs | request logs of the chat endpoint, where they record the message | the message field |
| support reports and tickets | complaints about wrong or missing answers | the sentence the user says they typed — these are bad-path gold |

You never need the model's answers from the export, only the user sentences. An
expectation is still derived from the artifacts (rule 1 of [`SKILL.md`](SKILL.md)),
never from what the model said in production.

### Paraphrase, then anonymise

A sentence taken from traffic is **paraphrased and anonymised** before it is
proposed as a row. Paraphrase keeps the user's vocabulary, length and errors while
changing the exact wording, so the dataset carries the pattern, not a person's
message. Anonymise every value that could identify someone:

| Found in the sentence | Replace with |
|---|---|
| a person's name | a different, common name |
| a national ID, tax number, company registration of a private person | a syntactically valid number that belongs to no one, or a public test value |
| e-mail address, phone number | a placeholder in the same format (`maria@example.com`, `+55 11 90000-0000`) |
| street address, precise location | a well-known public place in the same city |
| order, account or ticket numbers | an invented value in the same format |
| a public entity the flow is about (a listed company, a city) | keep it — the flow depends on it and it identifies no person |

The row records `source: trace`. The export itself stays outside the repository;
delete it once the rows are reviewed.

**Show the user the before/after pairs** for traffic-sourced rows in the step-6
diff, so the anonymisation is reviewed along with the rows.

### When there is no traffic

A backend before launch has none. Draft synthetic inputs, record
`source: synthetic`, and write them the way the least technical user would: short,
in the user's language, with the noun a user would use rather than the tool's
name. Replace them with traffic-sourced rows once traffic exists.
