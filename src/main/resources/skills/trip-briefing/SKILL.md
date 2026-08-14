---
name: trip-briefing
description: A combined briefing for visiting a place on a date — resolves the place, gets its forecast, and checks whether the date is a Brazilian national holiday, in one answer. Use only when the user asks about a place AND a date together.
---

# Trip briefing

One tool, `trip_briefing`, that answers "how will it be in X on date Y" in a
single call.

## When to use it, and when not to

Use it when the user asks about a **place and a date together** — a trip, a
visit, an event, a weekend somewhere.

Do **not** use it for a plain weather question, or a plain holiday question.
`geo-and-weather` and `brazil-civic-data` answer those directly and far more
cheaply: this tool runs a workflow of four model calls behind the scenes, so it
earns its cost only when all three findings are actually wanted.

## What it returns

Four fields: the resolved place, the weather in plain language, whether the date
is a national holiday, and one line of practical advice. Present them as prose,
not as a table — the user asked a question, not for a report.

## Limits worth passing on

- Holidays are **national only**. If the user is asking about a specific city,
  say that state and municipal holidays are not covered.
- The forecast reaches seven days ahead. Beyond that the tool has nothing, and
  saying so is better than a vague answer.
- Ambiguous place names resolve to the Brazilian candidate when there is one. If
  the user might have meant somewhere else, say which place you used.

## When it fails

If the place cannot be resolved, the briefing says so and the other fields are
not meaningful. Tell the user the place was not found and ask for a bigger
nearby city — do not fall back to guessing coordinates.
