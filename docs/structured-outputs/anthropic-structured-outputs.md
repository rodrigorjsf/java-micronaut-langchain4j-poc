# Structured Outputs — Anthropic API

**Source:** https://docs.anthropic.com/en/docs/build-with-claude/structured-outputs
**Category:** Structured Outputs (Anthropic)

## Summary

The official Anthropic documentation for Structured Outputs, covering how to constrain Claude's responses to follow a specific JSON schema via constrained (grammar-based) decoding. The feature provides two complementary mechanisms: JSON outputs (`output_config.format`) for controlling Claude's response format, and strict tool use (`strict: true`) for guaranteeing schema validation on tool names and inputs. Covers supported models, SDK helpers, schema limitations, complexity limits, data retention, and feature compatibility.

## Content

Structured outputs constrain Claude's responses to follow a specific schema, ensuring valid, parseable output for downstream processing. Structured outputs provide two complementary features:

- **JSON outputs** (`output_config.format`): Get Claude's response in a specific JSON format
- **Strict tool use** (`strict: true`): Guarantee schema validation on tool names and inputs

You can use these features independently or together in the same request.

### Supported Models

Structured outputs are **generally available** on the Claude API for:
- Claude Mythos Preview
- Claude Opus 4.7
- Claude Opus 4.6
- Claude Sonnet 4.6
- Claude Sonnet 4.5
- Claude Opus 4.5
- Claude Haiku 4.5

On **Amazon Bedrock**: generally available for Claude Opus 4.6, Sonnet 4.6, Sonnet 4.5, Opus 4.5, and Haiku 4.5. Claude Opus 4.7 and Mythos Preview are available through the Messages-API Bedrock endpoint.

**In beta** on Microsoft Foundry. **Not supported** on Google Cloud's Vertex AI for Claude Mythos Preview.

This feature qualifies for **Zero Data Retention (ZDR)** with limited technical retention.

> **Migrating from beta?** The `output_format` parameter has moved to `output_config.format`, and beta headers are no longer required. The old beta header (`structured-outputs-2025-11-13`) and `output_format` parameter will continue working for a transition period.

---

## Why Use Structured Outputs

Without structured outputs, Claude can generate malformed JSON responses or invalid tool inputs that break your applications. Even with careful prompting, you may encounter:

- Parsing errors from invalid JSON syntax
- Missing required fields
- Inconsistent data types
- Schema violations requiring error handling and retries

Structured outputs guarantee schema-compliant responses through **constrained decoding**:

- **Always valid**: No more `JSON.parse()` errors
- **Type safe**: Guaranteed field types and required fields
- **Reliable**: No retries needed for schema violations

---

## JSON Outputs

JSON outputs control Claude's response format, ensuring Claude returns valid JSON matching your schema.

**Use JSON outputs when you need to:**
- Control Claude's response format
- Extract data from images or text
- Generate structured reports
- Format API responses

**Response format:** Valid JSON matching your schema in `response.content[0].text`

### How It Works

1. **Define your JSON schema** — Create a JSON schema that describes the structure you want Claude to follow. The schema uses standard JSON Schema format with some limitations (see JSON Schema limitations below).
2. **Add the `output_config.format` parameter** — Include `output_config.format` in your API request with `type: "json_schema"` and your schema definition.
3. **Parse the response** — Claude's response is valid JSON matching your schema, returned in `response.content[0].text`.

### Working with JSON Outputs in SDKs

The SDKs provide helpers that make it easier to work with JSON outputs, including schema transformation, automatic validation, and integration with popular schema libraries.

> The Python SDK's `client.messages.parse()` still accepts `output_format` as a convenience parameter and translates it to `output_config.format` internally. Other SDKs require `output_config` directly.

#### Using Native Schema Definitions

Instead of writing raw JSON schemas, use familiar schema definition tools in your language:

