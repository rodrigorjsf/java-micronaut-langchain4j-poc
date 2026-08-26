# The research briefs

Reference for [`tool-search-rollout`](SKILL.md), Step 1. Three briefs, written to
be copied into a sub-agent request unchanged. Each one ends with the literal
shape of its return.

`subagent-context-isolation` owns why a sub-agent earns its cost, what a brief
must carry and how a return is read. These three are already in that shape; what
follows is what is specific to this pass.

## The rule that governs all three

**A sub-agent drafts and measures. It never signs off.**

A child here reads a repository it will never see again and returns facts. It
does not choose the strategy, does not write a description, does not decide which
tools are always visible, and does not judge whether the rollout should proceed.
Those are decisions with a user-facing gate in front of them, and a decision
arrives from a child stripped of everything that would let you check it — the
files it read, the ones it did not, and the sentence in the code it was not sure
about.

Three consequences, each of which is a line inside the briefs below.

**A child returns evidence, not conclusions.** "Searched six patterns for a
refusal an authenticated caller could receive: 0 hits, patterns listed" is a
finding you can paste into the gate. "There doesn't seem to be any auth" is an
opinion, and it loses the argument with whoever wrote the code.

**`unknown` is a first-class return value.** Every brief below names the cell it
goes in. A child that fills a gap with its best inference produces a census that
is confidently wrong in the permissive direction, and the wrongness is invisible
because it looks exactly like the rows that were read out of code.

**A child may not stamp anything as verified.** It reports what it ran and what
came back. Whether that constitutes proof is the parent's call, made against
output the parent can see.

## Brief 1 — the tool census

Required. Its output is the artefact the whole pass is built on.

