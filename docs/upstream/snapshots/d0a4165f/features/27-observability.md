# 27. Observability

## 상태

- 문서 상태: upstream snapshot 분석 문서
- 기준 스냅샷: `d0a4165f170193ba1d026a259af40d35bb7eaefe`
- 분석 범위: OpenTelemetry, feature telemetry, logging/sensitive data
- 비범위: 세션 snapshot 포맷, serializer 선택, schema/backward-compatibility 세부 규칙은 세션 문서 소유이며, 본 문서는 observability 파이프라인이 세션/대화 컨텍스트와 만나는 운영 경계만 최소한으로 다룬다.

## 스냅샷 요약

이 스냅샷의 observability 체계는 공통적으로 “기본 메타데이터 중심, 민감 데이터는 별도 opt-in”이라는 원칙을 가진다. .NET은 `AIAgent`/`WorkflowBuilder`에 장식자를 씌우는 래퍼 방식이 중심이고, Python은 하나의 observability 모듈이 tracer/meter/logger provider 설정과 enable/disable 정책, metrics view, 민감 데이터 스위치를 함께 소유한다. `.NET`의 `LoggingAgent`는 `Trace` 레벨 로그를 운영 환경에서 켜지 말라고 명시하고, Python은 instrumentation 비활성화가 sticky 하게 유지되어 framework 측 자동 재활성화를 막는다. OpenTelemetry 자체가 외부로 데이터를 보내는 것이 아니라, 개발자가 구성한 exporter/pipeline이 목적지를 결정한다는 점도 양쪽 모두에서 명시적이다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs#L15-L31  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/LoggingAgent.cs#L16-L30  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L739-L748  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L1140-L1230  

Feature telemetry는 비대칭적이다. Python은 process-global feature bitmask, live feature token, approved-origin 기반 request-time stamping, opt-out env var, thread-safety tests까지 실구현이 확인된다. 반면 이번 snapshot에서 직접 확인된 .NET production source는 `agent-framework-dotnet/{version}` 및 `foundry-hosting/agent-framework-dotnet/{version}` User-Agent 세그먼트 stamping까지는 확인되지만, Python과 동일한 `(feat=v...)` live feature token 삽입 구현은 이번 수집 근거만으로는 확인 불가다. 다만 저장소 ADR/spec에는 .NET용 bit registry와 UA-based feature telemetry 설계가 분명히 문서화되어 있다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_telemetry.py#L18-L33  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_telemetry.py#L131-L170  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/openai/agent_framework_openai/_feature_usage.py#L42-L56  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_feature_usage.py#L40-L59  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry/AgentFrameworkUserAgentPolicy.cs#L10-L27  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/HostedAgentUserAgentPolicy.cs#L12-L35  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/specs/feature-usage-bit-registry.md#L189-L247  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0033-feature-usage-bitmask-user-agent.md#L57-L71  

## 원본 기능 목적과 경계

### 1. OpenTelemetry의 목적

`.NET`의 `OpenTelemetryAgent`는 Generative AI semantic conventions를 따르는 agent-level tracing/metrics를 제공하고, Python의 `observability.py`는 tracing, logging, events, metrics, MCP span helper, tracer/meter accessor까지 포함하는 폭넓은 운영 관측성 모듈이다. 두 구현 모두 “기본은 메타데이터 위주, 원문 payload는 민감 데이터 opt-in일 때만”이라는 경계를 가진다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs#L15-L31  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L3-L14  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L739-L748  

### 2. Feature telemetry의 목적

Feature telemetry는 “어떤 패키지가 설치되었는가”가 아니라 “어떤 기능이 실제 실행 중 관측되었는가”를 coarse-grained 하게 수집하는 목적을 가진다. ADR은 privacy-preserving, first-party scoped, request-time live signal을 요구한다. Python 구현은 이 목적을 충실히 따르며, approved-origin 에 대해서만 live feature token을 찍는다. .NET은 설계 문서상 같은 방향을 갖지만, 이번 snapshot의 production source는 versioned UA 세그먼트 stamping까지만 직접 입증한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0033-feature-usage-bitmask-user-agent.md#L14-L23  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0033-feature-usage-bitmask-user-agent.md#L27-L39  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/openai/agent_framework_openai/_feature_usage.py#L42-L56  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry/AgentFrameworkUserAgentPolicy.cs#L10-L27  

