# The scenario row

Open this to write, review or adjust a row (steps 4–5 of [`SKILL.md`](SKILL.md)).
It holds the row shape, the kinds, the coverage floor, and — the part that keeps a
suite honest — where each expectation comes from. The fields a particular runner
implements are listed in [`WORKED-EXAMPLE.md`](WORKED-EXAMPLE.md); write only the
fields your runner checks, because a field nothing checks reads as a guarantee
nobody enforces.

## Shape

One JSON file per domain, rows in stable order (append; never re-sort), so a diff
shows only what changed.

```json
{
  "id": "weather-happy-forecast-tomorrow",
  "domains": ["weather"],
  "kind": "happy",
  "source": "synthetic",
  "turns": ["Vai chover amanhã em São Paulo?"],
  "expect": {
    "trajectory": {"outcome": "ANSWERED", "toolsCalled": ["get_weather"]},
    "answer": {"language": "pt-BR", "grounded": ["(-?\\d+(?:[.,]\\d+)?)\\s*mm"]},
    "rubric": ["The answer says directly whether rain is expected tomorrow."]
  },
  "dependsOn": ["geo-and-weather", "get_weather", "open-meteo-forecast"],
  "critical": true
}
```

| Field | Holds | Rule |
|---|---|---|
| `id` | `<domain>-<kind>-<short-slug>` | unique across the dataset, never reused, never renamed |
| `domains[]` | the domains the turn belongs to | two or more for a cross-domain turn; the row counts toward each |
| `kind` | one of the kinds below | exactly one |
| `source` | `trace`, `user` or `synthetic` | where the *input* came from — see [`INVENTORY.md`](INVENTORY.md) |
| `turns[]` | the user messages, in order, on one conversation | the only field a model may draft |
| `expect.trajectory` | final `outcome`, tools that must run; where the runner supports them, skills that must activate, argument constraints, forbidden tools and call bounds | derived from artifacts |
| `expect.answer` | deterministic answer checks: language, required content, **grounding** | derived from artifacts |
| `expect.turns[]` | the same checks aimed at one turn of a multi-turn row (1-based) | derived from artifacts |
| `expect.rubric[]` | plain-language criteria a **grader** model scores | report-only until the grader is calibrated |
| `upstream` | the one upstream an `upstream-failure` row fakes, and how | present if and only if `kind` is `upstream-failure` |
| `dependsOn[]` | identifiers of every artifact whose change could change the outcome | never empty; only identifiers from the inventory |
| `critical` | whether any failed repetition fails the suite | reserve for flows whose failure you would roll back a release for |

**Grounding** asserts on live data: the row names *where* a value sits in the
answer (a regular expression), and the check passes only when every value found
also appears in a tool result captured during the same run. Use it wherever the
real number changes daily — a forecast, an exchange rate — instead of asserting a
literal.

## Kinds

| Kind | The user… | Counts toward the floor as |
|---|---|---|
| `happy` | asks for something the domain serves, with everything it needs | happy |
| `missing-info` | leaves out something the tool requires (no city for a forecast) → the agent asks | named bad path |
| `out-of-territory` | asks for a neighbouring thing this domain does not serve → hand-off to the right domain or a refusal | named bad path |
| `upstream-failure` | asks normally while the upstream answers 5xx, times out or sends an oversized body | named bad path |
| `injection` | hides an instruction inside a real domain request | named bad path |
| `ambiguous` | asks something with two readings (a city name in two states) → the agent disambiguates | named bad path |
| `multi-turn` | relies on an earlier turn of the same conversation | the multi-turn slot of a stateful domain |
| `cross-domain` | combines two domains in one turn ("dollar rate and will it rain tomorrow") | neither; counts toward coverage of each domain |
| `out-of-scope` | asks for something no domain serves | neither |

The five **named bad paths** are the only bad paths that count toward the floor.
Choose from them rather than improvising: an improvised bad path is usually a
happy path with a typo.

## The coverage floor

Per domain: **≥ 2 happy**, **≥ 3 named bad paths** (three *different* kinds when
the domain allows it), and **1 multi-turn** row when the domain holds state. The
floor is a minimum for coverage, not a sample size for a gate — how many rows a
gate needs is `agentic-evals`'s question.

## Where each expectation comes from

The model may draft `turns`. Every other field under `expect` is read off an
artifact, and a reviewer should be able to point at the sentence it came from.

| Expectation | Read it off | Example |
|---|---|---|
| `outcome: ANSWERED` / refused / blocked | the scope rule of the system prompt or triage, and the guardrail that stops the turn | an injection row expects the outcome the injection guardrail produces, not "whatever the model did" |
| `toolsCalled` | the tool description's *when to use* sentence and the skill body's instruction | the forecast tool says it answers rain questions → a rain question calls it |
| activated skill | the skill description's trigger | — |
| argument constraints | the tool's parameter schema and descriptions | a date parameter documented as ISO-8601 |
| forbidden tools | the neighbouring tool's description that hands this request elsewhere | a place lookup must not call the forecast tool |
| call bounds | the skill body's sequence, or a documented budget | resolve the place once, then forecast |
| `language` | the system prompt's language rule | "answer in the user's language" + a Portuguese turn → `pt-BR` |
| `contains` | a fixed phrase an artifact promises (a refusal sentence, a hand-off name) | the out-of-territory row expects the domain it is handed to |
| `grounded` | the tool's result shape: which values the answer should quote from it | millimetres of rain, a currency rate |
| `rubric` | a behaviour an artifact promises that no regex can check | "tells the user the forecast is unavailable and invents no figure" |
| `critical` | the product decision, asked of the user | — |

If no artifact says what should happen, that is a finding, not a row to invent:
report it as a gap in the artifact and leave the row out.

## Complementing and adjusting

- **Load before you write.** Read every committed row first; plan additions
  against what is there.
- **Add beside, in stable order.** New rows go after the existing rows of their
  domain file.
- **Adjust as a proposal.** A row whose expectation an artifact now contradicts is
  shown as its own hunk with the contradicting sentence; its `id` does not change.
- **A failing row is diagnosed before it is edited.** Editing the row to match the
  new behaviour is right only when the behaviour change was intended; otherwise
  the row just caught a regression.
