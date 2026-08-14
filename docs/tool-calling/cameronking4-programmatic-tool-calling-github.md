# cameronking4/programmatic-tool-calling-ai-sdk — GitHub

**Source:** https://github.com/cameronking4/programmatic-tool-calling-ai-sdk
**Category:** Programmatic Tool Calling

## Summary

A proof-of-concept implementation of Programmatic Tool Calling (PTC) built on Vercel AI SDK 5.0, Next.js 16, and Vercel Sandbox. PTC replaces traditional multi-round-trip tool calling with a single code-generation + sandboxed-execution step, achieving 80%+ token savings, 3-5x latency improvements, and support for 100+ LLM providers via the AI Gateway. Inspired by Anthropic's advanced tool use pattern announced November 2025.

## Content

## A Universal LLM Optimization POC for Any Model

**Programmatic Tool Calling** is a novel approach to dramatically reduce LLM inference costs and latency by replacing traditional multi-round-trip tool calling with code generation and sandboxed execution.

> 💡 Inspired by [Anthropic's beta capabilities](https://www.anthropic.com/engineering/advanced-tool-use) announced November 2025. This project extends that paradigm to work with **any LLM** through the Vercel AI SDK, including 100+ models via the AI Gateway.

---

## 🎯 The Problem

Traditional LLM tool calling is **inherently inefficient**, especially with MCP:

```
User: "Get data for users 1-5 and find the highest scorer"

Traditional Approach (N round-trips):
┌─────────────────────────────────────────────────────────────┐
│ Round 1: LLM → getUser(1) → result → LLM (context grows)   │
│ Round 2: LLM → getUser(2) → result → LLM (context grows)   │
│ Round 3: LLM → getUser(3) → result → LLM (context grows)   │
│ Round 4: LLM → getUser(4) → result → LLM (context grows)   │
│ Round 5: LLM → getUser(5) → result → LLM (context grows)   │
│ Round 6: LLM → final answer                                 │
└─────────────────────────────────────────────────────────────┘
            6 LLM calls × full context each
            Accumulated results pollute context
            High latency, high token cost
```

---

## ✨ The Solution

PTC transforms tool orchestration into a single code generation + execution:

```
Programmatic Approach (1 round-trip):
┌─────────────────────────────────────────────────────────────┐
│ Round 1: LLM generates JavaScript:                          │
│   const users = await Promise.all([                         │
│     getUser({ id: '1' }), getUser({ id: '2' }),             │
│     getUser({ id: '3' }), getUser({ id: '4' }),             │
│     getUser({ id: '5' })                                    │
│   ]);                                                        │
│   return users.sort((a,b) => b.score - a.score)[0];         │
│                                                              │
│ → Execute in Sandbox → Return final result only             │
│                                                              │
│ Round 2: LLM receives final answer, responds to user        │
└─────────────────────────────────────────────────────────────┘
            2 LLM calls total
            Intermediate results never enter context
            Parallel execution, massive savings
```

---

## 📊 Proven Efficiency Gains

| Metric | Traditional | PTC | Improvement |
|--------|-------------|-----|-------------|
| **LLM Round-trips** | N (per tool) | 2 (fixed) | 90% reduction |
| **Context Growth** | Exponential | Constant | 85% efficiency |
| **Token Usage** | ~70,000 (10 tools) | ~14,000 | 80% savings |
| **Latency** | Sequential | Parallel | 3-5x faster |
| **MCP Tool Calls** | N round-trips | 1 code_execution | 60-80% savings |

---

## 🏗️ Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                        User Request                              │
└──────────────────────────────────────────────────────────────────┘
                                 │
                                 ▼
┌──────────────────────────────────────────────────────────────────┐
│  Vercel AI SDK 5.0 + Programmatic Tool Wrapper                  │
│    withProgrammaticCalling(tools)                                │
│      ├── Wraps local tools (Zod schemas)                         │
│      ├── Wraps MCP tools (JSON Schema)                           │
│      └── Injects code_execution meta-tool                        │
└──────────────────────────────────────────────────────────────────┘
                                 │
                                 ▼
┌──────────────────────────────────────────────────────────────────┐
│  LLM (Any Provider via AI Gateway)                               │
│    Generates JavaScript code orchestrating N tool calls          │
│    Uses defensive helpers (toArray, safeGet, isSuccess...)       │
└──────────────────────────────────────────────────────────────────┘
                                 │
                                 ▼
┌──────────────────────────────────────────────────────────────────┐
│  Vercel Sandbox (Isolated Cloud Execution)                       │
│    ├── Local Tools: getUser(), calculate()                       │
│    └── MCP Bridge (File-based IPC):                              │
│        ├── mcp_firecrawl_scrape()                                │
│        └── mcp_github_search()                                   │
└──────────────────────────────────────────────────────────────────┘
                                 │
                                 ▼
┌──────────────────────────────────────────────────────────────────┐
│  Main Process (MCP Tool Bridge Monitor)                          │
│    - Routes sandbox MCP requests to real MCP servers             │
│    - Supports HTTP, SSE, and Stdio transports                    │
│    - Normalizes responses for predictable code access            │
│    - Parallel batch execution for efficiency                     │
└──────────────────────────────────────────────────────────────────┘
                                 │
                                 ▼
           Final Result Only → Back to LLM → User Response
```

---

## 🌟 Key Features

### 🔧 Universal Model Support
- **Direct Providers**: Anthropic Claude, OpenAI GPT
- **Vercel AI Gateway**: 100+ models (Gemini, Mistral, Groq, DeepSeek, Meta, etc.)
- Works with any model that supports tool calling

### 🧪 Vercel Sandbox Execution
- Isolated cloud environment for LLM-generated code
- Node.js 22 runtime with full async/await support
- Automatic syntax validation before execution
- Singleton pattern for cost optimization

### 🔌 MCP Integration
- Connect any MCP server (HTTP, SSE, Stdio transports)
- File-based IPC bridge between sandbox and MCP servers
- Supports all standard MCP tools out-of-the-box

---

## 💰 Cost Analysis

### Vercel Sandbox Pricing

| Metric | Rate | Free Tier (Hobby) |
|--------|------|-------------------|
| Active CPU Time | $0.128/hour | 5 hours/month |
| Provisioned Memory | $0.0106/GB-hour | 420 GB-hours |
| Network Bandwidth | $0.15/GB | 20 GB |
| Sandbox Creations | $0.60/million | 5,000 |

### Cost Per Execution (2 vCPU, 4GB RAM)

| Scenario | Duration | Est. Cost |
|----------|----------|-----------|
| Quick (3-5 tools) | 10 sec | ~$0.0004 |
| Medium (5-10 tools) | 30 sec | ~$0.001 |
| Heavy (MCP-heavy) | 2 min | ~$0.003 |

### ROI Analysis

| Metric | Traditional (10 tools) | PTC |
|--------|------------------------|-----|
| LLM Round-trips | 10 | 2 |
| Context tokens | ~70,000 | ~14,000 |
| LLM cost (GPT-4) | $0.70-$2.10 | $0.14-$0.42 |
| Sandbox cost | $0 | ~$0.002 |
| **Net Savings** | — | **$0.50-$1.70** |

**Result**: Sandbox overhead of ~$0.002 saves $0.50-$1.70 in LLM costs per complex workflow.

---

## 🔮 How Token Savings Are Calculated

PTC tracks four categories of savings:

```json
{
  "intermediateResultTokens": 12500,
  "roundTripContextTokens": 35000,
  "toolCallOverheadTokens": 400,
  "llmDecisionTokens": 720,
  "totalSaved": 48620
}
```

---

## 🛠️ Usage Example

```typescript
import { withProgrammaticCalling } from 'programmatic-tool-calling-ai-sdk';
import { tool } from 'ai';
import { z } from 'zod';

const myTools = {
  getUser: tool({
    description: 'Get user by ID',
    inputSchema: z.object({ id: z.string() }),
    execute: async ({ id }) => ({ id, name: 'Alice', score: 95 }),
  }),
};

const { tools } = withProgrammaticCalling(myTools);
// → LLM now has access to code_execution meta-tool
// → Can generate JS to call getUser() in parallel for multiple IDs
// → Executes in Vercel Sandbox, returns only final result
```

---

## 📁 Project Structure

```
├── app/
│   ├── api/chat/route.ts       # Main chat API with PTC
│   ├── api/execute/route.ts    # Sandbox execution endpoint
│   └── page.tsx                # Chat UI
├── components/
│   ├── DebugPanel.tsx          # Tool call inspection
│   ├── EfficiencyMetrics.tsx   # Token savings display
│   └── MCPServerManager.tsx    # MCP configuration UI
├── lib/
│   ├── tool-wrapper.ts         # 🔑 Core PTC implementation
│   ├── sandbox.ts              # Vercel Sandbox orchestration
│   ├── mcp-bridge.ts           # MCP ↔ Sandbox communication
│   ├── mcp/
│   │   ├── client.ts           # MCP client implementation
│   │   ├── adapter.ts          # MCP → AI SDK conversion
│   │   └── manager.ts          # Multi-server management
│   ├── providers.ts            # AI provider factory
│   └── tools.ts                # Example tool definitions
└── types/
    └── chat.ts                 # TypeScript definitions
```
