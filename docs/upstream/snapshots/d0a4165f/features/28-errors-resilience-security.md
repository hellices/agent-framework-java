# 28. Errors, Resilience, Security

## 상태

- 문서 상태: upstream snapshot 분석 문서
- 기준 스냅샷: `d0a4165f170193ba1d026a259af40d35bb7eaefe`
- 분석 범위: error taxonomy, validation boundary, cancellation, timeout, cleanup, retry/resilience ownership, shell/tool/MCP security controls, policy guardrail 한계
- 비범위:
  - OpenTelemetry, feature telemetry, logging/sensitive data는 observability 문서 소유
  - serialization/versioning의 전체 체계는 session 문서 소유이며, 본 문서는 validation boundary 예시로만 최소한 언급한다

## 스냅샷 요약

이 스냅샷에서 Python은 **framework-wide 예외 계층**을 제공하고, validation/programming error는 built-in 예외로 남기며, domain failure만 Agent Framework 전용 예외로 승격하는 규칙을 문서와 코드 양쪽에서 명시한다. 반면 .NET은 이번 수집 근거 기준으로는 **패키지별 로컬 예외 계층**이 중심이며, `ArgumentException`, `InvalidOperationException` 같은 built-in 예외도 적극 사용한다. 즉 Python은 “공통 taxonomy 우선”, .NET은 “도메인별 taxonomy + built-in 혼용”에 가깝다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/exceptions.py#L15-L39  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/exceptions.py#L44-L146  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L220-L307  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative/Exceptions/DeclarativeWorkflowException.cs#L7-L35  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Purview/Exceptions/PurviewException.cs#L7-L25  

취소/타임아웃/정리(cleanup) 경로는 양쪽 모두 비교적 명확하다. Python declarative HTTP executor는 `CancelledError`를 잡지 않고 그대로 전파해 cancellation semantics를 보존하며, shell executor는 process tree 전체를 죽이는 cleanup을 수행한다. .NET shell은 stateless timeout에서 exit code `124`와 `TimedOut=true`를 표준화하고, persistent shell에서는 먼저 graceful interrupt로 세션을 살리려 한 뒤 실패하면 세션을 teardown 한다. MCP long-running task 쪽도 .NET은 local cancellation 시 원격 task cancel을 best-effort 로 시도한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_executors_http.py#L154-L156  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_executors_http.py#L206-L221  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_executor.py#L34-L93  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_killtree.py#L50-L133  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellExecutor.cs#L60-L88  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L260-L309  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellSession.cs#L353-L419  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/TaskAwareMcpClientAIFunction.cs#L81-L145  

보안 경계는 특히 shell/tool 영역에서 매우 노골적으로 정의된다. Python과 .NET 모두 regex 기반 policy/delist는 **보안 경계가 아니라 UX guardrail** 이라고 명시하고, 실제 security boundary를 **approval-in-the-loop** 와 **sandbox/container isolation** 으로 둔다. Python은 여기에 더해 FIDES 기반 content labeling과 safe declarative state path traversal을 제공하고, .NET은 single-session ownership, approval wrapper 기본값, tool-name collision 주의 같은 실행 경계 쪽 경고가 더 두드러진다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_policy.py#L5-L35  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_tool.py#L118-L166  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/security.py#L3-L13  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/security.py#L43-L66  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/security.py#L91-L124  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_declarative_base.py#L385-L405  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_declarative_base.py#L419-L438  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellPolicy.cs#L99-L141  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellExecutor.cs#L75-L88  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L15-L17  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L32-L50  

이 스냅샷의 수집 근거 안에서는 **runtime 전반에 걸친 공통 retry decorator** 는 직접 확인되지 않았다. 대신 test/CI 차원에서는 `.NET`의 `RetryFact` 와 Python integration workflow의 `--retries` 설정이 분명히 존재한다. 따라서 현재 snapshot에서 retry ownership은 “framework core가 보편적으로 자동 retry 해준다”기보다 “호출자/테스트/개별 provider 레벨에서 명시적으로 소유한다”는 해석이 더 안전하다. production-wide generic retry layer의 존재는 이번 수집 근거만으로는 확인 불가다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/AgentConformance.IntegrationTests/RunTests.cs#L20-L23  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/AgentConformance.IntegrationTests/RunStreamingTests.cs#L20-L23  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-integration-tests.yml#L61-L67  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-integration-tests.yml#L95-L101  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-integration-tests.yml#L235-L248  

## 원본 기능 목적과 경계

### 1. Error taxonomy의 목적

Error taxonomy의 목적은 단순히 “예외를 많이 정의한다”가 아니라, **실패 원인을 layer별로 포착 가능한 형태로 분리**하는 데 있다. Python은 agent/chat client/integration/tool/workflow/middleware/settings 에 대한 공통 예외 골격을 제공하고, validation bug와 domain failure를 분리하는 규칙까지 제시한다. .NET은 이번 근거에서 Purview, Declarative Workflows 등 패키지별 예외 트리를 보여주며, 그 외 일반적인 상태/입력 검증은 built-in 예외에 맡긴다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/exceptions.py#L15-L39  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/exceptions.py#L44-L146  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/exceptions.py#L169-L263  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L220-L307  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative/Exceptions/DeclarativeActionException.cs#L7-L35  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative/Exceptions/DeclarativeModelException.cs#L7-L35  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Purview/Exceptions/PurviewRequestException.cs#L7-L40  

### 2. Validation boundary의 목적

