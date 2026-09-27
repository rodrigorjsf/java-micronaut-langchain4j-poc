# Metadata and matching

Open this when settling whether this project embeds chunk metadata, or when a
heading or a tag is expected to help a query match.

## Metadata: what travels, and what is matched

Two different questions get confused into one. What **travels with the chunk** is
what makes attribution and filtering possible, and `retrieval-that-earns-its-place`
covers why to have it. What is **embedded** is a
separate setting, and it is the one that changes your writing: if metadata is not
part of the embedded text, a heading kept only in metadata contributes nothing to
matching, and every word that must match has to be in the chunk's own text.

**Determine which your framework does; do not assume.** Read the ingest path for
what is actually passed to the embedding call, or ingest two chunks with identical
text and different metadata and score a query that matches only the metadata — an
unchanged score means metadata is not embedded.

What the embedded case costs: metadata repeated on every chunk dilutes the text's
own signal, since the same few words compete with the sentence they were meant to
help — which is why a heading that matters belongs in the prose whatever the setting
turns out to be.
