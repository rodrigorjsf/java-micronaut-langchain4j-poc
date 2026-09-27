# The census schema

Reference for [`tool-search-rollout`](SKILL.md), Step 1: the columns brief 1
returns, one row per tool, and what goes in each. Open it when writing brief 1 and
again when its return lands.

### The census schema

| Column | What goes in it |
|---|---|
| `name` | the tool name exactly as the model sees it |
| `reach` | `static`, `provider` (non-dynamic), or `dynamic` — decided by reading the provider, not by guessing from where the class lives. A fourth value, `unknown`, is what a child writes for a provider it could not locate; it is a transient that Step 1 resolves, never a value Step 2 counts. **Where the provider's answer is computed rather than constant, the cell carries the expression verbatim and the condition that makes it true**, because the same tool is then `provider` on one deployment and `dynamic` on another and every decision downstream has to hold for both |
| `purpose` | one line, in the form *when a turn needs this*, not *what it calls* |
| `vocabulary` | the words a **user** would say for it — three to six literal phrasings, including the ones that do not contain the tool's own nouns |
| `wording` | `ours`, `upstream`, or `unknown` — whether this repository can edit the name and description that ship to the model |
| `effect` | `read`, or `write` plus whether the write is reversible |
| `principals` | who may run it: a check quoted verbatim with its location, or `unknown` |

**Evidence or `unknown`** is the whole reason this column is safe to have. An
inferred permission fact is a fabricated authorization claim arriving with a
green build and an approved plan behind it, and an `unknown` resolved either way
is guesswork: "all principals" is a vulnerability, "admins only" is an outage,
and neither was measured. The cell stays `unknown` in the artefact — a question
for whoever owns the tool — until they answer it.

`vocabulary` is not decoration either. Under a keyword strategy it is the literal
input to the rewrite, and under a semantic one it is the query set the rewrite is
measured with.

`wording` exists because the rewrite step assumes an editable string and a large
share of real tool layers do not have one — anything arriving over MCP, from a
vendored library or from a shared internal package ships its publisher's wording.
Those rows are searchable, `reach` says so, but they are **findable only by
whatever their publisher happened to write**, which was written to document the
tool rather than to match how your users speak. A census without this column
produces a rewrite plan that quietly cannot be executed for a third of its rows.
`STRATEGY-AND-DESCRIPTIONS.md`, *When the wording is not yours*, carries the
four moves and the order to try them; every `upstream` row leaves this pass with
one of them named. `unknown` here means nobody established ownership, and it goes to the
gate as that question.

**A row that is `unknown` in *both* `wording` and `principals` gets its own
disposition**, because each column's rule leaves it with half an answer. It
enters **neither** the rewrite set nor any per-caller filter — there is no string
this repository may edit and no principal evidence a filter could narrow on — and
it goes to the gate as **one line naming both gaps and the person who resolves
each**. Its `reach` is untouched by either gap, so a both-`unknown` row that is
`static` or `provider` **stays in the standing retrieval check** and fails it if
no query reaches it: unresolved is a question about the row, and never an
exemption for it. `RESEARCH-BRIEFS.md`, *Reading the returns*, carries why.
