# A section rewritten so that its chunk answers alone

Open this when rewriting an existing document that does not retrieve, or when
drafting a first section and the rules in [`SKILL.md`](SKILL.md) need a shape.

The domain is invented and neutral: the assistant of a public library, whose
corpus is the library's own rules. The splitter numbers below — **600 characters,
100 of overlap, cuts at structure, three chunks per answer** — are borrowed from
this repository's own splitter and retrieval settings purely to make the example
concrete. Read your own before applying any of it.

## Before

```markdown
## Renewal policy

Our circulation system allows patrons to extend the borrowing period of most
items in the collection. It is applied automatically at the end of the term,
provided that no other patron has placed a hold on the title and that the
account is in good standing as described above. Where this is not possible,
the item must be returned by the due date printed on the receipt, to avoid the
accrual of overdue charges.
```

430 characters, heading included (`wc -m`) — comfortably inside a 600-character
budget, so nothing here is a length problem. It still fails.

## The chunk the model was given

The section is third in the document. The splitter packs whole sections into a
segment until the next one will not fit, then descends to finer granularity for
the run that overflows — so a structural splitter still cuts inside a paragraph,
and here the boundary landed just after the heading:

```
It is applied automatically at the end of the term, provided that no other
patron has placed a hold on the title and that the account is in good standing
as described above. Where this is not possible, the item must be returned by
the due date printed on the receipt, to avoid the accrual of overdue charges.
```

Which cut it was does not matter. **Every cut of that section produces a passage
that cannot stand alone**, and one of them is what the user's question will draw.

## Why it does not retrieve, layer by layer

The user typed: *"can I keep this book longer or do I have to bring it back"*.

| Layer | What went wrong |
|---|---|
| subject | The subject of the chunk is "it". The noun it stands for — the extension of a borrowing period — is in the heading, which the cut left behind. The passage is about nothing |
| first sentence | The section opens with "Our circulation system allows patrons to…". Preamble is what the section is matched on, and preamble is what it matches |
| vocabulary | The document says *circulation*, *patron*, *hold*, *overdue charges*. The user says *keep it longer*, *bring it back*, *reserved*, *fine*. Not one content word meets |
| cross-reference | "as described above" points at a good-standing rule in another chunk, which will not be retrieved alongside this one. The condition on the answer is unreachable from the answer |
| heading | "Renewal policy" is the filing label of the team that wrote it, not a question anybody types |

Four of those five survive a change of embedding model, and none is visible in the
document while you read it top to bottom.

## After

```markdown
## Can I renew a book and keep it longer?

A loan renews for another three weeks, automatically, on its due date — you do
not have to come back to the desk, and there is no button to press. A loan does
not renew in two cases: somebody else has reserved the same title, or the
account owes more than the fine limit. When a loan does not renew, the book is
due on the date printed on the receipt and a late fee — the fines on your
account — starts the day after.
```

461 characters (`wc -m`): 31 more than the original, 7% longer, and still one
segment. **Rich did not mean bigger.** It meant that the words a user types are in
the text, the subject is named where the cut can reach it, and the exception is
answered in the same chunk as the rule.

What each change bought:

- **"A loan"** replaces "it", in the first sentence and again in the third. Cut this
  section anywhere and the surviving half still says what it is about.
- **The answer is the first clause.** "Renews for another three weeks,
  automatically, on its due date" is what carries the section in the embedding.
- **Both vocabularies are present**: *renew* and *keep it longer*; *reserved* rather
  than *hold*; *late fee* and *fines*, because half the users have only ever seen one
  of those two words.
- **The condition is inlined.** "Owes more than the fine limit" says what "good
  standing" meant, in this chunk, rather than pointing at a chunk that will not come.
- **The heading is the question**, so it matches whether or not the splitter carries
  headings into chunks — and if it does carry them, it matches twice.

## The trap on the next section down

The document also needs *"What happens if I return a book late?"*. The pull is to
restate the renewal rule there for completeness, and that is the failure described
under *Repeat the subject, not the explanation* in [`SKILL.md`](SKILL.md): two
sections saying the same thing both match the renewal question, both take a slot of
the three, and the answer is then assembled from two facts wearing three chunks.

Give the late-return section its own subject and its own facts — the daily rate, the
cap, the point at which borrowing stops — and let it restate exactly one sentence of
the renewal rule, the one it cannot be understood without: *"A loan that somebody
else has reserved does not renew, so it becomes late on its due date."*

## This rewrite is a hypothesis

Nothing above proves the new section retrieves for the question that failed. Reading
a document is not evidence about an embedding. Hand it to `corpus-retrieval-tests`,
which turns "can I keep this book longer" into a row naming the chunk it must return,
and which owns the diagnosis if it still does not — chunk boundary, vocabulary,
threshold or router, in that order.
