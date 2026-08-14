# Lost in the Middle: How Language Models Use Long Contexts

**Source:** https://arxiv.org/abs/2307.03172
**Authors:** Nelson F. Liu, Kevin Lin, John Hewitt, Ashwin Paranjape, Michele Bevilacqua, Fabio Petroni, Percy Liang
**Year:** 2023 (preprint); 2024 (published in TACL)
**arXiv ID:** 2307.03172 [cs.CL]
**DOI:** https://doi.org/10.48550/arXiv.2307.03172
**Category:** Long Context Research

## Abstract / Summary

While recent language models have the ability to take long contexts as input, relatively little is known about how well they use longer context. The authors analyze the performance of language models on two tasks that require identifying relevant information in their input contexts: multi-document question answering and key-value retrieval. Performance can degrade significantly when changing the position of relevant information, indicating that current language models do not robustly make use of information in long input contexts. Performance is often highest when relevant information occurs at the beginning or end of the input context, and significantly degrades when models must access relevant information in the middle of long contexts, even for explicitly long-context models.

## Key Findings

- **Positional bias confirmed across models:** All examined language models (including those explicitly designed for long contexts) show a pronounced bias toward beginning and end positions.
- **Multi-document QA benchmark:** Using the NaturalQuestions-Open dataset, performance varies significantly based solely on which position the relevant document is placed in a list of 20 documents.
- **Key-value retrieval task:** A synthetic retrieval task over long JSON inputs confirms the same U-shaped performance curve.
- **Performance degrades in the middle:** Even when relevant information is present in the context, models fail to reliably retrieve it if it is positioned in the middle.
- **Evaluation protocol contribution:** Proposed new evaluation methodologies for long-context language model benchmarking.

## Content

### Submission History

- **[v1]** Thu, 6 Jul 2023 17:54:11 UTC (410 KB)
- **[v2]** Mon, 31 Jul 2023 17:48:48 UTC (416 KB)
- **[v3]** Mon, 20 Nov 2023 23:09:34 UTC (334 KB)

### Abstract (Full)

While recent language models have the ability to take long contexts as input, relatively little is known about how well they use longer context. We analyze the performance of language models on two tasks that require identifying relevant information in their input contexts: multi-document question answering and key-value retrieval. We find that performance can degrade significantly when changing the position of relevant information, indicating that current language models do not robustly make use of information in long input contexts. In particular, we observe that performance is often highest when relevant information occurs at the beginning or end of the input context, and significantly degrades when models must access relevant information in the middle of long contexts, even for explicitly long-context models. Our analysis provides a better understanding of how language models use their input context and provides new evaluation protocols for future long-context language models.

### Metadata

- **Subjects:** Computation and Language (cs.CL)
- **Paper length:** 18 pages, 16 figures
- **Status:** Accepted for publication in Transactions of the Association for Computational Linguistics (TACL), 2023

### Notes

This is the arXiv preprint of the paper officially published in TACL 2024 (ACL Anthology ID: 2024.tacl-1.9). The published version is also available at:
- ACL Anthology: https://aclanthology.org/2024.tacl-1.9
- MIT Press DOI: https://doi.org/10.1162/tacl_a_00638

See also `lost-in-the-middle-acl.md` for the official ACL Anthology entry and `lost-in-the-middle-researchgate.md` for the ResearchGate reference.
