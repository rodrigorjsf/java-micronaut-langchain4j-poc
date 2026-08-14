# Implement Tool Use — Anthropic API

**Source:** https://docs.anthropic.com/en/docs/agents-and-tools/tool-use/implement-tool-use
**Category:** Structured Outputs (Anthropic)

## Summary

Anthropic's official guide on implementing tool use with Claude, covering model selection, tool definition best practices, the `input_examples` field for complex tools, controlling Claude's output via `tool_choice`, and guidance on model responses with tools. Directly relevant to structured outputs because strict tool use (`strict: true`) is configured at the tool definition level described here.

## Content

---

## Choosing a Model

- **Claude Opus 4.7**: Use for complex tools and ambiguous queries. Handles multiple tools better and seeks clarification when needed.
- **Claude Haiku models**: Use for straightforward tools. May infer missing parameters.

If using Claude with tool use and extended thinking, refer to the extended thinking guide for compatibility notes.

---

## Specifying Client Tools

Client tools (both Anthropic-schema and user-defined) are specified in the `tools` top-level parameter of the API request.

### Tool Definition Parameters

| Parameter | Description |
|-----------|-------------|
| `name` | The name of the tool. Must match `^[a-zA-Z0-9_-]{1,64}$` |
| `description` | A detailed plaintext description of what the tool does, when it should be used, and how it behaves |
| `input_schema` | A JSON Schema object defining the expected parameters for the tool |
| `input_examples` | (Optional) An array of example input objects to help Claude understand how to use the tool |

For the full set of optional properties including `cache_control`, `strict`, `defer_loading`, and `allowed_callers`, see the Tool reference documentation.

### Tool Use System Prompt

When you call the Claude API with the `tools` parameter, the API constructs a special system prompt from the tool definitions, tool configuration, and any user-specified system prompt. The constructed prompt instructs the model to use the specified tool(s) and provides the necessary context for the tool to operate properly.

---

## Best Practices for Tool Definitions

