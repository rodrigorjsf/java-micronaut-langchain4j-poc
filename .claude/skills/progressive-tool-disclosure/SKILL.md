---
name: progressive-tool-disclosure
description: Keep a large tool set affordable by showing the model only what the turn needs. Use when an agent has more than ~15 tools, when the system prompt is dominated by tool schemas, when tool selection accuracy drops as tools are added, or when deciding between skills and tool search. With fewer than about fifteen tools, a wrong pick is one description's fault — use agentic-tool-boundary. Once search is the chosen mechanism, rolling it out over a real layer is a pass a person runs: ask the user to run tool-search-rollout.
---

# Progressive tool disclosure

Every tool schema in the system prompt is paid on **every turn, for the life of
every conversation**. That standing cost is the problem; the mechanisms below all
attack it the same way — show names now, bodies later.

## The token math, first

A tool schema with a description and two documented parameters runs roughly
**80 tokens**. Fifty tools is ~4000 tokens of standing prompt, unchanged from the
first turn to the last.

The second cost is larger and less visible: **a model picks less accurately from
a list of fifty than from a list of six.** Paying more to choose worse is a bad
trade twice over.

Measure your own before deciding anything. Serialize the tool schemas your
framework actually sends and count. The number is usually worse than the guess.

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
tools visible:  activate_skill, read_skill_resource
```

Measured on a real catalogue: **81 tokens per skill**. Twelve skills is ~970
tokens standing, against ~4000 for fifty raw schemas — and the model chooses from
twelve options, not fifty.

Budget it in a test with a per-skill ceiling. Be generous with the ceiling: the
description is the *only* thing the model sees before activating, so squeezing it
below the point where it carries routing information saves tokens by making
routing worse.

## Skills or tool search — they do not compose

Some frameworks also offer **tool search**: tools stay hidden until the model
calls a search tool, which reveals matching ones.

Check whether the two can coexist in your framework before choosing. In
LangChain4j they cannot: skills expose their tools through a *dynamic* tool
provider, and a dynamic provider bypasses the tool-search filter entirely.
Enabling both leaves the filter running over whatever is *not* behind that
provider, so the skills' own tools are neither hidden by it nor findable
through it — and nothing reports the gap.

Choose skills when:

- the grouping is meaningful to a **human** — a skill is documentation, reviewable
  in a pull request; a search hit is not;
- the model needs **instructions with the tools** — activation delivers "when to
  use this, how to combine these, what to do when one fails" in the same breath
  as the tools themselves.

Choose tool search when:

- tools do not group naturally, or one tool belongs to several groups;
- you need **cross-group recall** — "refund" surfacing a billing tool whose group
  name never mentions refunds.

Choosing it is where this page stops. Actually rolling it out — the census that
records how each tool reaches the model, keyword against semantic, the rewrites
that make descriptions retrievable, per-caller scope and the guardrail that
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

- **Too few tools.** Under ~10, disclosure costs a round trip and saves little.
- **A skill with fifteen tools** reproduces the original problem one level down.
  Split it, or nest.
- **Every turn needs the same skill.** Then it is not progressive, just delayed;
  keep those tools always visible.
- **Descriptions written as titles.** "Weather tools" tells the model nothing.
  The description is doing routing work — write it as routing.
