# Strict Tool Use — Anthropic API

**Source:** https://docs.anthropic.com/en/docs/agents-and-tools/tool-use/strict-tool-use
**Category:** Structured Outputs (Anthropic)

## Summary

Anthropic's official documentation for strict tool use — a feature that sets `strict: true` on a tool definition to use grammar-constrained sampling, guaranteeing Claude's tool inputs match the provided JSON Schema exactly. The guide explains why strict mode matters for reliable agentic systems, how to enable it, how it compares to non-strict tool use, and its data retention and HIPAA eligibility characteristics.

## Content

Setting `strict: true` on a tool definition uses grammar-constrained sampling to guarantee Claude's tool inputs match your JSON Schema. This page covers why strict mode matters for agents, how to enable it, and common use cases.

For the supported JSON Schema subset, see JSON Schema limitations in the [Structured Outputs guide](./anthropic-structured-outputs.md). For non-strict schema guidance, see the tool use implementation guide.

Strict tool use validates tool parameters, ensuring Claude calls your functions with correctly-typed arguments.

**Use strict tool use when you need to:**
- Validate tool parameters
- Build agentic workflows
- Ensure type-safe function calls
- Handle complex tools with nested properties

---

## Why Strict Tool Use Matters for Agents

Building reliable agentic systems requires guaranteed schema conformance. Without strict mode, Claude might return incompatible types (`"2"` instead of `2`) or missing required fields, breaking your functions and causing runtime errors.

**Strict tool use guarantees type-safe parameters:**
- Functions receive correctly-typed arguments every time
- No need to validate and retry tool calls
- Production-ready agents that work consistently at scale

**Example:** Suppose a booking system needs `passengers: int`. Without strict mode, Claude might provide `passengers: "two"` or `passengers: "2"`. With `strict: true`, the response will always contain `passengers: 2`.

---

## Quick Start

**Response format:** Tool use blocks with validated inputs in `response.content[x].input`

**Guarantees:**
- Tool `input` strictly follows the `input_schema`
- Tool `name` is always valid (from provided tools or server tools)

---

## How It Works

1. **Define your tool schema** — Create a JSON schema for your tool's `input_schema`. The schema uses standard JSON Schema format with some limitations (see JSON Schema limitations).

2. **Add `strict: true`** — Set `"strict": true` as a top-level property in your tool definition, alongside `name`, `description`, and `input_schema`.

3. **Handle tool calls** — When Claude uses the tool, the `input` field in the tool_use block will strictly follow your `input_schema`, and the `name` will always be valid.

### Example Tool Definition with strict: true

```json
{
  "name": "book_flight",
  "description": "Book a flight for the given passenger count and route.",
  "strict": true,
  "input_schema": {
    "type": "object",
    "properties": {
      "origin": {
        "type": "string",
        "description": "IATA airport code for the departure airport"
      },
      "destination": {
        "type": "string",
        "description": "IATA airport code for the arrival airport"
      },
      "passengers": {
        "type": "integer",
        "description": "Number of passengers"
      }
    },
    "required": ["origin", "destination", "passengers"],
    "additionalProperties": false
  }
}
```

---

## Combining with tool_choice

Combine `tool_choice: {"type": "any"}` with strict tool use to guarantee both that one of your tools will be called AND that the tool inputs strictly follow your schema:

```python
response = client.messages.create(
    model="claude-opus-4-5",
    max_tokens=1024,
    tools=[
        {
            "name": "book_flight",
            "description": "...",
            "strict": True,
            "input_schema": { ... }
        }
    ],
    tool_choice={"type": "any"},
    messages=[{"role": "user", "content": "Book a flight from JFK to LAX for 2 passengers"}]
)
```

---

## Common Use Cases

- **Booking systems**: Guarantee integer passenger counts, valid enum seat classes, and required date fields.
- **Database queries**: Ensure filter parameters have correct types and required fields, preventing malformed queries.
- **API integrations**: Guarantee that function calls match the expected API contract exactly.
- **Multi-agent pipelines**: Ensure that agents in a pipeline receive correctly-typed inputs from upstream Claude calls.
- **Financial calculations**: Prevent string-typed amounts where integers or floats are required.

---

## Schema Complexity Limits (applies to strict tools)

The same complexity limits from structured outputs apply to strict tools. Per request:

| Limit | Value |
|-------|-------|
| Strict tools per request | **20** |
| Total optional parameters across all strict tool schemas | **24** |
| Total parameters with union types across all strict schemas | **16** |

---

## Data Retention

Strict tool use compiles tool `input_schema` definitions into grammars using the same pipeline as structured outputs. Tool schemas are **temporarily cached for up to 24 hours** since last use. Prompts and responses are not retained beyond the API response.

Strict tool use is **HIPAA eligible**, but **PHI must not be included in tool schema definitions**. The API caches compiled schemas separately from message content, and these cached schemas do not receive the same PHI protections as prompts and responses.

**Do not include PHI in:**
- `input_schema` property names
- `enum` values
- `const` values
- `pattern` regular expressions

PHI should only appear in message content (prompts and responses), where it is protected under HIPAA safeguards.

For ZDR and HIPAA eligibility across all features, see the API and data retention documentation.