Validation boundary는 “어떤 오류를 framework-specific exception 으로 감싸고, 어떤 오류를 built-in 으로 그대로 드러낼 것인가”를 정하는 운영 계약이다. Python coding standard는 constructor argument, invalid state, configuration mistakes에 대해 `ValueError`, `TypeError`, `RuntimeError` 같은 built-in 을 사용하고, domain-level external failure만 AF exception branch로 올리라고 한다. .NET 수집 근거도 유사하게 option 검증이나 잘못된 객체 상태에 built-in 예외를 사용한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L223-L227  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L278-L307  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L87-L98  

### 3. Cancellation / timeout / cleanup의 목적

이 계층의 목적은 “실패를 빨리 감지한다”가 아니라, **중단 의도를 보존하고**, **자식 프로세스나 원격 task를 남기지 않으며**, **가능하면 session을 복구 가능 상태로 남기는 것**이다. Python declarative executor는 cancel을 예외 번역에서 제외해 의도를 보존하고, shell executor는 tree kill을 수행한다. .NET shell은 stateless와 persistent를 구분해서 cleanup 전략을 달리하고, MCP task는 remote cancel을 best-effort 로 수행한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_executors_http.py#L154-L156  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_executor.py#L52-L53  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_killtree.py#L3-L17  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellExecutor.cs#L60-L67  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/TaskAwareMcpClientAIFunction.cs#L110-L145  

### 4. Retry / resilience ownership의 경계

이 snapshot에서 직접 확인되는 resilience는 주로 **번역(wrapping), timeout, cancel propagation, cleanup, fallback** 이다. 예를 들어 Python declarative HTTP executor는 transport failure를 `DeclarativeActionError`로 감싸되 retry하지 않고, .NET MCP wrapper는 task API가 없을 때 non-augmented call로 fallback 한다. 반면 “몇 번 재시도할 것인가”에 대한 공통 runtime policy는 직접 확인되지 않는다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_executors_http.py#L206-L221  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/TaskAwareMcpClientAIFunction.cs#L87-L107  

### 5. Shell / tool / MCP security control의 목적

이 범위의 목적은 기능 봉쇄 그 자체보다 **실행 경계 명시**, **approval 기본값**, **sandbox 권고**, **residual risk를 숨기지 않는 계약**에 있다. Python은 shell policy 한계, unsafe opt-out acknowledgement, FIDES labeling, declarative path safety를 보여준다. .NET은 shell approval wrapping, persistent executor의 single-session ownership, auto-approval 충돌 경고, Docker isolation을 명시한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_policy.py#L8-L35  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_tool.py#L127-L166  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/security.py#L43-L66  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/security.py#L91-L124  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellExecutor.cs#L75-L88  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L32-L50  

## 공개 API

### .NET 공개 API

1. `DeclarativeWorkflowException`, `DeclarativeActionException`, `DeclarativeModelException`  
   - declarative workflow/action/model 오류를 분리한다.  
   출처:  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative/Exceptions/DeclarativeWorkflowException.cs#L7-L35  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative/Exceptions/DeclarativeActionException.cs#L7-L35  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative/Exceptions/DeclarativeModelException.cs#L7-L35  

2. `PurviewException` 계열  
   - `PurviewAuthenticationException`, `PurviewPaymentRequiredException`, `PurviewRateLimitException`, `PurviewRequestException` 등으로 Purview failure mode를 분리한다.  
   출처:  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Purview/Exceptions/PurviewException.cs#L7-L25  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Purview/Exceptions/PurviewAuthenticationException.cs#L7-L26  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Purview/Exceptions/PurviewPaymentRequiredException.cs#L7-L24  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Purview/Exceptions/PurviewRateLimitException.cs#L7-L26  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Purview/Exceptions/PurviewRequestException.cs#L11-L40  

3. `ShellExecutor.RunAsync(...)`, `ShellExecutor.AsAIFunction(..., bool requireApproval = true)`  
   - shell execution과 approval-wrapped AIFunction 노출 계약을 정의한다.  
   출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellExecutor.cs#L49-L91

4. `LocalShellExecutorOptions`  
   - `Policy`, `Timeout`, `AcknowledgeUnsafe` 등 실행 경계를 설정한다.  
   출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutorOptions.cs#L12-L90

5. `ShellPolicy`  
   - deny/allow/custom callback 기반 policy pre-filter 이다.  
   출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellPolicy.cs#L99-L180  
   https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellPolicy.cs#L183-L220

6. `ShellResult`  
   - `ExitCode`, `Duration`, `Truncated`, `TimedOut`를 가진 결과 envelope 이다.  
   출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellResult.cs#L8-L23

### Python 공개 API

1. `AgentFrameworkException` 및 branch hierarchy  
   - `AgentException`, `ChatClientException`, `IntegrationException`, `ToolException`, `MiddlewareException`, `WorkflowException`, `SettingNotFoundError` 등.  
   출처:  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/exceptions.py#L15-L39  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/exceptions.py#L44-L146  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/exceptions.py#L169-L263  

2. `UserInputRequiredException`  
   - sub-agent/tool invocation에서 approval/oauth 같은 user-input request를 generic tool error로 삼키지 않고 상위 응답으로 전파하기 위한 예외다.  
   출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/exceptions.py#L184-L210

3. `OpenAIContentFilterException`  
   - Azure OpenAI content filter 정보를 구조화해 보존한다.  
   출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/openai/agent_framework_openai/_exceptions.py#L49-L90

4. `PurviewServiceError` 계열  
   - `PurviewAuthenticationError`, `PurviewPaymentRequiredError`, `PurviewRateLimitError`, `PurviewRequestError` 등.  
   출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/purview/agent_framework_purview/_exceptions.py#L15-L32

