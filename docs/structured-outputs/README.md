# Structured Outputs (Anthropic) — Research Index

**Category:** Structured Outputs — Anthropic Claude API
**Sources:** docs.anthropic.com, platform.claude.com

This directory contains research documents about Anthropic Claude's Structured Outputs feature — the mechanism that constrains Claude's responses to follow a specific JSON schema via grammar-based (constrained) decoding, enabling reliable, type-safe AI integration.

---

## Documents

| File | Source | Description |
|------|--------|-------------|
| [anthropic-structured-outputs.md](./anthropic-structured-outputs.md) | docs.anthropic.com | **Primary reference.** Full Anthropic Structured Outputs guide covering JSON outputs (`output_config.format`), strict tool use, supported models, SDK helpers (Pydantic/Zod), schema complexity limits, grammar caching, data retention, HIPAA eligibility, and feature compatibility. |
| [anthropic-strict-tool-use.md](./anthropic-strict-tool-use.md) | docs.anthropic.com | Detailed guide for `strict: true` on tool definitions — grammar-constrained sampling that guarantees Claude's tool inputs match the provided JSON Schema exactly. Includes why it matters for agents, how to enable it, and HIPAA/ZDR notes. |
| [anthropic-implement-tool-use.md](./anthropic-implement-tool-use.md) | docs.anthropic.com | Tool use implementation guide: model selection, tool definition best practices, `input_examples` field, `tool_choice` options, and combining `tool_choice: any` with `strict: true` for guaranteed schema-valid tool calls. |
| [anthropic-increase-consistency.md](./anthropic-increase-consistency.md) | docs.anthropic.com | Guardrails guide for general output consistency via prompt engineering — format specification, response prefilling, few-shot examples, retrieval, and prompt chaining. Also clarifies when to use Structured Outputs vs. these techniques. |

---

## Key Concepts

### Two Complementary Features

| Feature | Parameter | What it controls |
|---------|-----------|-----------------|
| **JSON outputs** | `output_config.format` | Claude's **response format** (what Claude says) |
| **Strict tool use** | `strict: true` on tool definition | Claude's **tool inputs** (how Claude calls your functions) |

Both use grammar-constrained (CFG) decoding and can be combined in the same request.

### How It Works

Structured outputs compile your JSON schemas into a **Context-Free Grammar (CFG)** that constrains which tokens Claude can generate. This means schema violations are impossible at the token generation level — not just discouraged by prompting.

### Supported Models (Claude API)

- Claude Mythos Preview, Claude Opus 4.7, Claude Opus 4.6
- Claude Sonnet 4.6, Claude Sonnet 4.5, Claude Opus 4.5, Claude Haiku 4.5
- Available on Amazon Bedrock (except Mythos Preview on Vertex AI)
- In beta on Microsoft Foundry

### Schema Complexity Limits (per request)

| Limit | Value |
|-------|-------|
| Strict tools per request | 20 |
| Total optional parameters | 24 |
| Parameters with union types (`anyOf`) | 16 |

### Performance Characteristics

- **First request**: Additional latency for grammar compilation
- **Subsequent requests**: Compiled grammars cached for **24 hours** from last use
- Cache is invalidated by schema structure changes (not `name`/`description`-only changes)

---

## Quick Reference

### JSON Outputs (Python / Pydantic)

```python
import anthropic
from pydantic import BaseModel

class ContactInfo(BaseModel):
    name: str
    email: str

client = anthropic.Anthropic()
response = client.messages.parse(
    model="claude-sonnet-4-5",
    max_tokens=1024,
    messages=[{"role": "user", "content": "Extract: Alice Smith, alice@example.com"}],
    output_format=ContactInfo,  # SDK translates to output_config.format internally
)
result = response.content[0].parsed  # typed as ContactInfo
```

### JSON Outputs (TypeScript / Zod)

```typescript
import Anthropic from "@anthropic-ai/sdk";
import { z } from "zod";
import { zodOutputFormat } from "@anthropic-ai/sdk/helpers/zod";

const ContactInfo = z.object({
  name: z.string(),
  email: z.string(),
});

const client = new Anthropic();
const response = await client.messages.create({
  model: "claude-sonnet-4-5",
  max_tokens: 1024,
  messages: [{ role: "user", content: "Extract: Alice Smith, alice@example.com" }],
  output_config: zodOutputFormat(ContactInfo, "contact_info"),
});
```

### Strict Tool Use

```python
tools = [{
    "name": "save_contact",
    "description": "Save a contact record.",
    "strict": True,                    # enables grammar-constrained sampling
    "input_schema": {
        "type": "object",
        "properties": {
            "name": {"type": "string"},
            "email": {"type": "string"}
        },
        "required": ["name", "email"],
        "additionalProperties": False
    }
}]

response = client.messages.create(
    model="claude-sonnet-4-5",
    max_tokens=1024,
    tools=tools,
    tool_choice={"type": "any"},       # guarantees a tool is called
    messages=[{"role": "user", "content": "Save Alice Smith, alice@example.com"}]
)
```

### Raw JSON Schema via output_config

```python
response = client.messages.create(
    model="claude-sonnet-4-5",
    max_tokens=1024,
    messages=[{"role": "user", "content": "..."}],
    output_config={
        "format": {
            "type": "json_schema",
            "json_schema": {
                "name": "contact_info",
                "schema": {
                    "type": "object",
                    "properties": {
                        "name": {"type": "string"},
                        "email": {"type": "string"}
                    },
                    "required": ["name", "email"],
                    "additionalProperties": False
                }
            }
        }
    }
)
result_json = response.content[0].text  # valid JSON string
```

---

## Feature Compatibility

| Feature | Compatible |
|---------|-----------|
| Batch processing | ✅ (50% discount) |
| Token counting | ✅ |
| Streaming | ✅ |
| Strict tool use + JSON outputs together | ✅ |
| Citations | ❌ (returns 400) |
| Message prefilling | ❌ |
| Extended Thinking (grammar scope) | ⚠️ Grammars apply to final output only, not thinking tags |