### 3. Logging의 목적과 경계

Logging은 tracing과 별도로 사람이 읽을 수 있는 lifecycle/diagnostic signal을 남기기 위한 체계다. `.NET`은 `LoggingAgent`를 별도 decorator로 두고, `Debug`와 `Trace`를 엄격히 구분한다. Python은 logger exporter를 OTel provider 설정의 일부로 다루며, 민감 데이터 capture는 logging exporter 자체가 아니라 observability settings가 통제한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/LoggingAgent.cs#L16-L30  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L944-L1001  

## 공개 API

### .NET 공개 API

1. `AIAgentBuilder.UseOpenTelemetry(string? sourceName = null, Action<OpenTelemetryAgent>? configure = null)`  
   - agent pipeline에 OpenTelemetry wrapper를 추가한다.  
   출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgentBuilderExtensions.cs#L47-L57

2. `OpenTelemetryAgent.EnableSensitiveData`  
   - raw inputs/outputs, tool args/results 포함 여부를 제어한다.  
   출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs#L118-L152

3. `WorkflowBuilder.WithOpenTelemetry(Action<WorkflowTelemetryOptions>? configure = null, ActivitySource? activitySource = null)`  
   - workflow build/run/executor/message span을 opt-in으로 활성화한다.  
   출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/OpenTelemetryWorkflowBuilderExtensions.cs#L15-L68

4. `WorkflowTelemetryOptions`  
   - `EnableSensitiveData`, `DisableWorkflowBuild`, `DisableWorkflowRun`, `DisableExecutorProcess`, `DisableEdgeGroupProcess`, `DisableMessageSend`를 제공한다.  
   출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Observability/WorkflowTelemetryOptions.cs#L8-L68

5. `AIAgentBuilder.UseLogging(ILoggerFactory? loggerFactory = null, Action<LoggingAgent>? configure = null)`  
   - logging decorator를 추가한다.  
   출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/LoggingAgentBuilderExtensions.cs#L12-L29

6. `LoggingAgent.JsonSerializerOptions`  
   - 로그 직렬화 옵션을 교체할 수 있다.  
   출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/LoggingAgent.cs#L33-L55

### Python 공개 API

1. `enable_instrumentation()` / `disable_instrumentation()`  
   - framework instrumentation on/off를 제어한다.  
   출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L1171-L1230

2. `enable_sensitive_telemetry()`  
   - 민감 payload telemetry capture를 명시적으로 켠다.  
   출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L1140-L1168

3. `configure_otel_providers()` / `create_metric_views()` / `get_tracer()` / `get_meter()`  
   - traces/logs/metrics provider 설치, metric filtering, tracer/meter access를 제공한다.  
   출처:  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L701-L715  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L944-L1109  

4. `get_user_agent()` / `mark_feature_used()` / `get_feature_token()` / `apply_feature_token()` / `remove_feature_token()` / `prepend_agent_framework_to_user_agent()`  
   - User-Agent 및 feature token 경로를 구성한다.  
   출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_telemetry.py#L123-L213

5. provider-specific feature stamping helper  
   - OpenAI: `create_feature_usage_http_client()`  
   - Foundry: `create_feature_usage_policy()` / `create_foundry_feature_usage_http_client()`  
   출처:  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/openai/agent_framework_openai/_feature_usage.py#L42-L56  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_feature_usage.py#L35-L59  

## 상세 실행 흐름

### 1. .NET OpenTelemetry agent 흐름