5. `ShellPolicy`, `LocalShellTool`  
   - `denylist`, `allowlist`, `custom`, `approval_mode`, `acknowledge_unsafe`, `timeout`을 노출한다.  
   출처:  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_policy.py#L70-L125  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_tool.py#L141-L176  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_tool.py#L232-L277  

6. `agent_framework.security` export surface  
   - `SecureMCPToolProxy`, label/middleware/security tool API가 export 된다. 다만 세부 구현 동작 전체는 이번 수집 근거만으로는 확인 불가다.  
   출처:  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/security.py#L43-L66  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L98-L103  

## 상세 실행 흐름

### 1. Python error taxonomy 및 validation boundary 흐름

1. 모든 AF branch 예외는 `AgentFrameworkException`을 공통 부모로 가진다.  
2. base constructor는 기본적으로 debug log를 남기지만, `log_level=None`이면 조용한 control-flow 예외로도 사용할 수 있다.  
3. coding standard는 invalid constructor arg, wrong object state, request validation failure 같은 경우 built-in 예외를 우선 사용하고, external/domain failure만 AF branch 예외로 승격하라고 규정한다.  
4. 따라서 Python 구현의 validation boundary는 “프로그래밍 오류/설정 오류는 built-in, 서비스/도메인 실패는 AF taxonomy”로 읽힌다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/exceptions.py#L15-L39  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L220-L227  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L278-L307  

### 2. Python user-input / tool escalation 흐름

`UserInputRequiredException`은 sub-agent/tool이 `oauth_consent_request`, `function_approval_request` 같은 content item을 emit 했을 때 그것을 generic tool failure로 변환하지 않기 위한 control-flow 예외다. 이 예외는 `contents`를 보존하고 `log_level=None`으로 생성되어 노이즈 로그를 남기지 않는다.  
출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/exceptions.py#L184-L210

### 3. Python declarative HTTP executor의 cancellation / wrapping 흐름

1. executor는 request를 dispatch 한다.  
2. `httpx.TimeoutException` 또는 `TimeoutError`는 `DeclarativeActionError`로 번역한다.  
3. 일반 `httpx.HTTPError`와 arbitrary transport exception도 같은 domain error로 감싼다.  
4. 그러나 `asyncio.CancelledError`는 intentionally 잡지 않으므로 cancellation은 원형 그대로 상위로 전파된다.  
5. 이 구현은 “실패 translation”과 “취소 보존”을 분리한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_executors_http.py#L154-L156  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_executors_http.py#L206-L221  

### 4. Python shell timeout / cleanup 흐름

1. `run_stateless()`는 subprocess를 띄우고 `asyncio.wait_for(proc.communicate(), timeout=...)` 로 stdout/stderr 수집을 기다린다.  
2. timeout이면 `kill_process_tree(proc)` 를 호출한다.  
3. kill 후에도 가능한 queued output 을 drain 해서 result envelope에 넣는다.  
4. `kill_process_tree()` 는 psutil 경로가 있으면 parent+descendants를 terminate 후 kill 하고, 없으면 stdlib fallback 을 사용한다.  
5. Windows fallback에서는 `taskkill.exe` 경로를 절대경로로 resolve 해서 PATH poisoning 위험을 줄인다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_executor.py#L34-L93  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_killtree.py#L10-L17  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_killtree.py#L39-L61  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_killtree.py#L64-L133  

### 5. .NET shell validation / timeout / cleanup 흐름

1. `LocalShellExecutor` constructor는 invalid options 를 먼저 걸러낸다.
   - `MaxOutputBytes <= 0` 이면 `ArgumentOutOfRangeException`
   - `Shell` 과 `ShellArgv` 를 동시에 주면 `ArgumentException`
2. stateless run에서는 linked cancellation token 으로 caller cancellation과 timeout 을 합친다.  
3. timeout 발생 시 process tree 를 kill 하고, exit code `124`, `TimedOut=true` 를 result에 넣는다.  
4. persistent session에서는 timeout 시 먼저 current command 를 interrupt 하여 세션을 살릴 기회를 준다.  
5. 그 후 sentinel을 다시 확인해 복구되면 same session을 유지하고, 그렇지 않으면 session 을 닫아 다음 호출이 새 shell을 띄우게 한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L87-L98  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L246-L309  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellSession.cs#L353-L419  

### 6. .NET MCP long-running task resilience 흐름

1. wrapper는 `DefaultTimeToLive`가 설정되어 있으면 task metadata TTL을 붙인다.  
2. `CallToolAsTaskAsync()` 가 `MethodNotFound`를 던지면 server capability drift 로 간주하고 non-augmented tool call 로 fallback 한다.  
3. poll 결과가 `Completed`면 결과를 가져오고, `Cancelled`면 `OperationCanceledException` 을 던진다.  
4. local cancellation 시 `CancelRemoteTaskOnLocalCancellation` 옵션이 켜져 있으면 remote cancel 을 5초 budget 내에서 best-effort 로 시도한다.  
5. cancel 실패는 원래 cancellation reason 을 가리지 않는다.  
출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/TaskAwareMcpClientAIFunction.cs#L81-L145

### 7. Python declarative state path safety 흐름

