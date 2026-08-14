# A2aprotocol — a Hugging Face Space by a2aprotocol

**Source:** https://huggingface.co/spaces/a2aprotocol/a2aprotocol | https://huggingface.co/blog/1bo/a2a-protocol-explained
**Category:** Agent Protocols & Communication

## Summary

The A2A Protocol (Agent-to-Agent) is an open-source framework launched by Google to facilitate communication and interoperability among AI agents. The HuggingFace Space by a2aprotocol provides a community platform for experimentation with the protocol. This document covers the protocol's architecture, core concepts, communication elements, interaction mechanisms, and includes a Python SDK implementation example.

---

## Content

### What is A2A?

[A2A](https://www.a2aprotocol.net), short for **Agent to Agent** protocol, is an open-source framework launched by Google to facilitate communication and interoperability among AI agents. By providing a standardized collaboration method for agents, regardless of their underlying frameworks or vendors, this protocol enables AI agents to securely exchange information, coordinate actions, and operate across diverse enterprise platforms and applications.

**Core question A2A answers**: How can AI agents developed by different teams, using different technologies, and owned by different organizations effectively communicate and collaborate?

---

### Why A2A is Needed

As AI agents become increasingly specialized and powerful, the need for them to collaborate on complex tasks grows. Consider a user requesting their primary AI agent to plan an international trip — this single request might involve coordinating multiple specialized agents:

1. An agent for flight bookings
2. An agent for hotel reservations
3. An agent for local tour recommendations and bookings
4. An agent handling currency conversion and travel advisories

Without a common communication protocol, integrating these disparate agents requires custom point-to-point solutions, making the system difficult to scale, maintain, and extend.

---

### Application Scenarios

**Enterprise Automation**

In corporate environments, A2A enables agents to work across siloed data systems and applications. For example, a supply chain planning agent can coordinate with inventory management, logistics, and procurement agents — even if they are built by different vendors or on different frameworks.

**Multi-Agent Collaboration**

A2A protocols facilitate true multi-agent scenarios where agents can collaborate in their natural, unstructured modes, even without shared memory, tools, or context. This goes beyond simply using one agent as a "tool" for another.

**Cross-Platform Integration**

A2A allows AI agents to operate across an entire ecosystem of enterprise applications — CRM systems, knowledge bases, project management tools, and more.

---

### How It Works

A2A facilitates communication between **client agents** and **remote agents**. The client agent formulates and conveys tasks; the remote agent executes them.

**Key functions:**

- **Capability Discovery**: Agents expose their capabilities using a JSON-formatted `Agent Card`. This allows client agents to identify the most suitable agent for a task.
- **Task Management**: Communication is task-oriented, with a defined task lifecycle. Tasks can be completed immediately or updated asynchronously for long-running operations.
- **Collaboration**: Agents exchange messages to share context, responses, artifacts, or user instructions.
- **Negotiation**: Each message includes a `parts` field with content fragments (text, images, etc.) and a specified content type, allowing agents to negotiate format.

---

### Core Concepts

#### Participants
- **User**: End user (human or automated service) initiating a request
- **A2A Client (Client Agent)**: Application or AI agent representing the user
- **A2A Server (Remote Agent)**: AI agent exposing an HTTP endpoint implementing A2A; receives requests, processes tasks, returns results

#### Communication Elements

**Agent Card**
- A JSON metadata document, discoverable via `/.well-known/agent.json`
- Describes agent identity, capabilities, skills, endpoint URLs, and authentication requirements
- Clients use the agent card to discover agents and learn how to interact securely

**Task**
- When a client sends a message, the agent may determine that fulfilling the request requires a task
- Each task has a unique ID and progresses through a defined lifecycle:
  - `submitted` → `working` → `input-required` → `completed` / `failed`
- Tasks are stateful and may involve multiple exchanges

**Message**
- Represents a single turn or unit of communication
- Has a `role` (user or agent) and contains one or more `Part` objects
- Used to convey instructions, context, questions, answers, or status updates

**Part (Content Types)**
- `TextPart`: Plain text content
- `FilePart`: File transmitted as inline base64-encoded bytes or referenced via URI
- `DataPart`: Structured JSON data (forms, parameters, machine-readable information)

**Artifact**
- Represents output results generated by the remote agent during task processing
- Examples: generated documents, images, spreadsheets, structured data
- Consists of one or more `Part` objects; can be streamed incrementally

#### Interaction Mechanisms

**Request/Response (Polling)**
- Client sends request via `message/send` RPC method
- Server may initially respond with `working` status; client polls via `tasks/get` until terminal state

**Streaming (SSE)**
- Used for tasks producing results incrementally or providing real-time progress updates
- Client initiates with `message/stream`; server sends a stream of Server-Sent Events (SSE)
- Events can be: `Task`, `Message`, `TaskStatusUpdateEvent`, or `TaskArtifactUpdateEvent`

**Push Notifications**
- For very long-running tasks or impractical persistent connections
- Client provides a webhook URL; server sends HTTP POST notifications on significant state changes

#### Additional Concepts
- **Context (contextId)**: Server-generated identifier to logically group related task objects
- **Transport and Format**: A2A communication over HTTP(S) with JSON-RPC 2.0 payloads
- **Authentication**: OAuth tokens, API keys, or mTLS passed via HTTP headers
- **Agent Discovery**: Process by which clients locate agent cards to identify available A2A servers

---

### Agent Card Object Structure

```typescript
export interface AgentCard {
  name: string;              // e.g., "Recipe Agent"
  description: string;       // e.g., "Agent that helps users with recipes and cooking."
  url: string;               // The URL where the agent is hosted
  iconUrl?: string;          // Optional icon URL
  provider?: AgentProvider;  // Service provider of the agent
  version: string;           // e.g., "1.0.0"
  documentationUrl?: string; // Optional docs URL
  capabilities: AgentCapabilities; // Optional capabilities
  securitySchemes?: { [scheme: string]: SecurityScheme };
  security?: { [scheme: string]: string[] }[];
  defaultInputModes: string[];     // Supported input media types
  defaultOutputModes: string[];    // Supported output media types
  skills: AgentSkill[];            // Units of capability the agent can perform
  supportsAuthenticatedExtendedCard?: boolean;
}
```

---

### Real-World Use Case: Employee Onboarding

A new employee is hired. Multiple systems and departments are involved:
- HR needs to create records and send a welcome email
- IT needs to provide a laptop and company account
- Facilities needs to prepare a desk and an access badge

With A2A, each department exposes its own agent:

| Agent | Responsibilities |
|-------|-----------------|
| `hr-agent.company.com` | Create employee records, send documents |
| `it-agent.company.com` | Set up email accounts, order laptops |
| `facilities-agent.company.com` | Assign desks, print badges |

The `OnboardingPro` orchestrator:
1. **Discovery**: Reads each agent's `.well-known/agent.json`
2. **Task Delegation**: Sends appropriate tasks to each specialized agent
3. **Continuous Updates**: Agents stream back progress via SSE ("Laptop shipped," "Desk assigned")
4. **Artifact Collection**: Final outputs (PDF badge, confirmation email, account credentials) returned as A2A artifacts
5. **Completion**: `OnboardingPro` notifies the hiring manager

---

### A2A and MCP: Complementary, Not Competing

- **MCP**: Focuses on connecting individual AI models with external tools and data sources (Model to Data/Tools). "If MCP is a socket wrench (for tools)..."
- **A2A**: Focuses on communication and collaboration between multiple AI agents (Agent to Agent). "...then A2A is the conversation between mechanics (for collaboration)."

> An agent application might use A2A to communicate with other agents, while internally, each agent employs MCP to interact with its specific tools and resources.

---

### Python SDK Quick Start

```python
from a2a.server.apps import A2AStarletteApplication
from a2a.server.request_handlers import DefaultRequestHandler
from a2a.server.tasks import InMemoryTaskStore
from a2a.types import AgentCapabilities, AgentCard, AgentSkill

# Install: uv add a2a-sdk uvicorn
# Python 3.10+ required

# Define a skill
skill = AgentSkill(
    id='hello_world',
    name='Returns hello world',
    description='just returns hello world',
    tags=['hello world'],
    examples=['hi', 'hello world'],
)

# Define agent card
public_agent_card = AgentCard(
    name='Hello World Agent',
    description='Just a hello world agent',
    url='http://0.0.0.0:9999/',
    version='1.0.0',
    defaultInputModes=['text'],
    defaultOutputModes=['text'],
    capabilities=AgentCapabilities(streaming=True),
    skills=[skill],
)
```

**Official Python SDK**: https://github.com/google-a2a/a2a-python

---

### HuggingFace Resources

- **A2AProtocol Space**: https://huggingface.co/spaces/a2aprotocol/a2aprotocol — community platform for experimentation
- **A2A Protocol Explained (Blog)**: https://huggingface.co/blog/1bo/a2a-protocol-explained
- **What is A2A and why is it**: https://huggingface.co/blog/Kseniase/a2a
- **MCP A2A Deepdive Space**: https://huggingface.co/spaces/airabbitX/mcp-a2a-deepdive

### Official Resources
- **A2A Protocol Docs**: https://a2aprotocol.ai/docs/
- **A2A Protocol Spec**: https://a2a-protocol.org/latest/
- **GitHub**: https://github.com/google-a2a/
