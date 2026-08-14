# Lost in the Middle: How Language Models Use Long Contexts

**Source:** https://aclanthology.org/2024.tacl-1.9
**Authors:** Nelson F. Liu, Kevin Lin, John Hewitt, Ashwin Paranjape, Michele Bevilacqua, Fabio Petroni, Percy Liang
**Year:** 2024
**Published in:** Transactions of the Association for Computational Linguistics, Volume 12, pp. 157–173
**DOI:** 10.1162/tacl_a_00638
**Category:** Long Context Research

## Abstract / Summary

While recent language models have the ability to take long contexts as input, relatively little is known about how well they use longer context. The authors analyze the performance of language models on two tasks that require identifying relevant information in their input contexts: multi-document question answering and key-value retrieval. They find that performance can degrade significantly when changing the position of relevant information, indicating that current language models do not robustly make use of information in long input contexts. In particular, performance is often highest when relevant information occurs at the beginning or end of the input context, and significantly degrades when models must access relevant information in the middle of long contexts, even for explicitly long-context models.

## Key Findings

- **U-shaped performance curve:** Language model performance follows a characteristic U-curve based on where the relevant information appears in the context — highest at the beginning and end, lowest in the middle.
- **Middle degradation is significant:** Performance can drop by over 20% in multi-document QA tasks when the answer is placed in the center of the context window.
- **Affects all examined models:** No examined model could process relevant information equally well across all positions — even models explicitly designed for long context suffer from this bias.
- **Two tasks evaluated:** Multi-document question answering (NaturalQuestions-Open) and key-value retrieval tasks both show the same positional bias pattern.
- **New evaluation protocols proposed:** The paper introduces new evaluation methods for assessing long-context language models based on positional robustness.

## Content

### Publication Details

- **Anthology ID:** 2024.tacl-1.9
- **Volume:** Transactions of the Association for Computational Linguistics, Volume 12
- **Year:** 2024
- **Address:** Cambridge, MA
- **Venue:** TACL (Transactions of the Association for Computational Linguistics)
- **Publisher:** MIT Press
- **Pages:** 157–173
- **PDF:** https://aclanthology.org/2024.tacl-1.9.pdf

### Citation (ACL Format)

Nelson F. Liu, Kevin Lin, John Hewitt, Ashwin Paranjape, Michele Bevilacqua, Fabio Petroni, and Percy Liang. 2024. *Lost in the Middle: How Language Models Use Long Contexts*. Transactions of the Association for Computational Linguistics, 12:157–173.

### Abstract (Full)

While recent language models have the ability to take long contexts as input, relatively little is known about how well they use longer context. We analyze the performance of language models on two tasks that require identifying relevant information in their input contexts: multi-document question answering and key-value retrieval. We find that performance can degrade significantly when changing the position of relevant information, indicating that current language models do not robustly make use of information in long input contexts. In particular, we observe that performance is often highest when relevant information occurs at the beginning or end of the input context, and significantly degrades when models must access relevant information in the middle of long contexts, even for explicitly long-context models. Our analysis provides a better understanding of how language models use their input context and provides new evaluation protocols for future long-context language models.

### Notes

This is the official ACL Anthology publication of the paper originally submitted to arXiv as arXiv:2307.03172 in July 2023. The same paper is also accessible via arXiv (see `lost-in-the-middle-arxiv.md`). This TACL 2024 version is the formally peer-reviewed and published version.
