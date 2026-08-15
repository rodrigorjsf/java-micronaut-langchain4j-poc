# Running a suite

The mechanics behind the rules in [`SKILL.md`](SKILL.md), which is where the
rules themselves live: pacing a live run under a measured limit, what the
`ran / total` and drift blocks print, and the four fields an override records.

## Pacing a live run under the measured limit

Free tiers cut off far lower than the paid documentation suggests — one measured
at roughly **10–20 requests per minute**, so that runner waited ten seconds
between rows. Measure yours rather than reading it off a pricing page: run the
suite once at full speed, count the quota errors, and set the wait from what you
saw.

Pace the runner rather than retrying into the limit. A retry loop against a
quota converts one slow run into a run that exhausts the quota for everything
else using the same key that day.

A run that pauses is cheaper than a run that produces a number nobody trusts.

## The `ran / total` block

Printed on every run, green included, with the skipped count and its cause on the
same line as the rows that ran:

```
scope golden set:  ran 3 / 62   (59 skipped: quota)
  accuracy 3/3 = 1.000
```

## The drift block

At the end of a run, in the build log, labelled advisory so nobody reads it as a
failure:

```
intent drift (advisory, not gated):
  address  -> company     3
  greeting -> smalltalk   1
```

Two columns and a count: what the label was in the recorded set, what it is now,
how many rows moved.

## What an override records

An override is one documented switch, used when the gate fails and the change
ships anyway. It records four things:

| Field | Why it is there |
|---|---|
| who overrode it | an override with no name is a permanent lowering nobody signed |
| which gate, by constant name | so the next failure of the *same* gate is visible as a second failure, not a first |
| why, in one line | the reason is what the expiry review reads |
| when it expires | an override with no expiry is a deleted gate with extra steps |

Make it expire. A build that has to re-approve the override next week gets the
gate back; one that silently carries it forward has lost the gate and kept the
line of code that implies it still has one.

The failure mode this exists to prevent is the alternative people reach for:
editing the threshold constant down until the run is green. That change is
permanent, invisible in a build log, and afterwards indistinguishable from a
threshold someone reasoned about.
