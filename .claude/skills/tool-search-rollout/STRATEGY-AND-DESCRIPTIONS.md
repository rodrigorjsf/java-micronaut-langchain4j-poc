# Strategy, configuration, and the rewrite

Reference for [`tool-search-rollout`](SKILL.md), Step 2 and Step 4. Which search
strategy to run, what to configure on the search tool, how to rewrite names and
descriptions for the scoring rule that is actually running, how to prove the
rewrite helped, and what the layer records once it ships.

## One axis, two ends

The choice is **keyword or semantic**. LangChain4j names the two ends
`SimpleToolSearchStrategy` and `VectorToolSearchStrategy`, and the second name
invites a mistake worth heading off: *vector* is not a separate dimension you add
to something else. It **is** the semantic option. There is no third position and
no combination.

Both implement the same interface:

```java
@Experimental
public interface ToolSearchStrategy {
    List<ToolSpecification> getToolSearchTools(InvocationContext invocationContext);
    ToolSearchResult search(ToolSearchRequest toolSearchRequest);
}
```

`getToolSearchTools` returns the tool the model is shown — normally one, the
search tool itself. `search` answers a call to it. `ToolSearchRequest` exposes
exactly three things: `toolExecutionRequest()`, `searchableTools()` and
`invocationContext()`. Everything either strategy can do is a function of those
three, and everything `SCOPED-TOOLS.md` does with identity comes out of the
third.

Register either one the same way:

```java
AiServices.builder(Assistant.class)
          .toolSearchStrategy(strategy)
          // ...
```

## The SPI is `@Experimental`, and that decides how you extend it

The annotation is on the interface itself, and it is the single API fact that
decides whether the design in this file and in `SCOPED-TOOLS.md` survives an
upgrade. Everything either file proposes building — a subclass overriding the
keyword strategy's `protected` `score()` and `clean()`, a full implementation of
the SPI, a per-caller filter over `searchableTools()` — is a compile-time
coupling to a surface whose author has said in an annotation that it is not
settled. Plan for the signatures to move between minor versions, and treat an
upgrade of this dependency as a change to your own code rather than to a number.

Three rules follow, and the third is the one that matters.

**Pin the version exactly.** No ranges, no "latest patch". A range moves this
surface on a rebuild that has no commit attached to it, and the first evidence is
a tool nobody can find.

**Sort the breakage into loud and silent.** A method whose signature moved is a
compile error, which is the good case: it stops the build and the reader goes and
reads the new one. A **default that changed value is silent** — the tool name,
the tool description, the argument name, the argument description, `maxResults`,
`minScore`, the relative weight of a name hit against a description hit, the
cleaning rule. Nothing fails, the layer just retrieves differently. That is the
second and stronger reason the six settings ship as literal strings in your own
configuration rather than as inherited defaults: it is not only so the gate can
read them, it is so an upgrade cannot move them without a diff. What it cannot
protect is the scoring itself, and the standing check below is what runs after a
bump to tell you whether it moved.

**Keep the library on one side of a seam you own.** Put the policy — the synonym
expansion, the alias table for tools you cannot re-describe, the per-caller
filter — behind your own interface, in your own types, and let the subclass or
the SPI implementation be a thin adapter that calls it. The whole of your
retrieval policy is then testable without constructing a library type, its tests
keep compiling across the upgrade that broke the adapter, and the edit an upgrade
demands is one file whose entire job is to absorb exactly this.

An upgrade's checklist is short, and it is short because of that seam: re-read
the two interface methods, the three accessors on `ToolSearchRequest`, any
`protected` member you override, and the table of defaults below; then run the
query set and the standing check before anything ships.

## What keyword search actually does

The whole algorithm, per candidate tool:

```java
for (String term : cleanedTerms) {
    if (name.contains(term))                                score += 2;
    if (description != null && description.contains(term))  score += 1;
}
```

`clean()` splits the incoming terms on whitespace, trims, lowercases and dedupes.
Results scoring **at or above** `minScore` are returned, up to `maxResults`. That
is all of it — there is no stemming, no lemmatisation, no synonym table, no
tokenisation beyond whitespace, and no notion of a word boundary.

**What that sentence does not say, and the hedge every rank-shaped decision in
this file inherits.** It says a score and a cut. It does not say an ordering, and
it does not say what happens to two tools that score the same at the cut line.
With per-term scores of 2 for a name hit and 1 for a description hit, ties there
are not an edge case — they are the ordinary arithmetic of small integers over a
handful of terms, and a five-result window will routinely have several
candidates sitting on the same total. So **a follower who tunes `maxResults` by
the rank a tool was observed at is tuning against behaviour nobody has
guaranteed**: it can hold for months and move on a patch release with no diff in
your repository. Rank is worth recording and worth reading as a symptom; it is
not worth designing against. Where a tool *must* come back for a given query,
the fix is to raise its score rather than to bet on where it lands — and under
this keyword strategy that means **give it a name hit**, which is worth two and
lifts it clear of the description-only crowd rather than leaving it tied with
them. (A semantic strategy embeds one text per tool — its name and its description
together — so there is no name-versus-description weighting to exploit, and the
remedy there is distinctness plus a real `minScore` floor rather than a name.)