```
You are surveying the tool layer of this repository for a documentation pass.
Read only; change no files.

Find every function, method or object that is published to an LLM as a tool.
Look for: annotations that mark a method as a tool; explicit registration on an
AI-service or agent builder; tool-provider classes; tool specifications built by
hand; and any tools reaching the model over MCP or a similar external protocol.
The list must be complete — a tool registered by one route you did not think to
look for is the tool this whole pass will silently skip.

For each tool, return these seven fields.

  name        The tool name as the MODEL sees it. This is often not the method
              name — an annotation or a builder may override it. Quote the
              string that actually ships.

  reach       Exactly one of: static | provider | dynamic.
                static   — the tool is handed to the AI-service or agent builder
                           directly at construction time.
                provider — it comes from a tool-provider object whose
                           "is this provider dynamic" method answers false
                           (in most frameworks this is the default).
                dynamic  — it comes from a provider that answers true.
              Determine this by OPENING THE PROVIDER AND READING THE METHOD. Do
              not infer it from the package, the class name, or where the file
              sits. If the answer is computed rather than constant, return the
              expression verbatim and the condition that makes it true — that
              condition is a finding on its own.
              If a tool reaches the model through a provider you cannot locate,
              reach is `unknown`. Do not guess `static`.

  purpose     One line, in the form "when a turn needs this", not "what it
              calls". If the tool's own description already says this, quote it;
              if it says something else, return both and mark the difference.

  vocabulary  Three to six phrasings a USER would plausibly type to ask for this
              capability. Include at least one that contains none of the nouns in
              the tool's own name or description. If the repository contains real
              user turns, prefer real phrasings and say where each came from.

  wording     ours | upstream | unknown. Can THIS repository edit the name and
              the description that ship to the model?
                ours     — the strings live here, and a change in this
                           repository changes what the model sees.
                upstream — the tool arrives from outside and brings its own
                           wording: an external tool protocol such as MCP, a
                           vendored library, a shared internal package. Say
                           where it comes from and name the artifact that would
                           have to change for its description to change.
                unknown  — you could not establish it.
              Do not default to `ours`. A plan written against an editable
              string that turns out not to be editable fails at execution time,
              after somebody approved it.

  effect      read | write. For a write, say whether it is reversible and by
              whom.

  principals  Who may run this tool. This field has one rule and it overrides
              your judgement:

                EVIDENCE OR `unknown`.

              You may fill it in only from one of:
                - a check on the tool's own execution path — quote it verbatim
                  with file and line;
                - a check on the single route that reaches the tool — quote it,
                  and say that it guards the route, not the tool;
                - a documented requirement on the downstream endpoint the tool
                  calls — quote it and mark it as downstream.
              Anything else is `unknown`. `unknown` must not become "everyone"
              and must not become "admins". An inferred permission is a
              fabricated security fact, and it is the one error in this brief
              that can cause harm.

              THIS COLUMN IS EXPECTED TO BE MOSTLY `unknown`, AND THAT IS NOT A
              FAILED RUN. A separate survey is discovering, in parallel with
              you, what this codebase's checks are spelled with and what its
              principal type is called — the two things that would tell you what
              to grep for. You do not have that list. Return what you can
              evidence and `unknown` for the rest; the column is completed after
              both returns land, by the person who has both.

              So that your sweep has something concrete to run, use this fallback
              vocabulary — it is the generic list, not this codebase's:
                hasRole, hasPermission, hasAuthority, hasScope, requireScope,
                requirePermission, checkAccess, can(, authorize, isAuthorized,
                enforce(, allow(, plus whatever annotation or decorator this
                stack uses to declare a requirement on a handler.
              Report the patterns you ran and the hit count for each, including
              the zeroes. A column of `unknown` backed by a named list of
              patterns and their counts is a finding. A column of `unknown`
              backed by nothing is a gap, and somebody has to redo your work to
              close it.

Also return, separately:

  A provider ledger. One row per tool-provider you found: the provider, its
  dynamic answer, the tools it supplies, and whether it exposes any
  "always visible" or equivalent exemption list.

  A registration-route list. Every distinct mechanism by which a tool reaches the
  model in this repository. If there are three, the pass needs to know there are
  three.

  A "could not determine" list. Every tool with an `unknown` in any field, the
  field, and what you would need in order to fill it.

Return the census as a markdown table with the seven columns above, then the
three lists. No prose summary, no recommendations, no opinion on whether the tool layer
is good. Do not propose changes to any name or description.

RETURN EXACTLY THIS SHAPE.

## Census

| name | reach | purpose | vocabulary | wording | effect | principals |
|---|---|---|---|---|---|---|
| `<tool name as shipped>` | static | <when a turn needs this> | "<phrase 1>"; "<phrase 2>"; "<phrase 3>" | ours | read | unknown |
| `<tool name as shipped>` | dynamic — `<expression>`, true when `<condition>` | <…> | "<…>" | upstream — <source>; changing it needs <artifact> | write, reversible by <whom> | `<quoted check>` (`<file>:<line>`) |

## Provider ledger

| provider | dynamic answer | tools supplied | exemption list? |
|---|---|---|---|
| `<provider>` | false | `<tool>`, `<tool>` | none |
| `<provider>` | `<expression>` — true when `<condition>` | `<tool>` | `<names>` |

## Registration routes

1. <mechanism> — <how many tools reach the model this way>

## Could not determine

| tool | field | what would fill it |
|---|---|---|
| `<tool>` | principals | <the artifact or person> |

## Principals patterns run

| pattern | hits |
|---|---|
| `<pattern>` | 0 |
```

## Brief 2 — the permission survey

Required. A system with a single class of caller needs none of the scoping
machinery this pass can build — but *that* is this brief's finding, not a
precondition for sending it, and the spine's third exit depends on it having
run. Its three negatives are as usable a result as a model it maps.

