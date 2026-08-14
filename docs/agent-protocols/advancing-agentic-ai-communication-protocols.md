# Advancing Agentic AI through Communication Protocols

**Source:** https://mail.ijsrst.com/index.php/home/article/view/IJSRST25125127 (DOI: https://doi.org/10.32628/IJSRST25125127)
**Category:** Agent Protocols & Communication

## Summary

This paper from the International Journal of Scientific Research in Science and Technology (IJSRST) provides a structured overview of emerging communication standards for AI agents. The authors explore four key protocols — MCP, ACP, A2A, and ANP — arguing that standardized communication frameworks are essential for enabling interoperability, tool discovery, and coordinated task execution across autonomous LLM-powered agents.

## Authors

- Aniket P. Kakde — Department of Computer Science & Engineering, JDIET, Yavatmal, Maharashtra, India
- Karan M. Bhoyar — Department of Computer Science & Engineering, JDIET, Yavatmal, Maharashtra, India
- Muhammad Aiman Shad — Department of Computer Science & Engineering, JDIET, Yavatmal, Maharashtra, India
- Prof. Sudesh A. Bachwani — Department of Computer Science & Engineering, JDIET, Yavatmal, Maharashtra, India

## Keywords

Large Language Models (LLMs), Agent Communication, Interoperability Protocols, Model Context Protocol (MCP), Agent Communication Protocol (ACP), Agent-to-Agent Protocol (A2A), Agent Network Protocol (ANP), Autonomous Agents, Multimodal Messaging, Decentralized Identity (DID), Agentic AI

## Abstract

Autonomous agents powered by Large Language Models (LLMs) require reliable and standardized frameworks to connect tools, exchange contextual information, and synchronize tasks across diverse systems. Despite growing interest in such agents, current integration with external tools remains disjointed. Developers often have to manually create interfaces, handle authentication protocols, and navigate incompatible function-calling standards across platforms. To overcome these limitations and promote the evolution of agentic AI, it is critical to establish standardized communication protocols that ensure interoperability—enabling agents and systems to seamlessly discover each other's capabilities, share data, and coordinate operations.

This paper explores a structured overview of emerging communication standards for agents, focusing on the Model Context Protocol (MCP), Agent Communication Protocol (ACP), Agent-to-Agent Protocol (A2A), and Agent Network Protocol (ANP).

- **MCP** utilizes a JSON-RPC based client-server architecture to enable secure execution of tools and well-typed data transfer.
- **ACP** introduces a REST-compliant message structure with support for asynchronous streaming and multipart formats, facilitating rich, multimodal agent outputs.
- **A2A** enables agents to delegate tasks peer-to-peer using capability-rich Agent Cards, enabling scalable and distributed workflows across organizations.
- **ANP** facilitates agent discovery and secure collaboration in open networks, leveraging decentralized identifiers (DIDs) and semantic graphs based on JSON-LD.

## References

1. D. B. Acharya, K. Kuppan, and B. Divya, "Agentic AI: Autonomous Intelligence for Complex Goals-A Comprehensive Survey," IEEE Access, vol. 13, pp. 18912-18936, 2025.
2. S. J. Russell and P. Norvig, Artificial Intelligence: A Modern Approach, 3rd ed. Prentice Hall, 2010.
3. Y. Yang et al., "A Survey of AI Agent Protocols," arXiv preprint arXiv:2504.16736, 2025.
4. A. Ehtesham, A. Singh, S. Kumar, and G. K. Gupta, "A Survey of Agent Interoperability Protocols: Model Context Protocol (MCP), Agent Communication Protocol (ACP), Agent-to-Agent Protocol (A2A), and Agent Network Protocol (ANP)," arXiv preprint arXiv:2505.02279, 2025.
5. L. Wang et al., "A survey on large language model based autonomous agents," Frontiers of Computer Science, vol. 18, no. 6, Mar. 2024.
6. X. Hou, Y. Zhao, S. Wang, and H. Wang, "Model Context Protocol (MCP): Landscape, Security Threats, and Future Research Directions," arXiv preprint arXiv:2503.23278, 2025.
7. OpenAI, "Function calling," OpenAI Platform, 2023. Available: https://platform.openai.com/docs/guides/function-calling
8. Google, "Agent2Agent (A2A) Protocol," 2025. Available: https://a2a-protocol.org/latest/
9. IBM BeeAI, "Introduction to Agent Communication Protocol (ACP)," 2024. Available: https://docs.beeai.dev/acp/alpha/introduction
10. Agent Network Protocol Contributors, "Agent Network Protocol (ANP)," 2024. Available: https://github.com/agent-network-protocol/AgentNetworkProtocol
11. M. R. Genesereth and S. P. Ketchpel, "The KQML protocol: A specification of language and communication," in Proc. Third Int. Conf. on Information and Knowledge Management (CIKM), 1993.
12. Foundation for Intelligent Physical Agents, "FIPA Communicative Act Library Specification," 2000. Available: https://www.fipa.org/specs/fipa00037/SC00037J.html

## Protocol Overview

### Model Context Protocol (MCP)
- **Architecture**: JSON-RPC based client-server
- **Primary use**: Connecting agents to external tools, databases, APIs
- **Key feature**: Secure tool execution with well-typed data transfer
- **Created by**: Anthropic (Nov 2024), donated to Linux Foundation

### Agent Communication Protocol (ACP)
- **Architecture**: REST-compliant
- **Primary use**: Asynchronous streaming, multimodal agent outputs
- **Key feature**: Support for multipart message formats
- **Created by**: IBM Research (merged into A2A, Sep 2025)

### Agent-to-Agent Protocol (A2A)
- **Architecture**: HTTP-based, JSON-RPC with SSE streaming
- **Primary use**: Peer-to-peer task delegation between agents
- **Key feature**: Capability-rich Agent Cards for agent discovery
- **Created by**: Google (Apr 2025), donated to Linux Foundation

### Agent Network Protocol (ANP)
- **Architecture**: Decentralized, using DIDs and JSON-LD semantic graphs
- **Primary use**: Agent discovery in open networks, secure collaboration
- **Key feature**: Decentralized identifiers (DIDs) for identity
- **Created by**: Agent Network Protocol Contributors