1. 호출자는 `AIAgentBuilder.UseOpenTelemetry(...)`로 builder에 wrapper를 추가한다.  
2. builder는 `OpenTelemetryAgent`를 생성한다.  
3. `OpenTelemetryAgent`는 내부적으로 `OpenTelemetryChatClient`와 `ForwardingChatClient`를 사용해 실제 agent 실행을 우회하지 않고 telemetry-aware 한 경로로 흘린다.  
4. 실행 시 현재 `Activity`를 `invoke_agent` 의미로 재기록하고, provider/agent id/name/description 태그를 붙인다.  
5. `ChatClientAgent`인 경우 기본적으로 inner chat client도 자동 계측하려 하되, 이미 계측되어 있거나 `UseProvidedChatClientAsIs`가 설정되면 추가 decoration을 하지 않는다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgentBuilderExtensions.cs#L47-L57  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs#L33-L60  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs#L97-L113  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs#L154-L214  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs#L242-L259  

### 2. .NET workflow telemetry 흐름

1. workflow builder는 `WithOpenTelemetry(...)` 호출 전까지 telemetry가 꺼져 있다.  
2. 호출 시 `WorkflowTelemetryOptions`와 `WorkflowTelemetryContext`가 생성된다.  
3. runtime은 `StartWorkflowBuildActivity`, `StartWorkflowSessionActivity`, `StartWorkflowRunActivity`, `StartExecutorProcessActivity`, `StartMessageSendActivity` 같은 진입점으로 span을 연다.  
4. 민감 데이터가 켜져 있지 않으면 executor input/output, message content는 span tag에 실리지 않는다.  
5. JSON serialization 실패 시 예외를 전파하지 않고 `[Unserializable: ...]` 문자열로 downgrade 한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/OpenTelemetryWorkflowBuilderExtensions.cs#L30-L44  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/OpenTelemetryWorkflowBuilderExtensions.cs#L53-L68  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Observability/WorkflowTelemetryContext.cs#L53-L119  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Observability/WorkflowTelemetryContext.cs#L130-L233  

### 3. Python observability bootstrap 흐름

1. `configure_otel_providers()`가 exporters를 받아 signal type별로 분류한다.  
2. trace provider, logger provider, meter provider를 각각 구성한다.  
3. log exporters가 있으면 framework logger에 `LoggingHandler`를 붙인다.  
4. metric exporters가 있으면 `PeriodicExportingMetricReader`를 사용해 meter provider를 구성한다.  
5. 필요하면 `create_metric_views()`로 `agent_framework*`와 `gen_ai*`만 남기고 나머지 instrument를 drop 한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L701-L715  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L944-L1012  

### 4. Python metric/trace signal 흐름

- duration histogram은 `gen_ai` 계열 duration instrument로 생성된다.
- token usage histogram은 token unit을 사용한다.
- tracer와 meter는 각각 `get_tracer()`와 `get_meter()`에서 framework version 정보를 기본값으로 사용한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L1015-L1109  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L1410-L1425  

### 5. Python feature telemetry 흐름

1. feature 사용 시 `mark_feature_used(index)`가 process-global bitmask를 OR 한다.  
2. `get_feature_token()`은 `v<registry>.<mask>` 형태의 live token을 만든다.  
3. `apply_feature_token()`은 기존 UA를 갱신/추가하고, `remove_feature_token()`은 stale token을 제거한다.  
4. `get_user_agent()`는 hosted 환경을 lazy-detect 해서 필요하면 `foundry-hosting/` prefix를 붙인다.  
5. OpenAI/Foundry request hook은 실제 request URL의 origin이 승인된 suffix인지 검사하고, 승인된 origin이면 token을 붙이고 아니면 제거한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_telemetry.py#L59-L69  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_telemetry.py#L72-L128  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_telemetry.py#L131-L170  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/openai/agent_framework_openai/_feature_usage.py#L18-L56  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_feature_usage.py#L29-L59  

### 6. .NET feature telemetry 관련 실행 흐름

이번 snapshot에서 직접 확인된 .NET 경로는 다음 두 단계다.