```
You are surveying this repository's authorization model for a documentation
pass. Read only; change no files.

The question is not "is this secure". It is: what decides whether a given caller
may perform a given action, and where does that decision live? Search by
vocabulary, confirm by shape, and report hit counts rather than impressions.

Run these tiers, in order, and report what each returned even when it returned
nothing.

  Tier 1 — a model exists, and this is it.
    Dependency manifests: an authorization or policy library, an identity
    provider SDK, an entitlements or permissions service client.
    Policy artifacts committed as data: a policy directory, policy-language
    files, a relationship schema, a roles or permissions config file.
    Migrations that create the model: tables such as roles, permissions,
    role_permissions, user_roles, acl, grants, shares, memberships, policies;
    any row-level-security or policy-creating statement.

  Tier 2 — a decision point, named.
    Grep the verbs: hasRole, hasPermission, hasAuthority, hasScope,
    requireScope, requirePermission, checkAccess, can(, authorize,
    isAuthorized, enforce(, allow(, plus whatever annotation or decorator this
    stack uses to declare a requirement on a handler.
    Then report what the call sites have in common. If all of them sit on HTTP
    handlers, say so explicitly — that single observation is one of the most
    important lines in your return.

  Tier 3 — the deny path.
    403, Forbidden, PermissionDenied, AccessDenied, NotAuthorized,
    insufficient_scope, and whatever exception type wraps them. Run this tier
    even if tiers 1 and 2 hit: it is what tells us which checks can actually
    refuse anything.

  Tier 4 — identity and its attributes.
    The principal type and its constructor — whatever this codebase calls it —
    and every signature that takes one. That parameter list is a map of how far
    identity travels.
    Token handling, claim names, scope strings.
    Scoping and entitlement columns: tenant, org, account, workspace, owner,
    plan, tier, entitlement, feature, flag.

  Tier 5 — what people wrote down.
    Authorization tests, especially any that assert a refusal; an ADR or
    security document; seed or fixture data, which usually enumerates every role
    that exists.

Then do the one thing that beats all five tiers.

  THE WALK. Follow a single request from the entry point to one executing tool.
  Write two lists: every place a decision could have been made, and every place
  the caller's identity was still available. Report both. Where they stop
  overlapping is the answer to the question this survey exists to ask.

Then answer these four directly.

  1. Which models are present? A system usually has TWO at once — a capability
     model (roles, scopes, plan, permission strings) and an object model
     (ownership, tenancy, per-object grants). Report each, or report it absent.
     Finding one and calling it "the" model is stopping halfway.
  2. For each model found, is its verdict decidable from the CALLER ALONE, or
     does it need the arguments of the specific call?
  3. Is the caller's identity reachable from inside a tool's execution? Pick one
     real tool and answer concretely: name the value in scope that identifies the
     caller, or state that there is none. Then repeat on any streaming path and
     any path that resumes a stored conversation — those diverge from the plain
     path more often than not.
  4. Does any tool take a user, account, tenant or org identifier as a PARAMETER?
     List them. That is identity being supplied by the model.

  THE NEGATIVE CASE. If you find no model, do not report an impression. Report
  three specific negatives:
    a. Can anything in this repository refuse an AUTHENTICATED caller? Give the
       patterns searched and the hit counts.
    b. Does any query filter by a caller attribute — an ownership join, a tenant
       predicate, row-level security?
    c. Is the deployment itself the model — a per-customer instance, a
       network-isolated internal tool, a single API key issued to one
       integration? That IS an authorization model; it lives in infrastructure.
  Record it as found, because it is the constraint that breaks the day a second
  customer shares the instance.

Return: the five tiers with hit counts and quoted examples; the walk's two lists;
the four numbered answers; and, if applicable, the three negatives. Do not
recommend a permission model, do not propose one where none exists, and do not
say whether what you found is adequate.

RETURN EXACTLY THIS SHAPE.

## Tiers

| tier | pattern or artifact | hits | quoted example (file:line) |
|---|---|---|---|
| 1 | `<dependency / policy file / migration>` | 0 | — |
| 2 | `<verb>` | <n> | `<quoted line>` (`<file>:<line>`) |
| 3 | `<deny pattern>` | <n> | `<quoted line>` (`<file>:<line>`) |
| 4 | `<principal type / claim / scoping column>` | <n> | `<quoted line>` (`<file>:<line>`) |
| 5 | `<test / ADR / fixture>` | <n> | `<quoted line>` (`<file>:<line>`) |

## The walk — <entry point> to <tool>

Decision points passed: <file>:<line> — <what it decides>
Identity still available at: <file>:<line> — <the value that carries it>
They stop overlapping at: <file>:<line>

## The four answers

1. Capability model: <present, described | absent>. Object model: <present, described | absent>.
2. <model>: decidable from the caller alone | needs the call's arguments.
3. Identity inside a tool's execution: `<value in scope>` | none.
   Streaming path: <same | diverges, how>. Resume path: <same | diverges, how>.
4. Tools taking a user/account/tenant/org identifier as a parameter: `<tool>`, `<tool>` | none.

## The negative case  (only if no model was found)

a. Can anything refuse an authenticated caller? patterns: `<…>` — hits: <n>
b. Any query filtering by a caller attribute? <finding, or 0 hits over `<patterns>`>
c. Is the deployment the model? <per-customer instance | network-isolated | single API key | no>
```

