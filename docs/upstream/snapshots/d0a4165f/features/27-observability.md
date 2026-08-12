# 27. Observability

## State

- Document status: upstream snapshot analysis document
- Reference snapshot: `d0a4165f170193ba1d026a259af40d35bb7eaefe`
- Analysis scope: OpenTelemetry, feature telemetry, logging/sensitive data
- Out of scope: the session snapshot format, serializer selection, and schema/backward-compatibility detail rules are owned by the session document; this document covers only the minimal operational boundary where the observability pipeline meets the session/conversation context.

## Snapshot summary

The observability system in this snapshot shares a common principle of “metadata by default, sensitive data as a separate opt-in.” .NET centers on a wrapper approach that decorates `AIAgent`/`WorkflowBuilder`, while Python has a single observability module that owns tracer/meter/logger provider configuration, enable/disable policy, metrics views, and the sensitive data switch together. `.NET`'s `LoggingAgent` explicitly states that `Trace`-level logs must not be enabled in production, and Python keeps instrumentation disabling sticky to prevent the framework side from automatically re-enabling it. Both implementations explicitly state that OpenTelemetry itself does not send data externally; the exporter/pipeline configured by the developer determines the destination.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs#L15-L31  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/LoggingAgent.cs#L16-L30  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L739-L748  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L1140-L1230  

Feature telemetry is asymmetric. Python has confirmed real implementations of a process-global feature bitmask, live feature token, approved-origin-based request-time stamping, opt-out env var, and thread-safety tests. In contrast, the .NET production source directly confirmed in this snapshot covers stamping of `agent-framework-dotnet/{version}` and `foundry-hosting/agent-framework-dotnet/{version}` User-Agent segments, but a production implementation of live feature token insertion equivalent to Python's `(feat=v...)` form cannot be confirmed from the evidence collected in this snapshot alone. However, the repository ADR/spec clearly documents the design for a .NET bit registry and UA-based feature telemetry.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_telemetry.py#L18-L33  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_telemetry.py#L131-L170  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/openai/agent_framework_openai/_feature_usage.py#L42-L56  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_feature_usage.py#L40-L59  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry/AgentFrameworkUserAgentPolicy.cs#L10-L27  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/HostedAgentUserAgentPolicy.cs#L12-L35  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/specs/feature-usage-bit-registry.md#L189-L247  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0033-feature-usage-bitmask-user-agent.md#L57-L71  

## Original feature purpose and boundary

### 1. Purpose of OpenTelemetry

`.NET`'s `OpenTelemetryAgent` provides agent-level tracing/metrics following Generative AI semantic conventions, and Python's `observability.py` is a broad operational observability module encompassing tracing, logging, events, metrics, an MCP span helper, and tracer/meter accessors. Both implementations share the boundary of “metadata by default; original payload only with sensitive data opt-in.”  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs#L15-L31  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L3-L14  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L739-L748  

### 2. Purpose of feature telemetry

Feature telemetry is designed to collect coarse-grained information about “which features were actually observed during execution,” not “which packages are installed.” The ADR requires privacy-preserving, first-party scoped, request-time live signals. The Python implementation faithfully follows this goal, stamping a live feature token only for approved origins. .NET shares the same direction in the design document, but the production source directly confirmed in this snapshot only demonstrates versioned UA segment stamping.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0033-feature-usage-bitmask-user-agent.md#L14-L23  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0033-feature-usage-bitmask-user-agent.md#L27-L39  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/openai/agent_framework_openai/_feature_usage.py#L42-L56  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry/AgentFrameworkUserAgentPolicy.cs#L10-L27  

### 3. Purpose and boundary of logging

Logging is a system for leaving human-readable lifecycle/diagnostic signals separate from tracing. `.NET` places `LoggingAgent` as a separate decorator and strictly distinguishes `Debug` from `Trace`. Python treats the logger exporter as part of the OTel provider setup, and sensitive data capture is controlled by observability settings rather than the logging exporter itself.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/LoggingAgent.cs#L16-L30  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L944-L1001  

## Public APIs

### .NET public API

