# LLM Multilingual Performance & Language Overhead

**Scope**: Tokenization disparities across languages, internal English-thinking phenomenon, empirical performance gaps, self-translate strategy, English vs Portuguese deep dive, and specialized Portuguese LLMs.  
**Part of**: [LLM Context Engineering: Comprehensive Research Synthesis](research-context-engineering-comprehensive.md)  
**Related scopes**: [Context Rot & Management](research-context-rot-and-management.md) | [Whitespace & Formatting](research-whitespace-and-formatting.md) | [Agent Workflows](research-agent-workflows-and-patterns.md)

---

## Part 10: Multilingual Performance — English Dominance

### 10.1 The Tokenization Tax

The same semantic content requires dramatically more tokens in non-English languages:

| Language | Median tokens (same content) | Ratio vs. English | Source |
|----------|-----|------|--------|
| English | 7 | 1× (baseline) | Jun (2023) |
| Spanish | ~8 | ~1.1× | Jun (2023) |
| French | ~9 | ~1.3× | Jun (2023) |
| German | ~10 | ~1.4× | Jun (2023) |
| Chinese | ~15 | ~2× | Jun (2023) |
| Hindi | ~35 | ~5× | Jun (2023) |
| Armenian | ~63 | ~9× | Jun (2023) |
| Burmese | 72 | ~10× | Jun (2023) |

**Source**: Yennie Jun, "All Languages Are Not Created (Tokenized) Equal," using cl100k_base on Amazon MASSIVE parallel dataset (2,033 texts × 52 languages)

Petrov et al. (NeurIPS 2023) found disparities of **up to 15×** across languages, even for tokenizers designed for multilingual use.

### 10.2 Models Think in English Internally