## Brief 3 — the vocabulary harvest

Conditional: run it when the repository holds real user turns. Skip it when the
only phrasings available are invented, and say in the plan that the query set is
synthetic — a synthetic set measures the rewrite against the imagination of
whoever wrote both, which is a weaker claim but an honest one.

```
You are collecting real user phrasings from this repository for a retrieval
evaluation. Read only; change no files.

Sources, in descending order of value: stored conversation transcripts or
fixtures; evaluation datasets; integration tests that send a user message;
support tickets, issue titles and bug reports quoting a user; documentation that
quotes a request; demo scripts and READMEs.

Return every phrasing VERBATIM. Do not normalise, do not correct spelling, do not
translate, do not merge near-duplicates, and do not summarise. Near-duplicates
are the most valuable rows here: two users asking the same thing with different
words is exactly the measurement this set exists to make. A summarised return
destroys the entire value of this brief.

For each phrasing return:
  text      the exact words, in the original language
  source    file and line, or the dataset row
  outcome   which tool the turn actually used, if the source records it;
            otherwise `unknown`
  shape     one of: keyword-like (a few nouns) | sentence | multi-step request

Also return a count of phrasings per distinct capability, so we can see which
capabilities have real coverage and which have none. Return the capabilities with
ZERO phrasings as their own explicit list. That list is worth more than any
individual row: it names the capabilities no user has ever been recorded asking
for, and every one of them is a tool the retrieval measurement cannot cover until
somebody writes a phrasing by hand.

Return the rows and the counts. No analysis, no grouping into themes, no
recommendations.

RETURN EXACTLY THIS SHAPE.

## Phrasings

| text | source | outcome | shape |
|---|---|---|---|
| <the exact words, original language, unedited> | `<file>:<line>` or `<dataset>` row <n> | `<tool>` | sentence |
| <the exact words, original language, unedited> | `<file>:<line>` | unknown | keyword-like |

## Phrasings per capability

| capability | phrasings |
|---|---|
| <capability> | <n> |

## Capabilities with ZERO phrasings

- <capability>
- <capability>
```

## Reading the returns

**Compare briefs 1 and 3 before deciding anything.** Brief 1's `vocabulary`
column is a competent guess; brief 3's rows are what users actually typed. Where
they disagree, brief 3 wins, and the disagreement is itself the strongest
possible argument for a description rewrite: the tool's author and the tool's
users are not using the same words.