1. `AIAgentBuilder.UseOpenTelemetry(string? sourceName = null, Action<OpenTelemetryAgent>? configure = null)`  
   - adds an OpenTelemetry wrapper to the agent pipeline.  
   Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgentBuilderExtensions.cs#L47-L57

2. `OpenTelemetryAgent.EnableSensitiveData`  
   - controls whether raw inputs/outputs and tool args/results are included.  
   Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs#L118-L152

3. `WorkflowBuilder.WithOpenTelemetry(Action<WorkflowTelemetryOptions>? configure = null, ActivitySource? activitySource = null)`  
   - enables workflow build/run/executor/message spans as an opt-in.  
   Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/OpenTelemetryWorkflowBuilderExtensions.cs#L15-L68

4. `WorkflowTelemetryOptions`  
   - provides `EnableSensitiveData`, `DisableWorkflowBuild`, `DisableWorkflowRun`, `DisableExecutorProcess`, `DisableEdgeGroupProcess`, `DisableMessageSend`.  
   Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Observability/WorkflowTelemetryOptions.cs#L8-L68

5. `AIAgentBuilder.UseLogging(ILoggerFactory? loggerFactory = null, Action<LoggingAgent>? configure = null)`  
   - adds a logging decorator.  
   Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/LoggingAgentBuilderExtensions.cs#L12-L29

6. `LoggingAgent.JsonSerializerOptions`  
   - allows replacing the log serialization options.  
   Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/LoggingAgent.cs#L33-L55

### Python public API

1. `enable_instrumentation()` / `disable_instrumentation()`  
   - controls framework instrumentation on/off.  
   Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L1171-L1230

2. `enable_sensitive_telemetry()`  
   - explicitly enables sensitive payload telemetry capture.  
   Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L1140-L1168

3. `configure_otel_providers()` / `create_metric_views()` / `get_tracer()` / `get_meter()`  
   - provides traces/logs/metrics provider installation, metric filtering, and tracer/meter access.  
   Source:  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L701-L715  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L944-L1109  

4. `get_user_agent()` / `mark_feature_used()` / `get_feature_token()` / `apply_feature_token()` / `remove_feature_token()` / `prepend_agent_framework_to_user_agent()`  
   - configures User-Agent and feature token paths.  
   Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_telemetry.py#L123-L213

5. provider-specific feature stamping helper  
   - OpenAI: `create_feature_usage_http_client()`  
   - Foundry: `create_feature_usage_policy()` / `create_foundry_feature_usage_http_client()`  
   Source:  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/openai/agent_framework_openai/_feature_usage.py#L42-L56  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_feature_usage.py#L35-L59  

## Detailed execution flow

### 1. .NET OpenTelemetry agent flow

1. The caller adds a wrapper to the builder via `AIAgentBuilder.UseOpenTelemetry(...)`.  
2. The builder creates an `OpenTelemetryAgent`.  
3. `OpenTelemetryAgent` internally uses `OpenTelemetryChatClient` and `ForwardingChatClient` to route through a telemetry-aware path without bypassing actual agent execution.  
4. At execution time, the current `Activity` is rewritten with `invoke_agent` semantics, and provider/agent id/name/description tags are attached.  
5. When the agent is a `ChatClientAgent`, it attempts to auto-instrument the inner chat client by default, but skips additional decoration if the client is already instrumented or `UseProvidedChatClientAsIs` is set.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgentBuilderExtensions.cs#L47-L57  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs#L33-L60  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs#L97-L113  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs#L154-L214  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs#L242-L259  

### 2. .NET workflow telemetry flow

1. The workflow builder has telemetry off until `WithOpenTelemetry(...)` is called.  
2. Upon the call, `WorkflowTelemetryOptions` and `WorkflowTelemetryContext` are created.  
3. The runtime opens spans through entry points such as `StartWorkflowBuildActivity`, `StartWorkflowSessionActivity`, `StartWorkflowRunActivity`, `StartExecutorProcessActivity`, and `StartMessageSendActivity`.  
4. If sensitive data is not enabled, executor input/output and message content are not attached to span tags.  
5. On JSON serialization failure, the exception is not propagated and the value is downgraded to the `[Unserializable: ...]` string.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/OpenTelemetryWorkflowBuilderExtensions.cs#L30-L44  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/OpenTelemetryWorkflowBuilderExtensions.cs#L53-L68  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Observability/WorkflowTelemetryContext.cs#L53-L119  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Observability/WorkflowTelemetryContext.cs#L130-L233  

