# Spec-Driven Development: From Code to Contract in the Age of AI Coding Assistants

**Source:** https://arxiv.org/abs/2602.00180 (abstract) + https://arxiv.org/html/2602.00180v1 (full text)
**Category:** Spec-Driven Development
**Authors:** Deepak Babu Piskala
**Submitted:** Fri, 30 Jan 2026 — Submitted to AIWare 2026 (8 pages, 3 figures)
**DOI:** https://doi.org/10.48550/arXiv.2602.00180

## Summary

This paper provides practitioners with a comprehensive guide to Spec-Driven Development (SDD), which inverts the traditional workflow by treating specifications as the source of truth and code as a generated or verified secondary artifact. The authors present three levels of specification rigor—spec-first, spec-anchored, and spec-as-source—with guidance on when each applies, a survey of tools from BDD frameworks to AI-assisted toolkits, and case studies across API development, enterprise systems, and embedded software.

## Content

### Abstract

The rise of AI coding assistants has reignited interest in an old idea: what if specifications—not code—were the primary artifact of software development? Spec-driven development (SDD) inverts the traditional workflow by treating specifications as the source of truth and code as a generated or verified secondary artifact. This paper provides practitioners with a comprehensive guide to SDD, covering its principles, workflow patterns, and supporting tools. We present three levels of specification rigor—spec-first, spec-anchored, and spec-as-source—with clear guidance on when each applies. Through analysis of tools ranging from Behavior-Driven Development frameworks to modern AI-assisted toolkits like GitHub Spec Kit, we demonstrate how the spec-first philosophy maps to real implementations. We present case studies from API development, enterprise systems, and embedded software, illustrating how different domains apply SDD. We conclude with a decision framework helping practitioners determine when SDD provides value and when simpler approaches suffice.

---

## I Introduction

For decades, code has been the king of software development. Requirements documents exist, but they drift. Design diagrams are drawn, but they rot. Tests are written, but often after the fact. The code—whatever it actually does—becomes the de facto truth of the system.

This code-centric reality has consequences. When a new developer asks "what should this function do?" the answer is often "read the code." When a stakeholder asks "does the system meet our requirements?" the answer requires reverse-engineering intent from implementation. When an AI coding assistant is asked to add a feature, it must guess what the developer wants from a vague prompt.

Spec-driven development (SDD) offers an alternative: make the specification the source of truth, and let code derive from it. Instead of coding first and documenting later (or never), teams write clear specifications of intended behavior, then generate, implement, or verify code against those specifications. The spec becomes the authoritative description that both humans and machines use to understand, build, and maintain the system.

### I-A The AI Catalyst

While spec-first thinking is not new—Test-Driven Development (TDD) and Behavior-Driven Development (BDD) have advocated for it for years—the emergence of AI coding assistants has made SDD newly relevant. The problem is simple: AI models are excellent at pattern completion but poor at mind reading.

Consider a developer who prompts an AI with: "Add photo sharing to my app." The AI must guess: What format? What permissions model? What size limits? Cloud storage or local? Compression? The result is often plausible-looking code that makes dozens of unstated assumptions—many of them wrong. This is what practitioners call "vibe coding"—relying on loose prompts that lead to inconsistent or erroneous outputs from LLMs. By providing AI with unambiguous, executable contracts, SDD enhances the reliability of coding agents and opens new avenues for scalable software creation.

Now consider the same request with a specification: "Users can upload JPEG or PNG photos up to 10MB. Photos are stored in S3 with user-ID-prefixed keys. Only the uploader can delete their photos. Photos are resized to 1024px max dimension on upload." The AI now has enough information to generate code that matches intent.

**The Core Principle:** In spec-driven development, code is the implementation detail of the specification—not the other way around. The spec declares intent; the code realizes it.

---

## II The Specification Spectrum

Not all spec-driven approaches are equal. Teams adopt different levels of rigor depending on their needs, tooling, and domain constraints.

### II-A Spec-First: Guided Initial Development

In spec-first development, a specification is written before coding to guide the initial implementation. Once code exists, the spec may or may not be maintained—the primary value is in the initial clarity it provides.