**Brief 1's `wording` column splits the rewrite in two before it starts.** The
`ours` rows are the rewrite as normally understood. The `upstream` rows have no
string in this repository to edit, and they get a different move —
`STRATEGY-AND-DESCRIPTIONS.md` carries the four and the order to try them. Read
this column before writing any before/after text, because a plan that proposes a
description change for a tool whose description belongs to somebody else is
discovered at execution, by the person executing it, after the gate approved it.

**Brief 3's zero-coverage list is a work item, not a footnote.** The retrieval
measurement, and the standing build check it becomes, can only assert a tool that
some query-set row is expected to reach. Every capability on that list therefore
needs a phrasing written by hand before the check exists — written as the sentence
a user would actually say, not as the one that makes the check pass. Those rows
are also the weakest evidence in the set, and the plan says which rows are
harvested and which are invented.

**Brief 2's answer 3 decides whether `SCOPED-TOOLS.md` applies at all.** If
identity is not reachable at the execution boundary, the per-caller discovery
filter is still buildable — the strategy receives the invocation context — but
the execution guardrail is not, and a plan that ships the filter alone must say
plainly that it added no enforcement. That sentence belongs at the gate, not in a
footnote.

**Brief 1's `principals` column arrives provisional, and you finish it.** The
child was grepping a generic verb list because the survey that discovers this
codebase's actual verbs and principal type was running beside it, not before it.
So a column of `unknown` is the expected return rather than a poor one. Once
brief 2 lands, walk its quoted decision points and its principal type back
across brief 1's per-tool execution paths and fill what the evidence supports.

You inherit the child's rule exactly: **evidence or `unknown`.** Brief 2 tells
you what to look for; it never tells you what any given tool checks. A cell
filled because a permission model exists *somewhere* is an inferred permission
carrying the parent's authority, which is the same error the column was built to
prevent and harder to catch, because nobody downstream can see it was inferred.
`SKILL.md`, Step 1 carries the same rule from the spine's side.

**A row that is `unknown` in both `wording` and `principals` has one disposition,
not two.** The two columns' rules are stated separately above and each has an
obvious next step on its own; a row holding both has neither, and it is the row
most likely to sit in a plan unresolved while everything around it proceeds. The
rule: it enters **neither** the rewrite set nor any per-caller filter — there is
no string this repository may edit and no principal evidence a filter could
narrow on — and it goes to the gate as **one line naming both gaps and the owner
of each**, rather than as two entries in two lists answered by two people at two
times. Its `reach` is untouched by either gap, so if it is `static` or `provider`
it **stays in the standing retrieval check** and fails it if no query reaches it.
Unresolved is a question about the row; it is never an exemption for it.

**Contradictions between children are findings, not noise.** Two children
disagreeing about whether a provider is dynamic means the answer is computed, and
the condition is the thing you actually need. Resolve it yourself by reading the
one method; do not send a third child to arbitrate.

**Everything unresolved becomes a gate line.** The `could not determine` list and
every `unknown` in `principals` go into the plan verbatim, as questions for named
people. A pass that quietly drops them presents a complete-looking census whose
gaps have been erased rather than answered.

## What you may not delegate

- Choosing the strategy. It depends on latency budgets, deployment cost and what
  the users are like — none of which is in the repository.
- Writing any tool name or description that will ship. A child cannot see the
  query set the wording will be measured against, and wording is the change the
  gate exists to review.
- Deciding which tools are always visible. That is a claim about which turns must
  never fail, which is a product judgement.
- Deciding the move for a tool whose `wording` is not `ours`. The child
  establishes that the string belongs to somebody else and names the artifact
  that owns it; choosing between carrying its vocabulary in the scorer,
  publishing a specification of your own, opening a request with its publisher,
  and exempting it from search altogether is a trade between maintenance burden
  and permanent standing-prompt cost, and the last of those four is a decision
  the user is entitled to see at the gate.
- Running the verification in Step 5, or reporting its result. The pass's
  measurements are made where their output can be read by whoever signs the pass.
