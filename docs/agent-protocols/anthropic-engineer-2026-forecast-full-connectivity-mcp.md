# Anthropic Engineer Forecasts 2026 as AI Agent 'Full Connectivity' Year, Driven by MCP

**Source:** https://finance.biggo.com/news/aee6b781d1226400
**Category:** Agent Protocols & Communication

## Summary

David Soria Parra, an engineer at Anthropic, speaking on the AI Engineer podcast, forecasts that 2026 will be defined by "full connectivity" for AI agents — where the most powerful agents will seamlessly combine computer use, command-line tools, and MCP to interact with the digital world. He sharply criticizes the practice of mechanically converting REST APIs to MCP servers as "cringe," and argues that the future requires agent-native interfaces designed for programmatic, low-latency tool composition.

## Content

The AI industry's race to build useful autonomous agents is hitting a critical infrastructure bottleneck. According to David Soria Parra of Anthropic, speaking on the AI Engineer podcast, 2026 will be defined by "full connectivity," where the most powerful agents will seamlessly combine multiple methods — like computer use, command-line tools, and the Model Context Protocol (MCP) — to interact with the digital world.

A key shift is moving away from simply wrapping existing REST APIs for AI, a practice Parra calls "cringe," and toward designing agent-native interfaces that support programmatic, low-latency tool composition. Innovations like MCP server discovery and "skills over MCP" will enable continuous, registry-free updates of agent capabilities.

---

### The 2026 Full Connectivity Vision

"2026 I think is all about connectivity and the best agents use every available method," Parra stated, predicting a move away from debates over a single best solution.

The future stack, he argues, will be a pragmatic combination of:
- **Computer use** (GUI automation)
- **Command-line interfaces (CLIs)**
- **Model Context Protocol (MCP)**
- **Packaged "skills"**

```
2026 AI Agent Connectivity Stack:
├── Computer Use (GUI)
│   ├── Direct screen control
│   └── Visual understanding
├── Command Line (CLI)
│   ├── Script execution
│   └── System-level access
├── Model Context Protocol
│   ├── Agent-native design
│   ├── Programmatic calling
│   ├── Server Discovery
│   └── Skills over MCP
└── Packaged Skills
    ├── Domain knowledge bundles
    └── Continuous updates
```

---

### The Cringe-Worthy Shortcut: Why Wrapping REST APIs Fails

A central theme in Parra's argument is a blunt critique of a common industry practice: taking existing REST APIs and mechanically converting them into MCP servers.

"We all need to stop taking REST APIs and put them one to one into an MCP server," Parra said. "Every time I see someone building another REST to MCP server conversion tool, I'm… it's a bit cringe because I think it just results in horrible things."

The problem: REST APIs are designed for deterministic, step-by-step human (or traditional software) orchestration. They often require multiple calls with specific parameters to complete a single logical task. Forcing an AI agent to navigate this sequential process ignores the agent's unique strength: its ability to reason about an entire workflow at once.

| Paradigm | How It Works | Latency & Efficiency | Agent Experience |
|----------|-------------|---------------------|-----------------|
| **Sequential REST Wrapping** | Agent calls API A, waits, calls API B, waits, calls API C | High latency, multiple network hops | Cumbersome, slow, prone to error |
| **Programmatic Tool Calling** | Agent writes a script that calls A, B, and C in one optimized operation | Low latency, single execution context | Fast, efficient, feels "intelligent" |

---

### From Sequential Tools to Programmatic Orchestration

The alternative is programmatic tool calling. Instead of asking the model to call one tool, process the result, then call another, developers should enable the model to write a small script or program that composes multiple tools in a single operation.

"You don't want the model to go call a tool, take the result, then go and talk call another tool, take the result, call another tool because what you're effectively doing is you're letting the model orchestrate things together," Parra explained.

This shift represents a move from agents as simple tool-users to agents as **micro-programmers**, capable of crafting bespoke solutions on the fly. This requires backend services designed to accept and safely execute these agent-generated programs — a significant architectural departure from traditional API design.

---

### Building the Discovery and Update Layer: MCP's New Features

Two key innovations coming to the MCP ecosystem:

#### 1. Server Discovery
A feature that will allow AI agents and crawlers to automatically detect MCP servers running on websites or local networks. This removes the need for manual configuration and registry listings, making it as easy for an agent to find a useful tool as it is for a human to browse to a webpage.

#### 2. Skills over MCP
This extension allows server authors to bundle updated domain knowledge, prompts, and capabilities directly within the MCP server itself.

"It allows you as a server author to continuously ship updated skills without having to rely on plug-in mechanisms and registries," Parra noted. This creates a **decentralized and dynamic update model** — an agent connecting to a server immediately gains access to its latest capabilities without any central app store approval process.

---

### Industry Implications: Beyond Hype to Infrastructure

The push for agent-native connectivity is not happening in a vacuum:

- Meta's aggressive AI product pushes signal a market-wide search for the next differentiator
- A stateless transport protocol proposed by Google would ease MCP deployment on platforms like Kubernetes
- The premium is shifting from rote coding to asking the right questions and synthesizing insights

**Bottom line**: The companies and developers who move beyond the "cringe" phase of simple API wrapping and invest in designing interfaces specifically for AI interaction will build the platforms upon which the first generation of truly useful general knowledge worker agents will operate.

This transition marks the moment **AI stops adapting to our digital world and begins forcing our digital world to adapt to it**.

---

### MCP Adoption Metrics (as of 2026)

- **97 million** monthly MCP SDK downloads (as of March 2026)
- All major cloud and AI vendors (OpenAI, Google, Microsoft, AWS) now ship MCP-compatible solutions
- 10,000+ active MCP servers in production
- SDKs available in Python, TypeScript, C#, and Java
- Registry-free skills & server discovery now standard features
