# An unreachable bar

Open this when a critic keeps returning the same grade round after round, or
before writing the exit a critic is briefed with — read it before touching the
work.

## An unreachable bar measures nothing

This is the failure that costs the most, because it looks like diligence.

Tell a critic to pass the work "only when you cannot name a concrete improvement" and it will
never pass anything. In any artifact of real size there is always something nameable — a
sharper example, a tighter sentence, a better heading. The grade stops carrying information: the
work improves, the score does not move, and the loop runs until something else stops it.

**Measured.** Eight documents ran four rounds under exactly that instruction. Every round
returned the same grade with six to ten blocking defects, and nothing in the number said whether
the work was getting better. The instruction was replaced with a checkable one — *no remaining
defect changes what someone following this would DO* — and findings were split into two buckets:

```
blocking   a contradiction, a term that silently becomes a second term, a step whose
           mechanism appears nowhere, a required item that is missing, a claim the work
           itself falsifies, ground another piece already owns

polish     wording you would tighten, an example you would sharpen, a heading you would
           rename — real suggestions that never hold the work below the bar
```

The next round returned 0–3 blocking per document, with the rest of each critic's findings landing
in polish, and two documents passed for the first time. Read those two numbers separately, because they say different
things. **The relabelling is what made the grade carry information** — the same critic, given
somewhere to put a rename, stopped reporting ten defects where two were load-bearing. **The passes
came from the round that followed**, which was a real build round: no work was relabelled into
passing, and a loop that could do that would be the failure this section opens on.

**So write the exit as an observable property, and give the critic somewhere to put the
rest.** Without the second bucket a conscientious critic files the rename as blocking, because
filing it nowhere feels like hiding it. A critic that can only reject has been given a rubber
stamp with one word on it.

**Even a checkable exit leaves one question open: which paths count.** A careful critic of a
system with state will trace ever rarer states — a crash between two writes, a file edited by
hand, data left by an earlier version — and each one is a real defect. Where the line sits is the
**human's** decision, not the loop's: *"blocking only when reachable on the normal path; the rest
is polish"* is a legitimate bar, and so is its opposite. Ask; then write the answer, word for
word, into every critic's brief — a critic that was never told the line redraws it.