Five consequences, all derivable from those four lines, and all of which change
what you write in a description.

**The name is worth double.** A term hitting the name scores 2, the same term
hitting the description scores 1. Retrieval weight is concentrated in the
shortest string the tool has, so the rewrite starts at names and only then moves
to prose.

**`contains` is substring, not word.** `"car"` matches inside `"carrier"`, and
`"a"` matches inside almost every description in existence. Short terms are not
weak signals; they are indiscriminate ones.

**Substring matching has a direction, and it decides which grammatical form you
write.** A query term of `"payment"` finds a description containing `"payments"`,
because the shorter string sits inside the longer one. A query term of
`"payments"` does **not** find `"payment"`. So where a plural is the singular plus
a suffix, **carry the plural and the singular comes free**. Where it is not —
irregulars, and any `y → ies` change — the two forms share no containment
relationship and both have to appear literally. `"company"` is not inside
`"companies"`.

**`minScore: 1` means one incidental hit is enough.** Combine that with substring
matching and with `clean()` splitting on whitespace, and a chatty query becomes a
match-everything query: the model sends a sentence, `clean()` turns it into a
dozen terms, several of them short and common, and every tool in the catalogue
clears the floor. The ranking is then decided by accidental word overlap and the
only thing bounding the damage is `maxResults`. Two levers against this, and you
usually want both: raise `minScore` so a result needs a name hit or several
description hits, and shape the argument description so the model sends few
specific terms rather than a sentence.

**The argument must be a JSON array of strings.** Anything else throws —
`ToolExecutionException`, or `ToolArgumentsException` with
`throwToolArgumentsExceptions(true)`. The argument's description is the only
thing standing between you and that exception, which makes it configuration
rather than documentation.

`score()` and `clean()` are `protected`. Subclassing is the intended extension
point, and it is where a synonym expansion or a stopword list belongs — added
once, in one place, rather than smuggled into forty descriptions as keyword
padding that a human reviewer then has to read past.

## What semantic search actually does

`VectorToolSearchStrategy` requires an `EmbeddingModel` and compares the query
against the tools' embeddings. It wraps the model in a
`ToolCachingEmbeddingModel` by default, so a tool's embedding is computed once
rather than on every search — the per-search cost is the *query* embedding, not
the catalogue's.

That default cache is worth being precise about, because it is the one people
point at when the subject of caching comes up and it is **not** the dangerous
one. What it holds is the embedding of a tool's own text, and a tool's text does
not vary by who is asking — the same tool embeds to the same vector for every
caller, so the cache stays correct however narrowly discovery is scoped. The
hazard is any cache *you* add on top: a cache of search **results**, or of a
candidate set, or of the search tool's own specification. Those do vary by
caller, in a layer specifically built to make them vary, and `SCOPED-TOOLS.md`
carries the key rule for them. Read it before adding one.

Its `minScore` default is `0.0`, which means **nothing is filtered out by score
at all**: `maxResults` is doing every bit of the work, and the five returned
tools are simply the five nearest, however far away they are. A query about
something the layer cannot do returns five tools with the same confidence as a
query about something it can. Setting a real floor is the first tuning you do,
and you can only set it once you have scores from your own query set to look at.

Literal word overlap stops mattering here, which removes the plural rule, the
substring rule and the synonym problem in one move — and introduces a different
one. Near-neighbours crowd. Two tools that do genuinely different things to the
same noun sit close together in embedding space, and no amount of keyword
distinctness separates them, because keywords are not what is being compared.

## Choosing, from inputs you can count

| Input | Measure it as | Reads toward |
|---|---|---|
| Searchable tool count | census rows with `reach` of `static` or `provider` | 15–40: keyword usually suffices; past roughly forty the five-result window fills with coarse-score noise → semantic. Both boundaries are illustrative — your own query set sets the real one |
| Embedding model already present | one line in the dependency manifest, or an existing retrieval component | present → semantic is nearly free. Absent → it is a new dependency, a new credential, a new outage mode |
| Do users speak the tool layer's vocabulary? | brief 3: the fraction of real phrasings sharing a literal word with their target tool's name or description | high → keyword. low → semantic, or a rewrite that closes the gap |
| Homonymous tools | census pairs whose `vocabulary` sets overlap | many → neither strategy separates them; see the note below |
| Latency budget | your p95 target minus what a turn already spends | an embedding call sits on the request path of every search; keyword search is arithmetic over strings |
| Cost per search | searches/turn × turns/day × embedding price | if the number is uncomfortable to write down, that is the answer |
| Control over query shape | can the argument description reliably get short terms out of your model? | if it cannot, keyword degrades toward random within `maxResults` |
| Languages | do users write in a language the descriptions are not in? | keyword fails outright across languages; semantic degrades gracefully with a multilingual model |

