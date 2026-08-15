# Writes and flags

Reached from `SKILL.md` when the tool changes anything, or when a boolean
parameter survives the three buckets.

## The token, and the call that replays it

The token the plan call mints is a value the model cannot construct, and that is
the whole control: text injected into the conversation can talk a model into
setting a boolean, and cannot make it produce a token the server minted.

**A commit gets called twice.** The model retries on its own whenever a response
is slow or a connection drops, and it retries by calling the tool again.

- A token commits **once**. A second commit with the same token performs nothing
  and reports the original outcome — that is the `already_done` entry on the
  taxonomy, and it is a success, not an error. A model that reads *"already done;
  here is what happened"* tells the user the truth. One that reads *"invalid
  token"* either retries again or apologises for something that worked.
- The token **expires** in minutes, not hours. A plan describes a world that has
  moved on by the time the user comes back to the conversation tomorrow.
- Where the caller supplies the key instead — an idempotency key on a create —
  derive it from the request content. A key generated fresh on each attempt
  deduplicates nothing, which is the failure a clock or a counter produces.

Where an effect is irreversible, regulated, or spends money, the tool is not an
actuator at all: it files a request a human approves, and the record is keyed to
the authenticated caller the system bucket supplies, never to the model.

## The flag trichotomy

`includeShipments: boolean` is one of three things, and the failure it produces
says which:

- **It only makes the result fatter.** The model sets it true out of caution and
  every call pays. Delete it; decide server-side.
- **It widens what the call may reach** — `scope: "all"`,
  `includeInternalNotes: true`. Injected text talks the model into flipping it
  and the tool complies, because complying is the whole job. This belongs in the
  system bucket: set outside the schema, or the capability goes.
- **It changes which question is being answered** — see
  [`SPLIT-TRIGGERS.md`](SPLIT-TRIGGERS.md).

A boolean surviving all three is rare. When one does, its description names the
sentence from the list that sets it, exactly as a parameter's does.
