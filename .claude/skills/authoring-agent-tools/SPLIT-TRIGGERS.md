# Split triggers

Reached from `SKILL.md` when the cannot-decide sentence did not fire and the tool
still feels like two. Three conditions split a tool; one argues against it.

## A read and a write behind one name

`manage_order(action: "status" | "cancel")` cannot be granted read-only access to
a support agent, cannot carry a two-call contract that applies to half of itself,
and writes one audit record for two events an auditor would call very different
things. The enum is a router in the parameter set, and the model chooses the
branch — so a prompt injection that flips a string flips the blast radius.

Split it, and the read half keeps cost tier one.

## Two questions sharing a verb

"Where is my order" and "did my refund go through" both read as *look up an
order*. They want different fields, different freshness and different failure
text: the shipment answer tolerates a minute of staleness, the money answer does
not. One tool serving both returns the union of two projections — the fat payload
the answer-sentence pass just removed.

The test is on the sentence list: run the answer sentence for each cluster of
sentences. Two answer sentences with disjoint field sets are two tools.

## A parameter that is really a mode

A parameter whose value changes *which question is answered* — not which rows
come back — is a tool boundary written as a string. `scope: "summary" | "detail"`
is a projection choice and stays; `report: "tax" | "shipping"` is two tools.

## The counter-trigger: no decision in the middle, no split

Two calls that always run in fixed order, with no user choice and no branch
between them, buy a model call, its input tokens and a round trip of latency for
nothing — and add a step where the model can stop early and answer from the
intermediate result. A split exists to put a decision where it can be made. When
the choice is deterministic policy the user never sees — which warehouse, which
pricing tier — it belongs inside the tool.