1. `FoundryChatClient`가 underlying chat client의 `OpenAIRequestPolicies` hook에 `AgentFrameworkUserAgentPolicy`를 best-effort/at-most-once로 등록한다.  
2. hosted-agent 해상 경로에서는 `HostedAgentUserAgentPolicy`가 bare `agent-framework-dotnet/{version}`를 `foundry-hosting/agent-framework-dotnet/{version}`로 업그레이드하고, 중복 prefix를 방지한다.  
3. Cosmos integration은 `CosmosClientOptions.ApplicationName`에 component/version을 넣어 별도의 diagnostics identity를 남긴다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry/FoundryChatClient.cs#L24-L39  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry/FoundryChatClient.cs#L69-L79  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry/FoundryChatClient.cs#L649-L665  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry/AgentFrameworkUserAgentPolicy.cs#L35-L87  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/HostedAgentUserAgentPolicy.cs#L61-L139  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/ServiceCollectionExtensions.cs#L405-L443  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.CosmosNoSql/CosmosOptionsHelper.cs#L23-L60  

### 7. .NET logging 흐름

1. `LoggingAgent`는 `RunAsync`/`RunStreamingAsync` 진입 전에 `Debug`이면 lifecycle log를 찍고, `Trace`면 messages/options/metadata를 JSON 직렬화해 기록한다.  
2. 성공 시 `completed`, 취소 시 `canceled`, 예외 시 `failed`를 분기 로깅한다.  
3. streaming에서는 각 update를 `Trace`에서만 남긴다.  
4. 직렬화 실패는 로깅 자체 실패로 보지 않고 `ToString()` fallback을 사용한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/LoggingAgent.cs#L57-L100  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/LoggingAgent.cs#L103-L209  

### 8. Python logging 흐름

Python은 logger path를 standalone decorator로 분리하지 않고, observability bootstrap의 일부로 다룬다. 또한 `MessageListTimestampFilter`를 framework logger에 붙여 같은 millisecond/microsecond로 뭉개질 수 있는 chat history event의 순서를 복원 가능하게 만든다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L188-L211  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L992-L1001  

## 상태 및 구성

### 기본 상태

- `.NET` agent OpenTelemetry는 builder에 `UseOpenTelemetry()`를 붙여야 활성화된다.  
  출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgentBuilderExtensions.cs#L47-L57

- `.NET` workflow telemetry는 기본 비활성이고 `WithOpenTelemetry()`가 opt-in 진입점이다.  
  출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/OpenTelemetryWorkflowBuilderExtensions.cs#L42-L44

- `.NET`의 sensitive telemetry는 기본 false이고, `OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT=true` 또는 property 설정으로 켤 수 있다.  
  출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs#L119-L152

- `.NET` logging은 `NullLoggerFactory`면 decorator를 추가하지 않고 no-op가 된다.  
  출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/LoggingAgentBuilderExtensions.cs#L39-L41  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/LoggingAgentBuilderExtensions.cs#L50-L64

- Python instrumentation은 설정값이 없으면 default로 enabled 이지만, sensitive data는 default false 이고, console exporter도 default false 다.  
  출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L751-L759  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L792-L803

### Sticky disable

Python은 `disable_instrumentation()`을 호출하면 framework 내부 자동 설정 경로, direct property write, `enable_sensitive_telemetry()`, `configure_otel_providers()` 등이 instrumentation을 다시 켜지 못하도록 sticky disable을 유지한다. 오직 `force=True`를 준 재활성화 호출만 이 상태를 해제한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L785-L800  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L809-L851  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L1149-L1230  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L1354-L1360  

### Feature telemetry 관련 환경 변수

- `AGENT_FRAMEWORK_USER_AGENT_DISABLED`는 Python User-Agent telemetry 전체를 끌 수 있다.
- `AGENT_FRAMEWORK_FEATURE_MASK_DISABLED`는 feature bitmask emission만 끌 수 있다.
- `FOUNDRY_HOSTING_ENVIRONMENT`는 hosted prefix 감지에 사용된다.  
출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_telemetry.py#L18-L22  
https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_telemetry.py#L59-L64  

## 오류와 보안

### OpenTelemetry 자체의 보안 경계