- **Provide extremely detailed descriptions.** This is by far the most important factor in tool performance. Your descriptions should explain:
  - What the tool does
  - When it should be used (and when it shouldn't)
  - What each parameter means and how it affects the tool's behavior
  - Any important caveats or limitations
  - Aim for at least 3–4 sentences per tool description; more if the tool is complex.

- **Consolidate related operations into fewer tools.** Rather than creating a separate tool for every action (`create_pr`, `update_pr`, `close_pr`), group them into a single tool with an `action` parameter. Fewer, more capable tools reduce selection ambiguity.

**Good vs. Poor Description Example:**

```python
# Good
{
    "name": "get_stock_price",
    "description": (
        "Retrieves the current stock price for a given ticker symbol. "
        "The ticker symbol must be a valid symbol for a publicly traded company on a major US stock exchange like NYSE or NASDAQ. "
        "The tool will return the closing price and is updated at the end of each trading day. "
        "It does not provide real-time prices or data for OTC markets."
    ),
    "input_schema": {
        "type": "object",
        "properties": {
            "ticker": {
                "type": "string",
                "description": "The stock ticker symbol, e.g. AAPL for Apple Inc."
            }
        },
        "required": ["ticker"]
    }
}

# Poor
{
    "name": "get_stock_price",
    "description": "Gets the stock price for a ticker.",
    "input_schema": {
        "type": "object",
        "properties": {
            "ticker": {"type": "string"}
        },
        "required": ["ticker"]
    }
}
```

---

## Providing Tool Use Examples

You can provide concrete examples of valid tool inputs via the `input_examples` field.

```python
{
    "name": "book_flight",
    "description": "Book a flight between two airports.",
    "input_schema": {
        "type": "object",
        "properties": {
            "origin": {"type": "string", "description": "IATA code, e.g. JFK"},
            "destination": {"type": "string", "description": "IATA code, e.g. LAX"},
            "date": {"type": "string", "description": "Date in YYYY-MM-DD format"},
            "passengers": {"type": "integer"}
        },
        "required": ["origin", "destination", "date", "passengers"]
    },
    "input_examples": [
        {"origin": "JFK", "destination": "LAX", "date": "2025-12-01", "passengers": 2},
        {"origin": "SFO", "destination": "ORD", "date": "2025-11-15", "passengers": 1}
    ]
}
```

### Requirements and Limitations

- **Schema validation**: Each example must be valid according to the tool's `input_schema`. Invalid examples return a `400` error.
- **Not supported for server-side tools**: Input examples work on user-defined and Anthropic-schema client tools only.
- **Token cost**: Examples add to prompt tokens — ~20–50 tokens for simple examples, ~100–200 tokens for complex nested objects.

---

## Controlling Claude's Output with tool_choice

### tool_choice Options

| Value | Behavior |
|-------|----------|
| `auto` | Claude decides whether to call any provided tools (default when tools are provided) |
| `any` | Claude must use one of the provided tools, but doesn't force a particular tool |
| `tool` | Forces Claude to always use a particular named tool |
| `none` | Prevents Claude from using any tools (default when no tools are provided) |

### Forcing Tool Use

```python
response = client.messages.create(
    model="claude-opus-4-5",
    max_tokens=1024,
    tools=[...],
    tool_choice={"type": "tool", "name": "get_stock_price"},
    messages=[{"role": "user", "content": "What is Apple's stock price?"}]
)
```

### Important Notes on tool_choice

- When `tool_choice` is `any` or `tool`, the API prefills the assistant message to force tool use — Claude will not emit a natural language explanation before `tool_use` content blocks, even if asked to do so.
- When using **extended thinking** with tool use, only `tool_choice: {"type": "auto"}` and `tool_choice: {"type": "none"}` are compatible. Using `any` or `tool` with extended thinking returns an error.
- **Claude Mythos Preview** does not support forced tool use. Use `tool_choice: {"type": "auto"}` and rely on prompting to influence tool selection.

### Combining tool_choice with Strict Tool Use

Combine `tool_choice: {"type": "any"}` with `strict: true` to guarantee both:
1. One of your tools will be called
2. The tool inputs strictly follow your schema

```python
response = client.messages.create(
    model="claude-sonnet-4-5",
    max_tokens=1024,
    tools=[{
        "name": "save_contact",
        "description": "Save a contact record.",
        "strict": True,
        "input_schema": {
            "type": "object",
            "properties": {
                "name": {"type": "string"},
                "email": {"type": "string"}
            },
            "required": ["name", "email"],
            "additionalProperties": False
        }
    }],
    tool_choice={"type": "any"},
    messages=[{"role": "user", "content": "Save Alice Smith, alice@example.com"}]
)
```

---

## Model Responses with Tools

When using tools, Claude will often comment on what it's doing or respond naturally to the user before invoking tools.

**Example response for "What's the weather in San Francisco right now, and what time is it there?":**

```
Claude might respond: "I'll check the current weather and time in San Francisco for you!"
[tool_use block: get_weather {"location": "San Francisco, CA"}]
[tool_use block: get_time {"location": "San Francisco, CA"}]
```

Your code should treat these responses like any other assistant-generated text and not rely on specific formatting conventions — Claude may use various phrasings when explaining its actions.

---

## Next Steps

- **Use meaningful namespacing in tool names.** When tools span multiple services, prefix names with the service (e.g., `github_list_prs`, `slack_send_message`). This is especially important when using tool search.
- **Design tool responses to return only high-signal information.** Return semantic, stable identifiers (e.g., slugs or UUIDs) rather than opaque internal references, and include only the fields Claude needs to reason about its next step.
- **Enable strict tool use** for production agentic workflows by adding `"strict": true` to your tool definitions. See [Strict Tool Use](./anthropic-strict-tool-use.md) for details.
