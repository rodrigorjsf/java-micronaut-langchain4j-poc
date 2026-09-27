---
name: progressive-tool-disclosure
description: Use when an agent has ten or more tools, when the system prompt is dominated by tool schemas, when tool selection accuracy drops as tools are added, or when deciding which tools go behind skills and which stay searchable. With fewer than ten tools, a wrong pick is one description's fault — use agentic-tool-boundary. Once search is the chosen mechanism, rolling it out over a real layer is a pass a person runs: ask the user to run tool-search-rollout.
---

# Progressive tool disclosure

Every tool schema in the system prompt is paid on **every turn, for the life of
every conversation**. That standing cost is the problem; the mechanisms below all
attack it the same way — show names now, bodies later.

| You are here because | Start at |
|---|---|
| an agent has ten or more tools, or tool schemas dominate the system prompt | *The token math, first*, then *The threshold: ten tools* |
| tool selection accuracy drops as tools are added | *The threshold: ten tools* |
| deciding which tools go behind skills and which stay searchable | *Group tools into skills*, then *Skills and tool search coexist — as a partition* |
| a tool that should be there is not, and nothing says why | *The failure mode: disclosure state is invisible* |
| disclosure is in place and turns got slower or worse | *When it backfires* |

## The token math, first

A tool schema with a description and two documented parameters runs roughly
**80 tokens**. Fifty tools is ~4000 tokens of standing prompt, unchanged from the
first turn to the last.

The second cost is larger and less visible: **a model picks less accurately from
a list of fifty than from a list of six.** Paying more to choose worse is a bad
trade twice over.

Measure your own before deciding anything. Serialize the tool schemas your
framework actually sends and count. The number is usually worse than the guess.

### The threshold: ten tools