### 3. Python observability bootstrap flow

1. `configure_otel_providers()` receives exporters and classifies them by signal type.  
2. The trace provider, logger provider, and meter provider are each configured.  
3. If log exporters are present, `LoggingHandler` is attached to the framework logger.  
4. If metric exporters are present, the meter provider is configured using `PeriodicExportingMetricReader`.  
5. If needed, `create_metric_views()` is used to retain only `agent_framework*` and `gen_ai*` instruments and drop the rest.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L701-L715  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L944-L1012  

### 4. Python metric/trace signal flow

- The duration histogram is created with a `gen_ai`-series duration instrument.
- The token usage histogram uses the token unit.
- The tracer and meter use framework version information as defaults in `get_tracer()` and `get_meter()` respectively.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L1015-L1109  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L1410-L1425  

### 5. Python feature telemetry flow

1. On feature use, `mark_feature_used(index)` ORs the process-global bitmask.  
2. `get_feature_token()` produces a live token in the form `v<registry>.<mask>`.  
3. `apply_feature_token()` updates or appends the existing UA, and `remove_feature_token()` removes stale tokens.  
4. `get_user_agent()` lazy-detects the hosted environment and attaches the `foundry-hosting/` prefix when necessary.  
5. The OpenAI/Foundry request hook checks whether the origin of the actual request URL is an approved suffix; if the origin is approved, the token is attached; otherwise it is removed.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_telemetry.py#L59-L69  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_telemetry.py#L72-L128  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_telemetry.py#L131-L170  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/openai/agent_framework_openai/_feature_usage.py#L18-L56  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_feature_usage.py#L29-L59  

### 6. Execution flow related to .NET feature telemetry

The .NET path directly confirmed in this snapshot consists of the following two steps.

1. `FoundryChatClient` registers `AgentFrameworkUserAgentPolicy` into the underlying chat client's `OpenAIRequestPolicies` hook on a best-effort/at-most-once basis.  
2. On the hosted-agent resolution path, `HostedAgentUserAgentPolicy` upgrades bare `agent-framework-dotnet/{version}` to `foundry-hosting/agent-framework-dotnet/{version}` and prevents duplicate prefixes.  
3. The Cosmos integration puts component/version into `CosmosClientOptions.ApplicationName` to leave a separate diagnostics identity.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry/FoundryChatClient.cs#L24-L39  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry/FoundryChatClient.cs#L69-L79  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry/FoundryChatClient.cs#L649-L665  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry/AgentFrameworkUserAgentPolicy.cs#L35-L87  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/HostedAgentUserAgentPolicy.cs#L61-L139  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/ServiceCollectionExtensions.cs#L405-L443  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.CosmosNoSql/CosmosOptionsHelper.cs#L23-L60  

### 7. .NET logging flow

1. `LoggingAgent` logs lifecycle information at `Debug` level and serializes messages/options/metadata to JSON at `Trace` level before entering `RunAsync`/`RunStreamingAsync`.  
2. On success, `completed` is logged; on cancellation, `canceled`; on exception, `failed`.  
3. In streaming, each update is logged only at `Trace`.  
4. Serialization failure is not treated as a logging failure; a `ToString()` fallback is used instead.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/LoggingAgent.cs#L57-L100  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/LoggingAgent.cs#L103-L209  

### 8. Python logging flow

Python does not separate the logger path as a standalone decorator but treats it as part of the observability bootstrap. In addition, `MessageListTimestampFilter` is attached to the framework logger to make it possible to restore the ordering of chat history events that might otherwise be collapsed to the same millisecond/microsecond.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L188-L211  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L992-L1001  

## State and configuration

### Default state

