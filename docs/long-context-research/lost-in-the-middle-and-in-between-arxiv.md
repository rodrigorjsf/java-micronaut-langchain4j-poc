# Lost in the Middle, and In-Between: Enhancing Language Models' Ability to Reason Over Long Contexts in Multi-Hop QA

**Source:** https://arxiv.org/abs/2412.10079
**Authors:** George Arthur Baker, Ankush Raut, Sagi Shaier, Lawrence E Hunter, Katharina von der Wense
**Affiliations:** University of Colorado Boulder; University of Chicago, Department of Pediatrics; Johannes Gutenberg University Mainz
**Year:** 2024
**arXiv ID:** 2412.10079 [cs.CL]
**DOI:** https://doi.org/10.48550/arXiv.2412.10079
**Submitted:** Fri, 13 Dec 2024
**Code:** https://github.com/Spongeorge/long-context-multihop
**Category:** Long Context Research — Follow-up to "Lost in the Middle"

## Abstract / Summary

Previous work finds that recent long-context language models fail to make equal use of information in the middle of their inputs, preferring pieces of information located at the tail ends which creates an undue bias in situations where we would like models to be equally capable of using different parts of the input. Thus far, the problem has mainly only been considered in settings with single pieces of critical information, leading the authors to question what happens when multiple necessary pieces of information are spread out over the inputs. This paper demonstrates the effects of the "lost in the middle" problem in the multi-hop question answering (MHQA) setting — in which multiple reasoning "hops" over disconnected documents are required — and shows that performance degrades not only with respect to the distance of information from the edges of the context, but also between pieces of information. The authors experiment with means of alleviating the problem by reducing superfluous document contents through knowledge graph triple extraction and summarization, and prompting models to reason more thoroughly using chain-of-thought prompting.

## Key Findings

- **Multi-hop degradation discovered:** In multi-hop QA, performance degrades not only based on absolute position of evidence from context edges, but also based on the relative distance *between* multiple evidence documents — the "in-between" effect.
- **Adjacent evidence outperforms separated evidence:** When two required pieces of evidence are adjacent in the context, performance is consistently higher than when they are separated by distractor documents — even when both are at the same absolute position.
- **Chain-of-Thought helps but doesn't solve it:** CoT prompting aids in identifying relevant documents but fails to resolve the performance disparity caused by evidence document positions.
- **Context reduction methods are fragile:** Knowledge graph triple extraction and document summarization reduce context size and can help, but produce reasoning chains that are too fragile for reliable multi-hop QA.
- **Scales combinatorially:** The number of possible evidence-document position permutations grows combinatorially with reasoning steps (190, 1140, 4845 combinations for 2-, 3-, 4-hop settings), making re-ranking approaches impractical.
- **Generalizes across model families:** Results hold across GPT-3.5-Turbo (16k context), MPT-7b-8k-instruct, and Llama-2-7b-longlora-8k-ft.

## Content

### Introduction

Recent advancements in attention mechanisms, such as Flash Attention and Attention with Linear Biases (ALiBi), have ushered in a new generation of language models capable of handling significantly larger context sizes. These developments enable question-answering tasks to be performed over a substantial number of retrieved documents within a single input prompt. However, despite this remarkable progress, recent studies reveal a critical limitation: long-context models fail to utilize information within their inputs equitably, exhibiting a pronounced bias toward information located at the edges of the context — a problem known as the "lost in the middle" phenomenon (Liu et al., 2024).

This paper extends the original "lost in the middle" analysis to the multi-hop QA setting, where models must reason across multiple disconnected documents. The core insight is that multi-hop QA introduces an additional dimension of difficulty: not only does absolute position matter, but so does the relative *distance between* evidence documents.

### Related Work: The "Lost in the Middle" Problem

The "Lost in the Middle" problem, first identified by Liu et al. (2024), highlights a significant limitation in long-context LMs. When relevant information is distributed throughout a long context, model performance varies depending on the information's position. Performance follows a characteristic curve: accuracy is poorest when critical information appears in the middle of the context and improves when the information is near the beginning or end.

Liu et al. proposed Query-Aware Contextualization (placing a query both before and after the request) which effectively resolves the issue in key-value retrieval but has little impact on multi-document question answering.

### Datasets

Three multi-hop QA datasets are used for evaluation:

| Dataset | Hops Required | Notes |
|---------|--------------|-------|
| HotpotQA | 2 | Cross-document reasoning |
| 2WikiMultiHopQA | 2–4 | Multi-step inference chains |
| MuSiQue | 2–4 | Compositional questions |

Because official test sets are private, the authors split existing validation sets: first half for validation, second half for test.

### Models Evaluated

- **MPT-7b-8k-instruct:** Instruction-tuned; trained with ALiBi replacing traditional positional embeddings.
- **Llama-2-7b-longlora-8k-ft:** Fine-tuned Llama 2 for long contexts, without instruction tuning.
- **GPT-3.5-turbo-1106:** 16k context window; closed-source OpenAI model.

### Methodology

For each question, multiple prompts are created by positioning evidence documents at various locations within a total of 20 documents — following Liu et al. (2024). Given the combinatorial explosion of position permutations, a representative subset of orderings is evaluated. Best-subspan accuracy is the evaluation metric (score of 1 if model output contains the annotated answer).

**Context Reduction Methods Tested:**

1. **Knowledge Graph Triple Extraction:** Uses LLaMA 2-7B to extract structured triples from each document, condensing content to key factual relationships.
2. **Document Summarization:** Uses BART-large-CNN (fine-tuned on CNN/Daily Mail) to generate concise ≤50-token summaries of each document.

### Results Summary

- Performance degrades as evidence documents move toward the center of the context (replicating Liu et al.).
- **New finding:** Performance also degrades as the distance *between* evidence documents increases — even when both documents are near the edges.
- CoT prompting improves document identification but does not eliminate positional bias.
- Context reduction methods reduce superfluous content but produce fragile reasoning chains that fail in multi-hop settings.

### Distinction from Contemporaneous Work

This study differs from Levy et al. (2024), which also examines LLM performance degradation with input length:

1. This paper focuses on document position within a fixed-size context; Levy et al. focus on overall input size.
2. This paper evaluates on three popular multi-hop QA datasets; Levy et al. use a custom true/false dataset (FLenQA).
3. This paper studies up to 4-hop reasoning; Levy et al. limit to 2-step questions.

### Code and Data

All code and data are openly available at: https://github.com/Spongeorge/long-context-multihop

### Citation

George Arthur Baker, Ankush Raut, Sagi Shaier, Lawrence E Hunter, Katharina von der Wense. 2024. *Lost in the Middle, and In-Between: Enhancing Language Models' Ability to Reason Over Long Contexts in Multi-Hop QA*. arXiv:2412.10079 [cs.CL]. https://doi.org/10.48550/arXiv.2412.10079
