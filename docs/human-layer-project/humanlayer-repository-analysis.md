# HumanLayer Repository Analysis

This report analyzes the local clone at `humanlayer/` for structure, runtime flow, tooling, and documentation posture. It treats the repository as a product monorepo with one primary shipping surface, several supporting libraries and services, and a few clearly experimental or transitional areas.

## Executive summary

The repository is best understood as **CodeLayer on top of HumanLayer infrastructure**.

- The public-facing story has shifted to **CodeLayer**, an open-source IDE for orchestrating AI coding agents.
- The operational core is still the **HumanLayer local runtime**: `hlyr` as the CLI/MCP entrypoint, `hld` as the daemon, SQLite for persistence, and an approval loop wired into Claude Code sessions.
- The main user interface is `humanlayer-wui`, a Tauri + React app branded as **CodeLayer**.
- The repo is a **hybrid monorepo**: Bun + Turbo for selected JS workspaces, Make as the real cross-project orchestrator, Go for the daemon and Claude Code SDK, and Tauri/Rust for desktop packaging.
- The codebase contains **three maturity levels at once**: shipping product paths, reusable shared packages, and prototype/scaffold apps.

## Repository identity

The root `README.md` presents the project as **CodeLayer**, not as a generic SDK repo. It positions the product as an IDE for orchestrating AI coding agents, highlights context-engineering expertise, and points legacy HumanLayer SDK readers to `humanlayer.md`.

That branding change is real, but incomplete:

- `humanlayer-wui` ships as **CodeLayer**
- several packages still use **HumanLayer** naming
- `humanlayer.md` remains as legacy SDK documentation
- the docs site mixes CodeLayer-first pages with older HumanLayer SDK material

The result is a repo in transition from a human-in-the-loop SDK/platform identity to a broader agent-orchestration product identity.

## High-level architecture

The core local runtime path is:

```text
User -> humanlayer / hlyr CLI or CodeLayer WUI
     -> hld daemon
     -> Claude Code session via claudecode-go
     -> injected approvals MCP subprocess (hlyr mcp claude_approvals)
     -> approval manager + SQLite store + event bus
     -> user decision surfaced back through WUI / CLI / daemon APIs
```

Two architectural facts matter most:

1. **`hld` owns orchestration.** It launches Claude Code sessions, injects MCP configuration, persists events, manages approvals, and exposes local RPC/HTTP surfaces.
2. **`hlyr` is both a user CLI and a runtime dependency.** The daemon injects it back into launched Claude sessions as the `claude_approvals` MCP server.

That makes the product less like a conventional app with a thin CLI and more like a **layered local agent runtime**.

## Component map

| Area | Role | Status |
| --- | --- | --- |
| `hld/` | Go daemon for session lifecycle, approvals, persistence, RPC, HTTP, and MCP surfaces | Core runtime |
| `hlyr/` | TypeScript CLI for launching sessions and serving the local approvals MCP tool | Core runtime |
| `humanlayer-wui/` | Tauri + React desktop/web UI, branded as CodeLayer | Primary product UI |
| `claudecode-go/` | Go SDK for launching and managing Claude Code sessions | Shared runtime dependency |
| `packages/contracts/` | Typed daemon contracts and OpenAPI generation | Shared package |
| `packages/database/` | Drizzle/Postgres schema and types for newer app surfaces | Shared package |
| `apps/react/` | Collaborative “thoughts documents” prototype using Electric + Yjs + Tiptap | Experimental/prototype |
| `apps/daemon/` | Minimal ORPC/OpenAPI scaffold around the shared daemon contract | Scaffold/prototype |
| `docs/` | Mintlify docs site with mixed CodeLayer and legacy HumanLayer material | Product docs |
| `.github/`, `hack/`, `scripts/` | CI, release automation, worktree/dev tooling, helper scripts | Repo operations |

## Main product surfaces

### 1. `humanlayer-wui`: the primary UI

`humanlayer-wui` is the main end-user surface. It bootstraps a React app with routing, global hotkeys, telemetry, session state, and daemon connectivity. On the desktop side, Tauri manages daemon startup, daemon status inspection, and the quick-launcher window.

Key characteristics:

- route-driven UI for session list, session detail, draft sessions, and launcher flows
- a central Zustand store for sessions, selection, settings, and optimistic UI updates
- daemon access through an HTTP client wrapper around the generated HLD SDK
- Tauri commands for starting, stopping, and querying the daemon
- packaging flow that bundles both `hld` and `humanlayer` binaries into the desktop app

This is the strongest signal of what the project is shipping now.

### 2. `hlyr`: the CLI and local approvals MCP server