1. state traversal은 dict path 와 object attribute path를 구분한다.  
2. dict lookup은 arbitrary non-empty key를 허용하지만, object attribute access는 safe declarative identifier 형식만 허용한다.  
3. `__class__` 같은 위험 segment는 warning 후 default 반환으로 차단된다.  
4. `Workflow.Inputs` 는 read-only 로 간주되어 수정 시 `ValueError` 를 던진다.  
이 경로는 direct code execution control 이 아니라, declarative workflow state access 를 이용한 reflective exfiltration 완화를 위한 security control 이다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_declarative_base.py#L385-L405  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_declarative_base.py#L419-L438  

### 8. Retry / resilience ownership 흐름

수집 근거에서 직접 확인되는 runtime resilience는:
- 실패를 domain error로 번역
- timeout envelope 작성
- process/session cleanup
- task capability mismatch fallback
- remote cancel best-effort  
까지다.  
반면 “자동 재시도”는 이번 수집된 runtime production source에서 cross-cutting 정책으로 직접 확인되지 않았다. retry가 확인되는 곳은 test/CI 계층이다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_executors_http.py#L206-L221  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/TaskAwareMcpClientAIFunction.cs#L98-L107  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/AgentConformance.IntegrationTests/RunTests.cs#L20-L23  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-integration-tests.yml#L61-L67  

## 상태 및 구성

### Error taxonomy 상태

- Python core에는 공통 예외 계층이 존재한다.
- Python provider/domain package는 이 계층을 기반으로 특화 예외를 추가한다.
- .NET에서는 이번 수집 기준 package-local exception trees가 직접 확인된다.
- `.NET` 전역 공통 예외 taxonomy가 Python 수준으로 정의되어 있는지는 이번 수집 근거만으로는 확인 불가다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/exceptions.py#L44-L146  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/purview/agent_framework_purview/_exceptions.py#L15-L32  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Purview/Exceptions/PurviewException.cs#L7-L25  

### Timeout 기본 구성

- `.NET` local shell의 `DefaultTimeout` 상수는 30초이지만, `LocalShellExecutorOptions.Timeout` 의 기본값은 `null` 이며 이는 “timeout 비활성”이다. 즉 30초는 권장값이지 자동 기본값이 아니다.  
  출처:  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L54-L60  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutorOptions.cs#L75-L80  

- Python `LocalShellTool` 은 생성자 surface에서 `timeout: float | None = 30.0` 을 가진다.  
  출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_tool.py#L141-L156

### Approval / unsafe opt-out 기본 구성

- Python `LocalShellTool` 은 기본 `approval_mode="always_require"` 이고, `never_require` 로 바꾸려면 `acknowledge_unsafe=True` 가 필요하다.  
  출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_tool.py#L127-L166

- `.NET ShellExecutor.AsAIFunction()` 은 `requireApproval=true` 가 기본값이다.  
  출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellExecutor.cs#L75-L88

### Policy 기본 구성

- Python과 .NET 모두 **default empty policy** 를 사용한다.
- 즉 operator가 denylist/allowlist를 명시적으로 주지 않으면 non-empty command는 기본 허용이다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_policy.py#L23-L35  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_policy.py#L85-L86  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellPolicy.cs#L121-L127  

## 오류와 보안

### Validation error와 domain error의 구분

Python coding standard는 validation/programming mistakes에 built-in 예외를 쓰고, 외부 서비스/도메인 failure에 AF exception branch를 쓰도록 요구한다. 이 규칙은 예외 taxonomy의 핵심 경계다. `.NET`도 shell option validation에 built-in 예외를 사용한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L223-L227  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L91-L98  

### Cancellation은 실패와 다르게 취급된다

Python declarative HTTP executor는 취소를 domain error 로 감싸지 않는다. `.NET` MCP wrapper도 remote task 가 `Cancelled` status로 끝나면 `OperationCanceledException` 을 사용한다. 이런 구현은 cancellation을 ordinary failure와 다른 제어 신호로 다룬다는 뜻이다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_executors_http.py#L154-L156  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/TaskAwareMcpClientAIFunction.cs#L116-L126  

### Timeout은 별도 결과 envelope를 가진다

`.NET` shell은 timeout을 예외로만 끝내지 않고 `ShellResult.TimedOut=true`, `ExitCode=124`로 표준화한다. Python shell도 timeout 후 output을 가능한 한 수습해 envelope로 반환하려고 한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellResult.cs#L11-L23  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellExecutor.cs#L61-L64  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_executor.py#L70-L93  

### Cleanup은 security boundary가 아니라 post-failure hygiene 이다

process tree kill, session teardown, remote task cancel은 모두 “손상 확산 방지”와 “자원 누수 방지”를 위한 resilience 동작이다. 이것이 곧 authorization/security control 은 아니다. 실제 security boundary는 approval/sandbox다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_killtree.py#L3-L17  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L43-L50  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_policy.py#L31-L35  

### Policy guardrail의 한계

Python과 .NET은 모두 regex pattern filtering 이 아래 우회 기법에 취약하다고 직접 설명한다.
- variable expansion
- interpreter escape
- command substitution / encoded payload
- envvar splicing
- alternative tools
- PowerShell native destructive verbs  
즉 정책 필터는 operator UX guardrail이지, hostile model input을 막는 defense가 아니다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_policy.py#L8-L35  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellPolicy.cs#L104-L118  

### Tool/MCP security control 범위

- Python은 `SecureMCPToolProxy` 를 export 하고 FIDES security surface에 포함시키지만, detailed runtime behavior는 이번 수집 근거만으로는 확인 불가다.  
- `.NET` MCP에서 직접 확인되는 것은 TTL, capability drift fallback, remote cancel 같은 operational safety 동작이며, 별도의 MCP authorization/security model은 이번 수집 근거만으로는 확인 불가다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/security.py#L43-L66  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L98-L103  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/TaskAwareMcpClientAIFunction.cs#L81-L145  

