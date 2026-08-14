# Increase Output Consistency — Anthropic Guardrails Guide

**Source:** https://docs.anthropic.com/en/docs/test-and-evaluate/strengthen-guardrails/increase-consistency
**Category:** Structured Outputs (Anthropic)

## Summary

Anthropic's guide on strengthening guardrails for Claude output consistency. For guaranteed JSON schema conformance, Anthropic recommends using Structured Outputs instead of the prompt engineering techniques described here. The page covers general-purpose consistency techniques including output format specification, response prefilling (where supported), constraining with examples, retrieval-augmented context, prompt chaining for complex tasks, and maintaining consistent character in role-based applications.

## Content

> **For guaranteed JSON schema conformance:** If you need Claude to always output valid JSON that conforms to a specific schema, use [Structured Outputs](./anthropic-structured-outputs.md) instead of the prompt engineering techniques below. Structured outputs provide guaranteed schema compliance and are specifically designed for this use case.
>
> The techniques below are useful for general output consistency or when you need flexibility beyond strict JSON schemas.

---

## Specify the Desired Output Format

Precisely define your desired output format using JSON, XML, or custom templates so that Claude understands every output formatting element you require.

Include in your system prompt or user message:
- The exact format (JSON, XML, Markdown, plain text)
- Required fields and their types
- Any ordering or nesting requirements
- Example output structure

---

## Prefill Claude's Response

> **Not supported** on Claude Mythos Preview, Claude Opus 4.7, Claude Opus 4.6, and Claude Sonnet 4.6. Use [structured outputs](./anthropic-structured-outputs.md) or system prompt instructions instead.

Prefill the `Assistant` turn with your desired format. This trick bypasses Claude's friendly preamble and enforces your structure.

**Example:**

```python
response = client.messages.create(
    model="claude-haiku-4-5",
    max_tokens=1024,
    messages=[
        {"role": "user", "content": "Extract the name and email from: Alice Smith, alice@example.com"},
        {"role": "assistant", "content": '{"name": "'}  # prefill starts the JSON
    ]
)
```

By starting Claude's response with `{"name": "`, Claude is constrained to continue in JSON format.

---

## Constrain with Examples

Provide examples of your desired output. This trains Claude's understanding better than abstract instructions.

**Example: Few-shot prompting for consistent format**

```
System: You extract contact information and return it in this exact format:
Name: [full name]
Email: [email address]
Phone: [phone number or "N/A"]

Example output:
Name: Jane Doe
Email: jane@example.com
Phone: 555-9876

User: Here is an email from Bob Jones at bob@acme.com, phone 555-1234.
```

This primes Claude to follow the same format reliably across varied inputs.

---

## Use Retrieval for Contextual Consistency

For tasks requiring consistent context (e.g., chatbots, knowledge bases), use retrieval to ground Claude's responses in a fixed information set. This prevents Claude from hallucinating or drifting from the source material, which is especially important when the output format must reference specific entities or values from a corpus.

---

## Chain Prompts for Complex Tasks

Break down complex tasks into smaller, consistent subtasks. Each subtask gets Claude's full attention, reducing inconsistency errors across scaled workflows.

**Example pipeline:**

1. **Step 1**: Extract raw data from the input document → returns unstructured text
2. **Step 2**: Parse the extracted text into a structured schema → returns JSON
3. **Step 3**: Validate and enrich the JSON with business logic → returns final output

Chaining allows each step to have its own clear format constraint, making it easier to detect and handle failures at each stage.

---

## Keep Claude in Character

For role-based applications, maintaining consistent character requires deliberate prompting.

- **Use system prompts to set the role:** Use system prompts to define Claude's role and personality. Provide detailed information about the personality, background, and any specific traits or quirks. This sets a strong foundation for consistent responses.

- **Prepare Claude for possible scenarios:** Provide a list of common scenarios and expected responses in your prompts. This "trains" Claude to handle diverse situations without breaking character.

**Example system prompt for role consistency:**

```
You are Aria, a friendly customer support assistant for Acme Inc. 
You always respond in English, use a warm and professional tone, 
and never discuss competitors. If you don't know the answer, 
say: "I'll need to check on that for you — let me get back to you shortly."
```

---

## When to Use Structured Outputs vs. These Techniques

| Situation | Recommended Approach |
|-----------|---------------------|
| Need guaranteed JSON schema compliance | **Structured Outputs** (`output_config.format`) |
| Need guaranteed schema-valid tool inputs | **Strict Tool Use** (`strict: true`) |
| Need general JSON-like output with flexibility | Prompt engineering + prefilling |
| Need consistent tone/character | Role system prompts + examples |
| Need contextual consistency across a session | Retrieval-augmented prompting |
| Complex multi-step workflow | Prompt chaining |