Spec-first works particularly well for initial feature development when working with AI coding assistants. The upfront spec prevents the AI from guessing at requirements, dramatically improving the quality of generated code. It is also valuable for prototypes and one-off features where the cost of maintaining a spec alongside code indefinitely is not justified. However, spec-first does not protect against drift over time—if the codebase will be maintained long-term, teams should consider spec-anchored approaches.

### II-B Spec-Anchored: Living Documentation

In spec-anchored development, the specification is maintained alongside the code throughout the system's lifecycle. Changes to behavior require updating both the spec and the code, keeping them synchronized.

Spec-anchored development treats the spec as a living document that evolves with the codebase. When a feature changes, the spec is updated first or simultaneously with the code. Automated checks—typically in the form of tests derived from the spec—ensure that spec and code remain aligned. If they drift, tests fail, providing immediate feedback.

Spec-anchored is the sweet spot for most production systems. BDD frameworks like Cucumber exemplify this approach. For API development, OpenAPI specifications paired with contract testing tools like Specmatic achieve alignment between spec and implementation.

### II-C Spec-as-Source: Humans Edit Specs, Machines Generate Code

In spec-as-source development, the specification is the only artifact humans edit directly. Code is entirely generated from the spec and should never be manually modified. Any change to behavior means changing the spec and regenerating.

This approach fundamentally inverts the traditional relationship between specs and code. Drift is eliminated by design: since code is regenerated rather than manually edited, spec and code are always aligned by construction.

Spec-as-source is already standard practice in domains with well-defined code generation, such as generating API server stubs from OpenAPI specifications, or producing certified embedded code from Simulink models. Emerging AI tools like Tessl aim to extend this approach to general software development.

---

## III The SDD Workflow

A common workflow emerges across SDD approaches with four core phases. Each phase produces an artifact that constrains and guides the next, creating a chain of accountability from intent to implementation.

### III-A Phase 1: Specify

The specify phase answers: **What should the software do?** The output is a functional specification describing behavior, requirements, and acceptance criteria—without prescribing implementation details.

Good specs are:
- **Behavior-focused**: describing what happens rather than how
- **Testable**: each requirement is verifiable
- **Unambiguous**: different readers reach the same interpretation
- **Complete enough** to cover essential cases without over-specifying

### III-B Phase 2: Plan

The plan phase answers: **How should we build it?** Given the functional spec, this phase produces a technical plan covering architecture, data models, interfaces, and technology choices. Where the spec declares intent, the plan declares constraints on implementation.

When using AI coding assistants, the plan provides crucial context: the AI learns not just what to build but how the system is structured and what conventions it should follow.

### III-C Phase 3: Implement

The implement phase produces working code that realizes the spec according to the plan. A key SDD principle is working in small, validated increments—each task delivers a testable piece of functionality, enabling frequent checkpoints where humans verify alignment.

Specifications act as "super-prompts" that break down complex problems into modular components aligned with agents' context windows, enabling AI systems to handle complexity that would overwhelm single-shot prompts.

### III-D Phase 4: Validate

The validate phase answers: **Does the code actually meet the spec?** Validation combines automated verification with human judgment, including unit, integration, and acceptance-level tests plus stakeholder acceptance testing.

If validation reveals gaps, the team decides: fix the code or revise the spec. Either way, the spec remains the authority.

---

## IV How SDD Boosts AI Coding Agents

Large language models like GPT-4 or Claude, when used as coding agents, benefit immensely from SDD by receiving optimized, context-rich inputs. Empirical studies suggest that human-refined specs significantly improve LLM-generated code quality, with controlled studies showing **error reductions of up to 50%**.

Key benefits for AI agents:
- Specifications enable **parallel agent execution** on non-overlapping tasks
- Teams can partition work at the spec level, allowing multiple AI agents to implement different components simultaneously without interference
- Property-based testing (PBT) addresses LLM non-determinism by verifying that invariants from specs are satisfied regardless of implementation variation

An emerging approach involves **"self-spec" methods** where LLMs author their own specifications before generating code. The agent first produces a spec from a high-level prompt, which is then reviewed and refined by humans before the same or another agent implements against it.

---

## V Tools and Frameworks