**Default to keyword and say what would flip it.** It adds no dependency, no
per-search latency and no per-search cost; it is deterministic, so a missed tool
is explainable by hand rather than by intuition; and the description rewrite it
forces is most of the work either way. Then name in the plan the one input whose
change flips the decision — usually the tool count crossing your own crowding
threshold, or a second language arriving.

**If you expect to flip later**, keep any synonym padding in a single trailing
sentence of each description rather than woven through the prose. Under semantic
retrieval that padding is dead weight that dilutes the embedding, and a trailing
sentence is one edit to remove.

**Homonyms are not a strategy problem.** Two tools that both answer to "invoice"
are separated by a *precondition* — what each requires and what each returns —
and preconditions help both strategies. If they cannot be separated by a sentence
a user would understand, the honest finding is that the tool layer has two tools
where it should have one, which belongs to `authoring-agent-tools`.

## Configuring the search tool

Six settings, defaults as shipped:

| Setting | Keyword default | Semantic default |
|---|---|---|
| tool name | `tool_search_tool` | `tool_search_tool` |
| tool description | `Finds available tools whose name or description contains given search terms` | `Finds available tools using semantic vector search` |
| argument name | `terms` | `query` |
| argument description | `A list of individual search terms (single words) used to find relevant tools` | `Natural language query describing desired tool` |
| `maxResults` | `5` | `5` |
| `minScore` | `1` | `0.0` |

**The search tool's own description is the one that is never free.** Every other
description in the layer is now deferred cost — paid only when a search returns
it. This one is standing prompt on every turn of every conversation, and it is
the sole routing signal for the entire tool layer: if the model does not
understand from this sentence that the tools it needs are behind it, it answers
from memory and nothing throws. Keep it short and write it as routing, not as a
label. Naming the *domain* the hidden tools cover is usually worth its tokens —
the default sentence describes the search mechanism, which the model does not
need, and says nothing about what might be found, which it does.

**The argument description is a contract with the caller, and the caller is a
model.** Under keyword it has to produce a JSON array of short, specific terms,
because a sentence becomes a dozen terms above the floor and a non-array throws.
Under semantic it should produce one natural sentence, because that is what
embeds well and a bag of keywords does not.

**`maxResults` is a token budget and a recall ceiling at once.** Each returned
specification enters the request in full, so a search costs `maxResults` times
your own layer's schema size. Serialize your own specifications and measure it
rather than importing a figure — and measure them *after* the rewrite, which
deliberately makes hidden descriptions longer. Raising `maxResults` buys recall
and pays those tokens on every search; the number is only defensible once your
query set shows where the targets are ranking — **and only as far as the hedge
above allows**, because ranking data is an observation of undocumented behaviour
and a `maxResults` fitted tightly around it is a setting whose correctness has
no owner. Use the ranks to find the tools in trouble; fix those tools at their
names and descriptions; leave `maxResults` set by the floor below rather than by
the rank distribution.

Its **floor** comes from somewhere else entirely: the disclosure-lifetime
observation in `SKILL.md`, Step 2. Where a search's results survive to the model
calls after it, a task can search twice and accumulate, and `maxResults` only has
to cover one step. Where they do not, every tool a step needs must return from a
*single* search, and the floor is the largest number of tools any one step of a
real task uses — a number you take from the query set's multi-step rows, not from
the framework's `5`. **Where the lifetime could not be measured at all**, that
same largest-single-step count *is* the answer rather than a floor under one: it
is `SKILL.md`, Step 2's default, and it goes to the gate labelled as one.

**Say in the description that the tool may be called again.** A model that
searches once, receives `maxResults` specifications and proceeds has done
nothing wrong from where it sits — nothing told it a second search was
available, and the set it got back looks like the answer rather than like a
page of one. This is the failure `SKILL.md`'s multi-search verification row
exists to catch, and it is one clause of configuration rather than a tuning
problem: the tool description says the tool may be called repeatedly, with
different terms, until the turn has what it needs. Raising `maxResults` is the
expensive way to paper over a sentence you did not write, and it pays those
tokens on every search forever.

**Keep `tool_search_tool` unless a rename survives the gate.** `tool_search_tool` is graceless
but stable across upgrades and matches every example a reader will find; a rename
is a routing change like any other and goes to the gate as one.

### Seeding the first search from a turn classifier

