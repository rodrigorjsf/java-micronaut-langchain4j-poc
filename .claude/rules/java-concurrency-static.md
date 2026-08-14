---
paths:
  - "**/*.java"
---

# Java 25 — Shared state, virtual threads, and observability

Applies to all Java code in this repository (microservices, high RPM, DynamoDB, Redis, OTEL).
Applies to new code **and** to existing code you touch.

## 1. Decision rule for `static`

Before writing or keeping a `static` method/field, answer:
does it hold mutable state? does it do I/O? does it vary by tenant/environment/flag? does it need interception (retry, circuit breaker, span)?

- Any "yes" → **it must not be `static`**. Use a singleton bean injected via constructor.
- All "no" → `static` is fine and preferable. Do not inject `StringUtils.isBlank()`.

Forbidden, no exceptions:
- Mutable `static` field (including `static final` of a mutable type: `List`, `Map`, `Date`, array).
- `static Map`/`HashMap` used as cache, dedupe, rate limit, or idempotency store.
- `static ThreadLocal` for request context.
- `static synchronized`.
- I/O client in `static final` (`DynamoDbClient`, `RedisClient`, `HttpClient`, configurable `ObjectMapper`).
- I/O, remote config reads, or anything that can throw inside `static { }`.

Reasons to cite when proposing a refactor (the "why" matters in review):
- A non-`volatile` `static` field guarantees no visibility across threads; the JIT may hoist the read out of the loop.
- A non-`volatile` `static long`/`double` permits torn reads — a value that never existed.
- An exception in `static { }` throws `ExceptionInInitializerError` once, then `NoClassDefFoundError` **forever** in that JVM. No retry recovers it; only a pod restart.
- The class-initialization lock serializes every thread on cold start, right after deploy.
- `static` is a singleton **per JVM**, not per system. With N replicas you get N divergent counters/caches.

## 2. Required replacement patterns

| Antipattern found | Refactor to |
| --- | --- |
| Several mutable `static` config fields | `AtomicReference<ImmutableConfig>` — atomic snapshot publication (record) |
| Mutable `static` flag/counter | Instance `AtomicBoolean`/`AtomicLong`, or a distributed store |
| `static ThreadLocal<Context>` | `ScopedValue` (JEP 506, final in Java 25) |
| `static synchronized` | Per-key (striped) lock or a lock-free structure |
| `static Map` cache | Caffeine in a singleton bean, or Redis if it must be global to the system |
| `static final DynamoDbClient` | `@Bean` singleton with lifecycle (`close()` on shutdown) |
| Counter/limit/idempotency in `static` | Atomic operation in Redis/Dynamo |

Use an immutable `record` for every object shared across threads.
Prefer publication by reference swap over in-place mutation.

## 3. Virtual threads (Java 25)

- Never pool virtual threads. One per task (`Executors.newVirtualThreadPerTaskExecutor()`), closed with try-with-resources.
- Concurrency against an external dependency is bounded by an **explicit `Semaphore`**, sized to downstream capacity — not by executor size. Without it, 100k VTs become an invisible queue on the connection pool.
- `synchronized` no longer pins (JEP 491, Java 24+), **but contention remains**. Migrating to VTs does not remove a logical bottleneck; do not treat resolved pinning as a resolved problem.
- Residual pinning: native/JNI frames and a class initializer in progress.
- Do not use `ThreadLocal` with VTs: each thread carries a copy; at hundreds of thousands, that is a memory leak. Use `ScopedValue`.
- Concurrent fan-out: `StructuredTaskScope` (JEP 505), never hand-rolled `CompletableFuture` on a shared executor.
- Timeouts are mandatory on every network call. A blocked VT is cheap; a VT blocked forever is a leak.

## 4. DynamoDB and Redis clients

- `DynamoDbClient`, `DynamoDbEnhancedClient`, `TableSchema`: thread-safe. One managed singleton, shared.
- Lettuce `RedisClient` / `StatefulRedisConnection`: thread-safe and multiplexed — one connection serves thousands of VTs. Exception: blocking commands (`BLPOP`, `WAIT`) and `MULTI/EXEC` need a dedicated connection.
- **A raw Jedis instance is never shared.** Only via `JedisPool`. Two threads on the same socket interleave the RESP stream and one receives the other's response — silently, under load.
- Every client needs ordered shutdown on `SIGTERM`, otherwise Kubernetes rolling updates produce 5xx.
- If you find a statically shared Jedis, treat it as a **data-correctness bug**, not a style improvement. Flag it at top priority.

## 5. OpenTelemetry

- OTEL context propagates via `Context`/`ScopedValue`, never via an application static field.
- When fanning out over VTs, wrap tasks to propagate `Context` (e.g. `Context.taskWrapping(executor)`), or child spans become orphans.
- Every external I/O call (Dynamo, Redis, HTTP) must sit inside a span with operation and outcome attributes.
- A `static` method cannot be intercepted by a proxy/AOP: **a static I/O call is a hole in the distributed trace** and loses annotation-driven retry, circuit breaker, and bulkhead. This is the decisive argument in code review.
- Never put tenant, user, or sensitive data in a span name; use attributes, and never PII.

## 6. Refactoring existing code (not only new code)

When editing any Java file, scan the file you touched and:

1. List the antipatterns from this rule, with file and line.
2. Classify:
   - **P0 — fix in the same PR**: request-context leakage (tenant/user in a static field), shared Jedis, mutable `static Map` under concurrency, I/O in `static { }`.
   - **P1 — immediate, separate PR**: `static synchronized` on a hot path, client in `static final`, `ThreadLocal` with VTs.
   - **P2 — record as debt**: pure statics with no risk, ergonomics.
3. Fix P0 immediately. Do not ask permission to fix cross-tenant context leakage — that is a security incident.
4. For P1/P2, propose the plan before applying; never mix broad refactoring with a functional change in one commit.
5. Never "solve" concurrency by sprinkling `synchronized` or `volatile` on top. Eliminate the shared state.

Scope rule: refactor what you touched and what sits in the path of the change. Do not open a repo-wide refactor without an approved plan.

## 7. Verification before calling the task done

Never mark done without proof. Required:

- [ ] `grep -rn "static.*\(Map\|List\|Set\|ThreadLocal\)" --include=*.java` reviewed
- [ ] `grep -rn "static synchronized" --include=*.java` empty or justified
- [ ] No `static {` containing a network call
- [ ] Concurrency tests for the changed path (JCStress, or at minimum N threads × M iterations asserting an invariant)
- [ ] Tests run with `-XX:+UnlockDiagnosticVMOptions -Djdk.tracePinnedThreads=full` when VTs are involved
- [ ] Suite runs in parallel without flakiness (static state leaks between tests in the same JVM)
- [ ] ArchUnit covers the rule: no mutable static fields outside immutable constants
- [ ] Spans present in the trace for all new I/O

If ArchUnit is not yet in the module, propose adding it — the rule only holds if it is executable in CI.

## 8. Argumentation errors to avoid

- Do not claim "`static` is slow". `invokestatic` is the cheapest, most easily inlined call on the JVM. The cost is **correctness, observability, and evolution** — argue from there.
- Do not turn pure utilities into injected interfaces. Excess DI is as bad as excess static.
- Do not conclude a concurrency bug is absent because it did not reproduce locally. Two threads on a laptop will not reproduce broken visibility across 32 cores at 50k RPM.