## .NET 구현

### 1. 패키지별 예외 계층

이번 snapshot에서 직접 확인된 .NET 구현은 `PurviewException` 과 `DeclarativeWorkflowException` 처럼 패키지별 예외 계층이 핵심이다. `PurviewRequestException` 은 HTTP status code를 보존해 caller가 메시지 parsing 없이도 분기할 수 있게 한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Purview/Exceptions/PurviewException.cs#L7-L25  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Purview/Exceptions/PurviewRequestException.cs#L13-L23  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative/Exceptions/DeclarativeWorkflowException.cs#L7-L35  

### 2. Built-in validation 예외 사용

`LocalShellExecutor`는 option validation에서 custom framework exception을 만들지 않고, `ArgumentOutOfRangeException` 과 `ArgumentException` 을 사용한다. 이는 “개발자 입력 오류는 built-in 으로 드러낸다”는 Python 쪽 규칙과 결과적으로 유사한 운영 경계를 형성한다.  
출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L87-L98

### 3. Shell security boundary와 session ownership

`.NET` shell 구현은 다음을 분명히 적는다.
- local shell은 host에서 직접 실행되므로 approval-in-the-loop가 security boundary다.
- persistent executor는 single conversation/single user 소유다.
- shared mutable state 때문에 singleton/shared 사용은 부적절하다.
- `AsAIFunction()` 의 approval wrapping이 기본이다.
- tool name collision 으로 auto-approval rule이 잘못 적용될 수 있으니 이름 선택에 주의해야 한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L15-L17  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L32-L50  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellExecutor.cs#L34-L47  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellExecutor.cs#L75-L88  

### 4. Timeout / cleanup semantics

`.NET` shell은 timeout을 표준화된 envelope로 반환하고, persistent mode에서는 세션 복구를 시도한다. 이는 단순 process kill보다 더 강한 운영 안정성 계약이다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L260-L309  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellSession.cs#L356-L419  

### 5. MCP long-running task resilience

`.NET` MCP 쪽은 취소와 capability drift에 대한 방어적 구현이 들어가 있다. task API가 없으면 inner call 로 fallback 하고, local cancellation이면 remote cancel 을 best-effort 로 시도한다.  
출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/TaskAwareMcpClientAIFunction.cs#L87-L145

## Python 구현

### 1. 공통 예외 계층과 분기 규칙

Python은 공통 예외 계층과 branch symmetry가 분명하다.
- `AgentException`
- `ChatClientException`
- `IntegrationException`
- `WorkflowException`
- `ToolException`
- `MiddlewareException`  
각 branch는 `InvalidAuth`, `InvalidRequest`, `InvalidResponse`, `ContentFilter` 같은 반복 패턴을 공유한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/exceptions.py#L44-L146  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L220-L276  

### 2. Built-in validation 우선 원칙

Python coding standard는 configuration/parameter validation에 built-in 예외를 쓰라고 직접 규정한다. 따라서 API 사용자에게는 “validation bug와 domain failure가 같은 예외 표면으로 섞이지 않는다”는 장점이 있다.  
출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L223-L227  
https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L278-L307

### 3. Content filter / integration 특화 예외

`OpenAIContentFilterException` 은 content filter code와 per-category filter results를 구조적으로 노출한다. Purview 쪽도 auth/payment/rate-limit/request를 별도 타입으로 나눈다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/openai/agent_framework_openai/_exceptions.py#L55-L90  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/purview/agent_framework_purview/_exceptions.py#L15-L32  

### 4. Declarative cancellation 보존

Python declarative HTTP executor는 cancel을 wrapping하지 않음으로써 workflow cancellation semantics를 보존한다. 이 설계는 “cancel은 실패가 아니다”라는 boundary를 production code 수준에서 분명히 한다.  
출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_executors_http.py#L154-L156  
https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_executors_http.py#L214-L221

### 5. Shell/tool security posture

Python shell tool은 policy를 pre-filter로만 두고, 실제 경계를 approval와 sandbox로 둔다. `approval_mode="never_require"` 는 명시적 `acknowledge_unsafe=True` 없이는 허용되지 않는다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_policy.py#L8-L35  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_tool.py#L127-L166  

### 6. FIDES와 state path safety

Python은 shell/tool 밖에서도 content labeling과 declarative state path safety를 보안 제어로 제공한다. 다만 FIDES 관련 API는 experimental feature stage다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/security.py#L91-L124  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/security.py#L212-L260  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_declarative_base.py#L392-L405  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L98-L103  

## 테스트 근거

### .NET 테스트 근거

- `ShellPolicy` default construction 은 non-empty command를 허용한다.
- operator denylist를 넣어도 `${RM:=rm} -rf /` 같은 우회가 가능함을 테스트가 의도적으로 문서화한다.
- timeout 시 `TimedOut=true`, `ExitCode=124` 가 보장된다.
- rejected command 는 `ShellCommandRejectedException` 을 던진다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Tools.Shell.UnitTests/LocalShellExecutorTests.cs#L31-L63  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Tools.Shell.UnitTests/LocalShellExecutorTests.cs#L147-L165  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Tools.Shell.UnitTests/LocalShellExecutorTests.cs#L180-L231  

### Python 테스트 근거