- `.NET` agent OpenTelemetry is activated only by attaching `UseOpenTelemetry()` to the builder.  
  Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgentBuilderExtensions.cs#L47-L57

- `.NET` workflow telemetry is off by default, and `WithOpenTelemetry()` is the opt-in entry point.  
  Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/OpenTelemetryWorkflowBuilderExtensions.cs#L42-L44

- `.NET` sensitive telemetry is false by default and can be enabled via `OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT=true` or a property setting.  
  Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs#L119-L152

- `.NET` logging becomes a no-op without adding a decorator when `NullLoggerFactory` is used.  
  Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/LoggingAgentBuilderExtensions.cs#L39-L41  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/LoggingAgentBuilderExtensions.cs#L50-L64

- Python instrumentation is enabled by default when no configuration value is provided, but sensitive data is false by default and the console exporter is also false by default.  
  Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L751-L759  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L792-L803

### Sticky disable

When `disable_instrumentation()` is called in Python, a sticky disable is maintained so that the framework's internal auto-configuration path, direct property writes, `enable_sensitive_telemetry()`, `configure_otel_providers()`, and similar calls cannot re-enable instrumentation. Only a re-enabling call given `force=True` releases this state.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L785-L800  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L809-L851  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L1149-L1230  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L1354-L1360  

### Environment variables related to feature telemetry

- `AGENT_FRAMEWORK_USER_AGENT_DISABLED` can disable the entire Python User-Agent telemetry.
- `AGENT_FRAMEWORK_FEATURE_MASK_DISABLED` can disable only feature bitmask emission.
- `FOUNDRY_HOSTING_ENVIRONMENT` is used for hosted prefix detection.  
Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_telemetry.py#L18-L22  
https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_telemetry.py#L59-L64  

## Errors and security

### Security boundary of OpenTelemetry itself

Neither `.NET` nor Python defines OpenTelemetry signals as “a feature by which the framework directly transmits data externally.” The framework emits spans/logs/metrics via the standard API, and the actual destination is determined by the exporter/pipeline configuration. Therefore, the key to operational security decisions lies in exporter configuration, backend access control, and retention policy.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs#L22-L31  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L739-L748  

### Risk of sensitive data capture

- `.NET` OpenTelemetry explicitly states that enabling sensitive capture can send user input, model output, function-call arguments, and function-call results as telemetry.  
  Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs#L127-L137

- `.NET` LoggingAgent explicitly states that `Trace` logs contain messages/options/responses and must not be enabled in production.  
  Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/LoggingAgent.cs#L21-L29

- Python also leaves a warning that `enable_sensitive_data` should be enabled only for development/test use.  
  Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L736-L748  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L1155-L1157

### Privacy boundary of feature telemetry

The ADR places “no hidden telemetry,” “first-party scope,” “request-time live signal,” and “no third-party leakage” as decision drivers. The Python implementation checks whether the request URL is an approved HTTPS origin and removes even stale feature tokens at unapproved hops.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0033-feature-usage-bitmask-user-agent.md#L25-L39  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0033-feature-usage-bitmask-user-agent.md#L57-L71  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/openai/agent_framework_openai/_feature_usage.py#L35-L56  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_feature_usage.py#L45-L59  

### Logging/telemetry error handling

- `.NET` LoggingAgent logs `OperationCanceledException` and general exceptions separately.  
  Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/LoggingAgent.cs#L73-L100  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/LoggingAgent.cs#L119-L168

- Python `configure_otel_providers()` and `create_metric_views()` explicitly raise `ModuleNotFoundError` when `opentelemetry-sdk` is absent.  
  Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L703-L709  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L950-L968  

## .NET implementation

### Core components

1. `OpenTelemetryAgent`  
   - represents agent invocation as an `invoke_agent` span and tags `gen_ai.agent.id`, `gen_ai.agent.name`, `gen_ai.provider.name`, and others.  
   Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs#L177-L214

2. `OpenTelemetryConsts`  
   - The default source name is `Experimental.Microsoft.Agents.AI`, and GenAI tag keys are defined as constants.  
   Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryConsts.cs#L5-L30

