# Context Engineering for Commercial Agent Systems

**Source:** https://jeremydaly.com/context-engineering-for-commercial-agent-systems/
**Category:** Context Engineering

## Summary

Jeremy Daly's comprehensive field guide to context engineering in commercial, multi-tenant AI agent systems. Drawing on experience building production agent systems serving hundreds of enterprise customers, the article establishes that context is infrastructure — not an implementation detail — requiring the same engineering discipline as networking, compute, or storage. It covers memory taxonomy, truth vs. acceleration layers, and the full context engine loop.

## Content

When you build agents for a single user on a laptop, almost anything works.
When you build commercial multi-tenant agents serving enterprises, almost nothing accidental survives.

Since late 2024, I've spent most of my time building and optimizing commercial, multi-tenant agent systems with a team of engineers inside a large SaaS platform serving hundreds of enterprise customers. We stress-tested agents under real constraints: tenant isolation, financial accuracy, auditability, cost control, and scale.

**Different environments. Same constraints.**

We evaluated and implemented systems across direct foundation model orchestration, managed agent runtimes, multi-agent coordination patterns, graph-oriented orchestration, and MCP integrations.

Across all of this, a clear convergence emerged.

Models improve. APIs standardize. Tool use matures.

But in commercial multi-tenant systems, those are not the determining factors.

**Context is.**

---

## Part I: Context Is Infrastructure

### The Three Non-Negotiables of Commercial Agent Systems

1. **Structural Isolation**
2. **Deterministic Replay**
3. **Economic predictability**

These are not optional features. They are architectural constraints.

If your system cannot enforce tenant boundaries structurally, it will eventually leak.
If your system cannot run deterministic replays, you cannot debug or evolve it safely.
If your system cannot predict cost per run, it cannot scale sustainably.

### Models Are Commoditized. Context Is Not.

Everyone has access to the same frontier models. Claude. GPT. Gemini. The APIs are public. The prices are falling. Capabilities are converging.

What differentiates systems is no longer the model. **It's the context.**

Two teams using the same model can produce radically different outcomes depending on how they externalize knowledge and constrain behavior.

In commercial systems, context isn't just about output quality. It's about:
- **Policy compliance:** did the agent follow the rules?
- **Data isolation:** can Tenant A's data leak into Tenant B's context?
- **Cross-tenant safety:** does shared infrastructure introduce shared risk?
- **Cost control:** can you predict and bound what each run costs?
- **Replay and auditability:** can you reconstruct what the agent knew at decision time?
- **Correctness under ambiguity:** does the system degrade gracefully or silently?

If you get context wrong, you don't just get worse answers. You get silent corruption.

### The Systems That Survive Production

The systems that survive production pressure consistently share the same traits:
- Typed memory instead of transcript blobs
- Separation between canonical truth and derived acceleration layers
- Explicit promotion and compaction gates
- Trace envelopes for replay and audit
- Aggressive pruning between turns
- Isolation boundaries enforced as security boundaries
- Cost surfaces treated as first-class signals

The systems that fail optimize for _demo velocity_. They index raw transcripts. They reuse context wholesale. They blur truth and acceleration. They discover cost, drift, and cross-tenant risk too late.

---

## Part II: Memory as a Scoped, Typed System

### Memory Must Be Scoped and Typed

A robust commercial agent system classifies memory along two dimensions:

1. **Scope**: who can see it (a security boundary)
2. **Type**: what kind of memory it is (semantic role)

### Memory Scopes (Security Boundaries)

These are structural isolation layers, enforced at the storage and routing layers, **never** delegated to the model.

**Global Scope:** Platform-Wide, Tenant-Invariant Memory
- System safety rules, tool contracts, product ontology, agent "constitution"
- Immutable at runtime; versioned; deployment-controlled; never writable by agents

**Tenant Scope:** Organization-Wide Shared Memory
- Organization policies, knowledge bases, playbooks, connector configurations
- Shared across users in a tenant; policy-gated promotion; subject to tenant retention rules

**User Scope:** Personalized Memory Within a Tenant
- Preferences, working style, personal notes, user-specific entitlements
- Visible only to the user (and system); promotion-gated; TTL or policy-based retention

**Session Scope:** Ephemeral Runtime State
- Tool outputs, intermediate plans, scratch buffers, temporary retrieval results
- Short-lived; subject to aggressive garbage collection; not durable unless explicitly promoted

### Memory Types (Semantic Role)

- **Policy memory** — Normative rules and constraints. Typically global or tenant-scoped. Versioned and tightly controlled.
- **Preference memory** — Stable personalization parameters. Usually user-scoped.
- **Fact memory** — Durable assertions the agent may reuse. Must include provenance.
- **Episodic memory** — Structured summaries of completed work. "Case resolved." "Migration completed." Reusable artifacts extracted from traces.
- **Trace memory** — Raw, append-only execution events. Your flight recorder.

> Memory without scope is exposure. Memory without type is entropy.

### Memory Layer Summary

