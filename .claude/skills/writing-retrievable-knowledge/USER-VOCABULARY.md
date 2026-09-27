# The user's words in the document

Open this when a question that should match a document does not, when the corpus
is written in the team's vocabulary and the users are not, or when writing the
question set that sits beside a new document.

## An embedding matches the user's words, not the team's

The corpus is written by people who know the system, in the vocabulary they use
with each other. The query is typed by somebody who does not. If the document says
"circulation period" and the user types "how long can I keep it", nothing anchors,
and the failure is silent: a top hit with a mediocre score, or nothing above the
threshold, and no line anywhere saying the words did not meet.

Put the user's phrasing into the document on purpose:

- the **common wrong term** — what people call the thing when they have it slightly
  wrong;
- the **abbreviation** and the expansion, both, at least once;
- the **terse form** somebody types at speed, without punctuation or a verb;
- the **question itself**, as a heading or as a sentence in the section.

**Where those words come from is one priority-ordered list, and
`corpus-retrieval-tests` owns it** — a skill a model cannot load, so ask the user to
run corpus-retrieval-tests, which they invoke by name. Read the list there rather
than from a second copy on this page: two copies drift, and the one you would be reading is the wrong one. What
this page needs from that list is only the property that makes it work — the wording
is somebody else's.

Where this project has none of the sources that list names, write the questions
yourself and then have
somebody who has not read the document write twenty more. Questions written by the
document's author reuse the document's vocabulary and match by construction — a
rigged green, the same one `authoring-agent-skills` warns about for skill
descriptions.

**There is one question set, it has one home, and it has one owner.** It is committed
beside the corpus, and `corpus-retrieval-tests` owns its schema. You build it here to
know what to write and to check coverage in both directions — or, on a migration run,
you extend the acceptance questions `prompt-to-corpus-migration` already wrote into
that same set, rather than starting a second list nobody reconciles. Later,
`corpus-retrieval-tests` turns each question into a row — the question, the intended
chunk (`expected_source` in the query set) and the negatives — and owns the diagnosis
when a row fails. Commit the questions with the document in this pass; do not write
assertions.
