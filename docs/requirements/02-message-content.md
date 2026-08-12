# 02 Messages and the content model

**Prefix** `MSG` · **Upstream features** [02 message-content](../upstream/snapshots/d0a4165f/features/02-message-content.md)

Defines the core data contract for roles, messages, multimodal content, responses, streaming
updates, and usage metadata. The execution entry point is owned by
[01 Agent execution and model calls](01-agent-execution.md), structured parsing by
[03 Structured output](03-structured-output.md), and tool execution semantics by
[04 Tool definitions and the tool call loop](04-tools.md).

## Adoption scope

The `Grade` column in this document is, as [README](README.md#requirement-grades) defines it, how binding a requirement is once the decision to build the feature has been made; whether the feature is adopted at all follows the [compatibility matrix](../upstream/snapshots/d0a4165f/compatibility-matrix.md).

- All message and content features (`MSG01`–`MSG04`) have adoption `Required`.

## Summary

| ID | Requirement | Adoption | Grade | Phase |
| --- | --- | --- | --- | --- |
| MSG-001 | The core directly owns conversation types | Required | Required | MVP |
| MSG-002 | Roles allow both known values and custom values | Required | Required | MVP |
| MSG-003 | Input is normalized to a message list | Required | Required | MVP |
| MSG-004 | Text projection sees only text content | Required | Required | MVP |
| MSG-005 | The core provides basic multimodal content kinds | Required | Required | Core+ |
| MSG-006 | URI and binary content are validated at creation time | Required | Required | Core+ |
| MSG-007 | Additional properties and the raw representation are preserved | Required | Required | MVP |
| MSG-008 | Message provenance is tagged separately from content | Required | Required | Core+ |
| MSG-009 | Leading instructions are not inserted redundantly | Required | Recommended | Core+ |
| MSG-010 | Usage carries both standard fields and extension fields | Required | Required | MVP |
| MSG-011 | Responses and updates share the same metadata axes | Required | Required | MVP |
| MSG-012 | The final response is reconstructed from an update sequence | Required | Required | MVP |
| MSG-013 | The stream wrapper provides a final response and transformation hooks | Required | Recommended | Core+ |

---

## MSG-001 The core directly owns conversation types

**Requirement.** The Java core must not expose external AI SDK types directly but instead define
its own `Message`, `Content`, `ChatResponse`, `AgentResponse`, `ChatResponseUpdate`,
`AgentResponseUpdate`, and `ResponseStream` family of types.

**Upstream comparison**

- .NET: `ChatMessage` and `AIContent` are delegated to the external `Microsoft.Extensions.AI` types, and only the `AgentResponse` family is wrapped.
- Python: The core directly defines `Content`, `Message`, `ChatResponse`, `AgentResponse`, and `ResponseStream`.

**Decision.** Adopts the Python decision. If the Java public API is locked to external SDK types,
the core cannot control content taxonomy and serialization rules. The core must own the types so
that features such as approval, MCP, and hosted assets can also be built on the same model.

**Acceptance criteria**

- Message, content, response, and update types are all defined in the core public package.
- Provider SDK types do not appear directly in the public signatures of the core module.
- Provider adapters are responsible only for converting between core types and provider types.

**Evidence** [02 Public API and types](../upstream/snapshots/d0a4165f/features/02-message-content.md),
[02 Java decision](../upstream/snapshots/d0a4165f/features/02-message-content.md)

---

## MSG-002 Roles allow both known values and custom values

**Requirement.** `Role` provides `system`, `user`, `assistant`, and `tool` as known values, but
must also allow arbitrary string roles for compatibility.

**Upstream comparison**

- .NET: `ChatRole` can round-trip custom string roles.
- Python: `RoleLiteral` has known values, but `Message(role: str)` is also allowed.

**Decision.** The intent is the same. Known values simplify default branching and documentation,
and allowing custom values does not block provider extensions or intermediate representations.

**Acceptance criteria**

- The four known roles are provided as constants or an equivalent public contract.
- A message carrying a custom role string is serialized and deserialized without loss.
- The update-to-response reconstruction rule is not broken by custom roles.

**Evidence** [02 Message / Role model](../upstream/snapshots/d0a4165f/features/02-message-content.md)

---

## MSG-003 Input is normalized to a message list

**Requirement.** The core must normalize `null`, a string, a single `Content`, a single
`Message`, and a message sequence into a consistent `List<Message>`, promoting strings and single
`Content` values to a single user message.

**Upstream comparison**

- .NET: The string execution overload wraps the string into a single `user` message.
- Python: `normalize_messages()` normalizes `None`, `str`, `Content`, `Message`, and mixed sequences.

**Decision.** Adopts the broader normalization scope from Python. Java also allows convenience
inputs, but the internal canonical form must be a single type so that interceptors, sessions, and
tests always see the same data type.

**Acceptance criteria**

- Normalizing a `null` input yields an empty list.
- Normalizing a string input yields a single text message with the `user` role.
- Normalizing a single `Content` input yields a single message with the `user` role.
- An existing `Message` list input is preserved without reordering.

**Evidence** [02 Normalization and message assembly](../upstream/snapshots/d0a4165f/features/02-message-content.md),
[02 Concrete acceptance scenarios](../upstream/snapshots/d0a4165f/features/02-message-content.md)

---

## MSG-004 Text projection sees only text content

**Requirement.** `Message.text` and response-level text projection must concatenate only text
content in order and must not interpret or stringify non-text content.

**Upstream comparison**

- .NET: `AgentResponse.Text` and `AgentResponseUpdate.Text` concatenate only `TextContent`.
- Python: `Message.text` and `ChatResponse.text` also project only `text` content.

**Decision.** The approach is the same. Text projection is an auxiliary human-readable view and
does not replace the original content. Mixing non-text as arbitrary strings breaks multimodal
fidelity and destabilizes structured parsing.

**Acceptance criteria**

- The `text` of a message containing only non-text content is an empty string.
- When text and non-text are mixed, only text fragments appear in `text`.
- Text content in the same order is preserved in the same order in the projection result.

**Evidence** [02 Content model and multimodal representation](../upstream/snapshots/d0a4165f/features/02-message-content.md),
[02 Response / Update / Usage / Finish Reason](../upstream/snapshots/d0a4165f/features/02-message-content.md)

---

## MSG-005 The core provides basic multimodal content kinds

**Requirement.** The `Content` hierarchy must represent at minimum text, reasoning text,
binary/URI media, function/tool calls and results, usage, and hosted asset references as
distinct kinds.

**Upstream comparison**

- .NET: The core does not define its own taxonomy and preserves the external `AIContent` hierarchy opaquely.
- Python: A single `Content` union model directly represents text, media, tool calls, usage, hosted assets, and more.

**Decision.** Adopts Python's breadth but resolves it in a Java-idiomatic way using a sealed
hierarchy or tagged union. If the core does not know the basic kinds and passes everything
opaquely, common semantics like tool results, usage, and hosted assets cannot be fixed as test
and serialization contracts.

**Acceptance criteria**

- The six categories above are represented as distinct discriminators or subtypes.
- Text content and tool call content are not mixed within the same type branch.
- Each content can be identified by its kind in the core JSON representation.

**Evidence** [02 Content model and multimodal representation](../upstream/snapshots/d0a4165f/features/02-message-content.md),
[02 Java decision](../upstream/snapshots/d0a4165f/features/02-message-content.md)

---

## MSG-006 URI and binary content are validated at creation time

**Requirement.** Data URI and external URI content must reject empty values and malformed formats
at creation time, and must preserve the media type when the value is valid.

**Upstream comparison**

- .NET: No equivalent URI validation logic is identified in the agent core of this snapshot.
- Python: `_validate_uri()` rejects empty URIs, malformed data URIs, and URIs without a scheme, and handles the media type.

**Decision.** Adopts the Python approach. Accepting URIs loosely causes late failures deeper in
the runtime or allows invalid external references to be stored in sessions. A safer default is
correct.

**Acceptance criteria**

- Creating URI content with an empty URI string fails immediately.
- Creating URI content with a malformed data URI fails immediately.
- Creating content with a valid URI or data URI allows the media type to be retrieved.

**Evidence** [02 Content model and multimodal representation](../upstream/snapshots/d0a4165f/features/02-message-content.md),
[02 Errors, validation, and security](../upstream/snapshots/d0a4165f/features/02-message-content.md)

---

## MSG-007 Additional properties and the raw representation are preserved

**Requirement.** All message, content, response, and update types must preserve
`additionalProperties` and `rawRepresentation`, and the core must treat these two fields only as
public escape hatches.

**Upstream comparison**

- .NET: `AgentResponse` and `AgentResponseUpdate` have `AdditionalProperties` and `RawRepresentation`.
- Python: `Message`, `Content`, `ChatResponse`, `AgentResponse`, and update types all have the same two fields.

**Decision.** The approach is the same. An escape hatch is needed to avoid losing provider-specific
metadata and debug-purpose raw objects. However, core semantic rules must not depend on these
fields.

**Acceptance criteria**

- An `additionalProperties` key/value set by an adapter can be read back from the same object.
- The presence of `rawRepresentation` does not change text projection or response reconstruction rules.
- Core public requirements do not assume the concrete type of `rawRepresentation`.

**Evidence** [02 State and persistence](../upstream/snapshots/d0a4165f/features/02-message-content.md),
[02 Java decision](../upstream/snapshots/d0a4165f/features/02-message-content.md)

---

## MSG-008 Message provenance is tagged separately from content

**Requirement.** Message provenance must be stored as message-level attribution rather than inside
the content body, and messages without a provenance marker must default to `External`.

**Upstream comparison**

- .NET: When `_attribution` is absent the source type is interpreted as `External`; when present the message is cloned and tagged.
- Python: `_attribution` is placed in `additional_properties` to track source type, source id, and origin session ids.

**Decision.** The intent is the same. Mixing provenance into the content prevents reusing the same
body in different contexts, and defaulting unmarked input to `External` is safer.

**Acceptance criteria**

- The source type of a message without attribution is `External`.
- Source types `External`, `AIContextProvider`, and `ChatHistory` can be distinguished.
- The same content can have different provenance tags recorded separately.

**Evidence** [02 Message / Role model](../upstream/snapshots/d0a4165f/features/02-message-content.md),
[02 State and persistence](../upstream/snapshots/d0a4165f/features/02-message-content.md),
[02 Concrete acceptance scenarios](../upstream/snapshots/d0a4165f/features/02-message-content.md)

---

## MSG-009 Leading instructions are not inserted redundantly

**Requirement.** If a leading instruction with the same role and same text already exists, the
core must not prepend the same instruction again.

**Upstream comparison**

- .NET: No equivalent helper or deduplication rule is identified in this snapshot.
- Python: `prepend_instructions_to_messages()` skips insertion when the same leading instruction already exists.

**Decision.** Adopts the Python approach. Duplicate system prompts waste tokens and alter
behavior. Deduplication is not a feature addition but a safe default.

**Acceptance criteria**

- If a leading instruction with the same role and text already exists, the message list is unchanged.
- If the leading message differs in role or text, the new instruction is prepended.
- The same rule applies to instructions with custom roles.

**Evidence** [02 Normalization and message assembly](../upstream/snapshots/d0a4165f/features/02-message-content.md),
[02 Python implementation and tests](../upstream/snapshots/d0a4165f/features/02-message-content.md),
[02 Concrete acceptance scenarios](../upstream/snapshots/d0a4165f/features/02-message-content.md)

---

## MSG-010 Usage carries both standard fields and extension fields

**Requirement.** Usage metadata must provide at minimum input, output, and total token counts as
standard fields, and must store cache/reasoning token counts and provider-specific metrics in
extension fields.

**Upstream comparison**

- .NET: Usage is linked to the response/update model, but the concrete field set is delegated to the external `UsageDetails` type.
- Python: `UsageDetails` allows input/output/total/cache/reasoning token count and extension keys together.

**Decision.** Takes the common denominator of the two upstreams. Java specifies standard fields
explicitly while retaining an extension map so that provider-specific billing metrics are not
lost.

**Acceptance criteria**

- Input, output, and total token counts can be read as individual fields.
- If cache/reasoning token counts are present, they can be read via a dedicated field or equivalent standard key.
- Unknown provider usage keys are preserved without loss.

**Evidence** [02 Public API and types](../upstream/snapshots/d0a4165f/features/02-message-content.md),
[02 Response / Update / Usage / Finish Reason](../upstream/snapshots/d0a4165f/features/02-message-content.md)

---

## MSG-011 Responses and updates share the same metadata axes

**Requirement.** `AgentResponse` and `AgentResponseUpdate` must express the metadata
`agentId`, `responseId`, `messageId`, `authorName`, `createdAt`, `finishReason`,
`continuationToken`, `additionalProperties`, and `rawRepresentation` without loss.

**Upstream comparison**

- .NET: `AgentResponse` and `AgentResponseUpdate` carry nearly the same metadata fields.
- Python: `ChatResponse`, `AgentResponse`, `ChatResponseUpdate`, and `AgentResponseUpdate` expose fields along the same axes.

**Decision.** The approach is the same. Streaming reconstruction, observability, and continuation
execution all depend on these axes.

**Acceptance criteria**

- Converting a complete response to an update sequence and reassembling preserves `agentId` and `responseId`.
- A custom role and `authorName` are preserved through the update path.
- `continuationToken` and `finishReason` may be absent but are not lost when present.

**Evidence** [02 Public API and types](../upstream/snapshots/d0a4165f/features/02-message-content.md),
[02 Response / Update / Usage / Finish Reason](../upstream/snapshots/d0a4165f/features/02-message-content.md)

---

## MSG-012 The final response is reconstructed from an update sequence

**Requirement.** The core must reconstruct a complete response from an update sequence, using
`messageId` as the primary signal for message boundaries and falling back to role changes as a
secondary signal when absent, and must sum usage fragments into response-level usage.

**Upstream comparison**

- .NET: Reconstructs logical message boundaries using `messageId` and sums usage.
- Python: Determines boundaries by `message_id` or role changes, and accumulates usage content into response-level usage.

**Decision.** Combines the two upstreams. Prioritizing `messageId` is precise, and the role-change
fallback is resilient to provider drift. Usage must be folded from fragments to response level so
that streaming and non-streaming see the same metadata.

**Acceptance criteria**

- Consecutive updates with the same `messageId` are reconstructed into a single message.
- When `messageId` is absent and the role changes, a new message begins.
- Sending multiple usage fragments results in their sum in the final response usage.

**Evidence** [02 Message-Content-Stream Model](../upstream/snapshots/d0a4165f/features/02-message-content.md),
[02 .NET implementation and tests](../upstream/snapshots/d0a4165f/features/02-message-content.md),
[02 Python implementation and tests](../upstream/snapshots/d0a4165f/features/02-message-content.md)

---

## MSG-013 The stream wrapper provides a final response and transformation hooks

**Requirement.** The streaming API must provide `getFinalResponse()` separately from update
iteration, and must preserve the inner stream's finalizer, cleanup, and result hook ordering even
after stream transformations.

**Upstream comparison**

- .NET: Response/update transformation helpers exist, but a general-purpose `ResponseStream` wrapper is not identified.
- Python: `ResponseStream` fixes the ordering of finalizer, `map()`, `flat_map()`, cleanup hook, and result hook via tests.

**Decision.** Adopts the Python approach. Java streaming must also combine update iteration with
final response retrieval so that upper layers can compose safely. However, this is a core stream
contract, not a host runtime feature.

**Acceptance criteria**

- Calling `getFinalResponse()` after fully consuming the stream yields the same final response.
- Final response computation is preserved after `map()` or an equivalent transformation.
- Cleanup or result hooks registered on the inner stream are not lost because of a transformation wrapper.

**Evidence** [02 Message-Content-Stream Model](../upstream/snapshots/d0a4165f/features/02-message-content.md),
[02 Concurrency, streaming, and cancellation](../upstream/snapshots/d0a4165f/features/02-message-content.md),
[02 Concrete acceptance scenarios](../upstream/snapshots/d0a4165f/features/02-message-content.md)

---

## What this document does not cover

| Topic | Owning document |
| --- | --- |
| Execution entry point and cancellation propagation | [01 Agent execution and model calls](01-agent-execution.md) |
| Structured output requests and schema parsing | [03 Structured output](03-structured-output.md) |
| Tool call algorithm and approval policy | [04 Tool definitions and the tool call loop](04-tools.md) |
| Session storage format and history retention | [06 Sessions and conversation state](06-sessions.md) |
| Interceptors and context propagation | [07 Interceptors and context management](07-interceptors.md) |