3. `WorkflowTelemetryContext` / `WorkflowTelemetryOptions`  
   - provides workflow-specific activity start helpers and category-level disable switches.  
   Source:  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Observability/WorkflowTelemetryOptions.cs#L8-L68  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Observability/WorkflowTelemetryContext.cs#L53-L233  

4. `LoggingAgent`  
   - `Debug` handles lifecycle logs, and `Trace` handles payload-bearing logs. JSON serialization options can be injected.  
   Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/LoggingAgent.cs#L31-L55  
   https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/LoggingAgent.cs#L189-L208

5. `AgentFrameworkUserAgentPolicy` / `HostedAgentUserAgentPolicy`  
   - stamps/upgrades the User-Agent identity of Foundry-series outbound requests.  
   Source:  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry/AgentFrameworkUserAgentPolicy.cs#L10-L27  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/HostedAgentUserAgentPolicy.cs#L12-L35  

### Implementation characteristics

- Workflow observability has a separate API surface distinct from general agent observability.  
  Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/OpenTelemetryWorkflowBuilderExtensions.cs#L10-L18

- Inner chat client auto-wiring is a convenience but is not always enforced. Decoration stops if the client is already instrumented or if the caller opts out to use the original chat client as-is.  
  Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs#L86-L91  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs#L242-L259

- The `.NET` feature telemetry production path directly confirmed in this snapshot is focused on Foundry/Hosted. A production implementation that actually inserts live `(feat=v...)` tokens across generic providers at the same level as Python cannot be confirmed from the evidence collected in this snapshot alone.

## Python implementation

### Core components

1. `observability.py`  
   - places exports, settings, provider setup, metric views, tracer/meter helpers, histograms, and logger filter in one place.  
   Source:  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L3-L14  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L91-L107  

2. `ObservabilitySettings`  
   - manages `enable_instrumentation`, `enable_sensitive_data`, `enable_console_exporters`, `vs_code_extension_port`, and sticky disable semantics.  
   Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L727-L880

3. `_telemetry.py`  
   - is responsible for User-Agent identity, hosted prefix detection, process-global feature mask, and live token formatting.  
   Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_telemetry.py#L18-L33  
   https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_telemetry.py#L72-L170

4. provider-specific feature usage hook  
   - For OpenAI, an httpx request hook is provided; for Foundry, both an Azure policy and an OpenAI default client wrapper are provided.  
   Source:  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/openai/agent_framework_openai/_feature_usage.py#L25-L56  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_feature_usage.py#L35-L59  

### Implementation characteristics

- Python handles “observability bootstrap” more centrally than `.NET`.
- Although logger, metrics, traces, and feature telemetry are separated into different files, operational policy is expressed consistently within the same parent module group.
- Framework identity is naturally incorporated into the default header merge flow via `APP_INFO` and `prepend_agent_framework_to_user_agent()`.  
  Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/openai/agent_framework_openai/_shared.py#L196-L200

## Test evidence

### .NET test evidence

- Unit tests verify that `UseOpenTelemetry()` throws `ArgumentNullException` on a null builder, returns `OpenTelemetryAgent` on a valid builder, and maintains chaining.  
  Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/OpenTelemetryAgentBuilderExtensionsTests.cs#L15-L39  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/OpenTelemetryAgentBuilderExtensionsTests.cs#L83-L125

### Python test evidence

- The feature bitmask is verified for deduplication, stale-token removal, thread safety, and registry consistency.  
  Source:  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_telemetry.py#L72-L83  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_telemetry.py#L130-L168  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_telemetry.py#L171-L180  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_telemetry.py#L183-L218  

- Observability tests verify role-specific semantic attributes, tool-execution span naming, and log timestamp filter behavior.  
  Source:  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_observability.py#L45-L60  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_observability.py#L66-L95  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_observability.py#L106-L159  

### Unconfirmed

- The body of unit tests dedicated to the `.NET` “live feature bitmask token” cannot be confirmed from the evidence collected in this snapshot alone.
- The body of tests dedicated to `.NET LoggingAgent` cannot be confirmed from the evidence collected in this snapshot alone.
- However, because the production code describes lifecycle, sensitive-data boundary, and no-op conditions very explicitly, the operational contract is sufficiently readable from the code itself.

