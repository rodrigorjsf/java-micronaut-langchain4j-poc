# Where the suite runs

Open this when deciding whether the query set is an ordinary unit test or a live
suite, or when tempted to cache embeddings.

## Where this suite runs is decided by the embedding model

Not by preference, and not by where the other tests happen to live.

**An in-process embedding model means no key, no network and a reproducible
number**, so the suite is an ordinary unit test that can gate every commit. The
figures recorded in this repository's `rag/EmbeddingModelFactory.java`, as an
illustration of the shape and not as a target: a quantized MiniLM on ONNX Runtime,
~14 ms per embedding and ~5.7 s to load. Per row that is free; per suite start it
is a fixed several seconds, which is an argument for one suite that loads the model
once, not for fewer rows.

**A hosted embedding API means a key, a bill and somebody else's rate limit**, so
it is a live suite: tagged out of the default build, run before a release, paced.
`agentic-evals` owns that split, the gating that follows from it, and the pacing.

**Find out which you have by reading the ingest path**, where the embedding model
is constructed: a model id string plus credentials is hosted; weights loaded from
a file or a bundled runtime are in-process. A framework default counts as a
decision — read it for the version in the lock file.

**The snapshot temptation, and its cost.** Caching embeddings to make a hosted
suite deterministic pins the vectors, and the suite then cannot notice that the
model changed under you — one of the two regressions it exists to notice. If you
cache, put the model id and version in the cache key and fail the run on a
mismatch rather than silently reusing yesterday's vectors.

Row counts, the band around a score, and how small a regression a suite can see at
all are `agentic-evals`'s ground. Hand off to it; do not invent a competing floor.
