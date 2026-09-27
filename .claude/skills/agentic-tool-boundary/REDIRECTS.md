# Redirects: the destination a response picks

Open this when a catalogued upstream can answer with a `3xx`, or when reviewing
the HTTP client a tool door uses. It extends rule 1 of `SKILL.md`: a tool never
names a destination, and neither may a response.

## A redirect is a destination too

The catalogue decides the **first** request. It says nothing about the second:
a catalogued host that answers `302 Location: http://169.254.169.254/...` hands
the client a destination nobody configured, and an HTTP client that follows
redirects on its own requests it before any of your code sees the `302`. The
parameter had nowhere to put a destination; the response did.

MCP's security guidance names this exact chain — "Normal-looking URLs that
redirect to internal resources" — and says clients "SHOULD apply the same URL
validation to redirect targets": "Do not blindly follow redirects to internal
resources", and "Consider disabling automatic redirect following and validating
each hop" [sourced — Model Context Protocol, *Security Best Practices*, version
2026-07-28, section on Server-Side Request Forgery, read 2026-09-27,
https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/docs/docs/2026-07-28/tutorials/security/security_best_practices.mdx].
That section is written about OAuth discovery URLs; the mechanism is the same
for any server-side client that fetches on a model's behalf.

So the door owns the redirects:

1. **Turn automatic following off** in the tool door's own client — not the
   shared one, where the same switch silently changes every other caller. Most
   clients follow by default; Micronaut's followed a `302` to an off-catalogue
   host in this repository's red test until its tool client set
   `follow-redirects: false` [sourced — `ToolHttpClientTest`, run in a ticket
   session rather than the main thread].
2. **Follow each hop yourself**, and only to a host **and port** a catalogue
   entry names **exactly**. Exact host, not registrable domain: a link is read
   by a person, a request is made by your process, and a sibling subdomain of a
   catalogued API is a host nobody reviewed. The port matters for the same
   reason: `localhost:6379` is not the service the catalogue reviewed.
3. **Never downgrade** `https` to `http`, and **cap the hops** (three is
   plenty; a longer chain is a loop or someone shopping for a host).
4. **Share one deadline** across the hops, so a chain cannot multiply the
   endpoint's timeout.
5. **A refused hop is a value, not an exception** — and never retried. It is
   deterministic: asking again gets the same `Location`. Log the target for
   operators; keep the address out of the model's text, which can repeat it.

Keep the hops that stay on the host. Refusing every `3xx` is the easy fix and a
silent regression: an upstream that moved a route answers `301`, and the answer
is still there.

The test that proves it needs something observable, because nothing listens on
`169.254.169.254` in a test. Point a stub at `302` to the same stub server
under a host the catalogue does not hold (`127.0.0.1` when the catalogue says
`localhost`), and assert the landing route was called **zero** times. Then
assert a `302` to `169.254.169.254` fails fast, as a refused redirect rather
than as a connection that timed out — a client that followed would also fail
there, just more slowly, so "the call did not succeed" proves nothing.

What this does not cover: a catalogued hostname whose DNS answer changes to a
private address between validation and use. Pinning resolved addresses, or an
egress proxy that blocks private ranges, is the layer for that.
