# The storage tier

Reference for [`conversation-memory-and-compaction`](SKILL.md): a conversation
store with a cache in front of it. Everything here is about *where the message
list lives*, not about what it contains.

- **Decide which one is authoritative and the ordering falls out.** Durable store
  first, then the cache; reads try the cache, fall through on a miss, warm it on the
  way back. Both writes replace the whole list, so two concurrent turns on one
  conversation lose messages — lock per conversation in the cache tier, or write
  down that you accepted it.
- **A failed cache write deletes the key**, never leaving the previous value in
  place. A classifier verdict cache cannot go stale — same input, temperature 0,
  same verdict forever — but a conversation cache can, and a stale conversation is
  worse than a slow one: the model answers from a history the user never had, and
  the correction they made two turns ago is gone.
- **An in-process store is an explicit configuration choice, never a fallback.** The
  cache being down costs latency; the durable store being down has to fail the
  request. `agentic-service-composition` owns why a silent fallback is a cascading
  failure; what is specific here is that the store is per node, so the loss arrives
  as *some* turns remembering and some not — which reads as the model behaving
  strangely, and gets diagnosed nowhere near storage.
- **Validate the conversation id before it builds a storage key.** It arrives in a
  request and selects whose history is loaded, which makes it an authorization
  boundary wearing the costume of a string: a wildcard matches other conversations'
  keys, a newline splits a line-protocol command. Accept a narrow character class —
  letters, digits, hyphen, underscore, bounded length — and name each rejected shape
  in a test. **ASI03, Identity and Privilege Abuse.**
- **The cache TTL is at most the durable one.** Reversed, the cache resurrects a
  conversation the durable store already expired, and the deletion you promised a
  user quietly did not happen.
