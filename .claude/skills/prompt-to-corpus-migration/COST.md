# What a block costs

Open this when costing the inventory's blocks: the three inputs, the formula, and
how each input is measured rather than guessed.

## What it costs, computed

Three inputs per block, two of them cheap:

```
B  bytes the block adds to the prompt           length of the block, from the payload
C  share of turns that carry it                 1.00 for standing text, by definition
U  share of carrying turns whose answer it changes

waste = B × C × (1 − U)   bytes shipped per turn for nothing
```

The arithmetic is the whole point and it is arithmetic, not a benchmark: at
`C = 1.00` and `U = 0.10`, nine tenths of `B` ships on every turn and does no
work; halving `B` halves the waste, and getting `U` to 1.00 removes it entirely,
which is what the block staying in the prompt means.

**`C` is 1.00 by definition and is not a per-row column.** It is in the formula
so that a block which is only *nearly* standing — carried by one request route of
three, or by one locale's assembly — can be costed on the same line as the rest.

**`U` is the one people guess, and the guess is always generous.** You cannot
measure it without labels. What you can do instead is state the qualifying
condition in one sentence — *this block changes the answer when the user asks
about X*. If you cannot write that sentence, `U` is not small, it is **unknown**,
and the next section's second question has already failed. Write `U unknown` in
the ledger rather than a number; a fabricated share is the one entry that gets an
otherwise correct plan thrown out.

**The second cost is not bytes and does not shrink when the block does.** Text
that is present and irrelevant competes for the model's attention with the text
that matters, and a prompt that answers a question the turn did not ask teaches
the model to answer questions nobody asked: the returns policy in front of the
model during a shipping turn comes back in the answer.

**The third cost is the one that produces wrong answers rather than slow ones.** A
fact in the prompt is the model's closest and most trusted context, so it outranks
the tool that would have returned the current value. A stale fact in the prompt is
worse than an absent one — absent, the model asks a tool or says it does not know;
present, it asserts last quarter's number with the prompt's authority.
