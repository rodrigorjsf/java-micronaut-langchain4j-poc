# Towards Fluid Human-Agent Collaboration: From Dynamic Collaboration Patterns to Models of Theory of Mind Reasoning

**Source:** https://pmc.ncbi.nlm.nih.gov/articles/PMC12353729/ (PMCID: PMC12353729, PMID: 40822442)
**DOI:** https://doi.org/10.3389/frobt.2025.1532693
**Journal:** Frontiers in Robotics and AI, 2025
**Category:** Agent Protocols & Communication

## Summary

This paper by Schröder, Heinrich, and Kopp introduces the concept of "fluid collaboration" (FC) — a mode of interaction marked by frequent, dynamic changes in tasks and roles in response to varying environmental demands. The authors argue that FC requires action-oriented Theory of Mind (ToM) reasoning for AI agents to continuously infer and adapt to human intentions in real-time. They introduce "Cooperative Cuisine" (CoCu), an Overcooked!-inspired research environment, and present an empirical study showing that humans naturally engage in dynamic collaboration patterns with minimal explicit communication.

## Authors

- Florian Schröder
- Fabian Heinrich
- Stefan Kopp

**Institution:** Bielefeld University (Germany)

## Keywords

fluid collaboration, theory of mind, dynamic mentalizing, human-agent collaboration, action-oriented reasoning, cooperative cuisine, real-time adaptation, collaborative agents

---

## Abstract

Collaborating in real-life situations rarely follows predefined roles or plans, but is established on the fly and flexibly coordinated by the interacting agents. We introduce the notion of **fluid collaboration (FC)**, marked by frequent changes of the tasks partners assume or the resources they consume in response to varying requirements or affordances of the environment, tasks, or other agents. FC thus necessitates dynamic, action-oriented Theory of Mind reasoning to enable agents to continuously infer and adapt to others' intentions and beliefs in real-time.

In this paper, we discuss how FC can be enabled in human-agent collaboration. We introduce **Cooperative Cuisine**, an interactive environment inspired by the game *Overcooked!* that facilitates human-human and human-agent collaboration in dynamic settings. We report results of an empirical study on human-human collaboration in CoCu, showing how FC can be measured empirically and that humans naturally engage in dynamically established collaboration patterns with minimal explicit communication and relying on efficient mentalizing. We then present an approach to develop artificial agents that can effectively participate in FC. Specifically, we argue for a model of dynamic mentalizing under computational constraints and integrated with action planning. We present first steps in this direction by addressing resource-rational and action-driven ToM reasoning.

---

## 1. Introduction: The Challenge of Fluid Collaboration

Human collaboration in everyday, real-life scenarios is characterized by flexible behavior and adaptive coordination. Consider working together with a friend in the kitchen to prepare a meal:
- Both share a common goal, but subtasks vary considerably
- Who is in charge of what rarely is determined at the outset
- There are rapid changes to tasks in response to arising needs, environmental events, or individual needs of other agents
- Adaptations can be reactive as well as proactive
- Coordination can be signaled implicitly (via behavioral changes) or explicitly (via situated communication)

This highly adaptive way of working together is **fluid collaboration (FC)** — natural and intuitive for humans but in stark contrast to professional teamwork environments where team structures, roles, and interaction protocols are largely predetermined.

### Why FC Matters for AI

FC is beyond the capabilities of current AI-based collaborative agents or robots. Enabling it would constitute a leap for human-agent interaction in settings where:
- Humans and AI differ considerably in skills and abilities
- Explicit negotiation and predetermination of roles is hardly feasible
- Humans identify and coordinate tasks as they arise in response to dynamic changes

---

## 2. Theoretical Background

### 2.1 Human Collaboration and Teamwork

Three core competencies drive effective team success (Salas et al., 2018):
1. **Coordination**: Distributing tasks between team members effortlessly
2. **Communication**: Sharing information, especially proactive communication of future goals
3. **Adaptability**: Adjusting to changing circumstances

**Team cognition** encompasses the collective knowledge structures and cognitive processes that emerge from and support coordinated team performance, including:
- Shared mental models
- Mentalizing (Theory of Mind)
- Communication mechanisms

**Task interdependence** (Saavedra et al., 1993):
- **Hard dependencies**: Structural constraints that must be managed as a team
- **Soft dependencies**: Flexible, opportunistic coordination opportunities

### 2.2 Theory of Mind (ToM) in Collaboration

**Theory of Mind (ToM)** — also called *mentalizing* — is the ability to infer the mental states of others, such as intentions, goals, desires, or emotions (Premack and Woodruff, 1978; Baron-Cohen et al., 1985).

Traditionally studied as offline inference from passive observation (e.g., the Sally-Anne false belief test). However, FC requires:
- **Active engagement** and online participation
- **Continuous inferences** of partners' intentions or beliefs about collaboration patterns
- **Concurrent** inference and task-oriented action

FC requires **online ToM reasoning** that:
1. Proceeds rapidly
2. Operates in service of collaborative action
3. Runs concurrently with task execution

These forms of ToM are not yet fully understood in humans, let alone successfully modeled computationally.

---

## 3. Defining Fluid Collaboration

### Core Properties of FC