## Differences between documentation and code

### 1. The README's “built-in OpenTelemetry integration” description and the actual opt-in boundary

The README broadly describes the framework as having a built-in OpenTelemetry integration. This description is directionally correct, but at the code level it is more accurate to note that workflow telemetry in particular is not active by default and is enabled only when `WithOpenTelemetry()` is called. Operational design must therefore prioritize code-level opt-in semantics over the README's description.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/README.md#L50-L52  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/OpenTelemetryWorkflowBuilderExtensions.cs#L42-L44  

### 2. Difference in the directly proven scope between the .NET feature telemetry design document and the production source

The ADR and registry documents describe a feature-usage bitmask in User-Agent model as also existing for .NET. However, the .NET production source directly confirmed in this snapshot merely shows the `agent-framework-dotnet/{version}` and hosted prefix upgrade paths, and a `(feat=v...)` live token stamping implementation equivalent to Python's was not directly confirmed in the evidence collected in this snapshot. In the documentation-to-code difference analysis, recording “the design document is broader and the code evidence is narrower” is therefore accurate.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0033-feature-usage-bitmask-user-agent.md#L21-L23  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0033-feature-usage-bitmask-user-agent.md#L57-L71  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/specs/feature-usage-bit-registry.md#L189-L247  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry/AgentFrameworkUserAgentPolicy.cs#L49-L66  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/HostedAgentUserAgentPolicy.cs#L61-L116  

### 3. Alignment between FAQ recommendations and the code

`TRANSPARENCY_FAQ` recommends proper error handling, logging, and OpenTelemetry use. This direction is aligned with the code. However, when making actual operational decisions, code-level defaults — for example, the discouragement of `.NET Trace`, Python sticky disable, and workflow telemetry opt-in — are more specific than the FAQ.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/TRANSPARENCY_FAQ.md#L83-L87  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/LoggingAgent.cs#L21-L29  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L1171-L1230  

## Java design decisions

### Decision 1. Design OpenTelemetry with a “bootstrap + wrapper” dual structure

For Java, a dual structure is appropriate: a central module for provider/exporter/meter/tracer bootstrap as in Python, together with agent/workflow wrappers as in `.NET`. This approach separates app-wide observability initialization from per-agent/per-workflow instrumentation decoration.  
Evidence:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L944-L1012  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgentBuilderExtensions.cs#L47-L57  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/OpenTelemetryWorkflowBuilderExtensions.cs#L53-L68  

### Decision 2. Keep sensitive data capture as an independent opt-in separate from base instrumentation

Base instrumentation sends only metadata such as token count, duration, and operation name; message content, tool args, and tool results must never be included in spans or logs without an explicit enable.  
Evidence:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs#L22-L31  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs#L127-L137  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L739-L748  

### Decision 3. Keep logging as a separate layer from tracing

In Java, trace/span and log must not be hidden behind the same API; lifecycle/debug logs and payload-bearing trace logs must be separated, as in `.NET LoggingAgent`. In production, payload logs must be blocked by default, and a redaction-aware serializer must be replaceable.  
Evidence:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/LoggingAgent.cs#L21-L29  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/LoggingAgent.cs#L176-L187  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L992-L1001  

### Decision 4. Adopt the Python model for feature telemetry

For Java, adopting Python's live feature-token model is preferable to the “versioned UA identity only” state of `.NET` in this snapshot. That is:
- process-global bitmask
- request-time stamping
- approved-origin-only emission
- opt-out env var
- stale token stripping
- registry consistency test  
must all be included.  
Evidence:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_telemetry.py#L131-L170  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/openai/agent_framework_openai/_feature_usage.py#L48-L56  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_telemetry.py#L183-L218  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0033-feature-usage-bitmask-user-agent.md#L57-L71  

### Decision 5. Workflow observability has category-level disable switches

Java workflow tracing must allow each of the build/run/executor/message categories to be disabled individually. This is because the volume/PII surface is larger than that of agent-level tracing.  
Evidence: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Observability/WorkflowTelemetryOptions.cs#L24-L68