`.NET`과 Python 모두 OpenTelemetry signal을 “framework가 외부에 직접 전송하는 기능”으로 정의하지 않는다. framework는 standard API로 span/log/metric을 emit 하고, 실제 목적지는 exporter/pipeline 설정이 정한다. 따라서 운영 보안 판단의 핵심은 exporter 구성, backend access control, retention policy다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs#L22-L31  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L739-L748  

### 민감 데이터 capture 위험

- `.NET` OpenTelemetry는 민감 capture를 켜면 user input, model output, function-call arguments, function-call results를 telemetry로 보낼 수 있다고 명시한다.  
  출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs#L127-L137

- `.NET` LoggingAgent는 `Trace` 로그가 messages/options/responses를 담으므로 production에서 켜지 말라고 명시한다.  
  출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/LoggingAgent.cs#L21-L29

- Python도 `enable_sensitive_data`는 development/test 용으로만 켜라는 경고를 남긴다.  
  출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L736-L748  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L1155-L1157

### Feature telemetry의 privacy 경계

ADR은 “hidden telemetry 금지”, “first-party scope”, “request-time live signal”, “no third-party leakage”를 decision driver로 둔다. Python implementation은 request URL이 승인된 HTTPS origin인지 검사하고, 승인되지 않은 hop에서는 stale feature token도 제거한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0033-feature-usage-bitmask-user-agent.md#L25-L39  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0033-feature-usage-bitmask-user-agent.md#L57-L71  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/openai/agent_framework_openai/_feature_usage.py#L35-L56  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_feature_usage.py#L45-L59  

### Logging/telemetry 오류 처리

- `.NET` LoggingAgent는 `OperationCanceledException`과 일반 예외를 분리해 기록한다.  
  출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/LoggingAgent.cs#L73-L100  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/LoggingAgent.cs#L119-L168

- Python `configure_otel_providers()`와 `create_metric_views()`는 `opentelemetry-sdk`가 없으면 명시적으로 `ModuleNotFoundError`를 올린다.  
  출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L703-L709  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L950-L968  

## .NET 구현

### 핵심 구성요소

1. `OpenTelemetryAgent`  
   - agent invocation을 `invoke_agent` span으로 표현하고 `gen_ai.agent.id`, `gen_ai.agent.name`, `gen_ai.provider.name` 등을 태깅한다.  
   출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs#L177-L214

2. `OpenTelemetryConsts`  
   - 기본 source name은 `Experimental.Microsoft.Agents.AI`이며, GenAI 태그 키를 상수화한다.  
   출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryConsts.cs#L5-L30

3. `WorkflowTelemetryContext` / `WorkflowTelemetryOptions`  
   - workflow-specific activity start helper와 category-level disable switch를 제공한다.  
   출처:  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Observability/WorkflowTelemetryOptions.cs#L8-L68  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Observability/WorkflowTelemetryContext.cs#L53-L233  

4. `LoggingAgent`  
   - `Debug`는 lifecycle, `Trace`는 payload-bearing log를 담당한다. JSON 직렬화 옵션을 주입 가능하다.  
   출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/LoggingAgent.cs#L31-L55  
   https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/LoggingAgent.cs#L189-L208

5. `AgentFrameworkUserAgentPolicy` / `HostedAgentUserAgentPolicy`  
   - Foundry 계열 outbound request의 User-Agent identity를 stamp/upgrade 한다.  
   출처:  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry/AgentFrameworkUserAgentPolicy.cs#L10-L27  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/HostedAgentUserAgentPolicy.cs#L12-L35  

### 구현상 특징

- workflow observability는 일반 agent observability와 분리된 별도 API surface를 가진다.  
  출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/OpenTelemetryWorkflowBuilderExtensions.cs#L10-L18

- inner chat client auto-wiring은 convenience이지만 항상 강제되는 것은 아니다. 이미 instrumented 되었거나 caller가 원본 chat client를 그대로 쓰도록 opt-out 한 경우 decoration이 중단된다.  
  출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs#L86-L91  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs#L242-L259

- 이번 snapshot에서 직접 확인된 `.NET` feature telemetry production path는 Foundry/Hosted 위주다. generic provider 전반에 대해 Python 수준의 live `(feat=v...)` token을 실제로 삽입하는 production implementation은 이번 수집 근거만으로는 확인 불가다.