| Layer | Scope | Typical Contents | Retention | Write Policy |
|-------|-------|-----------------|-----------|-------------|
| Constitution | Global | Safety rules, tool contracts, ontologies | Versioned | Write-locked |
| Org memory | Tenant | Playbooks, knowledge base, connectors, norms | Policy-based | Gated promotion |
| Personal memory | User | Preferences, working style, drafts | TTL-based + user controls | Gated promotion |
| Runtime state | Session | Tool outputs, scratch space, intermediate plans | Hours–days | Auto GC |
| Episodes | User / Tenant | "Case resolved," derived summaries | Months+ | Explicit promotion |
| Traces | Tenant | Events, retrievals, tool calls, approvals | Policy-based (often long-lived) | Append-only |

---

## Part III: Truth vs Acceleration

### Separate Truth from Acceleration

The core distinction you must preserve: **separate truth from acceleration.**

#### Canonical Stores (Truth)

**1. Canonical Event Log (Append-Only)**

This is your flight recorder. Every agent run emits multiple events including: context retrieved, policies evaluated, tool calls made, approvals routed, outputs generated, memory promoted.

It allows you to answer:
- What did the agent know at decision time?
- Which policy version applied?
- Why was this exception granted?
- What was retrieved and why?

**2. Canonical Structured Memory Store**

This stores durable memory state — facts, preferences, episodic summaries, approved overrides, tenant-level knowledge. Every record must include scope, class, provenance, retention policy, and sensitivity classification.

> If your vector store becomes your truth layer, you will eventually lose structural integrity.

#### Derived Stores (Projection)

- **Retrieval Index (Vector / Hybrid Search)** — Your serving layer for recall. Must be rebuildable from canonical sources.
- **Object Store (Large Payloads)** — For large documents, attachments, extraction outputs, tool responses. Objects should be content-addressed.

### Hybrid Search

Hybrid search (lexical + semantic) provides stronger precision and filtering guarantees than vector-only retrieval.

- Lexical search preserves _deterministic filtering_
- Semantic search improves _recall_

Critically, the index must NOT be built directly from raw transcripts. Raw transcripts are noisy, redundant, and context-fragmented.

---

## Part IV: The Context Engine Loop

### The High-Level Loop

Every agent run should follow a predictable sequence:

1. **Ingest:** establish identity, scope, constraints, and privacy mode
2. **Plan context needs:** determine what information is required to act safely
3. **Retrieve:** execute hybrid search within allowed scopes
4. **Assemble working set:** layer context by priority and token budget
5. **Semantic stabilization:** normalize references, extract structure, preserve meaning before reduction
6. **Agentic garbage collection:** deduplicate, prune low-confidence artifacts, enforce working-set limits
7. **Infer and act:** model + tools + policy enforcement + optional human approval
8. **Promotion gate:** decide what becomes durable memory
9. **Emit trace envelope:** record retrievals, actions, policies, versions, and cost surfaces
10. **Lifecycle garbage collection:** expire session buffers, enforce retention, invalidate derived projections

> If you skip steps, you get drift.

### Step 1: Ingest

At the beginning of a run, you must establish: tenant identity, user identity, role and entitlements, privacy mode, sensitivity level, task type.

**You DO NOT ask the model to filter data. You filter in the data plane.**

### Step 2: Plan Context Needs

Before retrieving anything, the agent should plan what kind of context it needs. This prevents the common anti-pattern: _"Retrieve everything and let the model figure it out."_

### Step 3: Retrieve (Isolation Enforced Here)

Filtering happens _before_ ranking, not after. Every retrieval query must include: Tenant ID, Scope visibility constraint, Expiration checks, Sensitivity boundaries.

### Step 4: Assemble the Working Set

The working set is layered:
1. Global constitution
2. Tenant policies
3. User preferences
4. Retrieved facts and episodes
5. Session state

**Without layering and budgets, context windows become dumping grounds.**

### Step 5: Semantic Stabilization (Pre-Compaction Flush)

Before shrinking context, you MUST stabilize meaning. This may include:
- Collapsing verbose tool traces into structured summaries
- Extracting typed episodic artifacts from conversation fragments
- Converting free-form dialogue into structured facts
- Normalizing references into concrete IDs
- Marking low-confidence artifacts explicitly
- Ensuring provenance metadata is attached

### Step 6: Agentic Garbage Collection

After meaning is stabilized, the system can safely optimize. Agentic GC enforces: token budgets by layer, deduplication of redundant artifacts, dropping stale session state, removing low-confidence provisional memory.

> Agentic garbage collection is not just optimization. It is drift control.

---

## Key Architectural Principles

- **Context boundaries are infrastructure, not application logic**
- **Isolation** is structural, not prompt-based
- **Replay** is the baseline for trust
- **Economics** must be predictable per run
- **Memory without scope is exposure. Memory without type is entropy**
- **Truth is rebuildable, acceleration is disposable**
- **What you exclude from context is becoming as important as what you include**

---

## Observed Ecosystem Convergence

Production systems like **Claude Code**, **Cursor**, **Letta**, **AWS AgentCore** all converge on the same architectural patterns:

- Typed, scoped memory layers
- Separation of event logs from structured state
- Explicit promotion gates
- Context budgets and priority ordering
- Aggressive pruning between turns
- Cryptographic isolation at projection layers

The same pressures produce the same load-bearing patterns.
