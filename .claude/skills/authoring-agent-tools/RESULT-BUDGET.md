# Result budget

Reached from `SKILL.md` before the result shape is fixed, and again whenever a
per-result budget has to be defended to someone who wants one more field.

## The probe

Make about twenty calls in total against the real upstream, spread across the
arguments the working list produces, **against the worst account you can
reach**, and record serialized size at p50 and at max across all of them. The endpoint that returns twelve rows for the test user
returns four hundred for a reseller: a test account is the one account whose size
tells you nothing.

**Max is the number the test gates; p50 is the number the bill is forecast
from.** p50 times the calls a turn makes is what this tool adds to an average
turn. A forecast that looks fine beside a max ten times larger is a tool that
fails on your biggest customer, in the hour they are biggest.

## The arithmetic

Illustratively: divide what a turn may spend on tool output by the calls it
makes — 6,000 tokens across three calls is ~2,000 per result, and that is what
the **shaped** max must sit under. Roughly four characters of JSON is a token, so
~2,000 tokens is ~8 KB serialized; measure bytes, convert once, argue in tokens
because that is what the bill is in.

A projection that will not fit under it is not a budget problem. It means the
tool is still answering more than one question, and the fix is upstream in the
interview, not another field deleted at random.

## The fixture

Keep the fattest payload the probe produced, checked in beside the code. It is
what the budget test runs against, and it is the only thing that will notice the
day the upstream adds a field to its response — which it will, without telling
you, in a release note about something else.