- `ShellPolicy()` 기본값이 empty denylist 임을 테스트한다.
- `LocalShellTool(approval_mode="never_require")` 는 `acknowledge_unsafe` 없으면 실패한다.
- representative denylist bypass가 계속 허용됨을 의도적으로 검증해 “guardrail이지 boundary가 아니다”를 테스트가 직접 문서화한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/tests/test_policy.py#L23-L68  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/tests/test_policy.py#L71-L80  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/tests/test_security.py#L57-L107  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/tests/test_security.py#L124-L165  

- declarative state path safety tests는 dunder-based reflective escape, read-only input mutation 차단, valid dict-key traversal 보존을 함께 검증한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/tests/test_declarative_state_path_safety.py#L89-L129  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/tests/test_declarative_state_path_safety.py#L160-L218  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/tests/test_declarative_state_path_safety.py#L235-L258  

- core security tests는 FIDES feature stage marker, label combine behavior, variable store 동작, legacy label-key compatibility를 검증한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/test_security.py#L78-L99  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/test_security.py#L101-L144  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/test_security.py#L146-L199  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/test_security.py#L240-L252  

### Retry 관련 테스트/CI 근거

- `.NET` conformance/integration contract tests는 `RetryFact` 를 사용한다.
- Python integration workflow는 pytest retries 를 설정한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/AgentConformance.IntegrationTests/RunTests.cs#L20-L23  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/AgentConformance.IntegrationTests/RunStreamingTests.cs#L20-L23  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-integration-tests.yml#L61-L67  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-integration-tests.yml#L95-L101  

## 문서와 코드 차이

### 1. FAQ의 일반론과 shell code의 구체 경계 차이

`TRANSPARENCY_FAQ` 는 containerization/sandboxing, security measures, human oversight를 권고한다. 그러나 production code는 그보다 훨씬 더 강하게 “regex policy는 security control 이 아니다”라고 말하고, default denylist도 비워 둔다. 운영 문서 작성 시에는 FAQ의 일반론보다 code-level threat model을 우선해야 한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/TRANSPARENCY_FAQ.md#L77-L87  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_policy.py#L8-L35  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellPolicy.cs#L104-L127  

### 2. .NET default timeout에 대한 오해 가능성

`.NET` 코드에는 `LocalShellExecutor.DefaultTimeout = 30s` 상수가 있지만, 실제 option default는 `null` 이라서 timeout이 자동 적용되지 않는다. 즉 상수는 “권장값”이고 “effective default” 가 아니다. 이 차이는 API 표면만 보면 오해하기 쉽다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L54-L60  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutorOptions.cs#L75-L80  

### 3. Retry에 대한 범용 runtime 보장 여부

저장소에는 test/CI retry 설정이 분명히 있지만, 수집된 production source에서는 framework-wide generic retry layer를 직접 확인하지 못했다. 따라서 “framework가 자동 retry를 제공한다”는 문장을 쓰면 과장될 수 있다. 이번 snapshot 근거상 더 정확한 표현은 “cleanup/fallback/cancel handling은 있다, generic retry는 확인 불가”다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/AgentConformance.IntegrationTests/RunTests.cs#L20-L23  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-integration-tests.yml#L61-L67  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_executors_http.py#L206-L221  

## Java 설계 결정

### 결정 1. 공통 예외 taxonomy를 도입하되 validation은 built-in 계열을 유지한다

Java는 Python처럼 공통 branch hierarchy를 갖는 편이 좋다.
- `FrameworkException`
- `AgentException`
- `ChatClientException`
- `IntegrationException`
- `WorkflowException`
- `ToolException`  
그러나 invalid argument / invalid state / programmer error는 `IllegalArgumentException`, `IllegalStateException` 같은 built-in/standard 예외를 유지해야 한다. 이렇게 해야 validation boundary가 명확해진다.  
근거:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L223-L227  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/exceptions.py#L44-L146  

### 결정 2. Cancellation은 failure translation에서 제외한다

Java workflow/tool transport layer는 `CancellationException` 또는 thread interruption을 generic domain exception으로 감싸지 말아야 한다. cancellation은 ordinary failure와 다른 control signal 이므로 그대로 전파해야 한다.  
근거:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_executors_http.py#L154-L156  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/TaskAwareMcpClientAIFunction.cs#L116-L126  

### 결정 3. Timeout은 예외와 별도로 result envelope에 반영한다

shell/code execution류 도구는 timeout 여부를 결과 구조체에 명시적으로 포함해야 한다. 예를 들어 `timedOut=true`, `exitCode=124` 같은 규약은 상위 agent/harness가 deterministic하게 처리하기 쉽다.  
근거:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellResult.cs#L11-L23  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellExecutor.cs#L61-L64  

### 결정 4. Cleanup은 process-tree / remote-task 단위로 수행한다

Java shell/tool runtime은 parent process 하나만 죽이지 말고 descendants까지 정리해야 하며, long-running MCP task 류는 local cancel 시 remote cancel도 best-effort로 시도해야 한다.  
근거:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_killtree.py#L50-L133  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/TaskAwareMcpClientAIFunction.cs#L123-L145  

### 결정 5. Persistent executor는 session-owned resource로 제한한다

Java persistent shell/code executor는 single conversation/session 소유 resource로 설계해야 한다. singleton 공유를 허용하면 state leakage 와 command interleaving 문제가 생긴다.  
근거:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L32-L41  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellExecutor.cs#L34-L47  

### 결정 6. Shell policy는 보안 기능이 아니라 guardrail로만 문서화한다

Java에서도 regex-based policy/denylist/allowlist를 UX guardrail로만 문서화해야 한다. approval와 sandbox가 실제 security boundary라는 점을 문서/코드/테스트 모두에서 일치시켜야 한다.  
근거:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_policy.py#L8-L35  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellPolicy.cs#L104-L118  