`hlyr` exposes the user-facing `humanlayer`/`hlyr`/`codelayer` binaries. Its two most important commands are:

- `launch <query>` for starting daemon-backed Claude Code sessions
- `mcp claude_approvals` for serving the injected approvals tool over stdio MCP

This dual role is central to the product design. The same binary is both the user entrypoint and a daemon-injected helper process inside Claude sessions.

### 3. `hld`: the daemon backbone

`hld` is the repository’s real center of gravity. It:

- boots the local runtime
- listens on a Unix socket for JSON-RPC
- starts an HTTP server with event streaming, proxying, and HTTP MCP support
- launches Claude Code through `claudecode-go`
- injects per-session MCP configuration
- persists session, event, approval, and raw-event data in SQLite
- correlates tool calls and approval requests
- publishes internal events to subscribers

If `humanlayer-wui` is the face of the product, `hld` is the spine.

## End-to-end workflow

### Launch flow

1. The user launches work from the CLI or WUI.
2. `hlyr` resolves daemon config and sends a JSON-RPC launch request to `hld`.
3. `hld` builds a `LaunchSessionConfig`, forces streaming JSON output, resolves working directories, persists initial session state, and launches Claude Code via `claudecode-go`.
4. During session creation, `hld` injects a `codelayer` MCP server whose command resolves to `hlyr mcp claude_approvals`.
5. The daemon monitors Claude stream events, stores raw and structured events, tracks tool calls/results, and updates session state over time.

### Approval flow

1. A Claude session calls the injected approvals MCP tool.
2. `hlyr` receives the MCP call, reads the session environment, and creates an approval through daemon RPC.
3. `hld` stores the approval, correlates it with a pending tool call, publishes events, and moves the session into `waiting_input` when needed.
4. A user resolves the approval through the WUI or another daemon-facing client.
5. `hlyr` polls until resolution and returns the result to the MCP caller.

### Important architectural nuance

There are **two approval-facing MCP surfaces**:

- the stdio MCP server served by `hlyr` inside Claude sessions, exposing `request_permission`
- the HTTP MCP endpoint exposed directly by `hld`, exposing `request_approval`

They serve related purposes, but they are not the same interface or contract.

## Shared layers and supporting packages

### `claudecode-go`

`claudecode-go` is a standalone Go SDK for launching Claude Code sessions with text, JSON, and streaming JSON output modes. `hld` depends on it directly and wraps its session/result types inside daemon session management.

This package is important because it keeps Claude Code process control in a narrow, test-backed library instead of smearing that logic across the daemon.

### `packages/contracts`

This package defines typed daemon contracts with Zod and oRPC and includes OpenAPI generation. It is the clearest example of contract-first design in the repo.

### `packages/database`

This package defines Drizzle schema, DB bootstrap logic, and inferred types for newer app surfaces. It is used most directly by `apps/react`, not by the daemon runtime. That distinction matters: the repo contains **two persistence worlds**:

- SQLite in `hld` for the operational local runtime
- Drizzle/Postgres in `packages/database` for newer app/database-backed surfaces

### `apps/react` and `apps/daemon`

These two apps read as exploratory or scaffold layers rather than primary product surfaces:

- `apps/react` is a collaborative editor prototype built around Electric, Yjs, and Tiptap
- `apps/daemon` is a thin contract-driven API scaffold with a Swagger/Scalar playground server

Both are useful signals of direction, but neither appears to be the main shipped path.

## Tooling, build, test, and release model

The repo uses a **hybrid orchestration model**.

### Root-level structure

- `package.json` uses Bun and Turbo for top-level JS tasks
- `turbo.json` defines build, lint, check-types, and persistent dev tasks
- the root `Makefile` is the real cross-project control plane

### What Make owns

The `Makefile` stitches together:

- `hlyr`
- `humanlayer-wui`
- `hld`
- `claudecode-go`
- nightly and dev variants
- ticket-scoped local environments
- worktree helpers
- SDK regeneration
- release bundling steps

This is one of the clearest signs that the repo behaves more like a polyglot product workspace than a pure JS monorepo.

### CI and release posture

The automation surface is strong:

- main CI splits checks from tests
- release workflows build macOS artifacts, desktop bundles, and GitHub releases
- Homebrew cask updates are automated
- Linear-driven workflows exist for research, plan, and implementation flows
- a Claude workflow is wired into GitHub events

The repo also invests heavily in local developer ergonomics:

- ticket-scoped socket, port, and DB isolation
- worktree creation and cleanup helpers
- silent command wrappers for cleaner dev output
- Linux/Tauri dependency bootstrapping

## Documentation posture

The documentation surface is useful, but not fully aligned.

### What is solid

