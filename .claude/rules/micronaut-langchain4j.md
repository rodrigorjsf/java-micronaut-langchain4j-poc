# Micronaut 5 + LangChain4j 1.18 — traps that cost real time here

Framework-specific gotchas hit in this repository. Each one compiled, or ran, or
passed a test while being wrong. The concepts behind them live in
`.claude/skills/`; this file is the local trap list.

## Configuration binding

**`@ConfigurationProperties` on an interface needs `@AccessorsStyle(readPrefixes = "")`**
for record-style accessors. Without it the build fails with
`Method format unrecognized for @ConfigurationProperties interfaces: endpoint()`.

**`@EachProperty` needs a class, not an interface.** On an interface the
`@Parameter` key collides with accessor binding and Micronaut looks for a literal
`…models.judge.name` property that does not exist. Use a class with a
`@ConfigurationInject` constructor.

**`@EachProperty` entries in `application.yml` merge into every test context.**
A test that defines `models.judge.*` programmatically gets the shipped definition
blended with its own. Either use names that do not exist in `application.yml`
(`probe-*`), or assert against the shipped config on purpose.

**A property key segment cannot contain a dot.** `gemini-2.5-flash-lite` is
written `gemini-2-5-flash-lite` and normalised in code.

**Placeholders resolve raw environment variable names.** `${GOOGLE_API_KEY}` in
`application.yml` works, and so does `env.getProperty("GOOGLE_API_KEY")` — no
need for the `google.api.key` transform.

## AOP

**`@Cacheable` is proxy-based: a call from one method of a bean to another method
of the same bean does not pass through the proxy.** An in-class `@Cacheable`
compiles, runs, and never caches anything. Put the cached method on its own bean.

## Beans and lifecycle

**`@Context` for anything that must exist before something else connects.** The
local AWS bootstrap is `@Context` so the ElastiCache replication group exists
before Lettuce dials it.

**Lettuce beans need explicit `preDestroy`.** `@Bean(preDestroy = "shutdown")`
for `RedisClient`, `@Bean(preDestroy = "close")` for the connection. Without it a
rolling restart drops in-flight commands and shows up as 5xx during deploys.

**Provisioning is opt-in.** `agentic.persistence.bootstrap-enabled` defaults to
false. An application should not create infrastructure unless told to.

## LangChain4j

**The tool-execution error handler must be set explicitly.** The default feeds
`Throwable.getMessage()` to the model — stack traces, upstream URLs, response
bodies and credentials into the prompt, the history and the provider's logs.

**`hallucinatedToolNameStrategy` defaults to throwing**, which turns a model typo
into a 500. Return text so the model can correct itself.

**`storeRetrievedContentInChatMemory` defaults to true**, writing every retrieved
chunk into persisted memory.

**`@V` on every AI-service parameter.** Argument binding otherwise depends on
`-parameters` surviving the annotation processor.

**Compaction may never separate a tool result from the `AiMessage` that
requested it.** Preserving an activation while summarising away its request leaves
a tool_call id with no counterpart, and a provider rejects the list. Drop a
result's payload if you must; keep the message.

**Skill activation state lives in chat-memory message attributes.** Serialize
with `ChatMessageSerializer`, never a hand-rolled format that keeps only
`message.text()` — see `ChatMemoryStoreFlociIT`.

**Model listeners are swallowed.** An exception in a `ChatModelListener` is a
silent hole in the accounting, not an error. Catch and log inside the listener.

**GA and beta modules move together.** `langchain4j-bom:1.18.1` manages the
stable modules at `1.18.1` and the beta ones at `1.18.1-beta28`. Never pin one
half by hand.

## The tool layer

**`ToolJson.project` with a container path keeps the container whole.**
`project(body, "meals")` on `{"meals":[…]}` reads like a projection and drops
nothing. Use `projectList(body, "meals", max, fields…)` for a list nested inside
an object; `project` only reaches leaves.

**A tool result gets `scoreToolResult`, never `score`.** The user-text rules
measure sentence properties. Compact JSON has no whitespace, so the 400-character
long-token rule fires on any result over 400 bytes — a 457-byte SELIC series was
answered as an injection attempt.

**A third-party URL in a tool result costs the whole answer, not the tokens.**
`ExfiltrationGuardrail` withholds any response linking outside the tool
catalogue, so a projection that keeps `strSource`, `strYoutube`, `website_url` or
a thumbnail turns the most ordinary question in that skill into "The response was
withheld by the output policy." Projections are **keep-lists**, never deny-lists:
a deny-list has to be right about every field the source adds next.

**A byte ceiling cannot classify semantics.** `max-response-bytes` was once used
to separate a dish name from an ingredient, on three samples. Ten of eleven
ordinary food words exceeded it, and each was told it had named an ingredient.
Bound the list where the list is (`projectList`), and leave the ceiling as the
transport backstop.

**`max-response-bytes` is a TRANSPORT ceiling, not a context ceiling.** On a
projecting endpoint it must sit ABOVE the raw body, because projection needs a
complete document. Set it at the size you want the model to see and the cut lands
mid-object, projection cannot parse it, and the tool answers "narrow your query"
for a request that would have worked.

**Every `base-url` is https.** A tool's arguments are user text.
`check-tool-catalogue.py` fails on any other scheme; `localhost` is exempt for
test fixtures. `ip-api.com` was rejected over exactly this — see
`docs/03-security.md`.

**The catalogue check runs in both directions.** A key in Java and absent from
`application.yml` compiles, passes every test, and fails only when a user asks
the question that reaches it.

## Java

**Text-block `\` continuations keep any indentation beyond the block's common
indent.** A numbered list written with `\` renders as `"If a tool    can answer"`
— invisible in the source, visible in every prompt. Use real line breaks in
prompts.

## floci

**`CreateCacheCluster` is memcached-only.** Valkey and Redis engines need
`CreateReplicationGroup`.

**The RESP endpoint is on the floci container's port 6379**, not on the Valkey
container it spawns — that one publishes nothing. Publish `6379:6379` from the
floci service.

## Build and tests

**Always `./mvnw`.** The `mvn` on `PATH` is 3.8.6 on a Windows drive mount.

**The default build needs no network, no Docker and no API key.** Tests that need
them are `@Tag("integration")` or `@Tag("evals")` and excluded by default; run
them with `-Pit` / `-Pevals` / `-Pall`.

**Only the main thread may claim a build is green.** A subagent writes code and
tests; the main session runs `./mvnw verify` and owns every verification claim.