### 결정 7. Unsafe opt-out은 항상 explicit acknowledgement를 요구한다

Java shell/tool APIs가 approval-required 기본값을 갖는다면, unattended mode를 켜는 opt-out path에는 explicit acknowledgement flag를 반드시 요구해야 한다.  
근거:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_tool.py#L159-L166  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellExecutor.cs#L75-L88  

### 결정 8. Declarative/state API가 있다면 safe path traversal을 강제한다

Java가 declarative workflow/state path interpolation을 제공한다면, dict/map keyed path와 object/property path를 구분하고 reflective escape (`__class__` 류)에 해당하는 경로를 금지해야 한다.  
근거:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_declarative_base.py#L392-L405  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/tests/test_declarative_state_path_safety.py#L95-L103  

### 결정 9. Retry는 core implicit behavior가 아니라 caller-owned policy로 둔다

이번 snapshot에서 generic runtime retry가 직접 입증되지 않았으므로, Java도 core library에서 silent retry를 넣기보다는 idempotency-aware provider adapter 또는 app policy에서 명시적으로 소유하게 하는 편이 안전하다. test/CI retry와 runtime retry를 분리해 문서화해야 한다.  
근거:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/AgentConformance.IntegrationTests/RunTests.cs#L20-L23  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-integration-tests.yml#L61-L67  

## 구체적인 acceptance scenarios

### Scenario 1. Validation 오류는 built-in 예외로 드러난다

- Given: `.NET LocalShellExecutor` 생성 시 `Shell` 과 `ShellArgv` 를 동시에 준다
- When: constructor가 실행된다
- Then: framework-specific shell exception이 아니라 `ArgumentException` 이 발생해야 한다.  
출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L91-L98

### Scenario 2. Timeout은 표준 envelope로 반환된다

- Given: `.NET LocalShellExecutor` 를 stateless mode, timeout 250ms로 생성한다
- When: `sleep 30` 또는 `Start-Sleep -Seconds 30` 을 실행한다
- Then: 결과는 `TimedOut=true`, `ExitCode=124` 이어야 한다.  
출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Tools.Shell.UnitTests/LocalShellExecutorTests.cs#L200-L210

### Scenario 3. Timeout 비활성은 실제로 비활성이다

- Given: `.NET LocalShellExecutor` 의 `Timeout = null`
- When: 짧은 `echo ok` 명령을 실행한다
- Then: result는 `TimedOut=false` 여야 한다.  
출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Tools.Shell.UnitTests/LocalShellExecutorTests.cs#L213-L226

### Scenario 4. Cancel은 domain error로 감싸지지 않는다

- Given: Python declarative HTTP executor
- When: underlying handler가 `asyncio.CancelledError` 를 일으킨다
- Then: executor는 이를 `DeclarativeActionError`로 wrapping하지 않고 그대로 전파해야 한다.  
출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_executors_http.py#L154-L156  
https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_executors_http.py#L214-L221

### Scenario 5. Shell policy 기본값은 허용이며 보안 경계가 아니다

- Given: 기본 `ShellPolicy()` 또는 denylist 없는 default shell policy
- When: `rm -rf /` 같은 non-empty command를 평가한다
- Then: policy 단독으로는 이를 거부하지 않을 수 있으며, 이것이 의도된 기본 동작이다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/tests/test_policy.py#L23-L28  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Tools.Shell.UnitTests/LocalShellExecutorTests.cs#L147-L153  

### Scenario 6. Policy bypass는 알려진 residual risk다

- Given: operator-supplied denylist
- When: `${RM:=rm} -rf /` 같은 variable indirection 기법을 사용한다
- Then: denylist는 이를 놓칠 수 있으며, 테스트는 이 residual risk를 의도적으로 고정한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/tests/test_security.py#L152-L165  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Tools.Shell.UnitTests/LocalShellExecutorTests.cs#L157-L165  

### Scenario 7. Unsafe unattended shell 사용은 explicit acknowledgement가 필요하다

- Given: Python `LocalShellTool`
- When: `approval_mode="never_require"` 이고 `acknowledge_unsafe=False` 이다
- Then: constructor는 `ValueError` 를 던져야 한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/tests/test_security.py#L99-L107  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_tool.py#L159-L166  

### Scenario 8. Declarative state reflective escape는 차단된다

- Given: Python declarative state에 plain object가 저장되어 있다
- When: `Local.obj.__class__.__init__.__globals__.os.environ` 같은 reflective path를 조회한다
- Then: result는 기본값/None이어야 하고, 민감 환경 데이터가 노출되면 안 된다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/tests/test_declarative_state_path_safety.py#L95-L103  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_declarative_base.py#L392-L405  

### Scenario 9. MCP local cancellation은 remote cancel을 best-effort 시도한다

- Given: `.NET` long-running MCP task wrapper와 `CancelRemoteTaskOnLocalCancellation=true`
- When: local cancellation token이 취소된다
- Then: wrapper는 최대 5초 budget 내에서 remote cancel 을 시도하고, 원래 cancellation 을 다시 던져야 한다.  
출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/TaskAwareMcpClientAIFunction.cs#L123-L145

## Source inventory

### Production source: Python

- `python/packages/core/agent_framework/exceptions.py`  
  - 공통 예외 계층, `UserInputRequiredException`, workflow/tool/middleware/settings branches  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/exceptions.py#L15-L39  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/exceptions.py#L44-L146  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/exceptions.py#L169-L263  