### Decision 6. Adopt sticky disable semantics

To prevent a situation where an operator disables instrumentation but a framework helper later re-enables it automatically, it is appropriate to bring Python's sticky disable semantics to Java as well.  
Evidence:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L785-L800  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L1171-L1230  

## Concrete acceptance scenarios

### Scenario 1. Creating a .NET agent telemetry wrapper

- Given: a default `AIAgentBuilder`
- When: `UseOpenTelemetry()` is applied and `Build()` is called
- Then: the resulting agent must be an `OpenTelemetryAgent`.  
Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/OpenTelemetryAgentBuilderExtensionsTests.cs#L25-L39

### Scenario 2. .NET workflow telemetry is off by default

- Given: `WorkflowBuilder`
- When: `WithOpenTelemetry()` is not called
- Then: workflow telemetry must not be active by default, and telemetry collection must be an explicit opt-in.  
Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/OpenTelemetryWorkflowBuilderExtensions.cs#L42-L44

### Scenario 3. .NET sensitive telemetry opt-in

- Given: `OpenTelemetryAgent`
- When: `EnableSensitiveData` is not explicitly enabled
- Then: default telemetry must not include raw message content, function args, or function results.  
Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs#L124-L137

### Scenario 4. .NET logging separates Debug and Trace

- Given: `LoggingAgent`
- When: the logger is at `Debug` but `Trace` is off
- Then: only method lifecycle must be logged and payload must not be logged.  
Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/LoggingAgent.cs#L61-L71  
https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/LoggingAgent.cs#L77-L87

### Scenario 5. Python metric view filtering

- Given: `create_metric_views()`
- When: default metric views are configured
- Then: only `agent_framework*` and `gen_ai*` instruments must be retained and the rest must be dropped.  
Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L701-L715

### Scenario 6. Python sticky disable

- Given: `disable_instrumentation()` has been called first
- When: `enable_sensitive_telemetry()` is called with `force=False`
- Then: the framework must not re-enable instrumentation and must remain in the ignored state.  
Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L1158-L1164  
https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L1171-L1194

### Scenario 7. Python feature token live update

- Given: only `CORE_AGENT` is marked
- When: `apply_feature_token("foundry-hosting/agent-framework-python/1.0")` is called, then `CORE_WORKFLOW` is additionally marked and `apply_feature_token(...)` is called again
- Then: the token must first be updated to `v1.1` and then to `v1.5`.  
Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_telemetry.py#L130-L141

### Scenario 8. Python approved-origin-only feature stamping

- Given: OpenAI/Foundry request hook
- When: the actual request URL is not an approved HTTPS origin
- Then: the feature token must be removed from the request header.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/openai/agent_framework_openai/_feature_usage.py#L48-L54  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_feature_usage.py#L48-L58  

### Scenario 9. .NET hosted User-Agent upgrade

- Given: the outbound request header already contains bare `agent-framework-dotnet/{version}`
- When: `HostedAgentUserAgentPolicy` is applied
- Then: the header must be upgraded in-place to the `foundry-hosting/agent-framework-dotnet/{version}` form and must not produce duplicate prefixes.  
Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/HostedAgentUserAgentPolicy.cs#L72-L109

## Source inventory

### Production source: .NET

- `dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs`  
  - purpose, security note, sensitive data opt-in, invoke flow, activity tagging, inner auto-wiring  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs#L15-L31  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs#L73-L113  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs#L118-L214  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs#L242-L259

- `dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgentBuilderExtensions.cs`  
  - public entrypoint  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgentBuilderExtensions.cs#L27-L57

- `dotnet/src/Microsoft.Agents.AI/LoggingAgent.cs`  
  - logging lifecycle, Trace sensitivity, fallback serialization  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/LoggingAgent.cs#L16-L30  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/LoggingAgent.cs#L31-L55  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/LoggingAgent.cs#L57-L209

- `dotnet/src/Microsoft.Agents.AI/LoggingAgentBuilderExtensions.cs`  
  - no-op behavior with `NullLoggerFactory`  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/LoggingAgentBuilderExtensions.cs#L31-L65

