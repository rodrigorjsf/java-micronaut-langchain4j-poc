# Proving that activation changed something

Open this before a skill ships, and whenever activating one appears to change nothing.

`SKILL.md` carries the checks that read text and activate nothing — grep in both directions, and
tool-name uniqueness across the catalogue. These are the ones that need the skill actually
activated, plus the reading that tells the three activation failures apart.

The comparison `SKILL.md` sends here is **the visible tool set against this skill's declared list,
with the skill active**. Unequal means the mount is wrong: tools declared and not attached, or
attached from somewhere you did not declare. It says nothing about a neighbour declaring the same
tool — that is the uniqueness check, and it runs on the files.

## Routing: did it fire on the right turns

Run the labelled set **first**. It is the only check here that can fail *before* the skill is ever
reached, and everything below assumes activation happened — read those results without this one and
a routing miss gets attributed to the mount.

Building the set and scoring the run are written down in `reviewing-agent-tools-and-skills` — read
that file, or ask the user to run it; no skill can invoke it. What this file adds is the position:
nothing below is diagnostic until this one is green.

## The lesson arrives, not just the tools

Capture the request your framework actually sends on the turn after activation, and assert that a
distinctive sentence of the body is in it. Choose a sentence no other document would contain — a
tool identifier plus its bound, not "start from what the user gave you".

A framework can mount the tools and drop the instructions. The model then holds the tools with no
lesson: it calls them in the wrong order, unbounded, and improvises past their failures. Every
symptom reads as a weak model, and the fix is one line of mount configuration.

This check is also the only one that survives a body edit. Routing does not move when you rewrite a
recipe, so nothing else in this file will tell you the new text reached the model at all.

## Reading a failure back to its cause

| What you observe | Which of the three it is | Where to look |
|---|---|---|
| no tool from this skill was called, on a turn that needed one | it never fired | the description, and the near-miss labels of whichever skill took the turn instead |
| the skill fired, and the model answered from memory | tools never attached | the mount: compare the visible tool set against the declared one |
| the skill fired, tools were called, order and bounds are wrong | the body did not arrive | the captured request: search it for a body sentence |

The middle row is the one that gets misdiagnosed as a weak model, because a model with tools it was
never taught to use produces confident, plausible, wrongly-ordered calls.

## In production

Watch **the share of activations followed by no tool call at all**. Rising means the description is
claiming traffic the body cannot serve — turns arrive, the body loads and is paid for on every
remaining turn, and nothing in it answers them. That is the visible half of the over-firing /
under-firing pair; the invisible half is why the labelled set exists at all.

Split that share by whether a peer skill was active on the same turn: the turn that straddles two
skills is the turn that stalls, and an unsplit share hides it inside the healthy one.