| Language | Tool |
|----------|------|
| **Python** | [Pydantic](https://docs.pydantic.dev/) models with `client.messages.parse()` |
| **TypeScript** | [Zod](https://zod.dev/) schemas with `zodOutputFormat()` or typed JSON Schema literals with `jsonSchemaOutputFormat()` |
| **Java** | Plain Java classes with automatic schema derivation via `outputConfig(Class<T>)` |
| **Ruby** | `Anthropic::BaseModel` classes with `output_config: {format: Model}` |
| **PHP** | Classes implementing `StructuredOutputModel` with `outputConfig: ['format' => MyClass::class]` |
| **CLI, C#, Go** | Raw JSON schemas passed via `output_config` |

#### How SDK Transformation Works

The Python, TypeScript, Ruby, and PHP SDKs automatically transform schemas with unsupported features:

1. **Remove unsupported constraints** (e.g., `minimum`, `maximum`, `minLength`, `maxLength`)
2. **Update descriptions** with constraint info (e.g., "Must be at least 100")
3. **Add `additionalProperties: false`** to all objects
4. **Filter string formats** to supported list only
5. **Validate responses** against your original schema (with all constraints)

This means Claude receives a simplified schema, but your code still enforces all constraints through validation.

**Example:** A Pydantic field with `minimum: 100` becomes a plain integer in the sent schema, but the SDK updates the description to "Must be at least 100" and validates the response against the original constraint.

---

## Strict Tool Use

For enforcing JSON Schema compliance on tool inputs with grammar-constrained sampling, see the [Strict Tool Use](./anthropic-strict-tool-use.md) guide.

---

## Using Both Features Together

JSON outputs and strict tool use solve different problems and work together:

- **JSON outputs** control Claude's response format (what Claude says)
- **Strict tool use** validates tool parameters (how Claude calls your functions)

When combined, Claude can call tools with guaranteed-valid parameters AND return structured JSON responses. This is useful for agentic workflows where you need both reliable tool calls and structured final outputs.

---

## Important Considerations

### Grammar Compilation and Caching

Structured outputs use constrained sampling with compiled grammar artifacts:

- **First request latency:** The first time you use a specific schema, there is additional latency while the grammar compiles.
- **Automatic caching:** Compiled grammars are cached for **24 hours** from last use, making subsequent requests much faster.
- **Cache invalidation:** The cache is invalidated if you change:
  - The JSON schema structure
  - The set of tools in your request (when using both structured outputs and tool use)
  - Changing only `name` or `description` fields does **not** invalidate the cache.

### Prompt Modification and Token Costs

When using structured outputs, Claude automatically receives an additional system prompt explaining the expected output format:

- Your input token count is slightly higher
- The injected prompt costs tokens like any other system prompt
- Changing `output_config.format` will invalidate any prompt cache for that conversation thread

### JSON Schema Limitations

Structured outputs support standard JSON Schema with some limitations. Both JSON outputs and strict tool use share these limitations. The Python, TypeScript, Ruby, and PHP SDKs can automatically transform schemas with unsupported features.

### Property Ordering

When using structured outputs, **required properties appear first, followed by optional properties** (within each group, schema order is preserved).

**Example schema properties:** `name` (required), `email` (required), `notes` (optional), `age` (optional)

**Output order:** `name`, `email`, `notes`, `age`

If property order matters to your application, mark all properties as required, or account for this reordering in your parsing logic.

### Invalid Outputs

While structured outputs guarantee schema compliance in most cases, there are edge scenarios:

**Refusals** (`stop_reason: "refusal"`)
- Claude maintains its safety properties even with structured outputs
- If Claude refuses for safety reasons: response has `stop_reason: "refusal"`, you receive a 200 status, you are billed for tokens generated, and output may not match your schema

**Token limit reached** (`stop_reason: "max_tokens"`)
- The output may be incomplete and not match your schema
- Retry with a higher `max_tokens` value

---

## Schema Complexity Limits

Structured outputs compile JSON schemas into grammars. More complex schemas produce larger grammars that take longer to compile.

### Explicit Limits

| Limit | Value | Description |
|-------|-------|-------------|
| Strict tools per request | **20** | Maximum number of tools with `strict: true`. Non-strict tools don't count. |
| Optional parameters | **24** | Total optional parameters across all strict tool schemas and JSON output schemas. Each parameter not listed in `required` counts. |
| Parameters with union types | **16** | Total parameters using `anyOf` or type arrays (e.g., `"type": ["string", "null"]`) across all strict schemas. These create exponential compilation cost. |

> These limits apply to the **combined total** across all strict schemas in a single request.

### Additional Internal Limits

Beyond explicit limits, there are internal limits on compiled grammar size. When exceeded, you'll receive a `400` error: `"Schema is too complex for compilation"`. A **compilation timeout of 180 seconds** also applies as a final stop-gap.

### Tips for Reducing Schema Complexity

1. **Mark only critical tools as strict.** Reserve it for tools where schema violations cause real problems.
2. **Reduce optional parameters.** Each optional parameter roughly doubles a portion of the grammar's state space.
3. **Simplify nested structures.** Deeply nested objects with optional fields compound complexity. Flatten where possible.
4. **Split into multiple requests.** If you have many strict tools, consider splitting them across separate requests or sub-agents.

---

## Data Retention

Prompts and responses are processed with ZDR when using structured outputs. However, the **JSON schema itself is temporarily cached for up to 24 hours** since last use for optimization purposes. No prompt or response data is retained beyond the API response.

Structured outputs are **HIPAA eligible**, but **PHI must not be included in JSON schema definitions**. Do not include PHI in schema property names, `enum` values, `const` values, or `pattern` regular expressions. PHI should only appear in message content (prompts and responses).

---

## Feature Compatibility

### Works With

- **Batch processing**: Process structured outputs at scale with 50% discount
- **Token counting**: Count tokens without compilation
- **Streaming**: Stream structured outputs like normal responses
- **Combined usage**: Use JSON outputs (`output_config.format`) and strict tool use (`strict: true`) together in the same request

### Incompatible With

- **Citations**: Citations require interleaving citation blocks with text, which conflicts with strict JSON schema constraints. Returns `400` error if citations enabled with `output_config.format`.
- **Message Prefilling**: Incompatible with JSON outputs.

### Grammar Scope

Grammars apply only to Claude's direct output, **not** to tool use calls, tool results, or thinking tags (when using Extended Thinking). Grammar state resets between sections, allowing Claude to think freely while still producing structured output in the final response.