- `dotnet/src/Microsoft.Agents.AI/OpenTelemetryConsts.cs`  
  - tag keys / default source name  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryConsts.cs#L5-L30

- `dotnet/src/Microsoft.Agents.AI.Workflows/OpenTelemetryWorkflowBuilderExtensions.cs`  
  - workflow telemetry opt-in boundary  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/OpenTelemetryWorkflowBuilderExtensions.cs#L15-L68

- `dotnet/src/Microsoft.Agents.AI.Workflows/Observability/WorkflowTelemetryOptions.cs`  
  - per-category switches  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Observability/WorkflowTelemetryOptions.cs#L8-L68

- `dotnet/src/Microsoft.Agents.AI.Workflows/Observability/WorkflowTelemetryContext.cs`  
  - workflow activity creation and sensitive-data serialization behavior  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Observability/WorkflowTelemetryContext.cs#L53-L233

- `dotnet/src/Microsoft.Agents.AI.Foundry/FoundryChatClient.cs`  
  - Foundry request policy registration path  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry/FoundryChatClient.cs#L24-L39  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry/FoundryChatClient.cs#L69-L79  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry/FoundryChatClient.cs#L649-L665

- `dotnet/src/Microsoft.Agents.AI.Foundry/AgentFrameworkUserAgentPolicy.cs`  
  - bare UA stamping  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry/AgentFrameworkUserAgentPolicy.cs#L10-L87

- `dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/HostedAgentUserAgentPolicy.cs`  
  - hosted UA upgrade and duplicate-prefix prevention  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/HostedAgentUserAgentPolicy.cs#L12-L139

- `dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/ServiceCollectionExtensions.cs`  
  - hosted UA policy registration  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/ServiceCollectionExtensions.cs#L405-L443

- `dotnet/src/Microsoft.Agents.AI.CosmosNoSql/CosmosOptionsHelper.cs`  
  - diagnostics identity via `ApplicationName`  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.CosmosNoSql/CosmosOptionsHelper.cs#L8-L12  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.CosmosNoSql/CosmosOptionsHelper.cs#L23-L60  

### Production source: Python

- `python/packages/core/agent_framework/observability.py`  
  - central observability bootstrap, settings, provider wiring, views, tracer/meter, histograms, timestamp filter  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L3-L14  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L91-L107  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L188-L211  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L701-L715  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L727-L880  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L944-L1109  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L1140-L1425  

- `python/packages/core/agent_framework/_telemetry.py`  
  - UA identity, hosted prefix detection, bitmask/token logic  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_telemetry.py#L18-L33  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_telemetry.py#L59-L170  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_telemetry.py#L178-L213  

- `python/packages/openai/agent_framework_openai/_feature_usage.py`  
  - approved-origin request-time stamping  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/openai/agent_framework_openai/_feature_usage.py#L18-L56

- `python/packages/foundry/agent_framework_foundry/_feature_usage.py`  
  - Foundry-specific approved-origin stamping  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_feature_usage.py#L19-L59

- `python/packages/openai/agent_framework_openai/_shared.py`  
  - APP_INFO + User-Agent merge path  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/openai/agent_framework_openai/_shared.py#L196-L200

### Tests

- `.NET` OpenTelemetry builder tests  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/OpenTelemetryAgentBuilderExtensionsTests.cs#L15-L125

- Python telemetry tests  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_telemetry.py#L38-L57  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_telemetry.py#L72-L83  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_telemetry.py#L130-L218

- Python observability tests  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_observability.py#L45-L159

### Documentation / design evidence

- README observability claim  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/README.md#L50-L52

- Transparency FAQ observability recommendation  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/TRANSPARENCY_FAQ.md#L83-L87

- Feature telemetry ADR  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0033-feature-usage-bitmask-user-agent.md#L10-L23  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0033-feature-usage-bitmask-user-agent.md#L25-L39  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0033-feature-usage-bitmask-user-agent.md#L57-L71  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0033-feature-usage-bitmask-user-agent.md#L112-L139

- Feature bit registry  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/specs/feature-usage-bit-registry.md#L189-L247