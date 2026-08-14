# MCP: Programmatic Tool Calling (Code Mode) with OpenSandbox — DEV Community

**Source:** https://dev.to/thangchung/mcp-programmatic-tool-calling-code-mode-with-opensandbox-4n3n
**Category:** Programmatic Tool Calling

## Summary

A deep technical exploration of MCP's Code Mode pattern implemented in .NET/C#, using OpenSandbox and a local Python sandbox. The article shows how `search`, `get_schema`, and `execute` tools replace pre-loaded JSON schemas, cutting token overhead by up to 85%. Generated Python code runs in an isolated sandbox (OpenSandbox or a local Python process), solving context bloat in enterprise environments with hundreds of APIs.

## Content

## Introduction

[Model Context Protocol](https://modelcontextprotocol.io/docs/getting-started/intro) (MCP) enables AI agents to access external systems they cannot reach by default — authenticated APIs, CI/CD pipelines, live process streams, and IDE integrations. It acts as a structured bridge between the model and real-world environments.

However, MCP does not automatically make interactions efficient or intelligent. Traditional MCP implementations often inject large JSON payloads into the model context, which increases token consumption and reduces efficiency.

In many software products, having hundreds of APIs is normal. With a traditional MCP approach, all tool definitions are loaded into the model's context upfront, inflating the context window with large OpenAPI schemas even when most tools are irrelevant to the current request.

### The Scale of the Problem

Statistics from Anthropic illustrate the scale under the current MCP pattern. Consider a five-server setup:

| Server | Tools | Tokens |
|--------|-------|--------|
| GitHub | 35 tools | ~26K tokens |
| Slack | 11 tools | ~21K tokens |
| Sentry | 5 tools | ~3K tokens |
| Grafana | 5 tools | ~3K tokens |
| Splunk | 2 tools | ~2K tokens |

That's 58 tools consuming approximately **55K tokens** before the conversation even starts. Add Jira (which alone uses ~17K tokens) and you're quickly approaching **100K+ token overhead**. Anthropic has seen tool definitions consume **134K tokens** before optimization.

**This means that before any meaningful task begins, thousands of tokens may already be consumed** simply to describe available tools.

---

## The Solution: Tool Search + Code Mode

To address this inefficiency, Anthropic introduced two optimization techniques:

1. **Tool Search Tool** — avoid preloading every tool definition by discovering them on demand
2. **Programmatic Tool Calling (Code Mode)** — execute tools only when required via code generation

This article focuses specifically on **Code Mode** with both a local sandbox and OpenSandbox.

---

## Code Mode with OpenSandbox and Local Sandbox Runners

At a high level, the flow operates as follows:

1. At application startup, all available OpenAPI specifications are discovered and loaded into a tool registry
2. When a request arrives, the system performs a `search` over registered OpenAPI endpoint metadata
3. The LLM inspects the selected tool's schema via `get_schema`, understanding available operations, parameters, and data structures
4. Using this schema, the LLM generates Python code that correctly invokes the chosen endpoint
5. The generated code is sent to a sandbox environment (local or OpenSandbox) via `execute`
6. The sandbox executes the code, issues outbound HTTP requests to the target system
7. After execution, the sandbox returns the raw result to the host
8. The LLM analyzes this output and converts it into a structured response for the end user

### Why a Sandbox?

A sandbox is required because the Python script is generated dynamically by the LLM. Executing model-generated code directly in the host environment introduces security risks:
- Arbitrary file access
- Network misuse
- Privilege escalation
- System compromise

A sandbox isolates execution, enforces resource limits, controls outbound network access, and restricts filesystem and process permissions.

### About OpenSandbox

OpenSandbox (by Alibaba) is a general-purpose sandbox platform for AI applications:
- Multi-language SDKs
- Unified sandbox APIs
- Docker/Kubernetes runtimes
- Scenarios: Coding Agents, GUI Agents, Agent Evaluation, AI Code Execution, RL Training
- Listed in the [CNCF Landscape](https://landscape.cncf.io/?item=orchestration-management--scheduling-orchestration--opensandbox)

---

## The Three Core MCP Tools

### 1. `search` — Discover Tools by Query

```csharp
[McpServerTool(Name = "search"), Description("Discover tools by query with optional detail, tag filter, and limit. Use before get_schema and execute in a multi-step code-mode workflow.")]
public static DiscoverySearchResponse Search(
  [FromServices] DiscoveryTools discoveryTools,
  [FromServices] UserContext context,
  [Description("Search query for tool discovery")] string query,
  [Description("Detail level: Brief (default), Detailed, or Full. Also accepts 0, 1, or 2.")] JsonElement? detail = null,
  [Description("Optional tag filters.")] IReadOnlyList<string>? tags = null,
  [Description("Optional maximum results.")] int? limit = null)
{
    SchemaDetailLevel resolvedDetail = detail.HasValue 
        ? ParseSchemaDetailLevel(detail.Value, SchemaDetailLevel.Brief) 
        : SchemaDetailLevel.Brief;
    return discoveryTools.Search(query, context, resolvedDetail, tags, limit);
}
```

### 2. `get_schema` — Retrieve Tool Input Schemas

```csharp
[McpServerTool(Name = "get_schema"), Description("Retrieve input schemas for tool names. Pass a list via toolNames or a single name via name. Default detail is Detailed markdown.")]
public static SchemaLookupResponse GetSchema(
  [FromServices] DiscoveryTools discoveryTools,
  [FromServices] UserContext context,
  [Description("List of tool names to retrieve schemas for")] IReadOnlyList<string>? toolNames = null,
  [Description("Single tool name shorthand")] string? name = null,
  [Description("Schema verbosity: Brief, Detailed, Full. Also accepts 0, 1, or 2.")] JsonElement? detail = null)
{
  IReadOnlyList<string> requestedToolNames = ResolveSchemaToolNames(toolNames, name);
  SchemaDetailLevel resolvedDetail = detail.HasValue 
      ? ParseSchemaDetailLevel(detail.Value, SchemaDetailLevel.Detailed) 
      : SchemaDetailLevel.Detailed;
  return discoveryTools.GetSchema(requestedToolNames, context, resolvedDetail);
}
```

### 3. `execute` — Run Generated Code in Sandbox

```csharp
[McpServerTool(Name = "execute"), Description("Execute constrained code and return the final result. IMPORTANT: call search, then get_schema, then get_execute_syntax before Execute.")]
public static async Task<object?> Execute(
  [Description("Code string written in the syntax returned by get_execute_syntax.")] string code,
  [FromServices] ExecuteTool executeTool,
  CancellationToken ct)
{
    try
    {
       ExecuteResponse response = await executeTool.ExecuteAsync(code, ct);
       return response.FinalValue;
    }
    catch (Exception ex) when (ex is not OperationCanceledException)
    {
       return $"[Execute error] {ex.Message}";
    }
}
```

---

## Sandbox Interface

```csharp
public interface ISandboxRunner
{
    string SyntaxGuide { get; }
    Task<RunnerResult> RunAsync(string code, CancellationToken ct);
}
```

### Local Sandbox Implementation

The local sandbox executes Python code in an isolated subprocess:

```csharp
public sealed class LocalConstrainedRunner : ISandboxRunner
{
    public string SyntaxGuide =>
        """
        Runner: local (Python)
        Write pure Python code.
        A lightweight `requests`-compatible shim is available for basic HTTP requests.
        Prefer assigning the final value to `result`.
        Use injected `BASE_URL` variable for API calls.
        Only call API URLs under configured OpenAPI bases.
        Do NOT use: SearchTools, CallTool, Search, GetSchema, or Execute in this code.
        
        Example:
            import requests
            response = requests.get(f"{BASE_URL}/pet/findByStatus", params={"status": "sold"}, timeout=10)
            response.raise_for_status()
            result = response.json()
        """;

    public async Task<RunnerResult> RunAsync(string code, CancellationToken ct)
    {
        // Executes Python in subprocess with:
        // - Base64-encoded user code to prevent injection
        // - Injected `requests` shim (no external dependencies)
        // - Injected BASE_URL from OpenAPI config
        // - stdout/stderr capture
        // - JSON payload extraction from last line of output
        var finalValue = await ExecutePythonLocallyAsync(code, ct);
        return new RunnerResult(finalValue, 0);
    }
}
```

The local runner wraps user code in a Python harness that:
- Decodes base64-encoded LLM-generated code
- Provides a `requests`-compatible HTTP shim
- Captures `result` variable or stdout as the final value
- Returns a JSON payload with `ok`, `finalValue`, `stdout`, `stderr`

---

## Code Mode Flow

```
Application Startup:
  Load OpenAPI specs → Tool Registry

Request arrives:
  1. search("relevant capability")
     → Returns: list of matching tool names + brief descriptions

  2. get_schema(toolName)
     → Returns: full parameter schema in markdown

  3. LLM generates Python code using the schema

  4. execute(pythonCode)
     → Sandbox runs code
     → Issues HTTP request to target API
     → Returns raw result

  5. LLM structures result for user
```

---

## Benefits of Code Mode

| Benefit | Detail |
|---------|--------|
| **Token reduction** | 50%+ cut in context tokens in real-world workflows |
| **Less latency** | Batch calls and parallel processing in a single script reduce round-trips |
| **Security** | All code executes in an isolated sandbox — no host system risk |
| **Enterprise scale** | Works with hundreds of APIs without context explosion |
| **Flexibility** | Supports local Python subprocess or OpenSandbox (Docker/K8s) |

---

## Related DEV.to Articles

- [Cutting MCP Tool-Call Token Costs by 50%+ with Code Mode](https://dev.to/kuldeep_paul/cutting-mcp-tool-call-token-costs-by-50-with-code-mode-4cd)
- [Classic MCP vs Code Mode: How the Two Patterns Stack Up](https://dev.to/kuldeep_paul/classic-mcp-vs-code-mode-how-the-two-patterns-stack-up-2fdo)
- [Code Mode: Batching MCP Tool Calls in a WASM Sandbox](https://dev.to/chrisremo85/code-mode-batching-mcp-tool-calls-in-a-wasm-sandbox-to-cut-llm-token-usage-by-30-80-18g7)

---

## Implementation Notes

- OpenAPI specifications are loaded into application memory at startup and adapted as MCP tools
- The article uses [OpenBreweryDB](https://www.openbrewerydb.org/documentation) and [Petstore3](https://petstore3.swagger.io) as example APIs
- The .NET/C# implementation uses `McpServerTool` attributes for tool registration
- The `ISandboxRunner` abstraction supports both local Python execution and OpenSandbox
- URL allowlisting prevents the LLM from calling arbitrary external services