FC is characterized by:
- **Frequent task changes**: Assignments of tasks and resources undergo frequent changes
- **Dynamic patterns**: Collaboration patterns become flexible and must be initiated, recognized, and coordinated
- **Minimal explicit communication**: Partners coordinate efficiently without extensive verbal negotiation
- **Efficient mentalizing**: Partners infer each other's intentions through behavioral observation

### Measuring FC Empirically

Two key metrics:
1. **Intertwinement**: How interleaved are the task contributions of different agents
2. **Fluidity**: How frequently and smoothly do task/resource assignments change

---

## 4. Cooperative Cuisine (CoCu) Research Environment

### Design Principles

CoCu is an interactive environment inspired by the game *Overcooked!* that:
- Facilitates human-human and human-agent collaboration in dynamic settings
- Creates time-pressured scenarios requiring adaptive coordination
- Allows empirical measurement of FC patterns
- Can be used to develop and test AI agents for FC participation

### Key Findings from Human-Human Study

1. Humans **naturally engage** in dynamic collaboration patterns
2. These patterns emerge **with minimal explicit communication**
3. Humans rely on **efficient mentalizing** to coordinate
4. FC can be **measured empirically** using objective metrics
5. High-performing pairs show more fluid task transitions

---

## 5. Approach: Artificial Agents for Fluid Collaboration

### Requirements for FC-Capable Agents

1. **Dynamic mentalizing**: Ability to infer partners' intentions in real-time
2. **Resource-rational ToM**: Efficient inference under computational constraints
3. **Action-driven reasoning**: ToM integrated with action planning, not separate
4. **Rapid adaptation**: Fast enough to keep up with human coordination pace

### Dynamic Mentalizing Model

The paper proposes a model of **dynamic mentalizing under computational constraints** that:
- Integrates ToM reasoning with action planning
- Operates online and concurrently with task execution
- Is resource-rational (efficient given cognitive/computational constraints)
- Is action-driven (directly informs action selection)

### Comparison to Existing Approaches

| Approach | Strengths | Limitations for FC |
|----------|-----------|-------------------|
| Multi-Agent Planning | Explicit plan creation | Requires domain knowledge; too slow for real-time FC |
| Multi-Agent Reinforcement Learning | Fast inference | Sample-inefficient; large variance in collaborative settings |
| LLM-based coordination | Flexible, language-based | Not real-time; high latency |
| PACT model (Lohrmann et al., 2024) | Identifiable, predictable behavior | Patterns are fixed after optimization; not fluid |

---

## 6. Related Work in Human-Agent Collaboration

### Team Fluency in Human-Robot Teaming
- **Team fluency** (Hoffman & Breazeal, 2007): Smooth, efficient joint performance
- **Cross-training** (Nikolaidis & Shah, 2013): Improving joint performance by fostering anticipatory behavior

### Ad-Hoc Teamwork
- Coordinating with unfamiliar partners without prior agreement (Stone et al., 2010; Mirsky et al., 2022)
- Self-organizing teams in crisis management: flexible role allocation but risk of role ambiguity

### LLM-Based Multi-Agent Coordination
- LLMs increasingly used for multi-agent task coordination (Liu et al., 2024)
- ToM integration into agent behavior (Li et al., 2023)
- Challenge: Not yet suitable for real-time, action-concurrent FC

---

## 7. Implications for AI Agent Design

### What Current AI Agents Lack for FC

1. **Real-time ToM**: Current agents cannot perform online mentalizing concurrently with action
2. **Dynamic adaptation**: Most agents work with fixed roles and predetermined task assignments
3. **Implicit coordination**: AI agents typically require explicit task assignment, not inference-based coordination
4. **Resource-rational inference**: Computationally efficient enough for time-critical situations

### Design Principles for FC-Capable AI Agents

1. **Integrate perception, ToM, planning, communication, and acting** in a unified framework
2. **Model dynamic, action-oriented mentalizing** rather than static belief inference
3. **Enable proactive behavior**: Anticipate when another agent requires assistance
4. **Support implicit coordination**: Go beyond explicit language-based negotiation
5. **Design for real-time operation**: Inference must be fast enough for concurrent action

---

## References (Selected Key Works)

- Baron-Cohen et al. (1985): Theory of Mind and false belief test
- Premack & Woodruff (1978): Original ToM concept
- Hoffman & Breazeal (2007): Team fluency in human-robot interaction
- Nikolaidis & Shah (2013): Cross-training for human-agent collaboration
- Stone et al. (2010): Ad-hoc teamwork
- Salas et al. (2018): Core competencies for team success
- Li et al. (2023): Theory of Mind in LLM-based agents
- Liu et al. (2024): LLMs for multi-agent task coordination
- Lohrmann et al. (2024): PACT model for predictable robot behavior

---

## Full Citation

Schröder F, Heinrich F and Kopp S (2025) Towards fluid human-agent collaboration: From dynamic collaboration patterns to models of theory of mind reasoning. *Front. Robot. AI* 12:1532693. DOI: 10.3389/frobt.2025.1532693

**PMCID:** PMC12353729 | **PMID:** 40822442
**Access:** https://pmc.ncbi.nlm.nih.gov/articles/PMC12353729/