**Source**: Wendler, C. et al. "Do Multilingual Language Models Think in English?" 2024. [arXiv:2402.10588](https://arxiv.org/abs/2402.10588)

Three phases in how multilingual LLMs process non-English input:
1. **Input phase**: Embeddings start in "input space"
2. **Concept phase**: Middle layers give **higher probability to English versions** of concepts
3. **Output phase**: Final layers move into input-language-specific region for generation

> *"The abstract 'concept space' lies closer to English than to other languages."*

### 10.3 Empirical Performance Gap

| Study | Languages | Key Finding |
|-------|-----------|-------------|
| Lai et al. (2023) | 37 languages, 7 tasks | ChatGPT **consistently worse** on non-English |
| Adelani et al. (EACL 2024) | 200 languages | Large gap even on simple topic classification |
| Ahuja et al. (2024) | 83 languages, 22 datasets | GPT-4 leads but significant gaps remain |
| Shi et al. (Google, 2022) | 10 languages | **English CoT prompts outperform native-language prompts** even for problems in other languages |
| Qin et al. (EMNLP 2023) | Multiple | Cross-lingual prompting outperforms standard non-English CoT |

### 10.4 The Self-Translate Strategy

**Source**: Etxaniz et al. "Do Multilingual Language Models Think Better in English?" 2023. [arXiv:2308.01223](https://arxiv.org/abs/2308.01223)

Having the LLM translate non-English input to English before processing:
- **Consistently outperforms direct non-English inference**
- Works using the LLM's own translation (no external MT system needed)
- The gap is **bigger for models with higher capabilities** — as models improve, the advantage of thinking in English increases
- For Romance languages, the improvement is **smaller** than for distant languages but still consistent

---

## Part 11: English vs Portuguese — Deep Dive

### 11.1 Tokenization: Exact Numbers

**Source**: Petrov, A. et al. "Language Model Tokenizers Introduce Unfairness Between Languages." *NeurIPS*, 2023. [arXiv:2305.15425](https://arxiv.org/abs/2305.15425). FLORES-200 parallel corpus (997 sentences).

| Tokenizer | English | Portuguese | PT/EN Ratio | Overhead |
|---|---|---|---|---|
| **cl100k_base** (GPT-4) | 52,835 | 78,313 | **1.48×** | +48.2% |
| **GPT-2** (r50k_base) | 52,567 | 101,774 | **1.94×** | +93.6% |
| **LLaMA** | 60,621 | 86,127 | **1.42×** | +42.1% |
| **Qwen** | 53,726 | 78,171 | **1.45×** | +45.5% |
| **BLOOM** (multilingual) | 53,174 | 59,813 | **1.12×** | +12.5% |
| **XLM-RoBERTa** | 59,656 | 66,406 | **1.11×** | +11.3% |

**Portuguese is the most efficiently tokenized Romance language on cl100k_base:**

| Language | cl100k_base Tokens | vs English |
|---|---|---|
| **Portuguese** | 78,313 | **1.48×** (best) |
| Spanish | 81,735 | 1.55× |
| French | 84,407 | 1.60× |
| Italian | 86,628 | 1.64× |

### 11.2 Portuguese Tokenization Characteristics

**Diacritics** (ã, õ, ç, é, ê, á, à, â, í, ó, ô, ú):
- On English-centric tokenizers (GPT-2): accented characters split into multi-byte sequences, explaining the ~1.94× ratio
- On modern tokenizers (cl100k_base): many common Portuguese words with diacritics are in vocabulary, reducing ratio to ~1.48×
- On multilingual tokenizers (BLOOM, XLM-RoBERTa): well-covered, ratio drops to ~1.11×

**Morphological impact**: Portuguese has rich verb conjugation (~100+ forms/verb vs ~5 in English), gender agreement, clitic pronouns ("diga-me", "fazê-lo"), and productive diminutives/augmentatives ("casinha", "casarão") — all increasing vocabulary diversity and token fragmentation.

### 11.3 Portuguese-Specific LLM Models

**Sabiá (BRACIS 2023)**: Pires et al. [arXiv:2304.07880](https://arxiv.org/abs/2304.07880)
- Further pretrained GPT-J and LLaMA on Portuguese text using ≤3% of original budget
- Sabiá-65B performed **on par with GPT-3.5-turbo** on Portuguese tasks
- Benefits came from domain-specific knowledge (Brazilian culture, law) rather than purely linguistic improvements

**Sabiá-2 (2024)**: Pires et al. [arXiv:2403.09887](https://arxiv.org/abs/2403.09887)
- **Matches or beats GPT-4 in 23/64 Brazilian exams** (36%)
- **Beats GPT-3.5 in 58/64 exams** (91%)
- **10× cheaper per token** than GPT-4
- Evaluated on ENEM, ENADE, BLUEX, OAB (Bar Exam), POSCOMP, medical residency exams

**Cabrita**: Larcher et al. [arXiv:2308.11878](https://arxiv.org/abs/2308.11878)
- Portuguese-optimized tokenizer: a 3B model matched 7B English-pretrained model performance

**BERTimbau (BRACIS 2020)**: Souza et al. First dedicated BERT for Brazilian Portuguese, trained on brWaC (~2.68B tokens).

**TeenyTinyLlama (2024)**: Corrêa et al. [arXiv:2401.16640](https://arxiv.org/abs/2401.16640). Compact models (160M, 460M) pre-trained on Brazilian Portuguese for $500 USD.

### 11.4 Portuguese in Multilingual Benchmarks

- **MGSM** (Shi et al.): Portuguese is one of 10 evaluated languages. As a high-resource Romance language, it sits in the higher-performing tier alongside Spanish, French, and German
- **MEGA** (Ahuja et al., EMNLP 2023): Portuguese performs closer to English than truly low-resource languages
- **SIB-200** (Adelani et al., EACL 2024): Portuguese performs in the upper tier as a high-resource language
- **BLOOMZ/mT0** (Muennighoff et al., ACL 2023): Portuguese benefits significantly from cross-lingual transfer due to good pretraining representation

### 11.5 Self-Translate for Portuguese

**Source**: Etxaniz et al. [arXiv:2308.01223](https://arxiv.org/abs/2308.01223)

- Self-translate improvement is **smaller for high-resource Romance languages** (Portuguese, Spanish, French) than for distant/low-resource languages
- Portuguese has good pretraining representation AND is typologically close to English → smaller internal representation gap
- **Self-translate still provides consistent improvement** even for Portuguese
- Translation quality PT→EN is high (high-resource pair), so little information is lost

### 11.6 Practical Implications for Portuguese

1. **The tokenization penalty is moderate (~1.48×)** — Portuguese costs about 48% more tokens than English on GPT-4's tokenizer, the best among Romance languages
2. **System prompts and instructions should still be in English** — the model reasons better in English even for Portuguese tasks
3. **For Portuguese-domain tasks** (Brazilian law, culture, geography), specialized models like Sabiá-2 can match or beat general-purpose models
4. **Multilingual tokenizers nearly eliminate the gap** — BLOOM achieves only 1.12× overhead for Portuguese
5. **European Portuguese (pt-PT) is underrepresented** — nearly all research focuses on Brazilian Portuguese

---

## Cited Sources (this scope)

### Multilingual & Tokenization (25–35)

| # | Authors | Title | Venue | Link |
|---|---------|-------|-------|------|
| 25 | Petrov et al. | Tokenization Unfairness Between Languages | NeurIPS 2023 | [arXiv:2305.15425](https://arxiv.org/abs/2305.15425) |
| 26 | Jun, Y. | All Languages Not Created (Tokenized) Equal | artfish.ai, 2023 | [artfish.ai](https://www.artfish.ai/p/all-languages-are-not-created-tokenized) |
| 27 | Wendler et al. | Do Multilingual LMs Think in English? | 2024 | [arXiv:2402.10588](https://arxiv.org/abs/2402.10588) |
| 28 | Etxaniz et al. | Self-Translate Strategy | 2023 | [arXiv:2308.01223](https://arxiv.org/abs/2308.01223) |
| 29 | Shi et al. (Google) | Multilingual Chain-of-Thought Reasoners | 2022 | [arXiv:2210.03057](https://arxiv.org/abs/2210.03057) |
| 30 | Lai et al. | ChatGPT Beyond English | 2023 | [arXiv:2304.05613](https://arxiv.org/abs/2304.05613) |
| 31 | Ahuja et al. | MEGA: Multilingual Evaluation | EMNLP 2023 | [arXiv:2311.07463](https://arxiv.org/abs/2311.07463) |
| 32 | Adelani et al. | SIB-200 Topic Classification | EACL 2024 | [arXiv:2309.07445](https://arxiv.org/abs/2309.07445) |
| 33 | Qin et al. | Cross-lingual Prompting | EMNLP 2023 | [arXiv:2310.14799](https://arxiv.org/abs/2310.14799) |
| 34 | Jiao et al. | ChatGPT as Translator / Pivot Prompting | 2023 | [arXiv:2301.08745](https://arxiv.org/abs/2301.08745) |
| 35 | Bang et al. | Multitask Multilingual Evaluation | AACL 2023 | [arXiv:2302.04023](https://arxiv.org/abs/2302.04023) |

### Portuguese-Specific Research (36–42)

| # | Authors | Title | Venue | Link |
|---|---------|-------|-------|------|
| 36 | Pires et al. | Sabiá: Portuguese LLMs | BRACIS 2023 | [arXiv:2304.07880](https://arxiv.org/abs/2304.07880) |
| 37 | Pires et al. | Sabiá-2 | Tech Report, 2024 | [arXiv:2403.09887](https://arxiv.org/abs/2403.09887) |
| 38 | Larcher et al. | Cabrita: Portuguese Tokenizer | 2023 | [arXiv:2308.11878](https://arxiv.org/abs/2308.11878) |
| 39 | Corrêa et al. | TeenyTinyLlama | ML with Apps, 2024 | [arXiv:2401.16640](https://arxiv.org/abs/2401.16640) |
| 40 | Souza et al. | BERTimbau | BRACIS 2020 | [DOI](https://doi.org/10.1007/978-3-030-61377-8_28) |
| 41 | Muennighoff et al. | BLOOMZ/mT0 Crosslingual | ACL 2023 | [arXiv:2211.01786](https://arxiv.org/abs/2211.01786) |
| 42 | Zhu et al. | LLMs for Massive Translation | NAACL 2024 | [arXiv:2304.04675](https://arxiv.org/abs/2304.04675) |
