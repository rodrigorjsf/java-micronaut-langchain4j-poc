# Programmatic Tool Calling - Claude API Docs

**Source:** https://docs.anthropic.com/en/docs/build-with-claude/tool-use
**Category:** Programmatic Tool Calling

## Summary

Claude's tool use system lets it call functions you define (client tools) or that Anthropic provides (server tools). Client tools run in your application with a request/response loop, while server tools like `web_search`, `code_execution`, `web_fetch`, and `tool_search` run on Anthropic's infrastructure. Adding tools produces outsized capability gains on benchmarks like SWE-bench and LAB-Bench.

## Content

### How Tool Use Works

Tools differ primarily by where the code executes.

**Client tools** (including user-defined tools and Anthropic-schema tools like bash and text_editor) run in your application:
1. Claude responds with `stop_reason: "tool_use"` and one or more `tool_use` blocks
2. Your code executes the operation
3. You send back a `tool_result`

**Server tools** (`web_search`, `code_execution`, `web_fetch`, `tool_search`) run on Anthropic's infrastructure — you see the results directly without handling execution.

### Tool Search (Programmatic Tool Calling)

Tool Search is a server-side tool that enables dynamic tool discovery. Instead of pre-loading all tool definitions, the model:
1. Uses a lightweight `tool_search` stub to discover relevant tools on demand
2. Dynamically loads full tool definitions just-in-time
3. Dramatically reduces context token overhead

This is the core mechanism behind Anthropic's programmatic/advanced tool use pattern.

### Strict Tool Use

Add `strict: true` to tool definitions to ensure Claude's tool calls always match your schema exactly.

### Pricing

Tool use requests are priced based on:
1. Total input tokens (including the `tools` parameter)
2. Output tokens generated
3. For server-side tools, additional usage-based pricing (e.g., web search charges per search performed)

Additional tokens from tool use come from:
- The `tools` parameter in API requests (tool names, descriptions, and schemas)
- `tool_use` content blocks in API requests and responses
- `tool_result` content blocks in API requests

A special system prompt is automatically included when tools are used. Token counts per model:

| Model | Tool choice | System prompt tokens |
|-------|-------------|----------------------|
| Claude Opus 4+ | `auto`, `none` | 346 tokens |
| Claude Opus 4+ | `any`, `tool` | 313 tokens |
| Claude Haiku 3.5 | `auto`, `none` | 264 tokens |
| Claude Haiku 3.5 | `any`, `tool` | 340 tokens |
| Claude Sonnet 3 | `auto`, `none` | 159 tokens |
| Claude Sonnet 3 | `any`, `tool` | 235 tokens |

### Key Capabilities

- **Tool access** is one of the highest-leverage primitives for agents
- On **LAB-Bench FigQA** (scientific figure interpretation) and **SWE-bench** (real-world software engineering), adding even basic tools produces outsized capability gains — often surpassing human expert baselines
- **MCP connector** available for connecting to MCP servers
- **Programmatic tool calling** via `code_execution` and `tool_search` server tools reduces multi-round-trip overhead

### Architecture: Agentic Loop

```
User Request
    ↓
LLM with tools defined
    ↓
Claude returns tool_use block (stop_reason: "tool_use")
    ↓
Application executes tool (client) OR Anthropic executes (server)
    ↓
tool_result sent back in next message
    ↓
Claude responds with final answer (stop_reason: "end_turn")
```

### Advanced Programmatic Tool Use

Anthropic's advanced tool use (announced November 2025) introduced the `code_execution` pattern where instead of N round-trips:
- LLM generates executable code that orchestrates multiple tool calls in one pass
- Code runs in a sandboxed environment
- Only the final result is returned to the LLM context
- Eliminates intermediate result pollution of the context window

This reduces LLM round-trips from N (one per tool) to 2 (fixed), with 80%+ token savings on complex workflows.

### Related Resources

- [How tool use works](https://docs.anthropic.com/en/agents-and-tools/tool-use/how-tool-use-works)
- [Define tools](https://docs.anthropic.com/en/agents-and-tools/tool-use/define-tools)
- [Handle tool calls](https://docs.anthropic.com/en/agents-and-tools/tool-use/handle-tool-calls)
- [Strict tool use](https://docs.anthropic.com/en/agents-and-tools/tool-use/strict-tool-use)
- [MCP connector](https://docs.anthropic.com/en/agents-and-tools/mcp-connector)
- [Tool use tutorial](https://docs.anthropic.com/en/agents-and-tools/tool-use/build-a-tool-using-agent)