### V-A Behavior-Driven Development (BDD) Frameworks

BDD frameworks allow teams to write specifications in near-natural language that can be executed as tests. The canonical format is Gherkin, using structured scenarios with Given/When/Then clauses:

```gherkin
Feature: Shopping Cart
  Scenario: Adding item to empty cart
    Given the cart is empty
    When I add item "Widget" to the cart
    Then the cart should contain 1 item
    And the item should be "Widget"
```

Tools: Cucumber (Ruby, Java, JavaScript), SpecFlow (.NET), Behave (Python).

### V-B API Specification Tools

In API development, spec-driven approaches have been standard practice under the names "design-first" or "API-first" for years:
- **OpenAPI** (formerly Swagger): define REST APIs with complete endpoint specifications, generate server stubs, client SDKs, and documentation
- **GraphQL SDL**: define types, queries, and mutations in a schema that becomes the contract between frontend and backend
- **AsyncAPI**: similar capabilities for event-driven architectures
- **Protocol Buffers / gRPC**: define service interfaces with automatic generation of strongly-typed client and server code
- **Contract testing tools**: Pact and Specmatic automate verification that implementations match their specs

### V-C AI-Assisted SDD Tools

**GitHub Spec Kit**: Open-source CLI with four explicit phases: `/specify` generates a detailed spec, `/plan` creates technical architecture, `/tasks` breaks the plan into implementation tasks, then implementation generates code task by task.

**Amazon Kiro**: VS Code extension that guides users through requirements, design, and task creation stages before any code generation begins.

**Tessl**: Most radical approach—spec-as-source where the specification is the maintained artifact and code is regenerated from it. Represents the emerging vision of "specs as the new source code."

---

## VI Case Studies

### VI-A API-First Microservices (Financial Services)
- **Pattern:** Spec-anchored with OpenAPI
- **Outcome:** 75% reduction in integration cycle time
- Used Specmatic to generate mock servers from specs, enabling frontend and backend parallel development. Any deviation caused the build to fail, preventing drift.

### VI-B BDD for Enterprise Features (Project Management Software)
- **Pattern:** Spec-anchored with Cucumber
- **Outcome:** Stakeholder-verifiable requirements; reduced requirement ambiguity
- Product managers wrote Gherkin scenarios; a feature was "done" only when all scenarios passed.

### VI-C Model-Based Embedded Development (Automotive)
- **Pattern:** Spec-as-source with Simulink
- **Outcome:** Verified control logic; certified code generation (ISO 26262)
- Engineers model control algorithms in Simulink, verify behavior at the model level, then auto-generate certified C code that nobody hand-edits.

---

## VII The Redefinition of Developer Work

SDD fundamentally reshapes what it means to be a software developer. Work is being redefined as developers shift from manual coding to orchestrating specifications, reviewing AI outputs, and focusing on high-level design.

- **Greenfield projects:** Developers become architects who design systems through specifications rather than code
- **Brownfield/legacy projects:** SDD enables encoding existing behavior as specifications before making changes
- In each case, the developer's role shifts from code producer to **spec author and AI orchestrator**

---

## VIII When to Use SDD

**SDD adds clear value when:**
- Using AI coding assistants (specs dramatically improve output quality)
- Dealing with complex requirements (stakeholders can validate before code is written)
- Systems with multiple maintainers (specs serve as documentation)
- Integration-heavy systems (API specs enable parallel development)
- Regulated domains (SDD provides natural traceability from requirements to implementation)
- Legacy modernization (specs for existing behavior enable clean reimplementation)

**SDD may be overkill when:**
- Throwaway prototypes
- Solo, short-lived projects
- Exploratory coding where you don't yet know what you're building
- Simple CRUD applications with obvious requirements

**The Golden Rule:** Use the minimum level of specification rigor that removes ambiguity for your context.

---

## IX Common Pitfalls

1. **Over-specification**: Specs that read like pseudo-code, constraining implementation unnecessarily
2. **Specification rot**: Specs drift from reality when not updated as code changes
3. **Specification as bureaucracy**: Forms to fill out rather than tools for clarity
4. **Tooling complexity**: Drowning in generated artifacts without demonstrable value
5. **False confidence**: A passing spec test only guarantees the software matches the spec, not that the spec itself is correct