## Python 구현

### 핵심 구성요소

1. `observability.py`  
   - exports, settings, provider setup, metric views, tracer/meter helper, histograms, logger filter를 한곳에 둔다.  
   출처:  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L3-L14  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L91-L107  

2. `ObservabilitySettings`  
   - `enable_instrumentation`, `enable_sensitive_data`, `enable_console_exporters`, `vs_code_extension_port`와 sticky disable semantics를 관리한다.  
   출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L727-L880

3. `_telemetry.py`  
   - User-Agent identity, hosted prefix detection, process-global feature mask, live token formatting을 담당한다.  
   출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_telemetry.py#L18-L33  
   https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_telemetry.py#L72-L170

4. provider-specific feature usage hook  
   - OpenAI는 httpx request hook, Foundry는 Azure policy와 OpenAI default client wrapper 양쪽을 제공한다.  
   출처:  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/openai/agent_framework_openai/_feature_usage.py#L25-L56  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_feature_usage.py#L35-L59  

### 구현상 특징

- Python은 `.NET`보다 “관측성 bootstrap”을 더 중앙집중적으로 다룬다.
- logger, metrics, traces, feature telemetry가 다른 파일에 분리되어 있지만 운영 정책은 같은 상위 모듈군에서 일관되게 표현된다.
- `APP_INFO`와 `prepend_agent_framework_to_user_agent()`를 통해 framework identity가 기본 header merge 흐름에 자연스럽게 들어간다.  
  출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/openai/agent_framework_openai/_shared.py#L196-L200

## 테스트 근거

### .NET 테스트 근거

- `UseOpenTelemetry()`가 null builder에서 `ArgumentNullException`을 던지고, 정상 builder에서는 `OpenTelemetryAgent`를 반환하며, chaining도 유지됨을 unit test가 검증한다.  
  출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/OpenTelemetryAgentBuilderExtensionsTests.cs#L15-L39  
  https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/OpenTelemetryAgentBuilderExtensionsTests.cs#L83-L125

### Python 테스트 근거

- feature bitmask는 deduplication, stale-token removal, thread safety, registry consistency까지 검증된다.  
  출처:  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_telemetry.py#L72-L83  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_telemetry.py#L130-L168  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_telemetry.py#L171-L180  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_telemetry.py#L183-L218  

- observability tests는 역할별 semantic attribute, tool-execution span naming, log timestamp filter behavior를 검증한다.  
  출처:  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_observability.py#L45-L60  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_observability.py#L66-L95  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_observability.py#L106-L159  

### 확인 불가

- 이번 수집 근거만으로는 `.NET` 쪽의 “live feature bitmask token” 전용 unit test 본문은 확인 불가다.
- 이번 수집 근거만으로는 `.NET LoggingAgent` 전용 테스트 본문은 확인 불가다.
- 다만 production code가 lifecycle, sensitive-data boundary, no-op 조건을 매우 명시적으로 기술하고 있으므로 운영 계약은 코드 자체로는 충분히 판독 가능하다.

## 문서와 코드 차이

### 1. README의 “built-in OpenTelemetry integration” 표현과 실제 opt-in 경계

README는 framework가 built-in OpenTelemetry integration을 가진다고 넓게 설명한다. 이 설명은 방향성은 맞지만, 코드 기준으로는 특히 workflow telemetry가 기본 활성 상태가 아니라 `WithOpenTelemetry()` 호출 시에만 켜진다는 점이 더 정확하다. 따라서 운영 설계에서는 README의 표현보다 code-level opt-in semantics를 우선해야 한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/README.md#L50-L52  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/OpenTelemetryWorkflowBuilderExtensions.cs#L42-L44  

### 2. .NET feature telemetry 설계 문서와 production source의 직접 입증 범위 차이