Reached only where a cheap classifier already runs in front of the agent and its
verdict reaches the turn's prompt — a category, a route label, a skill hint in a
turn-context block. `llm-triage-gate` owns that classifier. Where one exists, the
same verdict can carry a **suggested search query**, so the model's first search
is seeded rather than guessed. It is the cheapest recall improvement available,
because it fixes the query at the one point in the system that has already read
the user's message and decided what it is about.

The seam is generic: the classifier's structured verdict grows one optional
field; the prompt assembly renders it inside the same block the other hints go
in; the model is free to ignore it. Two failure modes, both worth writing into
the plan.

**A suggestion the model reads as an instruction.** Rendered as "use the invoice
tool", the hint stops being a seed and becomes a routing decision made by a model
too cheap to make it, and the search that would have corrected a bad hint never
runs. Render it as *terms to start from*, and keep every tool findable from a
query typed by a user who has never heard of the classifier.

**A hint that names a tool this caller may not use.** The classifier does not
know the caller. A hint that names tools re-opens the enumeration oracle the
scoping closed: the model narrates the name, the user learns a tool exists that
they were deliberately never shown, and a refusal follows for something they were
never offered. Two fixes, in preference order — have the hint carry search
*terms* rather than tool names, or pass it through the same per-caller filter
before it reaches the prompt.

## The rewrite

The house style for a description — say when to reach for it, name its
precondition, document every parameter's format — is `agentic-tool-boundary`'s
and does not change. What changes is that a description now has **two jobs in
sequence**: it must be *retrieved* before it can *route*. A perfectly written
description that never comes back from a search does nothing at all.

**Length is nearly free now, and this inverts the usual advice.** A hidden tool's
specification is not standing prompt; its cost is paid only on the searches that
return it. So a hidden tool's description can afford the plural and the singular,
the two synonyms the scorer cannot infer, and the phrasing your users use even
though nobody on the team does. The asymmetry is exact and worth holding onto:
**hidden descriptions get longer, the search tool's own gets tighter.**

### Under keyword

**Names first, because they score double.**

- Use the word a user would say, not the word the API vendor uses. A tool named
  for the upstream product is unfindable by anyone who has not read the vendor's
  documentation.
- Prefer a name whose terms are also the terms in the query. A verb plus a noun
  gives two chances at a name hit and reads correctly to a human:
  `find_invoice`, `refund_order`, `check_delivery_status`.
- Avoid names that are substrings of unrelated words or of each other.
  Everything containing `list` also matches a query term of `list`, and two tools
  whose names differ by a suffix will always return together.
- A rename moves routing on its own and is reviewed as a behaviour change, at
  the gate, in `reviewing-agent-tools-and-skills`' terms.

**Then descriptions, written against brief 3's rows.**

- Every phrasing in the census `vocabulary` column must appear as a **literal
  substring** somewhere in the name or description. Not a paraphrase of it —
  `contains` does not paraphrase.
- Carry the **plural** where the plural is the singular plus a suffix; carry
  **both forms** where it is not.
- Add the words users use that the team does not. If tickets say "bill" and the
  system says "invoice", the description contains both, and the trailing sentence
  is where they go.
- Keep the precondition sentence. It does two jobs here: it routes after
  retrieval, and it is what separates homonyms.
- Resist stuffing beyond that. Every added word raises the number of unrelated
  queries this tool clears `minScore` on, and a tool that returns for everything
  displaces the tool that was right.

### Under semantic

Literal overlap stops paying, and the description should read like the sentence
the user would have said if they had described the capability themselves. Write
prose. Drop the plural pairs. Keep the precondition, keep the domain nouns, and
put the distinguishing clause early rather than at the end.

The failure to watch for inverts: not *nothing returned*, but *five plausible
neighbours returned and the right one fourth*. That is a `minScore` and a
distinctness problem, not a keyword one, and you cannot see it at all until the
scores from your own query set are in front of you.

### When the wording is not yours

Everything above assumes the description is a string in your repository. For a
large share of real tool layers it is not: tools arriving over MCP or another
external protocol ship with the name and description their publisher wrote, and
so do tools from a vendored library or a shared internal package. The census
`wording` column is what tells you which rows those are, and they need a
different move — the rewrite step has nothing to edit for them.

**Start by ruling out the move people reach for first.** Marking such a tool
always-visible does not make it findable. It **exempts it from search**, which is
the opposite operation: the tool is now in the standing prompt on every turn of
every conversation, exactly the cost this whole pass exists to remove, and it is
found by the model reading its schema rather than by anybody's query matching it.
That is a legitimate last resort for a tool that must be reachable and cannot be
made findable. It is not a fix, and a plan that reaches for it on every `upstream`
row has quietly cancelled the rollout for that part of the layer.

Four moves, in the order to try them.