---

## X SDD vs Traditional Design Documents

Traditional design documents (HLD, LLD, SRS) are specifications—the difference lies not in what is written, but in **how it is used and whether it stays aligned with code**. Traditional specs drift; SDD specs are enforced.

**What SDD actually adds:**
1. **Executable specifications**: BDD scenarios, API contract tests, or model simulations fail if code doesn't match
2. **CI/CD integration**: Spec validation embedded in continuous integration catches drift immediately
3. **AI consumption**: SDD specs are structured so AI coding assistants can consume them, generating code from specs rather than guessing from vague prompts

As Bryan Finster observed: "SDD is not a revolution… it's just BDD with branding." But the branding reminds practitioners that specs should be authoritative, not advisory.

---

## XI Relationship to Existing Practices

- **TDD**: SDD at the unit level—extended to higher levels (features, systems, architectures)
- **BDD**: The most direct ancestor of modern SDD—Gherkin scenarios are executable specifications
- **DDD**: Aligns through ubiquitous language—specs written in domain terms both developers and stakeholders understand
- **Agile**: User stories with acceptance criteria are specifications; SDD treats them as authoritative rather than advisory

---

## XII Conclusion

Spec-driven development inverts the traditional relationship between specifications and code. As AI coding assistants become more capable, the bottleneck shifts to specification quality. The three levels of rigor provide options matching different project needs. Teams should match rigor to need, using the minimum specification discipline that removes ambiguity for their context.

---

## References

1. GitHub, "Spec-Driven Development with AI: Get Started with a New Open Source Toolkit," GitHub Blog, 2025.
2. Thoughtworks, "Spec-Driven Development," Technology Radar, Vol. 32, 2025.
3. B. Finster, "5-Minute DevOps: Spec-Driven Development Isn't New," Medium, Nov. 2025.
4. M. Fowler, "Exploring Gen AI: Spec-Driven Development," martinfowler.com, 2025.
5. L. Griffin and R. Carroll, "Spec Driven Development: When Architecture Becomes Executable," InfoQ, Jan. 2026.
6. R. Naszcyniec, "How Spec-Driven Development Improves AI Coding Quality," Red Hat Developer, 2025.
7. Cucumber, "Cucumber Documentation," cucumber.io, 2024.
8. OpenAPI Initiative, "OpenAPI Specification v3.1.0," 2024.
9. Specmatic, "Contract-Driven Development with Specmatic," 2025.
10. MathWorks, "Simulink: Simulation and Model-Based Design," 2024.
11. K. Beck, Test Driven Development: By Example, Addison-Wesley, 2002.
12. D. North, "Introducing BDD," dannorth.net, Mar. 2006.
13. Amazon Web Services, "Kiro: Agentic AI Development from Prototype to Production," 2025.
14. Tessl, "Tessl: Make Agents Work in Real Codebases," 2025.
15. GitHub, "GitHub Copilot Documentation," 2024.
16. Cucumber, "Gherkin Reference," 2024.
17. GraphQL Foundation, "GraphQL Specification," 2024.
18. Google, "Protocol Buffers Documentation," 2024.
19. gRPC Authors, "gRPC Documentation," 2024.
20. Pact Foundation, "Pact Documentation," 2024.
21. Reqnroll Contributors (formerly SpecFlow), "Reqnroll Documentation," 2024.
22. Behave, "Behave: BDD for Python," 2024.
23. SmartBear, "What Is API-First Development?," Swagger.io, 2024.
24. B. Meyer, "Applying Design by Contract," IEEE Computer, vol. 25, no. 10, pp. 40–51, 1992.
25. ISO, "ISO 26262: Road vehicles – Functional safety," 2018.
26. S. J. Mellor and M. J. Balcer, Executable UML: A Foundation for Model-Driven Architecture, Addison-Wesley, 2002.
27. AsyncAPI Initiative, "AsyncAPI Specification," 2024.
28. M. Chen et al., "Evaluating Large Language Models Trained on Code," arXiv:2107.03374, 2021.