- the root README clearly states the current CodeLayer story
- `humanlayer-wui` has a focused README and release notes
- the Mintlify docs include introduction, workshop, development, and quickstart material
- `humanlayer.md` makes the legacy status explicit

### What is drifting

- the docs nav exposes fewer pages than actually exist
- the docs set mixes CodeLayer-first material with legacy HumanLayer SDK material
- internal docs note outdated states in places where the repo already has newer artifacts
- protocol documentation does not cover every active approval helper or HTTP MCP surface

None of this blocks understanding, but it does make the repo feel like an active transition rather than a finished consolidation.

## Key strengths

### Strong local-runtime architecture

The split between `hlyr`, `hld`, and `claudecode-go` is clean enough to reason about. The daemon owns orchestration, the CLI owns user entry and stdio MCP, and the Go SDK owns Claude Code process control.

### Session-aware approval design

Approval handling is deeply integrated with session state, tool-call correlation, and event persistence. This is not bolted-on UI approval; it is part of the execution model.

### Serious operator ergonomics

The repo has unusually strong local-dev and release ergonomics for an agent product: nightly builds, ticket-specific environments, worktree helpers, release automation, and cross-language setup scripts.

### Clear product center of gravity

Even with transitional naming, it is obvious what the repo is converging toward: a local-first agent orchestration product with a desktop UI and a strong runtime core.

## Risks, caveats, and notable inconsistencies

### 1. Hybrid monorepo boundaries are uneven

Root workspaces cover only `apps/*` and `packages/*`, while major living components such as `humanlayer-wui`, `hlyr`, `hld`, and `claudecode-go` are orchestrated outside that workspace model through Make. This is workable, but it means the repo’s actual structure is broader than its root JS workspace model suggests.

### 2. Product naming is still transitional

CodeLayer is the dominant product story, but HumanLayer naming still appears in package names, docs, paths, and legacy artifacts. That can confuse first-time readers about what is current versus historical.

### 3. Multiple approval interfaces coexist

The repo exposes both stdio MCP and HTTP MCP approval paths, with different tool names and interaction models (`request_permission` vs `request_approval`). That is not necessarily wrong, but it raises the documentation burden.

### 4. Prototype and shipping surfaces live side by side

`humanlayer-wui` appears production-oriented, while `apps/react` and `apps/daemon` read as exploratory. That is a reasonable repo shape, but it should be understood explicitly when navigating the codebase.

### 5. Documentation and protocol coverage lag active code

Some documentation surfaces lag behind the implementation:

- protocol docs do not describe every active runtime surface
- docs navigation does not expose every page in `docs/`
- some repo-level scripts are present but empty

These are not signs of architectural weakness, but they are signs of ongoing consolidation.

## Practical reading order

For someone trying to understand the repo quickly, this order is the most efficient:

1. `README.md`
2. `CLAUDE.md`
3. `humanlayer-wui/README.md`
4. `hlyr/src/index.ts`
5. `hlyr/src/mcp.ts`
6. `hld/cmd/hld/main.go`
7. `hld/session/manager.go`
8. `hld/approval/manager.go`
9. `hld/store/sqlite.go`
10. `Makefile`
11. `.github/workflows/main.yml`
12. `.github/workflows/release-macos.yml`

That path gets you the product story, the runtime core, the approval loop, the persistence layer, and the operational surface with minimal wandering.

## Glossary of key paths

| Path | Meaning |
| --- | --- |
| `humanlayer-wui/` | Primary desktop/web UI for CodeLayer |
| `hlyr/` | CLI and stdio approvals MCP server |
| `hld/` | Local daemon and orchestration core |
| `claudecode-go/` | Go SDK for launching Claude Code |
| `packages/contracts/` | Typed daemon contracts and OpenAPI generation |
| `packages/database/` | Drizzle/Postgres schema for newer app surfaces |
| `apps/react/` | Collaborative editor prototype |
| `apps/daemon/` | Contract-first daemon API scaffold |
| `docs/` | Mintlify product docs |
| `humanlayer.md` | Legacy HumanLayer SDK documentation |

## Final assessment

This repository is not just a collection of SDKs or a thin wrapper around Claude Code. It is a **local agent runtime platform with a desktop UI, an approval-centric orchestration model, and strong operator tooling**.

Its most important design choice is the combination of:

- daemon-owned session orchestration
- session-scoped MCP injection
- persistent event and approval tracking
- a first-class desktop UI over that runtime

The biggest repo-level challenge is not architectural confusion inside the runtime. It is **surface-area consolidation**: aligning branding, docs, workspace boundaries, and prototype versus product signals around the CodeLayer-centered future the repo is already building.
