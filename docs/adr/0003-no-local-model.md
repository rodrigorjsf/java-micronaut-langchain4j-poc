# 0003 — No local model; hosted chat, in-process embeddings

**Status:** Accepted · **[measured]**

## Context

The brief offered a local model through Ollama as a way to keep the project free
to run, on the stated basis that the machine has 16 GB of RAM and a 12 GB
RTX 3060.

Two of those three facts survived contact with the machine.

```
$ free -g
               total        used        free      shared  buff/cache   available
Mem:               7           3           0           0           4           4

$ nvidia-smi --query-gpu=name,memory.total --format=csv
name, memory.total [MiB]
NVIDIA GeForce RTX 3060, 12288 MiB

$ ollama --version
/bin/bash: line 2: ollama: command not found
```

The host has 16 GB; **WSL2 is given 7**. That 7 GB has to hold the JVM, the floci
container, the Valkey container floci starts, and the Docker daemon itself.
Ollama is also not installed, so adopting it would mean installing a runtime and
pulling a model before the project could be built at all.

The brief's wording was conditional — *"if necessary you can download a small
local model"*. The condition is not met: there is a free path that costs no RAM.

## Decision

**No local model.** Chat runs on hosted models; embeddings run in-process.

- **Chat** — Google Gemini's free tier for both roles, with OpenAI as the
  configured alternative. Cost stays near zero and no memory is consumed by
  weights.
- **Embeddings** — `langchain4j-embeddings-all-minilm-l6-v2-q`, a quantized
  MiniLM running in-process on ONNX Runtime. No container, no GPU, no network.

Measured, in a JUnit test on this machine:

```
DIM=384 LOAD_MS=5715 EMBED_MS=14 HEAP_MB=18
```

384 dimensions, **5.7 s cold start**, 14 ms per embedding, ~18 MB heap. The cold
start is the operationally interesting number: the model must be warmed at
startup, never lazily on the first user request, or one unlucky user pays six
seconds for everyone else's convenience.

## Consequences

**Gained.** Nothing to install before `docker compose up`. Retrieval works
offline and free. The memory budget stays inside 7 GB with room to spare — the
whole local stack costs about 31 MiB of container memory:

```
floci-valkey-chat-cache   3.805 MiB / 7.756 GiB
floci                    26.99  MiB / 7.756 GiB
```

**Given up.** No fully offline chat path, so the chat model is a network
dependency and a rate limit. Retrieval quality is bounded by a 384-dimension
MiniLM, which is weaker than a hosted embedding model on nuanced Portuguese.
`gemini-embedding-001` is configured and available if that bound is ever reached;
the trade is quality against a per-request network call and a cost.

## Revisit if

- WSL2 memory is raised past ~16 GB **and** GPU passthrough to Docker is working,
  making a 7B-class model genuinely cheap to host locally.
- Free-tier rate limits become the binding constraint on load testing, at which
  point a local model stops being a luxury and becomes a throughput fix.

## Related

- [0006 — LLM-as-judge triage](0006-llm-as-judge-triage.md) picks the specific
  models and shows the latency measurements behind that choice.
