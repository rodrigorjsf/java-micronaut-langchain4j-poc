# Programmatic Tool Calling — Research Index

This directory contains research documents on Programmatic Tool Calling in AI agents, covering architecture patterns, implementations, and optimization techniques.

## Documents

| File | Source | Description |
|------|--------|-------------|
| [programmatic-tool-calling-claude-api.md](./programmatic-tool-calling-claude-api.md) | Anthropic Docs | Official Claude API docs on tool use, including client vs server tools, the agentic loop, strict tool use, pricing, and the programmatic/advanced tool use pattern with `code_execution` and `tool_search`. |
| [tool-search-redefining-agent-tool-calling-epsilla.md](./tool-search-redefining-agent-tool-calling-epsilla.md) | Epsilla Insights | In-depth analysis of Tool Search as the most significant AI agent architectural shift for 2025-2026. Covers just-in-time retrieval, token savings of 85%+, accuracy improvements (49%→74% for Claude Opus 4), and comparison of OpenAI/Anthropic/Spring AI implementations. |
| [cameronking4-programmatic-tool-calling-github.md](./cameronking4-programmatic-tool-calling-github.md) | GitHub (cameronking4) | Open-source POC implementing PTC on Vercel AI SDK 5.0 + Next.js 16. Shows the code-generation-then-sandbox-execution pattern, achieving 80% token savings and 3-5x latency improvement. Includes architecture diagrams, cost analysis, and usage examples. |
| [mcp-programmatic-tool-calling-opensandbox-dev.md](./mcp-programmatic-tool-calling-opensandbox-dev.md) | DEV Community (thangchung) | .NET/C# implementation of MCP Code Mode using OpenSandbox and a local Python sandbox. Details the three-tool pattern (`search`, `get_schema`, `execute`), sandbox security rationale, and a complete code walkthrough. |

## Key Concepts

### Programmatic Tool Calling (PTC)

Instead of N sequential LLM round-trips (one per tool call), PTC:
1. Has the LLM generate **executable code** (JavaScript or Python) that orchestrates all tool calls
2. Runs that code in an **isolated sandbox** (Vercel Sandbox, OpenSandbox, or local Python subprocess)
3. Returns **only the final result** to the LLM context

**Result:** 2 fixed LLM calls instead of N, with 60-90% token savings.

### Tool Search

Instead of pre-loading all tool schemas upfront, Tool Search:
1. Gives the model a single lightweight `tool_search` stub (~500 tokens)
2. Model calls `tool_search("query")` to discover relevant tools on demand
3. Full schema injected just-in-time, only for needed tools

**Result:** 98% reduction in tool-definition token overhead for large tool sets.

### The Context Bloat Problem

A typical 5-server MCP setup consumes **55K-134K tokens** before any conversation starts:
- GitHub: 35 tools → ~26K tokens
- Slack: 11 tools → ~21K tokens
- Jira: 20 tools → ~17K tokens
- Sentry: 5 tools → ~3K tokens

These techniques (PTC + Tool Search) solve context bloat at enterprise scale.

## Implementations Referenced

| Platform | Tool | Notes |
|----------|------|-------|
| Anthropic Claude | `tool_search` server tool | Available in Claude Sonnet 4+; `code_execution` for sandboxed PTC |
| OpenAI GPT-5.4 | `tool_search` | Namespace-level tool search |
| Spring AI | `ToolSearchToolCallAdvisor` | Cross-platform Java/Kotlin implementation |
| Vercel AI SDK | `withProgrammaticCalling()` | Universal wrapper for any LLM provider |
| .NET/C# MCP | `search` + `get_schema` + `execute` | OpenSandbox or local Python runner |