**1 — Move the vocabulary out of the description and into the scorer.** Keep a
table in your own code, tool name → the words your users use for that tool, and
score each candidate against its entry as well as against its own name and
description. Under a keyword strategy this is precisely what the `protected`
`score()` and `clean()` hooks are for: the upstream text is never touched, an
upstream release cannot revert your work, and the vocabulary sits in one
reviewable file instead of being smuggled into forty descriptions. Under a
semantic strategy, read your version to establish whether it exposes a comparable
hook of its own. The fallback that always exists is implementing the SPI: `search` receives `searchableTools()` and returns
the result, so the text you compare against and the ranking you return are both
yours. Either way the cost is real and must be written down: retrieval vocabulary
now lives in two places, so the census row for that tool has to say which file
carries its terms, or the next person rewrites a description that is not the
thing being matched.

**2 — Publish a specification you wrote, from a provider you own.** Rather than
surfacing the upstream tool directly, a non-dynamic provider of yours presents a
specification carrying your name and your description, and forwards the call.
This is the only move that also fixes the **name**, which is where a keyword
strategy concentrates its weight. It carries one hard constraint straight out of
fact 1 in `SKILL.md`: execution is a lookup of the name the model emitted, so the
name you publish is the name that has to resolve to an executor. Publish a
renamed specification without making that name resolve and you have built a
worse failure than the one you set out to fix — a tool that is discoverable and
uncallable. The standing cost is a description of somebody else's behaviour that
you now own, and which drifts every time they release.

**3 — Ask whoever publishes it.** A pull request for an internal package, an
issue for a third-party server. It is the slowest move and the only one that
fixes the tool for everyone downstream of that publisher, which makes it worth
opening in parallel with move 1 rather than instead of it.

**4 — Exempt it from search**, as above, having priced what that costs on every
turn and having said so at the gate.

Whatever you choose, **these rows stay in the standing check below.** They are
the rows most likely to be unfindable, and a check that quietly skips the tools
whose text nobody controls is asserting retrieval only where retrieval was never
in doubt. A `wording` of `unknown` is a question for a person, not a licence to
treat the row as editable.

### Always-visible tools

Three ways to mark a tool as never hidden:

```java
@Tool(searchBehavior = ALWAYS_VISIBLE)                          // annotation
ToolSpecification.builder().metadata(Map.of(
    ToolSpecification.METADATA_SEARCH_BEHAVIOR, SearchBehavior.ALWAYS_VISIBLE))
McpToolProvider.builder().alwaysVisibleToolNames("getWeather")  // MCP
```

Mark a tool always-visible when a turn that fails to find it fails badly:

- the tool nearly every turn needs, which would otherwise pay a search round trip
  to rediscover the same answer every time;
- a management or lifecycle tool the model needs in order to reach anything else
  — a library that ships its own such tools marks them this way for exactly this
  reason;
- the tool a graceful failure depends on: whatever the model should call when it
  cannot find anything, since a model that finds nothing and has nothing to fall
  back on invents.

Each marking is a permanent line item in the standing prompt, so it goes to the
gate individually with the turn it protects. Two further things are true of every
marking, and both change what a follower does with one.

**The marking bypasses the per-caller discovery filter, so a tool the scope was
meant to hide stays unmarked.** The filter in `SCOPED-TOOLS.md`, layer 1, narrows
what search returns, and a tool carrying a never-hidden-by-search guarantee sits
outside anything that path can narrow. The two features are configured in
different files by different people — a sensitive tool acquires its marking from
someone reasoning about availability while the scope is written by someone
reasoning about permissions, and neither of them is wrong on their own terms.
Cross-check this list against the census's `principals` column before it goes to
the gate, and show that you did.

**The marking is inert while the provider is dynamic — and that is a reason to
keep it, not to strip it.** A tool arriving through a dynamic provider was never
hideable, so today the marking exempts it from nothing. But a provider's answer
is often *computed* rather than constant, and the day the condition flips the
provider becomes non-dynamic, its tools become searchable, and the marking is
the only thing keeping them in front of the model. LangChain4j's own skills
module is the worked case: its provider answers `!skillScopedProviders.isEmpty()`
and its two management tools carry the marking anyway, precisely so that they
survive the deployment where no skill carries tools. The rule is therefore
**load-bearing exactly when the provider is, or can become, non-dynamic** —
which is decided by reading the census's `reach` cell, including the expression
recorded there when the answer is computed. Removing a marking because a
provider is dynamic in the configuration in front of you is optimising away the
thing that catches the other configuration.

## Proving the rewrite helped

The rewrite is a behaviour change that no test in the repository fails on today.
Two measurements replace the one that does not exist, and they are stages of a
single thing rather than alternatives: a **before-and-after over a query set**,
which shows that this rewrite helped, and a **standing build check**, which keeps
that true for every tool added afterwards. The first is immediately below; the
second closes this section, and skipping it means the whole measurement expires
the day the pass ends.

