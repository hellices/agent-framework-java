# 03 Structured output

**Prefix** `OUT` · **Upstream features** [04 structured-output](../upstream/snapshots/d0a4165f/features/04-structured-output.md)

Defines the core contracts for structured output requests, schema representation, native and
fallback paths, parsing and validation, and streaming constraints. The message model is owned by
[02 Messages and the content model](02-message-content.md), and general execution and option
merging are owned by [01 Agent execution and model calls](01-agent-execution.md).

## Adoption scope

The `Grade` column in this document is, as [README](README.md#requirement-grades) defines it, how binding a requirement is once the decision to build the feature has been made; whether the feature is adopted at all follows the [compatibility matrix](../upstream/snapshots/d0a4165f/compatibility-matrix.md).

- Structured output features (`SO01`, `SO02`) are all adoption `Required`.

## Summary

| ID | Requirement | Adoption | Grade | Phase |
| --- | --- | --- | --- | --- |
| OUT-001 | Structured output is requested explicitly | Required | Required | MVP |
| OUT-002 | Both a typed validation path and a JSON-only path are supported | Required | Required | MVP |
| OUT-003 | Non-object target types are requested via a wrapper schema | Required | Required | MVP |
| OUT-004 | The response format precedence is fixed | Required | Required | MVP |
| OUT-005 | Native support is treated as a best-effort contract | Required | Required | MVP |
| OUT-006 | Typed value parsing is performed at response access time | Required | Required | MVP |
| OUT-007 | The structured parsing target is the last assistant text body | Required | Required | MVP |
| OUT-008 | Validation errors are distinguished from JSON parsing errors | Required | Required | MVP |
| OUT-009 | An empty or null structured payload fails explicitly | Required | Required | MVP |
| OUT-010 | Valid JSON text is fallback-parsed even without native support | Required | Required | MVP |
| OUT-011 | When a wrapper was expected but bare JSON arrives, the original JSON is retried | Required | Recommended | Core+ |
| OUT-012 | Structured streaming values are read only from the final response | Required | Required | Core+ |

---

## OUT-001 Structured output is requested explicitly

**Requirement.** Structured output must be an explicit request separate from general execution;
if the caller provides no schema or response format, the core must operate as a free-text
execution.

**Upstream comparison**

- .NET: explicitly requests structured output via `RunAsync<T>()` and `ResponseFormat`.
- Python: `value` is meaningful only when `ChatOptions["response_format"]` is supplied.

**Decision.** Both upstreams agree. Making structured output a hidden default breaks the free-text
path unexpectedly. The structured contract must activate only when the caller requests it.

**Acceptance criteria**

- A response from an execution without a structured request does not auto-generate a typed value.
- When a structured request is supplied, schema or response format information is recorded in the execution options.
- The general execution API remains usable without a structured request.

**Evidence** [04 Public API and types](../upstream/snapshots/d0a4165f/features/04-structured-output.md),
[04 Purpose](../upstream/snapshots/d0a4165f/features/04-structured-output.md)

---

## OUT-002 Both a typed validation path and a JSON-only path are supported

**Requirement.** The core must support both a strongly typed validation path and a path that
guarantees only JSON.

**Upstream comparison**

- .NET: requests a typed schema via the generic type `T` and serializer options.
- Python: `response_format` accepts a Pydantic type or a JSON schema mapping.

**Decision.** The strengths of both upstreams are combined. Java must separate a typed route from
a JSON-only route. Forcing a strong binding on every caller blocks lightweight JSON collection use
cases.

**Acceptance criteria**

- The typed validation path returns a value as the Java type or equivalent schema type specified by the caller.
- The JSON-only path returns a tree/map value for any valid JSON.
- The JSON-only path is usable without additional type validation.

**Evidence** [04 Structured response type/schema](../upstream/snapshots/d0a4165f/features/04-structured-output.md),
[04 Extension points](../upstream/snapshots/d0a4165f/features/04-structured-output.md),
[04 Java decisions](../upstream/snapshots/d0a4165f/features/04-structured-output.md)

---

## OUT-003 Non-object target types are requested via a wrapper schema

**Requirement.** Non-object target types such as primitives, arrays, and enums must be wrapped
in a wrapper object schema at request time, and unwrapped back to the original type at parse
time.

**Upstream comparison**

- .NET: wraps non-object schemas in a wrapper object and tracks with `IsWrappedInObject`.
- Python: does not create a separate wrapper schema; parses the final text directly.

**Decision.** The .NET approach is adopted. In Java, it is better to make wire-level request
contracts explicit. Requesting non-objects directly causes ambiguous expected formats across
providers.

**Acceptance criteria**

- Object type requests use the original schema without a wrapper.
- Primitive, array, and enum type requests allow identification of whether wrapping occurred.
- A successful parse result is the original target type value, not the wrapper object.

**Evidence** [04 Structured response type/schema](../upstream/snapshots/d0a4165f/features/04-structured-output.md),
[04 .NET implementation and tests](../upstream/snapshots/d0a4165f/features/04-structured-output.md),
[04 Java decisions](../upstream/snapshots/d0a4165f/features/04-structured-output.md)

---

## OUT-004 The response format precedence is fixed

**Requirement.** When a response format is provided at multiple layers, the run-time override
takes highest precedence, followed by the per-run chat options, and finally the agent defaults.

**Upstream comparison**

- .NET: supports three layers — initialization options, invocation options, and run options — and the last value wins.
- Python: `response_format` is carried in `ChatOptions` and passed from both default and run options.

**Decision.** The .NET precedence rules are adopted. Structured output must have a fixed
precedence just like general option merging, so the caller can reason about which schema was
actually applied.

**Acceptance criteria**

- If only the agent default is present, that value is applied.
- Supplying a different response format in run options overrides the default.
- When a higher-level override conflicts with a lower-level default in the same execution, the higher-level override wins.

**Evidence** [04 Provider capability](../upstream/snapshots/d0a4165f/features/04-structured-output.md),
[04 .NET implementation and tests](../upstream/snapshots/d0a4165f/features/04-structured-output.md)

---

## OUT-005 Native support is treated as a best-effort contract

**Requirement.** The core can only request structured output; it must not force the provider to
honor it natively, and must not perform hidden retries or additional model calls to compensate
for lack of support.

**Upstream comparison**

- .NET: documents that `ResponseFormat` may be ignored by implementations.
- Python: has no native structured capability protocol; the outcome depends on whether the provider honors `response_format`.

**Decision.** Both upstreams agree. Structured support is a provider capability. If the core
secretly makes a second call, cost, latency, and auditability all break. AgentEngine must not
assume host responsibilities either.

**Acceptance criteria**

- A structured request is forwarded to exactly one model call.
- Even if the provider ignores the response format, the core does not auto-reprompt.
- Whether native support is absent is revealed only by parse success or failure.

**Evidence** [04 Provider capability](../upstream/snapshots/d0a4165f/features/04-structured-output.md),
[04 Native vs fallback behavior](../upstream/snapshots/d0a4165f/features/04-structured-output.md)

---

## OUT-006 Typed value parsing is performed at response access time

**Requirement.** Typed value parsing for structured output must be performed not at execution
completion time, but at the point when the response's typed accessor is read.

**Upstream comparison**

- .NET: deserializes the text when `AgentResponse<T>.Result` is read.
- Python: `ChatResponse.value` and `AgentResponse.value` do not parse until the property is accessed.

**Decision.** Both upstreams agree. Separating execution from parsing lets interceptors and
post-processors see the raw response first, and the caller pays the parse cost only when needed.

**Acceptance criteria**

- Even a malformed structured payload allows the execution itself to return a response object.
- Parse errors occur at the point the typed accessor is read.
- Post-execution hooks can observe the same response object before the typed accessor is called.

**Evidence** [04 Parsing / validation](../upstream/snapshots/d0a4165f/features/04-structured-output.md),
[04 Errors and .NET/Python drift](../upstream/snapshots/d0a4165f/features/04-structured-output.md)

---

## OUT-007 The structured parsing target is the last assistant text body

**Requirement.** The structured parser must target not the entire display `response.text` but
only the direct concatenation of text content bodies from the last non-empty assistant message.

**Upstream comparison**

- .NET: `AgentResponse<T>.Result` parses against the entire response `Text`.
- Python: parses only the direct concat of text contents from the last non-empty assistant message.

**Decision.** The Python approach is adopted. The display projection may insert newlines or
whitespace that corrupt JSON. Not mixing tool messages or earlier assistant messages is safer.

**Acceptance criteria**

- A trailing tool message is excluded from the structured parsing target.
- When multiple assistant messages exist, only the last non-empty assistant message is parsed.
- Even when a single assistant message is split across multiple text chunks, parsing uses the direct concat result.

**Evidence** [04 Native vs fallback behavior](../upstream/snapshots/d0a4165f/features/04-structured-output.md),
[04 Errors and .NET/Python drift](../upstream/snapshots/d0a4165f/features/04-structured-output.md),
[04 Python implementation and tests](../upstream/snapshots/d0a4165f/features/04-structured-output.md)

---

## OUT-008 Validation errors are distinguished from JSON parsing errors

**Requirement.** The typed validation path must report binding and validation errors separately,
and the JSON-only path must report a parsing error only when the JSON is invalid.

**Upstream comparison**

- .NET: raises explicit exceptions for failures such as empty text and null deserialize.
- Python: distinguishes mapping parse failures as `ValueError` and schema mismatches as `ValidationError`.

**Decision.** The Python error classification is combined with the .NET explicit-failure approach.
The caller must be able to distinguish "the JSON itself is broken" from "the JSON is valid but the
type does not match" in order to formulate a recovery strategy.

**Acceptance criteria**

- Invalid JSON is reported as a JSON parsing error.
- In the typed validation path, missing required fields or type mismatches are reported as validation errors.
- The JSON-only path does not raise a schema mismatch error for valid JSON.

**Evidence** [04 Structured response type/schema](../upstream/snapshots/d0a4165f/features/04-structured-output.md),
[04 Parsing / validation](../upstream/snapshots/d0a4165f/features/04-structured-output.md),
[04 Python implementation and tests](../upstream/snapshots/d0a4165f/features/04-structured-output.md)

---

## OUT-009 An empty or null structured payload fails explicitly

**Requirement.** When structured output is requested and the parsing target text is empty or the
JSON result is `null`, the typed accessor must not return a value but must fail explicitly.

**Upstream comparison**

- .NET: treats both empty text and `null` deserialize as exceptions.
- Python: returns `None` for empty text.

**Decision.** The .NET approach is adopted. A structured output request is a contract that "a
value must exist." Treating an empty value or `null` as success results in a silent failure.

**Acceptance criteria**

- If the parsing target text is empty, the typed accessor fails.
- If the JSON payload is `null`, the typed accessor fails.
- The failure is not expressed as a silent success such as `Optional.empty()`.

**Evidence** [04 Parsing / validation](../upstream/snapshots/d0a4165f/features/04-structured-output.md),
[04 Errors and .NET/Python drift](../upstream/snapshots/d0a4165f/features/04-structured-output.md),
[04 .NET implementation and tests](../upstream/snapshots/d0a4165f/features/04-structured-output.md)

---

## OUT-010 Valid JSON text is fallback-parsed even without native support

**Requirement.** Even when the provider does not strongly support native structured mode, if the
final assistant text is valid JSON, the core must produce a typed value or JSON value without an
additional model call.

**Upstream comparison**

- .NET: the default path is to request a schema and then deserialize the final text.
- Python: produces a `value` from valid JSON text even when the provider does not implement native structured mode.

**Decision.** The core behavior is the same across upstreams. The last line of defense for
structured output is the parseability of the final text, not the provider's capability flag.

**Acceptance criteria**

- A fake client that returns only plain JSON text can still produce a typed or JSON value.
- No additional model call occurs on this path either.
- If the JSON is invalid, the system fails explicitly rather than falling back to success.

**Evidence** [04 Provider capability](../upstream/snapshots/d0a4165f/features/04-structured-output.md),
[04 Native vs fallback behavior](../upstream/snapshots/d0a4165f/features/04-structured-output.md),
[04 Concrete acceptance scenarios](../upstream/snapshots/d0a4165f/features/04-structured-output.md)

---

## OUT-011 When a wrapper was expected but bare JSON arrives, the original JSON is retried

**Requirement.** Even when a wrapper schema was requested due to a non-object target type, if
the provider returns bare primitive, bare array, or bare enum JSON, the core must attempt one
more parse with the original JSON.

**Upstream comparison**

- .NET: parses via an original JSON fallback even when a wrapper was expected but a bare primitive arrives.
- Python: a corresponding wrapper-retry path is not confirmed in this snapshot.

**Decision.** The .NET compatibility fallback is adopted. It absorbs provider drift, but only
permits leniency up to the point where the JSON itself is invalid.

**Acceptance criteria**

- When a wrapper is expected for an `int` response and `42` arrives, it is parsed as `42`.
- When a wrapper is expected for an array response and a bare JSON array arrives, it is parsed as an array value.
- This fallback is not applied to invalid JSON.

**Evidence** [04 Native vs fallback behavior](../upstream/snapshots/d0a4165f/features/04-structured-output.md),
[04 Concrete acceptance scenarios](../upstream/snapshots/d0a4165f/features/04-structured-output.md),
[04 .NET implementation and tests](../upstream/snapshots/d0a4165f/features/04-structured-output.md)

---

## OUT-012 Structured streaming values are read only from the final response

**Requirement.** Structured streaming does not provide a separate typed update API; the
structured value must be read only from the final response after stream termination.

**Upstream comparison**

- .NET: there is no typed streaming public API such as `RunStreamingAsync<T>()`.
- Python: streaming structured output is also read only from `value` after `get_final_response()`.

**Decision.** The conservative common denominator of both upstreams is taken. Producing a
structured value on every update makes it difficult to distinguish partial JSON fragments from
complete JSON.

**Acceptance criteria**

- The streaming update type does not have a final structured value accessor.
- The final response obtained after stream termination allows reading the structured value.
- Structured value is not reported as success when only partial updates have been received.

**Evidence** [04 Concurrency, streaming, and cancellation](../upstream/snapshots/d0a4165f/features/04-structured-output.md),
[04 Concrete acceptance scenarios](../upstream/snapshots/d0a4165f/features/04-structured-output.md),
[04 Python implementation and tests](../upstream/snapshots/d0a4165f/features/04-structured-output.md)

---

## What this document does not cover

| Topic | Owning document |
| --- | --- |
| Message roles and content taxonomy | [02 Messages and the content model](02-message-content.md) |
| General execution entry point and cancellation model | [01 Agent execution and model calls](01-agent-execution.md) |
| Tool call loop and tool approval | [04 Tool definitions and the tool call loop](04-tools.md) |
| Session persistence and response cache durability | [06 Sessions and conversation state](06-sessions.md) |
| Per-provider wire format encoding | [12 Provider integrations](12-providers.md) |
