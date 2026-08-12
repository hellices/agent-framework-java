# 07 Interceptors and context management

**Prefix** `INT` · **Upstream features** [10 middleware](../upstream/snapshots/d0a4165f/features/10-middleware.md),
[11 compaction](../upstream/snapshots/d0a4165f/features/11-compaction.md)

Defines the interceptor contracts for the processing that surrounds agent execution, model calls,
tool calls, and sessions, together with the context compaction rules. Session snapshots and stores
are owned by [06 Sessions and conversation state](06-sessions.md), tool approval and the call loop
by [04 Tool definitions and the tool call loop](04-tools.md), and the default harness assembly by
[08 Harness features](08-harness.md).

## Adoption scope

The `Grade` column in this document is, as [README](README.md#requirement-grades) defines it, how binding a requirement is once the decision to build the feature has been made; whether the feature is adopted at all follows the [compatibility matrix](../upstream/snapshots/d0a4165f/compatibility-matrix.md).

- The interceptor seam (`MID01`) has adoption `Required`.
- `INT-014`–`INT-021`, corresponding to the compaction feature group (`CMP01`–`CMP03`), have adoption `Optional`.

## Summary

| ID | Requirement | Adoption | Grade | Phase |
| --- | --- | --- | --- | --- |
| INT-001 | The public extension points are fixed as typed interceptors, one per responsibility | Required | Required | MVP |
| INT-002 | Interceptors do not reimplement DI, transactions, security, or AOP | Required | Required | MVP |
| INT-003 | Registration order determines pre-processing order and wrapping direction | Required | Required | MVP |
| INT-004 | The interceptor context is explicit and mutable | Required | Required | MVP |
| INT-005 | Installing on an unsupported seam fails immediately | Required | Required | MVP |
| INT-006 | Not calling `next` short-circuits the execution | Required | Required | MVP |
| INT-007 | Post-processing can replace the result | Required | Required | MVP |
| INT-008 | Tool exception handling rules distinguish before and after execution | Required | Recommended | Core+ |
| INT-009 | The core owns the session state machine and interceptors only get participation points | Required | Required | Core+ |
| INT-010 | Multi-seam features ship only as an opaque bundle | Required | Recommended | Core+ |
| INT-011 | Tool set changes take effect only on the next iteration | Required | Recommended | Core+ |
| INT-012 | Streaming rewrites are allowed only inside the finalization boundary | Required | Required | Core+ |
| INT-013 | Cancellation propagates to every interceptor and to compaction | Required | Required | MVP |
| INT-014 | Compaction preserves the atomicity of a tool call and its tool result | Optional | Required | Core+ |
| INT-015 | Compaction keeps identifiers and counts stable across incremental updates | Optional | Required | Core+ |
| INT-016 | Compaction internal state is expressed as an explicit index plus trace metadata | Optional | Required | Core+ |
| INT-017 | The compaction start condition and stop condition are separated | Optional | Recommended | Core+ |
| INT-018 | Group-based and turn-based sliding policies are separated | Optional | Recommended | Core+ |
| INT-019 | Tool-result compaction replaces content with a summary and never re-exposes the original | Optional | Recommended | Core+ |
| INT-020 | Summarization compaction restores the originals on failure | Optional | Required | Core+ |
| INT-021 | The compaction provider offers a pre-run projection and an optional post-store hook | Optional | Recommended | Core+ |
| INT-022 | Logging and telemetry interceptors keep sensitive data exposure opt-in | Required | Recommended | Optional |

---

## INT-001 The public extension points are fixed as typed interceptors, one per responsibility

**Requirement.** The public interceptor API must be provided as types with separated
responsibilities, at least `AgentExecutionInterceptor`, `ModelCallInterceptor`,
`ToolCallInterceptor`, and `SessionInterceptor`, and a single general-purpose middleware type that
accepts everything is not offered as a first-class public extension point.

**Upstream comparison**

- .NET: The seams are split, as with the agent decorator, the function invocation wrapper, and the chat-client decorator.
- Python: The 3-seam typed pipeline of `AgentMiddleware`, `ChatMiddleware`, and `FunctionMiddleware` is central.

**Decision.** Python's typed seams are adopted as the public API, and .NET's prebuilt decorator
pattern is kept only as a convenience implementation. A general-purpose middleware with mixed
responsibilities blurs ordering and state ownership.

**Acceptance criteria**

- The public interceptor API distinguishes the execution, model, tool, and session seams by type.
- An interceptor is not implicitly installed on a seam it does not support.
- Even when a convenience wrapper exists, it ultimately maps onto one of the typed seams above.

**Evidence** [10 purpose/boundaries](../upstream/snapshots/d0a4165f/features/10-middleware.md),
[10 API/types](../upstream/snapshots/d0a4165f/features/10-middleware.md),
[10 Java typed interceptor decision and the Spring integration boundary](../upstream/snapshots/d0a4165f/features/10-middleware.md)

---

## INT-002 Interceptors do not reimplement DI, transactions, security, or AOP

**Requirement.** The interceptor layer is responsible only for observing and transforming around
execution, and does not replace the DI container, transaction boundaries, host security policy, or
general-purpose AOP.

**Upstream comparison**

- .NET: The builder and decorators fix only the execution contract, while hosted identity and the approval state machine are handled by separate layers.
- Python: The typed pipeline concentrates on execution control and limits its scope of responsibility with supported-category enforcement.

**Decision.** Both upstreams keep the seam narrow. Java must hold the same boundary. If AgentEngine
pulls in host responsibilities, the testable contract collapses.

**Acceptance criteria**

- The interceptor API does not expose methods dedicated to transaction propagation or authorization decisions.
- Host security information enters only as session or hosting context input and is never created by an interceptor itself.
- The documentation states that Spring AOP or a BeanPostProcessor must not reinterpret the execution order.

**Evidence** [10 purpose/boundaries](../upstream/snapshots/d0a4165f/features/10-middleware.md),
[10 Java typed interceptor decision and the Spring integration boundary](../upstream/snapshots/d0a4165f/features/10-middleware.md)

---

## INT-003 Registration order determines pre-processing order and wrapping direction

**Requirement.** Interceptors must pre-process in registration order and post-process in reverse
order, and the rule that the interceptor registered first wraps outermost must be fixed on every
seam.

**Upstream comparison**

- .NET: The builder applies factories in reverse order, so what is `Use()`d first becomes outermost.
- Python: The per-seam pipeline fixes registration order pre / reverse order post with tests.

**Decision.** Both upstreams agree. If this rule wavers, the order of logging, approval, and
redaction differs from environment to environment. Java must use the same ordering semantics in
both the builder and the runtime.

**Acceptance criteria**

- When two interceptors `A` and `B` are registered in that order, the call order is `A-pre → B-pre → handler → B-post → A-post`.
- The agent, model, tool, and session seams all use the same order.
- The runtime does not reverse the container's sort result again.

**Evidence** [10 execution flow](../upstream/snapshots/d0a4165f/features/10-middleware.md),
[10 Acceptance scenarios](../upstream/snapshots/d0a4165f/features/10-middleware.md)

---

## INT-004 The interceptor context is explicit and mutable

**Requirement.** Each seam must use an explicit context object carrying the messages, options,
session, metadata, result, and stream hooks, and must not depend on a global thread local.

**Upstream comparison**

- .NET: Decorator and callback shapes are more central than a typed context object, but execution options and the function context are passed explicitly.
- Python: `AgentContext`, `ChatContext`, and `FunctionInvocationContext` keep metadata and a mutable result on a shared surface.

**Decision.** Python's explicit context model is adopted. In Java asynchronous execution and
streaming, a context object is safer than global state. The `.NET` callback experience is used only
as a reference for designing the context fields.

**Acceptance criteria**

- There is a public context type corresponding to each seam.
- An interceptor can read and replace the result through the context.
- Reading the metadata needed during execution does not require calling a thread-local API.

**Evidence** [10 API/types](../upstream/snapshots/d0a4165f/features/10-middleware.md),
[10 state](../upstream/snapshots/d0a4165f/features/10-middleware.md),
[10 Java typed interceptor decision and the Spring integration boundary](../upstream/snapshots/d0a4165f/features/10-middleware.md)

---

## INT-005 Installing on an unsupported seam fails immediately

**Requirement.** An attempt to install an interceptor on a host or seam that does not exist must
fail explicitly at the initialization stage instead of being silently skipped.

**Upstream comparison**

- .NET: Attaching function middleware to an agent without `FunctionInvokingChatClient` makes the build fail.
- Python: Partially installing a bundle or middleware on an unsupported category raises `MiddlewareException`.

**Decision.** Both upstreams agree. A silent skip is the most dangerous failure mode, because a
security feature can pass by while switched off. Java must also surface a lack of support quickly.

**Acceptance criteria**

- Registering an interceptor on an unsupported seam fails at application startup or at the builder stage.
- The failure does not merely leave a warning log and continue.
- The failure message includes which seam is unsupported.

**Evidence** [10 errors](../upstream/snapshots/d0a4165f/features/10-middleware.md),
[10 Acceptance scenarios](../upstream/snapshots/d0a4165f/features/10-middleware.md)

---

## INT-006 Not calling `next` short-circuits the execution

**Requirement.** When an interceptor does not call `next`, the later interceptors and the actual
handler must not run.

**Upstream comparison**

- .NET: If function middleware does not call `next`, the tool is not executed and the middleware return value becomes the final result.
- Python: If `call_next()` is not called, all later middleware and the handler are skipped.

**Decision.** Both upstreams agree. Short-circuiting is the core contract behind approval, blocking,
caching, and precomputed responses. Java also gives every seam the same short-circuit rule.

**Acceptance criteria**

- The number of handler invocations after a short-circuiting interceptor is 0.
- The short-circuited response is observed as the final result.
- Even after a short circuit, the post-processing of the outer interceptors already entered runs in reverse order.

**Evidence** [10 purpose/boundaries](../upstream/snapshots/d0a4165f/features/10-middleware.md),
[10 errors](../upstream/snapshots/d0a4165f/features/10-middleware.md),
[10 Acceptance scenarios](../upstream/snapshots/d0a4165f/features/10-middleware.md)

---

## INT-007 Post-processing can replace the result

**Requirement.** After the downstream execution finishes, an interceptor must be able to read the
result from the context and swap it for a different result.

**Upstream comparison**

- .NET: When function middleware changes the tool result, the final `FunctionResultContent` changes too.
- Python: Post-execution middleware can swap `context.result` for a new value.

**Decision.** Both upstreams agree. Without result replacement, redaction, normalization, and
synthetic fallbacks cannot be gathered in one place. Java keeps the context result mutable but
makes the replacement point explicit.

**Acceptance criteria**

- An outer interceptor can replace the result produced by a downstream handler.
- The replaced result is used as the final return value and as the input to subsequent interceptors.
- Result replacement does not depend solely on in-place mutation of the original result object.

**Evidence** [10 state](../upstream/snapshots/d0a4165f/features/10-middleware.md),
[10 Acceptance scenarios](../upstream/snapshots/d0a4165f/features/10-middleware.md)

---

## INT-008 Tool exception handling rules distinguish before and after execution

**Requirement.** A tool interceptor can turn an exception thrown inside the tool into a replacement
result, but an exception raised by the interceptor itself before the actual tool call must be
propagated to the caller as is.

**Upstream comparison**

- .NET: A pre-invocation exception is surfaced, while an exception inside the tool can be caught by middleware and turned into a result.
- Python: Ordinary tool exceptions are turned into error content, but control exceptions such as `UserInputRequiredException` stay on a separate path.

**Decision.** Both upstreams distinguish the exceptions. Java must likewise not swallow an
interceptor bug and a tool failure at the same level. That is what lets an operator find a faulty
interceptor quickly.

**Acceptance criteria**

- A pre-next exception in an interceptor propagates as a final execution failure.
- An exception from the tool implementation can be caught by an interceptor and converted into a replacement `tool result`.
- Control exceptions such as a request for user input are not downgraded to an ordinary error result.

**Evidence** [10 errors](../upstream/snapshots/d0a4165f/features/10-middleware.md)

---

## INT-009 The core owns the session state machine and interceptors only get participation points

**Requirement.** The session state machine, covering message injection, approval binding, and
per-call persistence, must be owned by the core, and interceptors must observe and extend it only
at the designated seams rather than rebuilding it around the side.

**Upstream comparison**

- .NET: Approval binding, message injection, and per-service-call persistence are wired as dedicated decorators of the default stack.
- Python: Approval, message injection, and agent-hooks combine typed middleware with the session state machine.

**Decision.** Both upstreams agree. Scattering the state machine outside the interceptors breaks
session consistency. In Java the core runtime keeps ownership of the state and interceptors provide
only the connection points.

**Acceptance criteria**

- An arbitrary interceptor cannot replace the message injection queue or the approval response binding with its own store.
- The default features are assembled as a core-provided bundle or as built-in interceptors.
- The storage location and the ordering of the session state machine are defined in one place in the core documentation.

**Evidence** [10 purpose/boundaries](../upstream/snapshots/d0a4165f/features/10-middleware.md),
[10 execution flow](../upstream/snapshots/d0a4165f/features/10-middleware.md),
[10 Java typed interceptor decision and the Spring integration boundary](../upstream/snapshots/d0a4165f/features/10-middleware.md)

---

## INT-010 Multi-seam features ship only as an opaque bundle

**Requirement.** A feature that has to handle the agent, model, and tool seams together must ship as
an opaque bundle that does not allow partial installation.

**Upstream comparison**

- .NET: No public bundle concept corresponding exactly to agent-hooks was confirmed.
- Python: `MiddlewareBundle` groups multiple seam features into an opaque unit that cannot be partially installed.

**Decision.** The Python approach is adopted. Spreading a feature whose seams move together across
a list of individual interceptors cannot guarantee ordering or catch a missing installation. Java
also keeps the bundle as a first-class concept.

**Acceptance criteria**

- A bundle is registered only as its complete seam set.
- The caller cannot partially replace the interceptor list inside a bundle or disable only part of it.
- Installing a bundle on an unsupported seam fails explicitly.

**Evidence** [10 API/types](../upstream/snapshots/d0a4165f/features/10-middleware.md),
[10 execution flow](../upstream/snapshots/d0a4165f/features/10-middleware.md),
[10 Acceptance scenarios](../upstream/snapshots/d0a4165f/features/10-middleware.md)

---

## INT-011 Tool set changes take effect only on the next iteration

**Requirement.** Even though a tool interceptor can add to or remove from the visible tool set, that
change must take effect only on the next model iteration rather than in the current batch, and must
not directly modify the caller's original list.

**Upstream comparison**

- .NET: No equivalent public contract for progressive tool mutation was confirmed in the inspected scope.
- Python: `add_tools/remove_tools` apply on the next iteration and the caller's original tool list does not change.

**Decision.** Python's explicit contract is adopted. If the tool set changes in the middle of the
current batch, the schema the model saw and the actual executor diverge. Applying on the next
iteration is safer.

**Acceptance criteria**

- A tool added while the current tool batch is running is not called immediately in that batch.
- The added and removed tool set is reflected in the next model iteration.
- The original tool collection passed by the caller is unchanged after execution.

**Evidence** [10 state](../upstream/snapshots/d0a4165f/features/10-middleware.md),
[10 extension points](../upstream/snapshots/d0a4165f/features/10-middleware.md),
[10 Acceptance scenarios](../upstream/snapshots/d0a4165f/features/10-middleware.md)

---

## INT-012 Streaming rewrites are allowed only inside the finalization boundary

**Requirement.** A streaming interceptor can register hooks before the stream starts, but must not
rewrite content again after a verdict or after finalization.

**Upstream comparison**

- .NET: Streaming middleware and logging/telemetry wrap cancellation and stream execution.
- Python: `ResponseStream` provides hook registration and sealing rules, and a gated stream blocks rewrites after the verdict.

**Decision.** Python's sealing rules are adopted as mandatory. Correcting a stream after it has been
finalized makes the audit log and the user response diverge. Java must include the streaming
boundary in the interceptor contract.

**Acceptance criteria**

- During streaming execution an interceptor can register transform/result/cleanup hooks.
- Registering a new rewrite hook after finalization fails or is ignored.
- A regression test that changes content again after the verdict fails.

**Evidence** [10 concurrency/streaming/cancellation](../upstream/snapshots/d0a4165f/features/10-middleware.md),
[10 security](../upstream/snapshots/d0a4165f/features/10-middleware.md),
[10 Acceptance scenarios](../upstream/snapshots/d0a4165f/features/10-middleware.md)

---

## INT-013 Cancellation propagates to every interceptor and to compaction

**Requirement.** The cancellation signal must reach the agent, model, tool, and session interceptors
and the compaction strategies without interruption, and a cancellation exception is not turned into
a success.

**Upstream comparison**

- .NET: The middleware and compaction surfaces broadly take a `CancellationToken` and propagate summarization cancellation.
- Python: The middleware signatures carry no explicit token but rely on async cancellation and stream cleanup semantics.

**Decision.** Java chooses .NET-style explicit cancellation. Python-style implicit cancellation is
weak in the Java standard, so an explicit argument is safer. Compaction must use the same rule so
that no intermediate summary is left behind.

**Acceptance criteria**

- The four seam interceptor APIs and the compaction API all take a cancellation argument.
- A cancelled summarization compaction does not commit its summary.
- A cancelled execution is not recorded as a successful response or as a success metric.

**Evidence** [10 concurrency/streaming/cancellation](../upstream/snapshots/d0a4165f/features/10-middleware.md),
[11 concurrency/streaming/cancellation](../upstream/snapshots/d0a4165f/features/11-compaction.md)

---

## INT-014 Compaction preserves the atomicity of a tool call and its tool result

**Requirement.** Compaction must treat a tool call declaration and the corresponding tool result as
one indivisible group, and must group even a non-adjacent result with it when the call id is
unambiguous.

**Upstream comparison**

- .NET: The `ToolCall` group guarantees the atomicity of the assistant tool call and the following tool results.
- Python: Annotation grouping reconnects even a non-adjacent unambiguous result to its declaration group.

**Decision.** The shared core of the two upstreams is atomicity. As instructed by the user, this
rule is fixed as an acceptance criterion. A partial deletion is not merely a quality problem but an
API correctness problem.

**Acceptance criteria**

- No compaction result keeps a tool call while removing its tool result.
- A non-adjacent tool result is still grouped into the same compaction group when its call id is unique.
- When a duplicate call id makes the connection ambiguous, the grouping is not forced incorrectly.

**Evidence** [11 purpose/boundaries](../upstream/snapshots/d0a4165f/features/11-compaction.md),
[11 Grouping / annotation / indexing](../upstream/snapshots/d0a4165f/features/11-compaction.md),
[11 Acceptance scenarios](../upstream/snapshots/d0a4165f/features/11-compaction.md)

---

## INT-015 Compaction keeps identifiers and counts stable across incremental updates

**Requirement.** When the earlier part of an existing conversation is unchanged, re-running
compaction must incrementally update only the new tail, and must not needlessly rewrite the group
identifiers and token counts already computed.

**Upstream comparison**

- .NET: `CompactionMessageIndex.Update(...)` chooses between a suffix append and a full rebuild.
- Python: Incremental annotation preserves the prefix token counts and IDs.

**Decision.** Both upstreams agree. With only a full rebuild, the cost grows in long sessions and
traceability breaks. Java must also support the incremental path by default while falling back to a
full rebuild when the prefix does not match.

**Acceptance criteria**

- On a re-run where the prefix is unchanged, the existing group IDs do not change.
- When only a new message tail is appended, the existing token counts are kept as they are.
- When the prefix is truncated or differs, a full rebuild happens.

**Evidence** [11 Grouping / annotation / indexing](../upstream/snapshots/d0a4165f/features/11-compaction.md),
[11 Acceptance scenarios](../upstream/snapshots/d0a4165f/features/11-compaction.md)

---

## INT-016 Compaction internal state is expressed as an explicit index plus trace metadata

**Requirement.** The canonical internal state of Java compaction must be an explicit group index,
and summary tracking information must also be left in the message metadata so that the relationship
between the original and the summary can be traced.

**Upstream comparison**

- .NET: `CompactionMessageIndex` and `CompactionMessageGroup` are the canonical state, and only a summary marker is left on the message.
- Python: Message annotations carry group, token, and summary linkage broadly.

**Decision.** The two upstreams are reconciled. An index is better for computation and metrics,
while message trace metadata is better for auditing and recovery. Java takes the index as the
internal basis but also leaves trace metadata.

**Acceptance criteria**

- The compaction engine does not manage state with a flat message list alone but uses an explicit group index.
- A summary message records the identifiers of the original group or messages it summarized.
- From an original message it is also possible to look back at which summary replaced it.

**Evidence** [11 state and snapshot model](../upstream/snapshots/d0a4165f/features/11-compaction.md),
[11 Grouping / annotation / indexing](../upstream/snapshots/d0a4165f/features/11-compaction.md),
[11 Java decisions](../upstream/snapshots/d0a4165f/features/11-compaction.md)

---

## INT-017 The compaction start condition and stop condition are separated

**Requirement.** A compaction strategy must express the start predicate and the stop target
separately, and when no stop target is given it must use the inverse of the trigger as the default.

**Upstream comparison**

- .NET: `CompactionStrategy` separates `trigger` from an optional `target`.
- Python: Threshold semantics are built into each strategy constructor and there is no explicit trigger algebra.

**Decision.** The .NET model is adopted. Separating starting from stopping simplifies composition
and testing. Java is better off keeping trigger/target as a shared abstraction and layering
Python-style convenience constructors on top of it.

**Acceptance criteria**

- Creating a compaction strategy requires a trigger.
- When the target is omitted, the default target is set to the inverse of the trigger.
- Triggers can be composed from tokens, messages, turns, groups, and the presence of a tool call.

**Evidence** [11 Token estimation / metrics / triggers](../upstream/snapshots/d0a4165f/features/11-compaction.md),
[11 Java decisions](../upstream/snapshots/d0a4165f/features/11-compaction.md)

---

## INT-018 Group-based and turn-based sliding policies are separated

**Requirement.** Sliding compaction must provide the group-based policy and the turn-based policy as
separate strategies, and must not hide different semantics under a single name.

**Upstream comparison**

- .NET: `SlidingWindowCompactionStrategy` is based on recent turns.
- Python: `SlidingWindowStrategy` is based on recent non-system groups.

**Decision.** The most dangerous case is the same name behaving differently. Java does not pick one
of the two arbitrarily but separates them starting from the strategy name. That is what keeps tests
and users from misreading the meaning.

**Acceptance criteria**

- Group-based sliding and turn-based sliding are distinguished by different types or by an explicit mode.
- A test shows that the two strategies can produce different results from the same input.
- The documentation does not describe the default sliding semantics ambiguously.

**Evidence** [11 Sliding window](../upstream/snapshots/d0a4165f/features/11-compaction.md),
[11 documentation differences and differences between the SDKs](../upstream/snapshots/d0a4165f/features/11-compaction.md),
[11 Java decisions](../upstream/snapshots/d0a4165f/features/11-compaction.md)

---

## INT-019 Tool-result compaction replaces content with a summary and never re-exposes the original

**Requirement.** An old tool-result group can be replaced by a summary message, but the summary must
be synthetic content with a length limit and must not restore an already excluded original payload
verbatim.

**Upstream comparison**

- .NET: An old tool-call group is turned into a YAML-like summary block and a replacement summary group is inserted.
- Python: An old tool-call group is turned into a one-line bracket summary and a large payload is cut to a bounded string.

**Decision.** Both upstreams share the principle of "replace with a summary". Java puts safety
before format. Putting the original back in whole gives no compaction benefit and increases the
exposure of sensitive information.

**Acceptance criteria**

- After tool-result compaction, a synthetic summary message takes the place of the original group.
- A large tool result does not enter the summary in full but carries a length limit or a truncation marker.
- An already excluded original payload is not included again while the summary is generated.

**Evidence** [11 Tool-result compaction](../upstream/snapshots/d0a4165f/features/11-compaction.md),
[11 Acceptance scenarios](../upstream/snapshots/d0a4165f/features/11-compaction.md)

---

## INT-020 Summarization compaction restores the originals on failure

**Requirement.** When summary generation fails, summarization compaction must roll back every
original group exclusion, and must propagate cancellation rather than swallowing it.

**Upstream comparison**

- .NET: On a summarizer exception the excluded groups are restored and cancellation is propagated.
- Python: On a summarizer exception or an empty summary it returns `False` and keeps the originals.

**Decision.** The shared core of the two upstreams is "preserve the originals even on failure". As
instructed by the user, restoring the originals is fixed for both cancellation and failure. An
empty summary is not treated as a success either.

**Acceptance criteria**

- When the summarizer throws, the included/excluded state from before compaction is restored as it was.
- An empty or whitespace-only summary is not committed as a success.
- A cancellation exception is not turned into a summary fallback but propagated to the caller.

**Evidence** [11 Summarization](../upstream/snapshots/d0a4165f/features/11-compaction.md),
[11 concurrency/streaming/cancellation](../upstream/snapshots/d0a4165f/features/11-compaction.md),
[11 errors/validation/recovery behavior](../upstream/snapshots/d0a4165f/features/11-compaction.md),
[11 Acceptance scenarios](../upstream/snapshots/d0a4165f/features/11-compaction.md)

---

## INT-021 The compaction provider offers a pre-run projection and an optional post-store hook

**Requirement.** A compaction provider must offer at least a pre-run projection hook, and must be
able to keep an optional post-store compaction hook for paths where the store has to preserve the
original excluded messages.

**Upstream comparison**

- .NET: `CompactionProvider` is a pre-invocation provider and skips when the session is remote-managed.
- Python: `CompactionProvider` provides both `before_run` and `after_run`, and the after path leaves the excluded originals in storage.

**Decision.** The two models are combined. A pre-run projection is needed in common. A post-store
hook is not mandatory on every path, so it is kept optional. Still, when a path has to preserve the
originals, the core must support it.

**Acceptance criteria**

- A pre-run provider can pass only the compacted projection to the next stage.
- Turning on the post-store hook leaves the excluded originals in storage, and the next load policy can optionally hide them.
- With no session or no messages, the compaction provider passes through without side effects.

**Evidence** [11 Session / context integration](../upstream/snapshots/d0a4165f/features/11-compaction.md),
[11 state/persistence](../upstream/snapshots/d0a4165f/features/11-compaction.md),
[11 Java decisions](../upstream/snapshots/d0a4165f/features/11-compaction.md)

---

## INT-022 Logging and telemetry interceptors keep sensitive data exposure opt-in

**Requirement.** The built-in logging and telemetry interceptors must not emit sensitive messages,
tool arguments, or tool results by default, and must allow full payload exposure only on explicit
opt-in.

**Upstream comparison**

- .NET: The `LoggingAgent` trace logs and `OpenTelemetryAgent.EnableSensitiveData` warn directly about the risk of exposing sensitive data.
- Python: The middleware samples warn about data leakage and indirect injection risks in judge client transmission and gated stream rewriting.

**Decision.** The same safety principle is documented. Observability matters, but the default must
be safe. Exposure of sensitive information must not be switched on without an explicit opt-in.

**Acceptance criteria**

- With the default logging/telemetry interceptor configuration, raw message content and tool arguments are not written to an external sink.
- The option that records sensitive data defaults to `false`.
- Turning on the sensitive data option comes with both documentation and a runtime warning.

**Evidence** [10 security](../upstream/snapshots/d0a4165f/features/10-middleware.md),
[11 security](../upstream/snapshots/d0a4165f/features/11-compaction.md)

---

## What this document does not cover

| Topic | Owning document |
| --- | --- |
| Session identifiers, snapshots, stores | [06 Sessions and conversation state](06-sessions.md) |
| Tool approval payloads and the function call loop | [04 Tool definitions and the tool call loop](04-tools.md) |
| How the harness assembles the default interceptors and compaction provider | [08 Harness features](08-harness.md) |
| Retry and long-running policies of the workflow graph | [09 Workflows and orchestration](09-workflows.md) |
| Per-host authentication, user isolation, transport protocols | [10 Hosting and protocols](10-hosting.md) |