**Write the query set before the edits.** Brief 3's verbatim rows, each paired
with the tool or tools it should reach — or with `none`, which is a label the set
needs as much as it needs any tool name. A set assembled after the rewrite is a
set assembled from the rewrite, and it will pass. `agentic-evals` owns how many
rows a claim like this needs and where a threshold goes; the shape below is what
this particular claim measures.

**Measure the strategy directly, not through the model.** Call `search` with each
query and read `ToolSearchResult`. That removes the model's variance, makes the
number reproducible, and separates two failures that look identical from the
outside: *search never returned the tool* and *search returned it and the model
chose another*. The first is this rewrite's problem; the second belongs to the
descriptions' routing job and to `reviewing-agent-tools-and-skills`.

**The set needs three classes of row, and a set with only the first measures the
rewrite's failure mode as if it were its success.** Recall and rank are both
maximised by widening every description until every tool returns for every query
— the exact move *Resist stuffing beyond that* warns against, and a positive-only
set gives that move a rising number every time. The two negative classes are what
make widening cost something.

| class | `expected` holds | a pass is |
|---|---|---|
| **positive** — a turn this tool should serve | the target tool | the target returns, within `maxResults` and at or above `minScore` |
| **neighbour** — a turn a *different*, adjacent tool should serve | the neighbour, named | the **neighbour** outscores the tool under rewrite. Not "the tool under rewrite also returns" — that is the displacement being measured, not a pass |
| **none** — a turn no tool in this layer should serve | `none` | **nothing** clears `minScore`. Every tool that does is a false positive, listed by name with the term that carried it |

Both negative classes are written against a score, so **under a semantic
strategy they need a real `minScore` before they can be read at all.** The
default of `0.0` filters nothing: every query returns the five nearest tools, so
a `none` row is failed by construction and a neighbour row's margin is a
similarity gap rather than an integer difference. Set the floor first, from the
scores your own positive rows produce, then run the negative classes against it.
That is "the first tuning you do" from *What semantic search actually does*,
arriving with the rows that make it decidable.

Run all three classes **before** the rewrite as well as after. A negative class
measured only afterwards has no baseline, so a description set that was already
returning four tools for an unanswerable question reads as clean, and the widening
that made it seven is invisible. The before-the-rewrite reading is the floor the
after-reading is compared against, and it is cheap: the same harness, the same
rows, one extra run against the descriptions you are about to replace.

| query | class | expected | before: returned? rank | after: returned? rank |
|---|---|---|---|---|
| *(verbatim user phrasing)* | positive | `find_invoice` | no | yes, 1 |
| *(verbatim user phrasing)* | neighbour | `refund_order` | `refund_order` 1, `find_invoice` 3 | `refund_order` 1, `find_invoice` absent |
| *(verbatim user phrasing)* | none | `none` | 2 tools returned | 0 tools returned |

**Record the score beside the rank in both columns**, and treat the rank under
the hedge from *What keyword search actually does*: it is observed behaviour,
not a documented guarantee, and ties at the cut line are ordinary arithmetic
rather than a rarity. A row that improves from "absent" to "returned" is a
result. A row that improves from rank 3 to rank 2 with an unchanged score is not
a result, and reading it as one is how a rewrite gets credited with work it did
not do. Where a row must return for a query, raise the score — under keyword,
by giving it a name hit — rather than accepting a rank that happens to fall
inside the window today.

Expect the `none` rows to be **hard to keep clean under a keyword strategy**, and
read that difficulty as the measurement working rather than as a badly written
row. With `minScore` at `1`, substring matching and `clean()` splitting a
sentence into a dozen terms, an unanswerable question clearing the floor on two
or three tools is the arithmetic, not a defect — it is the same mechanism as the
chatty-query problem above, seen from the evaluation side. What the rows tell you
is whether the rewrite made it worse, and which added term did it. If they can
only be made clean by raising `minScore`, that is a real finding about
`minScore`, arrived at with evidence instead of by taste.

Four numbers come out of the set: how many positive queries returned their
target at all, at what rank, how many unrelated tools came back alongside — that
third is tokens and distraction, and it is the cost of every synonym you added —
and how many negative rows the rewrite newly broke. The fourth is the one that
turns a recall improvement into an honest verdict, and it is the only number in
the table that can go the wrong way while everything else improves.

**The whole set decides, not the query that motivated the edit.** A rewrite that
fixes the query you were annoyed by and quietly breaks two others is a loss, and
the only reason anyone knows is that both runs covered the same rows. Every
regression goes to the gate individually, with the word that caused it — under
keyword the culprit is almost always a term you added to one description that now
outscores a better tool.