ADR과 registry 문서는 .NET에도 feature-usage bitmask in User-Agent 모델이 있다고 설명한다. 그러나 이번 snapshot에서 직접 확인된 .NET production source는 `agent-framework-dotnet/{version}` 및 hosted prefix 업그레이드 경로를 직접 보여줄 뿐이며, Python과 같은 `(feat=v...)` live token stamping 구현은 이번 수집 근거에서 직접 확인되지 않았다. 따라서 문서-코드 차이 분석에서는 “설계 문서가 더 넓고, code evidence는 더 좁다”고 기록하는 것이 정확하다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0033-feature-usage-bitmask-user-agent.md#L21-L23  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0033-feature-usage-bitmask-user-agent.md#L57-L71  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/specs/feature-usage-bit-registry.md#L189-L247  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry/AgentFrameworkUserAgentPolicy.cs#L49-L66  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/HostedAgentUserAgentPolicy.cs#L61-L116  

### 3. FAQ 권고와 코드의 정합성

`TRANSPARENCY_FAQ`는 proper error handling, logging, OpenTelemetry 사용을 권고한다. 이 방향은 코드와 정합적이다. 다만 실제 운영 결정을 내릴 때는 FAQ보다 code-level defaults—예를 들어 `.NET Trace` 비권장, Python sticky disable, workflow telemetry opt-in—가 더 구체적이다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/TRANSPARENCY_FAQ.md#L83-L87  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/LoggingAgent.cs#L21-L29  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L1171-L1230  

## Java 설계 결정

### 결정 1. OpenTelemetry는 “bootstrap + wrapper” 이중 구조로 설계한다

Java는 Python처럼 provider/exporter/meter/tracer bootstrap을 중앙 모듈로 두고, `.NET`처럼 agent/workflow wrapper를 제공하는 이중 구조가 적합하다. 이렇게 해야 app-wide observability 초기화와 per-agent/per-workflow 계측 장식이 분리된다.  
근거:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L944-L1012  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgentBuilderExtensions.cs#L47-L57  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/OpenTelemetryWorkflowBuilderExtensions.cs#L53-L68  

### 결정 2. 민감 데이터 capture는 base instrumentation과 분리된 독립 opt-in으로 둔다

기본 계측은 token count, duration, operation name 등 메타데이터만 보내고, message content / tool args / tool results는 명시적 enable 없이는 절대 span/log에 싣지 않는다.  
근거:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs#L22-L31  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs#L127-L137  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L739-L748  

### 결정 3. Logging은 tracing과 별도 계층으로 유지한다

Java에서는 trace/span과 log를 같은 API로 숨기지 말고, `.NET LoggingAgent`처럼 lifecycle/debug log와 payload-bearing trace log를 분리해야 한다. 운영 환경에서는 payload log를 기본 차단하고, redaction-aware serializer를 교체 가능하게 해야 한다.  
근거:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/LoggingAgent.cs#L21-L29  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/LoggingAgent.cs#L176-L187  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L992-L1001  

### 결정 4. Feature telemetry는 Python 모델을 채택한다

Java는 `.NET` 이번 snapshot의 “versioned UA identity only” 상태보다 Python의 live feature-token 모델을 채택하는 편이 낫다. 즉:
- process-global bitmask
- request-time stamping
- approved-origin-only emission
- opt-out env var
- stale token stripping
- registry consistency test  
를 모두 포함해야 한다.  
근거:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_telemetry.py#L131-L170  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/openai/agent_framework_openai/_feature_usage.py#L48-L56  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_telemetry.py#L183-L218  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/docs/decisions/0033-feature-usage-bitmask-user-agent.md#L57-L71  

### 결정 5. Workflow observability는 category-level disable switch를 갖는다

Java workflow tracing은 build/run/executor/message category를 개별적으로 끌 수 있어야 한다. 이는 agent-level tracing보다 volume/PII surface가 더 넓기 때문이다.  
근거: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/Observability/WorkflowTelemetryOptions.cs#L24-L68

### 결정 6. Sticky disable semantics를 채택한다

운영자가 instrumentation을 껐는데 framework helper가 나중에 자동으로 다시 켜는 상황을 막기 위해, Python의 sticky disable semantics를 Java에도 그대로 가져오는 것이 적절하다.  
근거:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L785-L800  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L1171-L1230  