- `python/CODING_STANDARD.md`  
  - validation vs domain failure boundary, exception hierarchy 설계 원칙, no compatibility aliases  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L220-L307  

- `python/packages/openai/agent_framework_openai/_exceptions.py`  
  - structured content filter exception  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/openai/agent_framework_openai/_exceptions.py#L49-L90  

- `python/packages/purview/agent_framework_purview/_exceptions.py`  
  - Purview integration-specific exception tree  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/purview/agent_framework_purview/_exceptions.py#L15-L32  

- `python/packages/declarative/agent_framework_declarative/_workflows/_executors_http.py`  
  - timeout/error wrapping, cancel propagation  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_executors_http.py#L154-L156  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_executors_http.py#L206-L221  

- `python/packages/declarative/agent_framework_declarative/_workflows/_declarative_base.py`  
  - safe path traversal, read-only `Workflow.Inputs`  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_declarative_base.py#L385-L405  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_declarative_base.py#L419-L438  

- `python/packages/tools/agent_framework_tools/shell/_policy.py`  
  - threat model, no default patterns, guardrail 한계, evaluation order  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_policy.py#L3-L35  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_policy.py#L70-L125  

- `python/packages/tools/agent_framework_tools/shell/_tool.py`  
  - approval mode, acknowledge unsafe, timeout ownership, policy pre-filter  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_tool.py#L118-L166  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_tool.py#L232-L277  

- `python/packages/tools/agent_framework_tools/shell/_executor.py`  
  - stateless timeout and post-timeout draining  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_executor.py#L34-L93  

- `python/packages/tools/agent_framework_tools/shell/_killtree.py`  
  - process tree cleanup, PATH poisoning mitigation for `taskkill.exe`  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_killtree.py#L3-L17  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_killtree.py#L39-L61  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_killtree.py#L64-L133  

- `python/packages/core/agent_framework/security.py`  
  - security export surface, FIDES feature stage, label combination  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/security.py#L3-L13  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/security.py#L43-L66  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/security.py#L91-L124  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/security.py#L212-L260  

- `python/PACKAGE_STATUS.md`  
  - FIDES / security features experimental status  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L98-L103  

### Production source: .NET

- `dotnet/src/Microsoft.Agents.AI.Workflows.Declarative/Exceptions/*.cs`  
  - declarative workflow/action/model exception tree  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative/Exceptions/DeclarativeWorkflowException.cs#L7-L35  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative/Exceptions/DeclarativeActionException.cs#L7-L35  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative/Exceptions/DeclarativeModelException.cs#L7-L35  

- `dotnet/src/Microsoft.Agents.AI.Purview/Exceptions/*.cs`  
  - Purview domain exception tree, HTTP status preservation  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Purview/Exceptions/PurviewException.cs#L7-L25  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Purview/Exceptions/PurviewAuthenticationException.cs#L7-L26  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Purview/Exceptions/PurviewPaymentRequiredException.cs#L7-L24  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Purview/Exceptions/PurviewRateLimitException.cs#L7-L26  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Purview/Exceptions/PurviewRequestException.cs#L11-L40  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Purview/Exceptions/PurviewJobException.cs#L7-L25  

- `dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellExecutor.cs`  
  - approval default, single-session ownership, exit code 124 contract  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellExecutor.cs#L13-L47  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellExecutor.cs#L60-L91  

- `dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutorOptions.cs`  
  - timeout, policy, acknowledge unsafe  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutorOptions.cs#L12-L90  

- `dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellResult.cs`  
  - timeout result envelope  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellResult.cs#L8-L23  

- `dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellPolicy.cs`  
  - threat model, no default patterns, deny/allow/custom ordering  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellPolicy.cs#L99-L141  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellPolicy.cs#L149-L220  

- `dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs`  
  - validation, threat model, timeout handling, approval boundary  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L15-L17  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L32-L50  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L54-L60  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L87-L98  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L246-L309  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L318-L325  

- `dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellSession.cs`  
  - persistent session graceful interrupt vs teardown  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellSession.cs#L353-L419  

- `dotnet/src/Microsoft.Agents.AI.Mcp/TaskAwareMcpClientAIFunction.cs`  
  - task TTL, capability drift fallback, remote cancel best-effort  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/TaskAwareMcpClientAIFunction.cs#L81-L145  

### 테스트 및 CI

- `.NET` shell unit tests  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Tools.Shell.UnitTests/LocalShellExecutorTests.cs#L31-L63  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Tools.Shell.UnitTests/LocalShellExecutorTests.cs#L147-L165  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Tools.Shell.UnitTests/LocalShellExecutorTests.cs#L180-L231  

- Python shell/security/path-safety tests  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/tests/test_policy.py#L23-L80  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/tests/test_security.py#L57-L165  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/tests/test_declarative_state_path_safety.py#L89-L129  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/tests/test_declarative_state_path_safety.py#L160-L218  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/tests/test_declarative_state_path_safety.py#L235-L258  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/test_security.py#L78-L99  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/test_security.py#L101-L144  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/test_security.py#L146-L199  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/test_security.py#L240-L252  

- retry evidence in tests/CI  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/AgentConformance.IntegrationTests/RunTests.cs#L20-L23  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/AgentConformance.IntegrationTests/RunStreamingTests.cs#L20-L23  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-integration-tests.yml#L61-L67  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-integration-tests.yml#L95-L101  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-integration-tests.yml#L235-L248  

### 상위 문서

- general security / sandboxing guidance  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/TRANSPARENCY_FAQ.md#L77-L87