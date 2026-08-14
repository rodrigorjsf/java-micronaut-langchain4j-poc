# Lost in the Middle: How Language Models Use Long Contexts

**Source:** ResearchGate — https://www.researchgate.net (direct fetch returned 403 Forbidden; paper referenced in multiple ResearchGate-hosted works)
**Authors:** Nelson F. Liu, Kevin Lin, John Hewitt, Ashwin Paranjape, Michele Bevilacqua, Fabio Petroni, Percy Liang
**Year:** 2023 (preprint); 2024 (TACL publication)
**Category:** Long Context Research

> **Note:** ResearchGate blocks automated fetching (HTTP 403). This file is compiled from cross-referenced citations found in other ResearchGate-hosted publications and the canonical arXiv/ACL sources. The full paper is openly available at https://arxiv.org/abs/2307.03172.

## Abstract / Summary

While recent language models have the ability to take long contexts as input, relatively little is known about how well they use longer context. The authors analyze language model performance on two tasks requiring identification of relevant information in long input contexts: multi-document question answering and key-value retrieval. Performance can degrade significantly when changing the position of relevant information — current language models do not robustly make use of information in long input contexts. Performance is often highest when relevant information occurs at the beginning or end of the input context, and significantly degrades when models must access relevant information in the middle of long contexts, even for explicitly long-context models.

## Key Findings

- **The "Lost in the Middle" effect:** LLMs exhibit a pronounced positional bias — they are far more capable of using information at the beginning or end of a long context than information in the middle.
- **Over 20% performance drop:** In multi-document QA tasks, accuracy can drop by more than 20 percentage points when the answer document is placed in the center of the context vs. the edges.
- **Affects diverse models:** The effect is observed across ChatGPT, Claude, and other long-context LLMs — no model was immune.
- **Practical implications:** For RAG (retrieval-augmented generation) systems, the ordering of retrieved documents can dramatically affect answer quality — most relevant documents should be placed at the beginning or end.
- **Recency bias noted:** Models show a secondary bias toward information near the end of the context (recency bias), in addition to primacy bias at the start.

## Content

### Paper Identity

This is the same paper as:
- **arXiv:** https://arxiv.org/abs/2307.03172
- **ACL Anthology (TACL 2024):** https://aclanthology.org/2024.tacl-1.9
- **MIT Press DOI:** https://doi.org/10.1162/tacl_a_00638

### ResearchGate Presence

The paper is cited extensively in ResearchGate-hosted publications such as:
- "Learning to Reduce: Towards Improving Performance of Large Language Models on Structured Data" (ResearchGate publication 381960094)
- "CUB: Benchmarking Context Utilisation Techniques for Language Models" (ResearchGate publication 391991890)
- "On Extrapolation of Long-Text Translation with Large Language Models" (ResearchGate, Longyue Wang et al.)

### Access

As of 2024, ResearchGate's primary hosting of this paper redirects to arXiv. The full open-access PDF is available at:
- https://aclanthology.org/2024.tacl-1.9.pdf
- https://arxiv.org/pdf/2307.03172

### Citation

Nelson F. Liu, Kevin Lin, John Hewitt, Ashwin Paranjape, Michele Bevilacqua, Fabio Petroni, and Percy Liang. 2024. *Lost in the Middle: How Language Models Use Long Contexts*. Transactions of the Association for Computational Linguistics, 12:157–173. https://doi.org/10.1162/tacl_a_00638