## 구체적인 acceptance scenarios

### Scenario 1. .NET agent telemetry wrapper 생성

- Given: 기본 `AIAgentBuilder`
- When: `UseOpenTelemetry()`를 적용하고 `Build()` 한다
- Then: 결과 agent는 `OpenTelemetryAgent`여야 한다.  
출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/OpenTelemetryAgentBuilderExtensionsTests.cs#L25-L39

### Scenario 2. .NET workflow telemetry는 기본 비활성

- Given: `WorkflowBuilder`
- When: `WithOpenTelemetry()`를 호출하지 않는다
- Then: workflow telemetry는 기본 활성 상태가 아니어야 하며, telemetry collection은 explicit opt-in 이어야 한다.  
출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows/OpenTelemetryWorkflowBuilderExtensions.cs#L42-L44

### Scenario 3. .NET sensitive telemetry opt-in

- Given: `OpenTelemetryAgent`
- When: `EnableSensitiveData`를 명시적으로 켜지 않는다
- Then: 기본 telemetry에는 raw message content / function args / function results가 포함되지 않아야 한다.  
출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs#L124-L137

### Scenario 4. .NET logging은 Debug와 Trace를 분리한다

- Given: `LoggingAgent`
- When: logger가 `Debug`지만 `Trace`는 꺼져 있다
- Then: method lifecycle만 로그로 남고 payload는 남지 않아야 한다.  
출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/LoggingAgent.cs#L61-L71  
https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/LoggingAgent.cs#L77-L87

### Scenario 5. Python metric view filtering

- Given: `create_metric_views()`
- When: default metric views를 구성한다
- Then: `agent_framework*`와 `gen_ai*` instrument만 유지되고 나머지는 drop 되어야 한다.  
출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L701-L715

### Scenario 6. Python sticky disable

- Given: `disable_instrumentation()`가 먼저 호출되었다
- When: `enable_sensitive_telemetry()`를 `force=False`로 호출한다
- Then: framework는 instrumentation을 다시 켜지 않고, ignored 상태를 유지해야 한다.  
출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L1158-L1164  
https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/observability.py#L1171-L1194

### Scenario 7. Python feature token live update

- Given: `CORE_AGENT`만 mark 된 상태
- When: `apply_feature_token("foundry-hosting/agent-framework-python/1.0")`를 호출한 뒤 `CORE_WORKFLOW`를 추가로 mark 하고 다시 `apply_feature_token(...)` 한다
- Then: token은 먼저 `v1.1`, 그다음 `v1.5`로 갱신되어야 한다.  
출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_telemetry.py#L130-L141

### Scenario 8. Python approved-origin-only feature stamping

- Given: OpenAI/Foundry request hook
- When: 실제 request URL이 승인된 HTTPS origin이 아니다
- Then: request header에서는 feature token이 제거되어야 한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/openai/agent_framework_openai/_feature_usage.py#L48-L54  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_feature_usage.py#L48-L58  

### Scenario 9. .NET hosted User-Agent 업그레이드

- Given: outbound request header에 bare `agent-framework-dotnet/{version}`가 이미 있다
- When: `HostedAgentUserAgentPolicy`가 적용된다
- Then: header는 `foundry-hosting/agent-framework-dotnet/{version}` 형태로 in-place 업그레이드되어야 하고, 중복 prefix가 생기면 안 된다.  
출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Foundry.Hosting/HostedAgentUserAgentPolicy.cs#L72-L109

## Source inventory

### Production source: .NET

- `dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs`  
  - 목적, security note, sensitive data opt-in, invoke flow, activity tagging, inner auto-wiring  
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

### 테스트

- `.NET` OpenTelemetry builder tests  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.UnitTests/OpenTelemetryAgentBuilderExtensionsTests.cs#L15-L125

- Python telemetry tests  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_telemetry.py#L38-L57  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_telemetry.py#L72-L83  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_telemetry.py#L130-L218

- Python observability tests  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/core/test_observability.py#L45-L159

### 문서 / 설계 근거

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