Any figure in this file or in your plan that you did not produce from your own
set is illustrative and says so in the sentence.

### The check that outlives the pass

Everything above is a one-off: two runs of one query set, around one rewrite.
It says nothing about the tool somebody adds next month, whose description shares
no word with anything a user says. That tool ships, compiles, passes every test,
and is unreachable — the whole failure of this pass, arriving after the pass is
over and with nobody looking. The fix is to leave the measurement behind as a
**standing check in the build**, enumerating the tools that exist *now* rather
than the ones the census recorded.

**Partition it by class, because a check that demands retrieval of every
registered tool fails on a correct layer.**

| Class of tool | What the check asserts | What it must not assert |
|---|---|---|
| Searchable — `reach` of `static` or `provider`, not exempt | at least one query-set entry returns it, within `maxResults` and **at or above** `minScore` | — |
| Always-visible | it is in the specifications the framework sends, with no search performed | that it is retrievable; it was deliberately exempted from search |
| `reach` of `dynamic` | it reaches the model at all, unfiltered | that it is retrievable; the framework guarantees it is not, and asserting otherwise is a red build on correct behaviour |

**Enumerate from the live registry, never from a checked-in list.** A check that
reads the census file only ever checks the tools somebody remembered to add to
the census file — which is the same class of omission it exists to catch, one
level up. Ask the assembled configuration what tools it has, and assert over
that.

**Derive each tool's class from the real provider graph too, not only its
membership.** The partition above is only as good as the `reach` it partitions
on, and `reach` is the one census cell that changes without anybody editing a
tool: a provider whose dynamic answer is computed flips when a condition
elsewhere changes, and its tools move from the first row of this table to the
third — silently searchable one week and silently unsearchable the next. So the
check asks **the providers the application actually assembles** what they answer,
row by row, and fails when a tool the census recorded as `static` or `provider`
now arrives through one answering dynamic. A test double cannot catch this: it
answers whatever it was constructed to answer, which is the census's opinion
wearing a green tick. This is the same assertion `SKILL.md`, Step 5 makes once
at the end of the pass; here it becomes the thing that keeps making it.

Then the new-tool experience is the point of the whole thing: **adding a tool
with no query-set row fails the build.** The failure is not "your tool is
broken", it is "nobody has written down how a user would ask for this", and the
cost of clearing it is one honest sentence. Writing a query row that no user
would ever type, purely to turn the build green, converts the check into
decoration — the same move as writing a test that asserts what the code does.

**Only positive rows count toward that coverage.** A neighbour row is labelled to
a different tool and a `none` row is labelled to no tool at all, so neither can
be the row that proves a given tool is reachable — they measure what the
descriptions cost, not what they cover. Both classes stay in the set and both
run in the build; they simply do not discharge any tool's coverage requirement,
and a set that reaches full coverage by counting them has counted rows that
assert the opposite of coverage.

That cuts both ways on the day the check is introduced, and it is the one thing
to get straight before proposing it: **the check cannot be switched on while a
searchable tool has no row.** Some tools will have none — the vocabulary harvest
returns a list of exactly those capabilities nobody has been recorded asking for.
Write their rows first, as phrasings a user would say, and count them, because
the honest version of this proposal at the gate is "a build check, plus these
eleven sentences I wrote myself". Narrowing the check to the tools that happen to
have rows is the other way out, and it reopens the hole the check exists to
close.

**Where it runs depends on the strategy, and this is the one part of it worth
arguing about.** Keyword scoring is arithmetic over strings: no network, no
credential, no model, so the check belongs in the default build where every
change runs it. Semantic scoring needs an embedding model — a real one makes the
build depend on a network call and a secret, and a stand-in proves the wiring
rather than the retrieval. So gate on whatever runs offline, run the
model-dependent form on a tag or a schedule, and report it rather than blocking
on it. `agentic-evals` owns that distinction — what a build may gate on versus
what it may only report, and where a threshold belongs — and this check is an
ordinary instance of it.

One more thing this check earns its place with: it is what you run after a
framework upgrade. A moved signature is a compile error and needs no help; a
changed default or a changed scoring weight is silent, and this is the only thing
in the repository that goes red when retrieval quietly moved.

## What the record shows once it ships

The query set is a sample of one team's vocabulary, and `SKILL.md`, Step 5 says
what a green verification therefore leaves unproven. The mechanism that corrects
it after launch is a record of what search was actually asked for and what it
actually returned, it costs a handful of fields, and it ships in the same change
as the strategy — gate item 14 in `SKILL.md` is where it is approved.

**Per search:**

