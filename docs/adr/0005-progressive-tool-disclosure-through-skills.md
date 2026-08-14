# 0005 — Disclose tools through skills, not through tool search

**Status:** Accepted · **[measured]** · **[sourced]**

## Context

The application exposes 102 tools across 14 skills. Declaring them all on the AI service puts
every schema in the system prompt on **every turn**, for the life of every
conversation. Two costs, and the second is worse than the first:

- **Tokens.** A tool schema with a description and two documented parameters runs
  around 80 tokens. A hundred of them is roughly 8000 tokens of standing prompt,
  charged on every request, unchanged.
- **Accuracy.** A model picks less accurately from a list of a hundred than from
  a list of six. Paying more to choose worse is a bad trade twice over.

LangChain4j 1.18.1 offers two mechanisms for showing a model fewer tools than
exist.

## Options

**A — Tool search.** `ToolSearchStrategy` hides tools until the model searches for
them. `SimpleToolSearchStrategy` ranks by substring; `VectorToolSearchStrategy`
ranks by embedding similarity. Visibility is reconstructed each round trip from a
`found_tools` attribute on tool-result messages in chat memory.

**B — Skills.** `langchain4j-skills` implements the
[Agent Skills specification](https://agentskills.io). The system prompt carries
only an `<available_skills>` block of names and descriptions; an `activate_skill`
tool returns a skill's full instructions and makes its tools available.

## Decision

**Skills.** Three reasons, in order of weight.

**They do not compose, so this is a choice and not a preference.** `Skills`
returns a *dynamic* `ToolProvider` as soon as any skill owns tools, and a dynamic
provider bypasses the tool-search filter entirely — `ToolService.createContext`
applies the search adjustment before refreshing dynamic providers, and the
refresh adds to both the effective and available tool sets. Enabling both would
silently disable one.

**A skill is meaningful to a human, a search hit is not.** `SKILL.md` is
documentation, review-able in a pull request, and portable to any harness that
speaks the same format. A ranking function is none of those things.

**Instructions and tools arrive together.** `activate_skill` returns the skill's
body — when to use it, how to combine its tools, what to do when one fails — in
the same breath as the tools themselves. Under tool search the model gets a tool
with no guidance for using it.

### What it costs, measured

```
skills index: 14 skills, 5059 chars, ~1264 tokens (~90 per skill)
```

At 90 tokens per skill the fourteen-skill catalogue costs **1264 tokens**
standing, against ~8000 for a hundred raw schemas. Both numbers are asserted in
`SkillCatalogTest`, with a per-skill ceiling of 95 tokens and a total ceiling of
1500.

The shape of that comparison is the argument. Going from 12 skills to 14 — from
roughly 60 tools to 102 — added about 180 tokens to the standing prompt. Under
raw declaration the same 42 tools would have added around 3400.

The ceiling is deliberately generous. A description is the only thing the model
sees before activating, so squeezing it below the point where it carries routing
information would save tokens by making the routing worse.

## The mechanism fails silently, so it is tested directly

Visibility is reconstructed on every round trip by scanning chat memory for a
`ToolExecutionResultMessage` carrying the activation attribute. If that attribute
is lost, the tools go hidden again — with no exception and no log line. The agent
simply stops being able to do anything and starts answering from memory.

Two ways to lose it, both covered:

- **A memory store that persists only message text.** `ChatMemoryStoreFlociIT`
  asserts the attribute survives a round trip through both Valkey and DynamoDB.
  This is why the store serializes with LangChain4j's own `ChatMessageSerializer`
  rather than a hand-rolled format.
- **The activation result sliding out of the memory window.**
  `SkillActivationTest` asserts the tools appear after activation, that a second
  activation adds rather than replaces, that activation survives into the next
  user turn — and, with a deliberately small window, that it is eventually lost
  and the model must re-activate. That last one documents real behaviour rather
  than wishing it away.

## Consequences

**Gained.** Standing prompt cost grows with the number of *skills*, not tools. A
new tool is one class plus one line of `SKILL.md`, and a tool naming a skill with
no `SKILL.md` fails the build rather than becoming quietly unreachable. The
`/api/chat/capabilities` endpoint is served from the same catalogue the model
sees, so documented capabilities cannot drift from real ones.

**Given up.** One extra round trip on the first turn that needs a skill. No
cross-skill semantic recall — a user asking about "refunds" will not surface a
tool in an unrelated skill unless the skill description says so, which puts real
weight on writing those descriptions well. Activation is per-conversation state
that lives in chat memory, so the memory window and the disclosure mechanism are
coupled, and shrinking the window silently shortens how long tools stay visible.

## Revisit if

Skill-level granularity proves too coarse — a skill with fifteen tools reproduces
the original problem one level down. The fix would be sub-skills or a hybrid
where large skills nest, not a switch to tool search.
