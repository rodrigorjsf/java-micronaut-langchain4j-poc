# Claude Cookbook - Anthropic

**Source:** https://github.com/anthropics/anthropic-cookbook
**Category:** Agentic Engineering

## Summary

The Anthropic Claude Cookbook is an official collection of code examples and guides for building with Claude. It covers core AI capabilities (classification, RAG, summarization), tool use and integrations, multimodal capabilities, and advanced techniques including sub-agents, automated evaluations, and prompt caching. The cookbook is community-driven and designed to provide copy-paste code snippets adaptable to any project.

## Content

The Claude Cookbooks provide code and guides designed to help developers build with Claude, offering copy-able code snippets that you can easily integrate into your own projects.

## Prerequisites

To make the most of the examples in this cookbook, you'll need a Claude API key (sign up for free at https://www.anthropic.com).

While the code examples are primarily written in Python, the concepts can be adapted to any programming language that supports interaction with the Claude API.

If you're new to working with the Claude API, the [Claude API Fundamentals course](https://github.com/anthropics/courses/tree/master/anthropic_api_fundamentals) provides a solid foundation.

## Explore Further

- [Anthropic developer documentation](https://docs.claude.com/claude/docs/guide-to-anthropics-prompt-engineering-resources)
- [Anthropic support docs](https://support.anthropic.com)
- [Anthropic Discord community](https://www.anthropic.com/discord)

## Contributing

The Claude Cookbooks thrives on the contributions of the developer community. To avoid duplication of efforts, please review the existing issues and pull requests before contributing.

If you have ideas for new examples or guides, share them on the [issues page](https://github.com/anthropics/anthropic-cookbook/issues).

## Table of Recipes

### Capabilities

- **[Classification](https://github.com/anthropics/anthropic-cookbook/tree/main/capabilities/classification):** Explore techniques for text and data classification using Claude.
- **[Retrieval Augmented Generation](https://github.com/anthropics/anthropic-cookbook/tree/main/capabilities/retrieval_augmented_generation):** Learn how to enhance Claude's responses with external knowledge.
- **[Summarization](https://github.com/anthropics/anthropic-cookbook/tree/main/capabilities/summarization):** Discover techniques for effective text summarization with Claude.

### Tool Use and Integration

- **[Tool use](https://github.com/anthropics/anthropic-cookbook/tree/main/tool_use):** Learn how to integrate Claude with external tools and functions to extend its capabilities.
  - [Customer service agent](https://github.com/anthropics/anthropic-cookbook/blob/main/tool_use/customer_service_agent.ipynb)
  - [Calculator integration](https://github.com/anthropics/anthropic-cookbook/blob/main/tool_use/calculator_tool.ipynb)
  - [SQL queries](https://github.com/anthropics/anthropic-cookbook/blob/main/misc/how_to_make_sql_queries.ipynb)

### Third-Party Integrations

- **[Retrieval augmented generation](https://github.com/anthropics/anthropic-cookbook/tree/main/third_party):** Supplement Claude's knowledge with external data sources.
  - [Vector databases (Pinecone)](https://github.com/anthropics/anthropic-cookbook/blob/main/third_party/Pinecone/rag_using_pinecone.ipynb)
  - [Wikipedia](https://github.com/anthropics/anthropic-cookbook/blob/main/third_party/Wikipedia/wikipedia-search-cookbook.ipynb/)
  - [Web pages](https://github.com/anthropics/anthropic-cookbook/blob/main/misc/read_web_pages_with_haiku.ipynb)
- **[Embeddings with Voyage AI](https://github.com/anthropics/anthropic-cookbook/blob/main/third_party/VoyageAI/how_to_create_embeddings.md)**

### Multimodal Capabilities

- **[Vision with Claude](https://github.com/anthropics/anthropic-cookbook/tree/main/multimodal):**
  - [Getting started with images](https://github.com/anthropics/anthropic-cookbook/blob/main/multimodal/getting_started_with_vision.ipynb)
  - [Best practices for vision](https://github.com/anthropics/anthropic-cookbook/blob/main/multimodal/best_practices_for_vision.ipynb)
  - [Interpreting charts and graphs](https://github.com/anthropics/anthropic-cookbook/blob/main/multimodal/reading_charts_graphs_powerpoints.ipynb)
  - [Extracting content from forms](https://github.com/anthropics/anthropic-cookbook/blob/main/multimodal/how_to_transcribe_text.ipynb)
- **[Generate images with Claude](https://github.com/anthropics/anthropic-cookbook/blob/main/misc/illustrated_responses.ipynb):** Use Claude with Stable Diffusion for image generation.

### Advanced Techniques

- **[Sub-agents](https://github.com/anthropics/anthropic-cookbook/blob/main/multimodal/using_sub_agents.ipynb):** Learn how to use Haiku as a sub-agent in combination with Opus.
- **[Upload PDFs to Claude](https://github.com/anthropics/anthropic-cookbook/blob/main/misc/pdf_upload_summarization.ipynb):** Parse and pass PDFs as text to Claude.
- **[Automated evaluations](https://github.com/anthropics/anthropic-cookbook/blob/main/misc/building_evals.ipynb):** Use Claude to automate the prompt evaluation process.
- **[Enable JSON mode](https://github.com/anthropics/anthropic-cookbook/blob/main/misc/how_to_enable_json_mode.ipynb):** Ensure consistent JSON output from Claude.
- **[Create a moderation filter](https://github.com/anthropics/anthropic-cookbook/blob/main/misc/building_moderation_filter.ipynb):** Use Claude to create a content moderation filter for your application.
- **[Prompt caching](https://github.com/anthropics/anthropic-cookbook/blob/main/misc/prompt_caching.ipynb):** Learn techniques for efficient prompt caching with Claude.

## Additional Resources

- **[Anthropic on AWS](https://github.com/aws-samples/anthropic-on-aws):** Explore examples and solutions for using Claude on AWS infrastructure.
- **[AWS Samples](https://github.com/aws-samples/):** A collection of code samples from AWS which can be adapted for use with Claude.
