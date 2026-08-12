# 04 Tool definitions and the tool call loop

**Prefix** `TOOL` · **Upstream features** [05 function-tools](../upstream/snapshots/d0a4165f/features/05-function-tools.md),
[06 tool-approval](../upstream/snapshots/d0a4165f/features/06-tool-approval.md)

Defines the contracts for defining, selecting, executing, approving, and normalizing the results
of tools. The agent execution entry point is owned by
[01 Agent execution and model calls](01-agent-execution.md), MCP remote tools and server hosting
by [05 MCP integration](05-mcp.md), and session persistence by
[06 Sessions and conversation state](06-sessions.md).

## Adoption scope

The `Grade` column in this document is, as [README](README.md#requirement-grades) defines it, how binding a requirement is once the decision to build the feature has been made; whether the feature is adopted at all follows the [compatibility matrix](../upstream/snapshots/d0a4165f/compatibility-matrix.md).

- Function tools (`TOOL01`–`TOOL03`) and approval core (`APP01`, `APP02`) all have adoption `Required`.

## Summary

| ID | Requirement | Adoption | Grade | Phase |
| --- | --- | --- | --- | --- |
| TOOL-001 | Tools are represented as core-defined types | Required | Required | MVP |
| TOOL-002 | The tool call loop is solely owned by the Java core | Required | Required | MVP |
| TOOL-003 | Input schema is inferred but an explicit schema wins | Required | Required | MVP |
| TOOL-004 | Context arguments are injected outside the schema | Required | Required | MVP |
| TOOL-005 | Arguments are validated on every invocation path | Required | Required | MVP |
| TOOL-006 | Declaration-only tools and additional tools are not executed locally | Required | Required | Core+ |
| TOOL-007 | Tool selection is fixed via `ToolMode` | Required | Required | MVP |
| TOOL-008 | Run-level options override default options | Required | Required | MVP |
| TOOL-009 | Invocation configuration has a normalized type and safe defaults | Required | Required | MVP |
| TOOL-010 | The semantics of iteration limit and call budget are fixed | Required | Required | MVP |
| TOOL-011 | Call budget blocks best-effort after a batch completes | Required | Required | MVP |
| TOOL-012 | Parallel execution covers only executable batches and preserves order | Required | Required | MVP |
| TOOL-013 | Tool results are normalized to a `Content` list | Required | Required | MVP |
| TOOL-014 | Tool definitions and runtime counters are separated | Required | Recommended | Core+ |
| TOOL-015 | Streaming uses the same tool loop | Required | Required | MVP |
| TOOL-016 | Approval requests and responses are core content | Required | Required | Core+ |
| TOOL-017 | An approval response binds only to its original request | Required | Required | Core+ |
| TOOL-018 | Approval denial leaves a synthetic termination result | Required | Required | Core+ |
| TOOL-019 | Standing approval matches on exact arguments and host boundary | Required | Required | Core+ |
| TOOL-020 | The approval queue advances one at a time on a session | Required | Required | Core+ |
| TOOL-021 | Auto-approval chains are cut off by an upper bound | Required | Recommended | Core+ |

---

## TOOL-001 Tools are represented as core-defined types

**Requirement.** The Java core defines tools as a core-owned `FunctionTool` type, and the public
API must not adopt an external provider SDK tool type as its primary contract.

**Upstream comparison**

- .NET: core accepts external `AITool`/`AIFunction` and wires them into the execution stack.
- Python: core directly provides `FunctionTool` and `@tool`.

**Decision.** The Python approach is chosen. The core must co-own schema, validation, and result
normalization so that behavior is decoupled from any provider. This lets the Java implementation fix
tool semantics in a single location.

**Acceptance criteria**

- A `FunctionTool` or equivalent core-defined type exists in the public API.
- The core tool loop can read and execute tool definitions without external provider SDK types.

**Evidence** [05 Public API and types](../upstream/snapshots/d0a4165f/features/05-function-tools.md),
[05 Java decision](../upstream/snapshots/d0a4165f/features/05-function-tools.md)

---

## TOOL-002 The tool call loop is solely owned by the Java core

**Requirement.** The execution loop that repeatedly invokes tool calls and reinjects them into
the model must be owned solely by the Java core, and must not run redundantly with automatic
tool execution from an external framework or provider SDK.

**Upstream comparison**

- .NET: the underlying `FunctionInvokingChatClient` largely owns the tool execution policy and the agent core retains only a seam.
- Python: `FunctionInvocationLayer` directly performs the tool loop inside the core.

**Decision.** The Python approach is chosen. The Java core must own the loop directly so that
budget, approval, streaming, and observability are bound by a single set of rules. The .NET-style
dual ownership easily conflicts with external automatic execution in Java.

**Acceptance criteria**

- A tool call included in the same model response is not executed twice by both the core loop and the underlying SDK loop.
- When the core tool loop is enabled, the underlying SDK's automatic tool execution is deactivated or bypassed.
- Budget and approval decisions are made exclusively inside the core loop.

**Evidence** [05 Public API and types](../upstream/snapshots/d0a4165f/features/05-function-tools.md),
[05 Invocation configuration / layers / budgets](../upstream/snapshots/d0a4165f/features/05-function-tools.md)

---

## TOOL-003 Input schema is inferred but an explicit schema wins

**Requirement.** The tool input schema is inferred from the function signature by default, but
when an explicit schema is provided it must completely overwrite the inferred result.

**Upstream comparison**

- .NET: no first-class schema inference helper exists; externally prepared tool definitions are accepted.
- Python: supports both signature inference and explicit Pydantic/JSON Schema overrides.

**Decision.** The Python approach is chosen. Java can infer from annotations and type information.
However, a human-authored explicit schema is more trustworthy, so its precedence is fixed.

**Acceptance criteria**

- A simple function definition alone produces an object-type input schema.
- When an explicit schema is provided, descriptions, required fields, and constraints come from the explicit values rather than inferred values.
- Explicit and inferred schemas are not partially merged.

**Evidence** [05 Function tool definition / decorator / schema generation](../upstream/snapshots/d0a4165f/features/05-function-tools.md)

---

## TOOL-004 Context arguments are injected outside the schema

**Requirement.** Execution context, runtime metadata, and framework-internal arguments must be
excluded from the input schema and injected only at runtime.

**Upstream comparison**

- .NET: the wrapper separately passes ambient `FunctionInvocationContext` and `AIFunctionArguments`.
- Python: context parameters are excluded from the schema and injected at invoke time.

**Decision.** The intent is the same in both upstreams. Values that the model must not fill must
not appear in the schema. This keeps the boundary between user-supplied arguments and
framework-injected arguments clear.

**Acceptance criteria**

- Context arguments do not appear in the generated JSON schema `properties`.
- The tool body can receive a context object injected at runtime.
- Even if a user sends a value under the context argument's name, it is not adopted as an execution argument.

**Evidence** [05 Function tool definition / decorator / schema generation](../upstream/snapshots/d0a4165f/features/05-function-tools.md),
[05 Argument validation / parsing](../upstream/snapshots/d0a4165f/features/05-function-tools.md)

---

## TOOL-005 Arguments are validated on every invocation path

**Requirement.** Tool invocations must perform the same schema validation regardless of the
argument delivery path, and undeclared runtime keys must be explicitly rejected.

**Upstream comparison**

- .NET: repo-local core has no Python-style argument schema validator.
- Python: validates arguments on both the Pydantic path and the explicit JSON Schema path, rejecting unexpected runtime kwargs.

**Decision.** The Python approach is chosen. If there are multiple validation paths, different
calling patterns produce different bugs. Java must always maintain input validation even in raw
result mode.

**Acceptance criteria**

- Execution fails before invocation if a required argument is missing.
- Providing a key not present in a schema with `additionalProperties: false` fails.
- Input validation is still performed even when result parsing bypass is enabled.

**Evidence** [05 Argument validation / parsing](../upstream/snapshots/d0a4165f/features/05-function-tools.md),
[05 Concrete acceptance scenarios](../upstream/snapshots/d0a4165f/features/05-function-tools.md)

---

## TOOL-006 Declaration-only tools and additional tools are not executed locally

**Requirement.** Declaration-only tools without a local implementation and declaration-only tools
added at run time must only be exposed to the model and must not be executed directly by the
Java core.

**Upstream comparison**

- .NET: provides constructor tools and middleware seams but declaration-only local execution semantics are not surfaced.
- Python: treats `func=None` tools and `additional_tools` as declaration-only and does not execute them locally.

**Decision.** The Python approach is chosen. Mixing remote and hosted tools requires separating
"exposure" from "local execution." This preserves boundaries even when MCP and approval enter the
same loop.

**Acceptance criteria**

- Tools without a local body still appear in the model's selection candidate list.
- Even if the model invokes such a tool, the Java core does not fabricate a fake success `function_result`.
- `additionalTools` is an exposure-only list applied at run time and does not require registering
  a local function body.

**Evidence** [05 Public API and types](../upstream/snapshots/d0a4165f/features/05-function-tools.md),
[05 Concrete acceptance scenarios](../upstream/snapshots/d0a4165f/features/05-function-tools.md)

---

## TOOL-007 Tool selection is fixed via `ToolMode`

**Requirement.** Tool selection policy must be expressed as a provider-neutral `ToolMode` type
carrying `auto`/`required`/`none` along with an optional allow-list and forced function name.

**Upstream comparison**

- .NET: delegates selection semantics to the underlying `ChatOptions.ToolMode`.
- Python: `ToolMode` and `validate_tool_mode()` let the core validate combination rules.

**Decision.** The Python approach is chosen. Java can surface combination errors early via static
types. A provider-neutral object is necessary to avoid inscribing a specific SDK's semantics into
the public API.

**Acceptance criteria**

- A public type distinguishes `auto`/`required`/`none`.
- `requiredFunctionName` is only allowed when the mode is `required`.
- An invalid `allowedTools` combination fails before the model call.

**Evidence** [05 Tool modes / selection](../upstream/snapshots/d0a4165f/features/05-function-tools.md),
[05 Java decision](../upstream/snapshots/d0a4165f/features/05-function-tools.md)

---

## TOOL-008 Run-level options override default options

**Requirement.** When agent-default tool options conflict with run-level tool options, the
run-level value must win, and tool lists must be merged without duplicates.

**Upstream comparison**

- .NET: merges and passes agent-level defaults and request-level values.
- Python: `merge_chat_options()` fixes run-level `tool_choice` precedence and tools dedupe via tests.

**Decision.** Both upstreams agree. If per-request policy cannot override the default, the host
cannot block a dangerous execution. Tool list dedupe also reduces confusion from duplicate
exposure and duplicate execution.

**Acceptance criteria**

- When the same `toolChoice` is specified on both sides, the run-level value is applied.
- Keys not specified at run level retain their default values.
- Even if the same tool name appears on both sides, it appears only once in the final list.

**Evidence** [05 Tool modes / selection](../upstream/snapshots/d0a4165f/features/05-function-tools.md),
[05 Concrete acceptance scenarios](../upstream/snapshots/d0a4165f/features/05-function-tools.md)

---

## TOOL-009 Invocation configuration has a normalized type and safe defaults

**Requirement.** Tool invocation configuration must be a normalized type carrying `enabled`,
iteration limit, call budget, consecutive error limit, unknown-call policy, detailed error
exposure, and additional tools, and must be populated with safe defaults when omitted.

**Upstream comparison**

- .NET: no Python-style unified configuration object exists; responsibility is distributed across underlying client policies.
- Python: the `FunctionInvocationConfiguration` normalization function fixes defaults and numeric constraints.

**Decision.** The Python approach is chosen. When configuration is scattered, it is impossible to
tell which safeguards a given execution has enabled. Java must gather them into a single type and
fix documentation and tests together.

**Acceptance criteria**

- When configuration is omitted, `enabled=true` and `includeDetailedErrors=false` are populated.
- `maxIterations` and `maxConsecutiveErrorsPerRequest` reject values less than 1.
- `maxFunctionCalls` accepts only `null` or a positive number.

**Evidence** [05 Public API and types](../upstream/snapshots/d0a4165f/features/05-function-tools.md),
[05 Invocation configuration / layers / budgets](../upstream/snapshots/d0a4165f/features/05-function-tools.md)

---

## TOOL-010 The semantics of iteration limit and call budget are fixed

**Requirement.** `maxIterations` is a limit on the number of model round-trips and
`maxFunctionCalls` is a limit on the number of executed function calls; the semantics of the
two values must not substitute for each other.

**Upstream comparison**

- .NET: repo-local core does not directly surface the same budget type and semantics.
- Python: separates `max_iterations` and `max_function_calls` and fixes their semantics via tests.

**Decision.** The Python approach is chosen. Mixing iteration count and call count breaks cost
prediction. Java must pin not only the API names but also the semantics via tests.

**Acceptance criteria**

- `maxIterations` is decremented before receiving one more model response.
- `maxFunctionCalls` accumulates the count of actually executed tool calls.
- In the same execution, the two limits operate independently.

**Evidence** [05 Invocation configuration / layers / budgets](../upstream/snapshots/d0a4165f/features/05-function-tools.md),
[05 Concrete acceptance scenarios](../upstream/snapshots/d0a4165f/features/05-function-tools.md)

---

## TOOL-011 Call budget blocks best-effort after a batch completes

**Requirement.** `maxFunctionCalls` must not cut calls mid-parallel-batch; instead it must be
fixed as a best-effort block preventing further tool execution after the batch completes.

**Upstream comparison**

- .NET: repo-local core does not directly define parallel budget blocking semantics.
- Python: implements and tests `max_function_calls` as a best-effort limit checked after a parallel batch.

**Decision.** The Python approach is chosen. Cutting only some calls mid-batch makes order and
results unstable. Stating the budget semantics clearly in the API and acceptance criteria is
operationally more honest.

**Acceptance criteria**

- If the budget allows it when a batch starts, all executable calls in that batch run to completion.
- If the budget is exceeded after a batch, `toolChoice=none` or an equivalent block is applied starting from the next iteration.
- If the limit is 5 and two consecutive batches each contain 3 parallel calls, a total of 6 executions may occur before blocking.

**Evidence** [05 Parallel calls and execution result semantics](../upstream/snapshots/d0a4165f/features/05-function-tools.md),
[05 Java decision](../upstream/snapshots/d0a4165f/features/05-function-tools.md)

---

## TOOL-012 Parallel execution covers only executable batches and preserves order

**Requirement.** Parallel execution must apply only to executable batches free of
approval-pending or unspecified tools, and result group order must preserve input call order
regardless of completion order.

**Upstream comparison**

- .NET: repo-local core does not directly define parallel execution and result flattening semantics.
- Python: only executable batches are processed in parallel via `asyncio.gather()`, preserving input order.

**Decision.** The Python approach is chosen. Losing order changes the conversation history fed back
to the model. Mixing approval-pending calls and executable calls in the same parallel group also
makes the control flow ambiguous.

**Acceptance criteria**

- A group containing approval-needed calls, declaration-only calls, or unknown calls is not executed in parallel as-is.
- The array order of the parallel execution result group matches the input call order.
- Even if individual calls complete in a different order, the result merge order does not change.

**Evidence** [05 Parallel calls and execution result semantics](../upstream/snapshots/d0a4165f/features/05-function-tools.md)

---

## TOOL-013 Tool results are normalized to a `Content` list

**Requirement.** Tool return values must be normalized to a `Content` list by default, and raw
result bypass must be allowed only as an explicit opt-in.

**Upstream comparison**

- .NET: repo-local core focuses on the underlying function call seam rather than result normalization rules.
- Python: normalizes `None`, strings, a single `Content`, and arbitrary objects to `list[Content]`, with `SKIP_PARSING` as a separate opt-in.

**Decision.** The Python approach is chosen. The model and session layers must operate
content-first. Allowing raw results only as an exception path for debug or special adapters is
safer.

**Acceptance criteria**

- A `null` return is normalized to a single empty-string text result.
- A string return becomes a single text content.
- An arbitrary object return is normalized to JSON text or an equivalent structured text.
- Unless raw result bypass is enabled, a raw JVM object does not propagate up to the outer loop as-is.

**Evidence** [05 Parallel calls and execution result semantics](../upstream/snapshots/d0a4165f/features/05-function-tools.md)

---

## TOOL-014 Tool definitions and runtime counters are separated

**Requirement.** Tool definitions must be immutable, and runtime counters such as invocation
count and exception count must be kept in execution state separate from the definition object.

**Upstream comparison**

- .NET: repo-local core does not create persistent counters on the function tool definition itself.
- Python: invocation count and exception count accumulate on the `FunctionTool` instance.

**Decision.** Unlike both upstreams, the Java convention is chosen. Attaching mutable counters to a
singleton tool definition leaks state across sessions. Separating definition from state simplifies
reuse and testing.

**Acceptance criteria**

- Reusing the same tool definition object across multiple executions does not change the definition object's own public state.
- `maxInvocations`/`maxInvocationExceptions` decisions are made via execution state or a scoped copy.
- A counter from one execution does not automatically carry over to another execution.

**Evidence** [05 State and persistence](../upstream/snapshots/d0a4165f/features/05-function-tools.md),
[05 Java decision](../upstream/snapshots/d0a4165f/features/05-function-tools.md)

---

## TOOL-015 Streaming uses the same tool loop

**Requirement.** The streaming path and the non-streaming path must share the same tool
iteration, budget, and approval rules, and consuming a stream to completion must be able to
restore the same final result.

**Upstream comparison**

- .NET: the lower-level seam does not directly define streaming rules.
- Python: streaming and non-streaming function invocation loops share the same budget logic.

**Decision.** The Python approach is chosen. If the two paths have different semantics, calling the
same agent produces different results. The tool loop must be fixed by execution semantics, not I/O
shape.

**Acceptance criteria**

- `maxIterations`/`maxFunctionCalls`/approval rules apply identically in streaming and non-streaming.
- The final response restored by consuming the stream to completion has the same tool results as the non-streaming final response.
- The streaming path does not have a separate budget interpretation.

**Evidence** [05 Concurrency, streaming, and cancellation](../upstream/snapshots/d0a4165f/features/05-function-tools.md),
[05 Invocation configuration / layers / budgets](../upstream/snapshots/d0a4165f/features/05-function-tools.md)

---

## TOOL-016 Approval requests and responses are core content

**Requirement.** Approval requests and approval responses must be content types owned by the
Java core, and approval requests must always surface as additional user-input requests.

**Upstream comparison**

- .NET: the approval type body is closer to an external type; repo-local code focuses on wrappers and harnesses.
- Python: core directly defines approval request/response content and exposes them as user-input-requests.

**Decision.** The Python approach is chosen. The core must own approval types for a provider-neutral
API. This lets the caller, session, and logs handle approval state with the same types.

**Acceptance criteria**

- Approval request and approval response types exist in the core content model.
- An approval request carries a request id and a tool call.
- The final response and streaming updates expose approval requests as a user-input-request set.

**Evidence** [06 Public API and types](../upstream/snapshots/d0a4165f/features/06-tool-approval.md),
[06 Java decision](../upstream/snapshots/d0a4165f/features/06-tool-approval.md)

---

## TOOL-017 An approval response binds only to its original request

**Requirement.** An approval response must bind only to the request most recently surfaced by the
Java core, and even a response that shares the same request id but has a different tool name or
arguments must be rebound to the original request's tool call.

**Upstream comparison**

- .NET: drops forged responses and rebinds substituted responses to the surfaced request's tool call.
- Python: core and middleware resolve the approval response id against the stored request.

**Decision.** The safeguard common to both upstreams is adopted. If the approval response executes
the payload the caller sent as-is, the approval screen and the actual execution diverge. That
is a security vulnerability.

**Acceptance criteria**

- An approval response with an unknown request id is not forwarded to tool execution.
- An approval response sharing only the request id but with a changed tool name or arguments still uses the original surfaced tool call at execution time.
- The above conditions hold equally on the streaming resume path.

**Evidence** [06 Middleware / state / rules detailed execution flow](../upstream/snapshots/d0a4165f/features/06-tool-approval.md),
[06 Concrete acceptance scenarios](../upstream/snapshots/d0a4165f/features/06-tool-approval.md)

---

## TOOL-018 Approval denial leaves a synthetic termination result

**Requirement.** Approval denial must not silently erase the tool call; instead it must leave a
synthetic `function_result` error visible to the caller, model, and logs.

**Upstream comparison**

- .NET: the queued denial flow is visible, but final user-visible error materialization is not fully fixed in the repo-local layer.
- Python: converts a denied approval into an `Error: Tool call invocation was rejected by user.` synthetic result.

**Decision.** The Python approach is chosen. Deleting the denial erases why execution stopped. The
safer default is to leave an explicit failure artifact.

**Acceptance criteria**

- When an `approved=false` response is given, a synthetic `function_result` error is added to the final conversation history.
- The denied tool's actual body is not invoked.
- The denial error string is fixed as stable text.

**Evidence** [06 Python core approval resume](../upstream/snapshots/d0a4165f/features/06-tool-approval.md),
[06 Denial / error / resume flow](../upstream/snapshots/d0a4165f/features/06-tool-approval.md),
[06 Java decision](../upstream/snapshots/d0a4165f/features/06-tool-approval.md)

---

## TOOL-019 Standing approval matches on exact arguments and host boundary

**Requirement.** Standing approval rules must match on tool name, normalized exact arguments,
and an optional host boundary value, and must not treat `null` arguments and empty-object
arguments as equivalent.

**Upstream comparison**

- .NET: uses only `ToolName` and exact serialized `Arguments` as the rule key.
- Python: matches rules including `tool_name`, exact `arguments`, and `server_label`.

**Decision.** The Python approach is chosen, and the host boundary remains an optional field. Tools
with the same name may reside on different MCP servers. Widening empty arguments into a
wildcard creates approval leakage.

**Acceptance criteria**

- The rule key includes the tool name and exact-arguments.
- `arguments=null` is a tool-wide rule and `arguments={}` is a no-arg exact rule.
- Even for the same tool name, auto-approval does not apply when the host boundary differs.

**Evidence** [06 Tool approval rule / standing approval model](../upstream/snapshots/d0a4165f/features/06-tool-approval.md),
[06 Argument-aware approvals](../upstream/snapshots/d0a4165f/features/06-tool-approval.md),
[06 Java decision](../upstream/snapshots/d0a4165f/features/06-tool-approval.md)

---

## TOOL-020 The approval queue advances one at a time on a session

**Requirement.** The approval middleware providing standing approval and queue functionality must
require a session, and when multiple unresolved approval requests exist it must surface only one
at a time to the caller.

**Upstream comparison**

- .NET: the higher-level `ToolApprovalAgent` stores rules, queue, and responses in session state and performs one-at-a-time surfacing.
- Python: `ToolApprovalMiddleware` fails without a session and resurfaces queued requests one at a time.

**Decision.** Both upstreams agree. The queue and standing rules require cross-run state.
Allowing operation without a session loses intermediate approval state.

**Acceptance criteria**

- Running the approval middleware without a session fails immediately.
- When multiple unresolved approvals exist, only the first one is returned to the caller.
- Actual tool execution does not resume until the last approval is resolved.

**Evidence** [06 Python opt-in `ToolApprovalMiddleware`](../upstream/snapshots/d0a4165f/features/06-tool-approval.md),
[06 Session integration](../upstream/snapshots/d0a4165f/features/06-tool-approval.md),
[06 Denial / error / resume flow](../upstream/snapshots/d0a4165f/features/06-tool-approval.md)

---

## TOOL-021 Auto-approval chains are cut off by an upper bound

**Requirement.** Auto-approval rules must be evaluated after standing approval rules, and
internal re-invocation chains sustained solely by auto-approval must be cut off by a separate
upper bound.

**Upstream comparison**

- .NET: evaluates standing rules before heuristic auto-approval and places a `MaxAutoApprovalIterations` cap.
- Python: provides standing rules and a heuristic callback but does not surface a separate .NET-style runaway cap.

**Decision.** The .NET cap is adopted together with the precedence idea from both upstreams.
Heuristic rules are convenient but can produce infinite internal re-invocations. An upper bound to
prevent cost runaway is necessary.

**Acceptance criteria**

- When a standing approval rule matches, the heuristic auto-approval rule is not evaluated.
- When only auto-approvals repeat, the next approval request is surfaced to the caller after the configured upper bound is reached.
- The streaming path uses the same upper bound.

**Evidence** [06 Extension points](../upstream/snapshots/d0a4165f/features/06-tool-approval.md),
[06 Concurrency, streaming, and cancellation](../upstream/snapshots/d0a4165f/features/06-tool-approval.md),
[06 Concrete acceptance scenarios](../upstream/snapshots/d0a4165f/features/06-tool-approval.md)

---

## What this document does not cover

| Topic | Owning document |
| --- | --- |
| Agent public entry point and model port | [01 Agent execution and model calls](01-agent-execution.md) |
| Messages, content, and response types | [02 Messages and the content model](02-message-content.md) |
| Structured output schema and parsing | [03 Structured output](03-structured-output.md) |
| MCP remote tool transport and server hosting | [05 MCP integration](05-mcp.md) |
| Session store and state serialization | [06 Sessions and conversation state](06-sessions.md) |
| Interceptor chain and context propagation | [07 Interceptors and context management](07-interceptors.md) |