| Field | Why it is the one you will want |
|---|---|
| The query **as the model sent it**, verbatim | the input to every other conclusion. Under a keyword strategy record the cleaned terms beside it, because cleaning is where a sentence becomes a dozen indiscriminate terms |
| The names returned, **with their scores and their ranks** | a target arriving consistently at rank four under a `maxResults` of five is a week from disappearing, and nothing else shows that coming. Record the rank **as observed** and read it as a symptom, under the hedge in *What keyword search actually does*: what the framework specifies is a score and a cut, not an ordering or a tie-break, so a target sitting at the cut line is a warning without being a prediction. The **score** beside it is the sturdier of the two numbers |
| The size of the candidate set the scorer actually saw | separates "scored badly" from "was filtered out before scoring" — under per-caller narrowing those look identical downstream |
| Whether the search returned **nothing** | the highest-value row in the whole record: it is a user's phrasing, in production, that reaches no tool. These are query-set rows waiting to be added |

**Per tool call, one field: where this tool came from.** Make it three-valued,
because two values throw away the interesting case.

| Value | Meaning |
|---|---|
| `from-search` | this name appeared in a search result earlier in this conversation |
| `always-visible` | it never needed a search; it is in the standing set by design |
| `neither` | **the alarm.** The model called a tool that no search offered it and that is not always-visible |

The first two values buy in production the split *Measure the strategy directly*
buys offline — *search never returned it* versus *search returned it and the
model chose something else*. Those two produce the same symptom, the wrong tool
ran, and they have nothing in common as problems.

The third value is fact 1 in production. The guardrail test proves refusal for
one name you drove by hand; this field counts how often it happens for real, on
names nobody predicted. A non-zero count is not automatically an attack — a
resumed conversation and a model reusing a name from earlier both land here — but
it is the only place the system ever says out loud that the executable set is
wider than the visible one.

**What the record is read for**, on a fixed cadence rather than when something
breaks: empty searches become new query-set rows and new `vocabulary` cells;
rank-versus-`maxResults` *argues for* a `maxResults`, under the hedge above — so
a tool that must survive the cut gets fixed at the description rather than by
tuning the window around where it happened to land; the scores of results nobody
used set `minScore`; a searchable tool that has never been returned is either
unfindable or dead, and the census says which; and an always-visible tool whose
provenance value never appears was exempted for a turn nobody makes, so its
permanent place in the standing prompt has no buyer. Each of those edits then
re-enters this pass at the rewrite step, where it is measured against the query
set like any other.

**Two constraints on how it is written.** Search terms are user text — they carry
whatever a user typed, so they are subject to the same retention and redaction
rules as any other message content, not looser ones because they look like
telemetry. And tool names are a bounded set while query terms are unbounded: name
a metric dimension after the tool, never after the query, and keep the queries
themselves in whatever store already holds turn-level detail.
`llm-cost-observability` owns the plumbing that makes a turn attributable; this
is the one search-shaped record to put on top of it.

## Failure signatures

| Symptom | Cause to check first | Move |
|---|---|---|
| One tool never returns for any real phrasing | its description shares no literal substring with how users speak | rewrite from brief 3's rows, name first |
| Search returns five tools and the right one is sixth | `minScore` too low, so noise fills the window | raise `minScore`; shorten the query the model sends |
| Nothing returns for a reasonable question | the query term is longer than the word in the description (`payments` vs `payment`), or the model sent a sentence | carry the plural; rewrite the argument description |
| The search call throws | the argument was not a JSON array of strings | say so in the argument description; turn on `throwToolArgumentsExceptions` while debugging so the failure is typed |
| The model answers without ever searching | the search tool's own description does not say when to call it | it is the only routing signal for the whole layer — write it as routing, and name the domain |
| The model searches once, gets a partial set, gives up | nothing told it the search tool may be called again, or `maxResults` is too small for a multi-tool turn | say in the tool description that it may be called repeatedly, with different terms, until the turn has what it needs — *then* consider raising `maxResults`, which pays tokens on every search rather than on the turns that need it |
| A tool the census recorded as searchable is no longer hidden by search, and nothing failed | its provider's dynamic answer is computed, and the condition flipped | the standing check re-derives `reach` from the real provider graph; a test double cannot see this |
| Recall improved on every query and the layer got worse | the set has no negative rows, so widening every description scored as a win | add the neighbour and `none` classes, and re-read them against the before-the-rewrite floor |
| A tool is always visible when it should be searchable | it reaches the model through a dynamic provider | `SKILL.md`, fact 2 |
| A tool never returns and there is no description to rewrite | its wording belongs to whoever publishes it | *When the wording is not yours* — the vocabulary moves into the scorer, not into a description you cannot edit |
| Retrieval changed and nothing in the repository did | an upgrade moved a default, a scoring weight or the cleaning rule | pin the version; ship the six settings as literal strings; run the standing check after every bump |
| Two tools always return together and the wrong one wins | homonyms | separate them by precondition, in both descriptions; if that is impossible, they are one tool |