This page uses **ten tools** as the line, and so does every page that routes
here. It is Anthropic's published guidance for its own Tool Search Tool, which
says to use it with "10+ tools available" or "Tool definitions consuming >10K
tokens", and calls it less beneficial for a "Small tool library (<10 tools)"
[sourced — Anthropic, *Introducing advanced tool use on the Claude Developer
Platform*, 2025-11-24, read 2026-09-27,
https://www.anthropic.com/engineering/advanced-tool-use].

The same article gives the figures behind that line. They are Anthropic's
measurements of its own tool, on its own MCP evaluations, and not measurements
of any framework's search:

| Measured | Without search | With search |
|---|---|---|
| Context used before any work (50+ MCP tools) | ~77K tokens | ~8.7K tokens (an 85% reduction) |
| Accuracy on MCP evaluations, Opus 4 | 49% | 74% |
| Accuracy on MCP evaluations, Opus 4.5 | 79.5% | 88.1% |

Treat ten as the point where disclosure starts to pay, not as a hard rule. A
set of nine tools with very long schemas can cross the 10K-token line first.

## Group tools into skills

A **skill** is a documented capability: a name, a one-line description, a body of
instructions, and the tools that belong to it. The standing prompt carries only
the names and descriptions; the body and the tools arrive when the model
activates the skill.

```
system prompt:  <available_skills>
                  <skill><name>brazil-civic-data</name>
                  <description>Brazilian public registries — postal codes, area
                  codes, holidays, companies. Use for any question about a
                  Brazilian address, phone area code or registered company.
                  </description></skill>
                  ... 11 more
                </available_skills>
tools visible:  activate_skill        always
                read_skill_resource   only when at least one skill ships a resource file
                a skill's own tools   only after activate_skill has named that skill
```

`read_skill_resource` is not a fixed part of the picture. LangChain4j registers
it only when at least one loaded skill has resources, so a catalogue of
single-file skills shows the model `activate_skill` alone.

Measured on a real catalogue: **81 tokens per skill**. Twelve skills is ~970
tokens standing, against ~4000 for fifty raw schemas — and the model chooses from
twelve options, not fifty.

Budget it in a test with a per-skill ceiling. Be generous with the ceiling: the
description is the *only* thing the model sees before activating, so squeezing it
below the point where it carries routing information saves tokens by making
routing worse.

## Skills and tool search coexist — as a partition

Some frameworks also offer **tool search**: tools stay hidden until the model
calls a search tool, which reveals matching ones.

In LangChain4j the two work together, and the split between them is fixed.
Every tool falls in exactly one row:

| Tool | Searchable? | When the model sees it |
|---|---|---|
| A skill's own tools (skill-scoped) | never | only after `activate_skill` names that skill |
| Regular tools (`.tools(...)`, or a non-dynamic provider) | yes | when a search finds it, whether or not any skill is active |
| `activate_skill` | no — `ALWAYS_VISIBLE` | on every turn |
| `read_skill_resource` | no — `ALWAYS_VISIBLE` | on every turn, but it exists only when some skill has resources |

The tutorial states the partition: "Skill-scoped tools are never searchable",
"Regular tools remain searchable", and "`activate_skill` is always visible"
[sourced — docs.langchain4j.dev/tutorials/skills, *Using Skills with Tool
Search*, read 2026-09-27]. The code explains why it holds, in two cases
[sourced — `Skills` and `ToolService` read in the langchain4j-skills
1.18.1-beta28 and langchain4j 1.18.1 jars, and again at tag 1.20.1, read 2026-09-27]:

- **At least one skill has tools.** `Skills.toolProvider()` is then a *dynamic*
  provider (`isDynamic()` returns true). `ToolService.createContext` runs the
  search filter first and refreshes dynamic providers after it, so nothing from
  that provider is ever filtered or found. The provider adds the skill
  management tools on every turn, and it adds a skill's own tools after that
  skill is activated.
- **No skill has tools.** The provider is then static, so its tools do pass
  through the search filter. `activate_skill` and `read_skill_resource` survive
  it only because both carry the `ALWAYS_VISIBLE` search behaviour.

So the choice is not skills *or* search. It is where each tool goes.

Put a tool **behind a skill** when:

- the grouping is meaningful to a **human** — a skill is documentation, reviewable
  in a pull request; a search hit is not;
- the model needs **instructions with the tools** — activation delivers "when to
  use this, how to combine these, what to do when one fails" in the same breath
  as the tools themselves.

Keep a tool **regular, where search can reach it**, when:

- it does not group naturally, or it belongs to several groups;
- you need **cross-group recall** — "refund" surfacing a billing tool whose group
  name never mentions refunds.

The same bypass applies to any other dynamic provider: its tools are never
searchable either, skill or not.

**The pitfall is a tool on the wrong side.** A tool placed behind a skill in the
hope that search will also find it never appears in a search result, and
nothing reports that. If a tool has to be findable by search, keep it out of
every skill.

Deciding which tools stay searchable is where this page stops. Actually rolling
it out — the census that records how each tool reaches the model, keyword
against semantic, the rewrites that make descriptions retrievable, per-caller scope and the guardrail that
enforces it — is `tool-search-rollout`, and a model cannot load it: **ask the user
to run it, which they invoke by name.**

## The failure mode: disclosure state is invisible

Activation is not application state. Frameworks reconstruct the visible tool set
**on every round trip** by scanning the conversation for a marker left by the
activation call.

That has two silent failure modes, and neither raises an error. The agent simply
stops being able to do anything and starts answering from memory.

**1. A memory store that drops message attributes.** If you persist conversations
yourself and serialize only message *text*, the activation marker is lost on the
next turn, every turn. Serialize with the framework's own message serializer, and
**test the round trip** through your real store.

**2. The activation sliding out of the memory window.** In a long conversation
the activation result is eventually evicted and the tools go hidden again. This
is correct behaviour, not a bug — but a reader who does not know it will size the
memory window wrong.

Write both tests. The first asserts the marker survives your store; the second
asserts the tools reappear in the *next* request after activation, and — with a
deliberately small window — that they eventually disappear.

## Other levers on the same principle

Disclosure is not only about tools. The same "names now, bodies later" move
applies wherever a body is large and usually unneeded:

| Lever | Standing cost | Deferred cost |
|---|---|---|
| Skills | name + description | instructions + tool schemas on activation |
| Retrieval | nothing | chunks, and only when routed |
| Memory tiering | recent turns verbatim | older turns summarised, oldest archived |
| Sub-agents | one tool description | the sub-agent's whole context, discarded after |

Sub-agents are the strongest version: a sub-agent reads the raw output of three
API calls and returns four sentences. The raw text never enters the conversation
that persists.

## When it backfires

- **Too few tools.** Under ten, disclosure costs a round trip and saves little
  (see *The threshold: ten tools*).
- **A skill with fifteen tools** reproduces the original problem one level down.
  Split it, or nest.
- **Every turn needs the same skill.** Then it is not progressive, just delayed;
  keep those tools always visible.
- **Descriptions written as titles.** "Weather tools" tells the model nothing.
  The description is doing routing work — write it as routing.
