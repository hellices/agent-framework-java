# 29. Evaluation & Testing

## 상태

- 문서 상태: upstream snapshot 분석 문서
- 기준 스냅샷: `d0a4165f170193ba1d026a259af40d35bb7eaefe`
- 분석 범위: evaluation API/converters/checks/scoring, agent/workflow evaluation, generated/provider evaluator integration, unit/integration/conformance test 조직, golden/trace-driven 테스트, coverage gate
- 비범위:
  - observability, feature telemetry, logging/sensitive data는 observability 문서 소유
  - error taxonomy/timeout/security boundary는 errors-resilience-security 문서 소유
  - packaging/release/compatibility 일반론은 packaging-compatibility 문서 소유이나, evaluation 기능의 사용 가능 runtime/coverage gate와 직접 연결된 범위만 본 문서에서 제한적으로 다룬다

## 스냅샷 요약

이 스냅샷의 evaluation 체계는 양쪽 언어 모두 “provider-agnostic core + provider-specific evaluator integration”을 지향하지만 구현의 중심축은 다르다. `.NET`은 `IAgentEvaluator` 중심의 배치 평가 인터페이스, `AIAgent.EvaluateAsync(...)` 확장 메서드, `LocalEvaluator`, `EvalChecks`, `AgentEvaluationResults`를 통해 **agent response scoring**을 표준화한다. Python은 `agent_framework._evaluation`이 `EvalItem`, `EvalResults`, `LocalEvaluator`, `evaluate_agent`, `evaluate_workflow`, `@evaluator` wrapper, `AgentEvalConverter`까지 한 모듈에서 관리하고, 별도 `FoundryEvals`가 provider integration을 맡는다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/IAgentEvaluator.cs#L9-L32  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L20-L60  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/LocalEvaluator.cs#L10-L65  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L3-L33  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1518-L1622  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1630-L1831  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1834-L2025  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_foundry_evals.py#L3-L24  

Scoring surface도 다르다. `.NET`의 `LocalEvaluator`는 각 `EvalCheck` 결과를 `Microsoft.Extensions.AI.Evaluation`의 `BooleanMetric`으로 올리고, aggregate 결과는 `AgentEvaluationResults`가 `Passed`, `Failed`, `AllPassed`, `AssertAllPassed`, `AssertScoreAtLeast` 같은 quality gate API를 제공한다. Python은 `CheckResult`, `EvalScoreResult`, `EvalItemResult`, `EvalResults`를 자체적으로 정의하고, `raise_for_status()`, `assert_score_at_least()`, `assert_dimension_score_at_least()` 같은 gate를 제공한다. Python은 `@evaluator` wrapper를 통해 bool/float/dict/`CheckResult` 반환값을 모두 evaluation check로 승격할 수 있고, score가 숫자일 경우 기본 threshold `0.5` 규칙을 적용한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/LocalEvaluator.cs#L30-L65  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationResults.cs#L21-L70  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationResults.cs#L71-L159  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L304-L354  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L372-L543  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1036-L1058  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1354-L1404  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1415-L1497  

Workflow evaluation은 Python 쪽이 더 구체적이다. `evaluate_workflow()`는 workflow를 직접 실행하거나 기존 `WorkflowRunResult`를 받아, per-agent 결과와 overall 결과를 함께 계산하고 `sub_results`를 채운다. `.NET`에서는 `AgentEvaluationResults.SubResults`가 workflow evaluation을 위한 구조를 이미 갖고 있고 `AgentEvaluationExtensions`의 주석도 workflow runs를 언급하지만, 이번 snapshot에서 직접 확인된 public evaluation entrypoint는 agent/response 평가 메서드뿐이다. 따라서 Python은 **public workflow evaluation API가 명시적**이고, `.NET`은 **result model은 준비되어 있으나 이번 수집 근거 기준 explicit workflow evaluation public API는 직접 확인되지 않음**이 더 정확하다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1834-L2025  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L2033-L2055  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationResults.cs#L59-L69  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L13-L15  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L20-L210  

Testing 조직도 비대칭적이다. `.NET`은 project 이름 suffix 기반으로 `*UnitTests*`, `*IntegrationTests*`를 분리해 filtered solution을 생성하고, OpenAI hosting 영역에서는 `ConformanceTraces`를 golden corpus처럼 복사해서 disk-based trace-driven conformance tests를 실행한다. Python은 `packages/**/tests`를 중심으로 aggregate pytest를 돌리고, integration workflow를 provider 별 job으로 쪼개며, coverage workflow에서 85% threshold를 별도로 enforce한다. golden/trace-driven conformance evidence는 이번 수집 기준 `.NET` 쪽이 훨씬 명확하고, Python은 더 강한 package/provider test matrix와 coverage discipline을 보여준다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L223-L337  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests.csproj#L25-L29  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/ConformanceTestBase.cs#L21-L76  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L179-L192  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-tests.yml#L17-L60  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-integration-tests.yml#L41-L67  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-integration-tests.yml#L68-L101  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-test-coverage.yml#L15-L46  

## 원본 기능 목적과 경계

### 1. Evaluation API의 목적

Evaluation API의 기본 목적은 “agent나 workflow가 좋은 답을 냈는가”를 provider-neutral 한 형태로 표현하는 것이다. 이를 위해 `.NET`은 `EvalItem`과 `IAgentEvaluator`를 통해 query/response/conversation/tool/context/expected output을 evaluator가 소비할 수 있는 배치 입력으로 만든다. Python도 동일하게 `EvalItem`, `EvalResults`, `EvalItemResult`, `EvalScoreResult`를 제공해 provider별 결과를 공통 형태로 수용한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/IAgentEvaluator.cs#L9-L32  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/EvalItem.cs#L9-L153  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L181-L260  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L304-L354  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L372-L543  

### 2. Converter의 목적

Converter의 목적은 runtime message/response/tool 구조를 evaluator-friendly schema로 손실을 통제하며 변환하는 것이다. Python의 `AgentEvalConverter`는 text, image-like content, function call, function result를 evaluator가 읽을 수 있는 typed content dict로 바꾸고, tool definitions도 agent/default options/MCP function surface에서 추출한다. `.NET`은 별도 named converter class 대신 `BuildEvalItem()`과 `BuildItemsFromResponses()`에서 minimal conversation과 tool definitions를 evaluator input으로 조립한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L742-L830  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L847-L926  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L213-L269  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L335-L368  

### 3. Check와 scoring의 목적

Local evaluation은 빠른 inner-loop 검증과 CI smoke gate를 위한 것이다. `.NET`은 `EvalCheck` delegate와 `EvalChecks` built-ins를 제공하고, Python은 `keyword_check`, `tool_called_check`, `tool_calls_present`, `tool_call_args_match`, `@evaluator` wrapper를 제공한다. Scoring은 단순 bool pass/fail 에서 끝나지 않고, provider eval 결과나 rubric 기반 generated evaluator score까지 포함해 threshold gate로 이어진다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/EvalCheck.cs#L5-L10  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/EvalChecks.cs#L23-L105  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/EvalChecks.cs#L126-L217  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1036-L1277  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1354-L1497  

### 4. Agent / workflow evaluation의 목적

Agent evaluation은 query-response 수준의 품질과 tool usage correctness를 보려는 목적이고, workflow evaluation은 multi-agent orchestration 결과를 **per-agent + overall** 로 분리해 보려는 목적이다. Python은 `evaluate_workflow()` 에서 이것을 explicit 하게 구현하고, `.NET` 결과 타입은 workflow breakdown을 수용할 수 있도록 `SubResults`를 노출한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1834-L2025  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L2033-L2055  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationResults.cs#L59-L69  

### 5. Generated/provider evaluator integration의 목적

Generated/provider evaluator integration의 목적은 local boolean checks를 넘어, cloud judge / rubric evaluator / built-in provider evaluator를 동일 evaluation pipeline에 연결하는 것이다. `.NET`은 `IEvaluator`를 `MeaiEvaluatorAdapter` 로 감싸는 overload를 통해 `Microsoft.Extensions.AI.Evaluation` 생태계와 연결하고, Python은 `FoundryEvals`가 built-in evaluator short name, conversation/tool/ground-truth 요구사항, generated rubric evaluator reference를 관리한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L63-L101  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/GeneratedEvaluatorRef.cs#L6-L54  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_foundry_evals.py#L62-L103  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_foundry_evals.py#L105-L183  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_foundry_evals.py#L220-L260  

### 6. Test 조직의 목적

이 스냅샷에서 test 조직의 목적은 세 가지다.
1. 빠른 unit correctness 확인
2. provider-backed integration confidence 확보
3. wire-level conformance와 regression corpus 고정  
`.NET`은 suffix-based project 분리와 trace-driven conformance suite가 강점이고, Python은 package-wide aggregate tests, provider-sharded integration jobs, separate coverage workflow가 강점이다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L223-L337  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/ConformanceTestBase.cs#L21-L76  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L179-L192  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-tests.yml#L17-L60  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-integration-tests.yml#L41-L67  

### 7. Golden / trace-driven 테스트의 목적

Golden/trace-driven 테스트의 목적은 “실제 wire format과 framework output이 계속 맞는지”를 고정하는 것이다. `.NET` OpenAI hosting test project는 `ConformanceTraces/**`를 output으로 복사하고, test base가 disk에서 요청/응답 trace를 읽어 test server에 replay 한다. malformed request와 structured JSON output 같은 사례도 corpus 안에 포함된다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests.csproj#L25-L29  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/ConformanceTestBase.cs#L33-L76  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/OpenAIResponsesConformanceTests.cs#L20-L120  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/ConformanceTraces/Conversations/error_invalid_json/request.txt#L1-L6  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/ConformanceTraces/Responses/json_output/response.json#L1-L90  

## 공개 API

### .NET 공개 API

1. `IAgentEvaluator`  
   - batch-oriented evaluator interface  
   - `EvaluateAsync(IReadOnlyList<EvalItem> items, string evalName = ..., CancellationToken cancellationToken = default)`  
   출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/IAgentEvaluator.cs#L17-L32

2. `AIAgent.EvaluateAsync(...)` 확장 메서드들  
   - queries를 직접 실행해 평가
   - `IEvaluator`를 adapter로 감싸 평가
   - 여러 evaluator를 순차 실행
   - pre-existing `AgentResponse`를 재평가  
   출처:  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L20-L60  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L63-L101  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L104-L149  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L151-L210  

3. `EvalItem`  
   - query, response, conversation, tools, context, expected output, expected tool calls, splitter 보유  
   - multimodal conversation과 per-turn splitting 지원  
   출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/EvalItem.cs#L9-L170

4. `EvalCheck` / `EvalChecks`  
   - `KeywordCheck`, `ToolCalledCheck`, `ToolCallsPresent`, `ToolCallArgsMatch`, `NonEmpty` 등 built-in local checks  
   출처:  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/EvalCheck.cs#L5-L10  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/EvalChecks.cs#L23-L217  

5. `LocalEvaluator`  
   - local checks를 API call 없이 실행  
   출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/LocalEvaluator.cs#L10-L65

6. `AgentEvaluationResults`  
   - provider metadata, item results, workflow `SubResults`, detailed item scores, gate helpers  
   출처:  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationResults.cs#L21-L70  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationResults.cs#L71-L159  

7. `GeneratedEvaluatorRef`  
   - provider registry에 이미 존재하는 generated rubric evaluator reference  
   - version pinning strongly recommended  
   출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/GeneratedEvaluatorRef.cs#L6-L54

### Python 공개 API

1. `evaluate_agent(...)`  
   - queries를 실행하거나 pre-existing responses를 재평가  
   - expected output / expected tool calls / repetitions / conversation split 지원  
   출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1630-L1831

2. `evaluate_workflow(...)`  
   - `workflow.run()` 결과 또는 기존 `WorkflowRunResult`를 대상으로 per-agent + overall evaluation 수행  
   출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1834-L2025

3. `LocalEvaluator`  
   - sync/async check를 local provider로 실행  
   출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1518-L1622

4. `keyword_check`, `tool_called_check`, `tool_calls_present`, `tool_call_args_match`, `@evaluator`  
   - built-in checks와 function wrapper  
   출처:  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1061-L1277  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1354-L1497  

5. `EvalItem`, `EvalResults`, `EvalItemResult`, `EvalScoreResult`, `ExpectedToolCall`, `ConversationSplit`  
   - provider-neutral evaluation data/score/result model  
   출처:  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L76-L178  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L181-L260  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L304-L543  

6. `AgentEvalConverter`  
   - message/response/tool definition을 evaluator item으로 변환  
   출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L742-L926

7. `FoundryEvals`, `GeneratedEvaluatorRef`, `evaluate_traces`, `evaluate_foundry_target`  
   - provider integration surface  
   출처:  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_foundry_evals.py#L3-L24  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_foundry_evals.py#L62-L103  

## 상세 실행 흐름

### 1. .NET agent evaluation 실행 흐름

1. 호출자는 `AIAgent.EvaluateAsync(...)`에 queries/evaluator를 준다.  
2. 내부 `RunAgentForEvalAsync()`가 repetition, expected output/tool call count를 검증한다.  
3. 각 query에 대해 `agent.RunAsync(...)`를 실행한다.  
4. `BuildEvalItem()`이 user query, response messages, raw response, tool definitions를 묶어 `EvalItem`을 만든다.  
5. evaluator는 batch 단위로 `EvaluateAsync(items, evalName, cancellationToken)`를 수행한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L20-L60  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L272-L333  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L335-L368  

### 2. .NET pre-existing response 재평가 흐름

`.NET`은 기존 `AgentResponse`를 다시 평가할 수 있다. 이 경로는 agent를 다시 실행하지 않고 `BuildItemsFromResponses()`로 `EvalItem`을 재구성하며, query/response/expected-output/expected-tool-calls count mismatch는 즉시 `ArgumentException`으로 실패한다.  
출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L167-L210  
https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L220-L269

### 3. .NET built-in check와 local scoring 흐름

`.NET`의 `EvalChecks`는 string containment, tool-called presence, expected tool args subset match, non-empty response 같은 패턴을 `EvalCheck` delegate로 만든다. `LocalEvaluator`는 각 item마다 check를 수행해 `BooleanMetric`을 기록하고, 성공이면 `EvaluationRating.Good`, 실패면 `EvaluationRating.Unacceptable`를 붙인다. 이 결과는 `AgentEvaluationResults`로 aggregate 된다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/EvalChecks.cs#L23-L105  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/EvalChecks.cs#L126-L217  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/LocalEvaluator.cs#L30-L65  

### 4. .NET scoring / gate 흐름

`AgentEvaluationResults`는 단순 result container를 넘어서 quality gate API를 제공한다.
- `AllPassed`
- `AssertAllPassed()`
- `AssertScoreAtLeast(...)`  
또한 Foundry-style provider metadata (`ReportUrl`, `EvalId`, `RunId`, `Status`, `Error`) 와 workflow breakdown용 `SubResults`, per-evaluator summary, detailed item score를 보유할 수 있다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationResults.cs#L31-L69  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationResults.cs#L71-L159  

### 5. .NET provider evaluator integration 흐름

`.NET`은 evaluator 자체를 두 방식으로 연결한다.
1. AF-native `IAgentEvaluator`
2. `Microsoft.Extensions.AI.Evaluation.IEvaluator`를 `MeaiEvaluatorAdapter`로 감싼 경로  
이 구조 덕분에 local evaluator와 외부 judge evaluator가 같은 `EvaluateAsync(...)` 진입점 아래 놓인다.  
출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L63-L101

### 6. Python AgentEvalConverter 흐름

Python의 converter는 evaluator용 serialization 경로를 별도로 둔다.
- text content는 `{"type":"text","text":...}`
- image/media content는 `input_image`
- function call은 `tool_call`
- function result는 `tool_result`  
특히 function-call arguments가 string인데 JSON parse가 실패하면 `{"_raw_arguments":"[unparseable]"}` 로 sanitize 한다. 이는 외부 evaluation service에 raw/unparseable tool arguments를 그대로 흘리지 않으려는 방어적 변환이다. 이후 `to_eval_item()`은 input messages + response messages + typed tools를 `EvalItem`으로 조립한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L768-L830  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L847-L926  

### 7. Python local check / score coercion 흐름

Python의 local evaluation은 두 단계다.
1. check function이 `CheckResult | bool | float | dict | awaitable` 중 하나를 반환한다.
2. `_coerce_result()`가 이를 `CheckResult`로 normalize 한다.  
여기서 float score는 `>= 0.5`이면 pass로 본다. `dict`는 `score` 또는 `passed` key를 가질 수 있다. `@evaluator`는 function signature를 introspect 해서 `query`, `response`, `expected_output`, `expected_tool_calls`, `conversation`, `tools`, `context`를 자동 주입한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1036-L1277  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1354-L1497  

### 8. Python LocalEvaluator 실행 흐름

1. `LocalEvaluator(*checks)`가 sync/async check 목록을 받는다.  
2. 각 item마다 `asyncio.gather()`로 모든 check를 실행한다.  
3. item은 “적어도 하나의 check가 실행되고, 모든 check가 pass” 해야 pass다. check가 전혀 없으면 pass로 보지 않는다.  
4. 각 check는 `EvalScoreResult`로 1.0/0.0 score와 reason sample을 가진다.  
5. aggregate 결과는 `EvalResults`의 `result_counts`, `per_evaluator`, `items`, `error`에 반영된다.  
출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1518-L1622

### 9. Python evaluate_agent 실행 흐름

1. singular `queries`, `expected_output`, `responses`, flat `expected_tool_calls`를 list/nested list로 normalize 한다.  
2. `num_repetitions` 와 길이 정합성을 `ValueError`로 먼저 검증한다.  
3. `responses`가 주어지면 agent를 다시 실행하지 않고 converter를 사용해 item을 만든다.  
4. `queries + agent` 조합이면 실제 agent를 반복 실행해 item을 만든다.  
5. 이후 expected output/tool calls/split strategy를 stamp 하고 `_run_evaluators()`로 evaluator를 실행한다.  
6. bare check callable은 `_resolve_evaluators()` 과정에서 `LocalEvaluator`로 auto-wrap 된다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1630-L1831  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L2058-L2065  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L2090-L2116  

### 10. Python evaluate_workflow 실행 흐름

1. `workflow_result` 또는 `queries` 중 하나는 반드시 필요하다.  
2. run mode에서는 `workflow.run(query)` 결과를 누적하고, post-hoc mode에서는 기존 `WorkflowRunResult`를 사용한다.  
3. `_extract_agent_eval_data()`가 `executor_invoked` / `executor_completed` event를 pairing 하여 internal executor를 건너뛰고 agent-specific query/response를 추출한다.  
4. executor id별로 `EvalItem` 목록을 만들고, evaluator마다 각 agent를 별도 평가한다.  
5. `include_overall` 이면 final workflow output도 별도 `EvalItem`으로 평가한다.  
6. 결과는 provider별 `EvalResults` 하나씩 반환되며, 그 안에 `sub_results`가 per-agent breakdown으로 채워진다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1834-L2025  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L934-L1009  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1012-L1028  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L2033-L2055  

### 11. Python FoundryEvals provider integration 흐름

`FoundryEvals`는 built-in evaluator short name을 fully-qualified evaluator name으로 resolve 하고, agent/tool/ground-truth evaluator 집합을 관리한다. generated rubric evaluator는 `GeneratedEvaluatorRef`로 참조되며 version pinning이 강하게 권장된다. versionless ref는 실행 시점 latest를 가리키므로 reproducibility가 깨질 수 있어 warning 대상이다. 테스트 일관성은 `test_foundry_evals.py`에서 보강된다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_foundry_evals.py#L62-L103  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_foundry_evals.py#L105-L183  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_foundry_evals.py#L185-L212  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_foundry_evals.py#L220-L260  

### 12. .NET unit/integration/conformance test 조직 흐름

`.NET` CI는 하나의 filtered solution에서 다시 `*UnitTests*` 와 `*IntegrationTests*` project 이름 필터로 분리된 solution을 생성한다. unit tests는 coverage와 함께 실행되고, integration tests는 PR 외 이벤트에서만 돌며 `FoundryHostedAgents` category는 별도 비용이 큰 job으로 분리된다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L223-L337  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Foundry.Hosting.IntegrationTests/README.md#L151-L159  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Foundry.Hosting.IntegrationTests/README.md#L161-L203  

### 13. .NET golden/trace-driven conformance 흐름

OpenAI hosting unit test project는 `ConformanceTraces` 폴더를 build output으로 복사한다. `ConformanceTestBase`는 trace 파일을 로드해 test server를 띄우고, 실제 endpoint로 request를 보내고, response wire shape를 검사한다. 이 접근은 snapshot corpus를 golden data처럼 다룬다는 의미다. malformed request trace와 structured JSON response trace 모두 corpus의 일부다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests.csproj#L25-L29  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/ConformanceTestBase.cs#L33-L76  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/OpenAIResponsesConformanceTests.cs#L20-L120  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/ConformanceTraces/Conversations/error_invalid_json/request.txt#L1-L6  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/ConformanceTraces/Responses/json_output/response.json#L1-L90  

### 14. Python unit/integration/coverage 조직 흐름

Python은 `pyproject.toml` 에서 `packages/**/tests` 와 `packages/**/ag_ui_tests` 를 기본 test path로 선언하고, integration marker를 둔다. PR workflow는 aggregate pytest를 실행하고, integration workflow는 unit-only / OpenAI / Azure OpenAI / misc provider 등으로 job을 나눈다. coverage는 별도 workflow에서 aggregate test + coverage XML + threshold check를 수행한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L179-L192  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-tests.yml#L17-L60  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-integration-tests.yml#L41-L67  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-integration-tests.yml#L68-L101  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-integration-tests.yml#L165-L255  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-test-coverage.yml#L19-L46  

## 상태 및 구성

### Evaluation 기능 성숙도와 사용 가능 범위

- Python의 `EVALS` feature는 package status 상 experimental 이다. root package 전체가 stable classifier를 가지더라도, eval feature surface는 별도로 experimental stage를 가진다.  
  출처:  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L87-L93  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L68-L70  

- `.NET` core package는 released 패키지이지만 evaluation support는 `net8.0+` 에서만 compile 된다. legacy TFM에서는 `Evaluation/**` source를 제거한다.  
  출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Microsoft.Agents.AI.csproj#L36-L42

### Test 조직 구성

- Python test discovery는 `packages/**/tests` 중심이다.
- Python integration은 `integration` marker 기준으로 나뉜다.
- `.NET`은 solution filtering 기반으로 unit/integration이 분리된다.
- 비용이 큰 Foundry hosted integration tests는 별도 job과 별도 infra prerequisite를 가진다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L179-L192  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L223-L337  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Foundry.Hosting.IntegrationTests/README.md#L34-L60  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Foundry.Hosting.IntegrationTests/README.md#L151-L203  

### Coverage gate 구성

- `.NET` workflow는 `COVERAGE_THRESHOLD: 80` 을 사용하고, coverage report를 생성한 뒤 PowerShell 스크립트로 threshold를 검사한다.  
  출처:  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L19-L21  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L317-L336  

- Python은 별도 coverage workflow에서 `COVERAGE_THRESHOLD: 85` 를 사용하고, DEV_SETUP 문서상 Beta/Production/Stable package에 blocking gate를 적용한다. Alpha는 non-blocking 이고 DevUI/Lab은 aggregate enforcement 대상에서 제외된다.  
  출처:  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-test-coverage.yml#L15-L46  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/DEV_SETUP.md#L176-L190  

## 오류와 보안

### 1. Evaluation failure와 quality gate failure의 구분

이 스냅샷의 evaluation 결과 모델은 “평가 API가 성공적으로 실행되었는가”와 “품질 기준을 통과했는가”를 분리한다.
- Python은 `EvalResults.status`, `error`, `items[*].status`, `raise_for_status()`를 제공한다.
- `.NET`은 `AgentEvaluationResults.Status`, `Error`, `AssertAllPassed()`, `AssertScoreAtLeast()`를 제공한다.  
즉 evaluator run 자체의 infra failure와 model quality failure를 분리해 다룬다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L372-L543  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationResults.cs#L34-L47  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationResults.cs#L95-L159  

### 2. Converter 경로의 민감 인자 보호

Python `AgentEvalConverter`는 function-call arguments가 JSON parse에 실패할 경우 raw string을 그대로 외부 evaluator에 전달하지 않고 `"[unparseable]"` placeholder로 sanitize 한다. 이는 evaluation converter가 단순 포맷터가 아니라, 외부 judge 서비스로 전달되는 payload의 정보량을 제한하는 경계라는 뜻이다.  
출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L786-L795

### 3. Validation boundary

- Python `evaluate_agent()` 는 `queries`/`responses`/`expected_output`/`expected_tool_calls`/`num_repetitions` 정합성 오류를 `ValueError`로 처리한다.
- `.NET` `RunAgentForEvalAsync()` 와 `BuildItemsFromResponses()` 는 count mismatch와 invalid repetition을 `ArgumentException`으로 처리한다.  
이는 evaluation API가 사용자 입력 정합성 문제를 domain-specific eval exception으로 감싸지 않는다는 뜻이다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1754-L1809  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1910-L1923  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L225-L240  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L281-L300  

### 4. Conformance corpus의 테스트 데이터 성격

`.NET`의 `ConformanceTraces`는 malformed request와 realistic response를 함께 포함하는 golden corpus다. 이 데이터는 product runtime이 아니라 test harness를 위한 것이지만, wire-level regression 고정이라는 의미에서 quality/security-sensitive validation asset으로 동작한다. 이 corpus가 실제 request validation failure 경로도 포괄한다는 점은 중요하다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/ConformanceTraces/Conversations/error_invalid_json/request.txt#L1-L6  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/OpenAIConversationsConformanceTests.cs#L1140-L1162  

## .NET 구현

### 1. Agent 평가가 중심인 public surface

이번 snapshot에서 직접 확인된 `.NET` public evaluation surface는 agent query execution과 pre-existing response evaluation이 중심이다. `IAgentEvaluator` 는 batch contract를 제공하고, `AgentEvaluationExtensions` 는 single evaluator, MEAI adapter, multiple evaluators, pre-existing responses 경로를 모두 지원한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/IAgentEvaluator.cs#L9-L32  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L20-L210  

### 2. Built-in local checks와 score gate

`.NET`은 local evaluation을 `EvalChecks`와 `LocalEvaluator`로 해결한다. built-in checks는 keyword, tool-called, tool-args, non-empty 같은 범용 패턴이고, 결과는 `BooleanMetric` 기반으로 aggregate 된다. CI gate는 `AgentEvaluationResults.AssertAllPassed()` 또는 `AssertScoreAtLeast()` 같은 API로 구성할 수 있다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/EvalChecks.cs#L23-L217  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/LocalEvaluator.cs#L30-L65  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationResults.cs#L95-L159  

### 3. Generated/provider evaluator integration

`.NET`은 `GeneratedEvaluatorRef`를 통해 cloud-side rubric evaluator reference를 모델링하고, version pinning을 재현 가능성의 핵심으로 둔다. 또한 `IEvaluator` adapter overload로 `Microsoft.Extensions.AI.Evaluation` evaluator를 바로 연결할 수 있다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/GeneratedEvaluatorRef.cs#L16-L54  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L63-L101  

### 4. Workflow evaluation 관련 구현 상태

`AgentEvaluationResults.SubResults`는 workflow evaluation breakdown을 위한 구조를 제공하고, 클래스 주석도 workflow evaluations를 전제로 한 helper를 설명한다. 그러나 이번 수집 근거에서 `AgentEvaluationExtensions` 안의 explicit workflow evaluation public method는 직접 확인되지 않았다. 따라서 `.NET`은 결과 모델 차원에서는 workflow evaluation을 고려하지만, Python처럼 명시적 `evaluate_workflow()` 공개 API가 현재 snapshot source에서 직접 확인되지는 않는다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationResults.cs#L59-L69  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L13-L15  

### 5. Test 조직과 conformance

`.NET`은 tests를 unit/integration/hosted-integration/conformance로 명확히 분리한다. 특히 OpenAI hosting conformance suite는 trace corpus를 replay하는 golden-style 접근을 쓰고, Foundry hosted integration tests는 별도 README에서 infra bootstrap과 scenario gating을 설명한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L223-L337  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/ConformanceTestBase.cs#L21-L76  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Foundry.Hosting.IntegrationTests/README.md#L1-L33  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Foundry.Hosting.IntegrationTests/README.md#L151-L203  

## Python 구현

### 1. Provider-agnostic core 평가 프레임워크

Python은 `agent_framework._evaluation` 하나에 conversation split, expected tool calls, converter, local evaluator, workflow extraction, result gating, public orchestration function을 넣는다. 이 구조는 eval functionality를 매우 응집적으로 보여준다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L3-L33  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L76-L178  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L181-L260  

### 2. Agent / workflow evaluation이 public API로 분리됨

Python은 `evaluate_agent()` 와 `evaluate_workflow()` 를 별도 public API로 노출한다. workflow evaluation은 post-hoc mode와 run+evaluate mode를 모두 지원하고, per-agent sub-results를 standard result model 안에 싣는다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1630-L1831  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1834-L2025  

### 3. Local checks와 function wrapper가 더 유연함

Python은 built-in helper뿐 아니라 임의 함수도 `@evaluator`로 wrapping 해서 evaluation check로 쓸 수 있다. bool/float/dict/`CheckResult`를 모두 허용하는 점은 `.NET`보다 유연하다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1354-L1497  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1518-L1622  

### 4. Provider integration이 더 명시적임

Python `FoundryEvals`는 evaluator class와 관련 helper가 코드상 명확하며, built-in evaluator mapping, tool-aware evaluator set, ground-truth evaluator set, generated rubric evaluator reference를 모두 한곳에서 관리한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_foundry_evals.py#L105-L183  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_foundry_evals.py#L220-L260  

### 5. Test matrix와 coverage discipline이 강함

Python은 eval feature가 experimental 임에도 불구하고 test infrastructure는 강하다. aggregate tests, provider-sharded integration, retry/timeouts, 별도 coverage workflow, maturity-aware threshold 문서화가 모두 존재한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-tests.yml#L17-L60  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-integration-tests.yml#L41-L67  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-integration-tests.yml#L68-L101  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-test-coverage.yml#L15-L46  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/DEV_SETUP.md#L176-L190  

### 6. Python conformance harness 상태

이번 수집 근거 기준으로는 Python 측에 `.NET OpenAI ConformanceTraces`와 동급의 explicit trace-driven wire conformance harness는 직접 확인되지 않았다. Python에서 확인된 것은 evaluation unit tests, package tests, integration workflows, coverage gates다. 따라서 “Python에 conformance가 없다”가 아니라, **이번 snapshot 근거로는 explicit golden-wire conformance harness 확인 불가**가 정확하다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/tests/test_foundry_evals.py#L96-L109  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/tests/test_foundry_evals.py#L116-L217  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-tests.yml#L45-L47  

## 테스트 근거

### 1. Python evaluation unit tests

`test_foundry_evals.py` 는 `_resolve_evaluator()` short-name mapping, `AgentEvalConverter.convert_message()` 의 text/tool_call/tool_result 변환, structured object tool result 보존 등을 검증한다. 즉 provider integration과 converter correctness가 unit-level에서 꽤 폭넓게 고정되어 있다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/tests/test_foundry_evals.py#L96-L109  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/tests/test_foundry_evals.py#L116-L217  

### 2. .NET agent contract integration tests

`AgentConformance.IntegrationTests` 의 `RunTests` 와 `RunStreamingTests` 는 no-message, string input, `ChatMessage`, multi-message, session history preservation을 generic fixture 위에서 검사한다. 이는 provider-specific behavior보다 **agent contract** 자체를 고정하는 층이다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/AgentConformance.IntegrationTests/RunTests.cs#L20-L122  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/AgentConformance.IntegrationTests/RunStreamingTests.cs#L20-L117  

### 3. .NET trace-driven conformance tests

`OpenAIResponsesConformanceTests` 는 request trace에서 expected text를 읽고 test server에 요청을 보내고, response의 field presence/object/type/token accounting/wire shape를 검증한다. 이는 단순 snapshot 비교가 아니라 trace-driven structural conformance 검사다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/OpenAIResponsesConformanceTests.cs#L20-L120  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/ConformanceTestBase.cs#L33-L76  

### 4. Coverage gate 근거

- `.NET`: CI workflow environment에 `COVERAGE_THRESHOLD: 80` 이 선언되고 summary JSON을 사용해 gate 한다.  
- Python: coverage workflow에 `COVERAGE_THRESHOLD: 85` 가 선언되며, DEV_SETUP 문서가 maturity별 enforcement 차이를 설명한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L19-L21  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L333-L336  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-test-coverage.yml#L15-L46  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/DEV_SETUP.md#L187-L190  

## 문서와 코드 차이

### 1. .NET 평가 확장 문서와 실제 공개 메서드 표면 차이

`AgentEvaluationExtensions` 클래스 주석은 “agents, responses, and workflow runs” 평가를 말한다. 그러나 이번 snapshot에서 직접 확인된 메서드 범위는 agent queries와 pre-existing responses 경로이며, explicit workflow evaluation public method는 확인되지 않았다. 반면 결과 타입 `SubResults`는 workflow evaluation을 예상한다. 따라서 문서 설명이 implementation evidence보다 약간 넓다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L13-L15  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L20-L210  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationResults.cs#L59-L69  

### 2. 저장소 FAQ의 “.NET과 Python 모두 conformance testing” 일반론과 수집 근거의 비대칭

FAQ는 framework가 `.NET`과 Python 구현 전반에서 conformance testing을 수행한다고 말한다. 이번 snapshot 수집 근거는 `.NET`에 대해서는 explicit `ConformanceTraces` 기반 test suite로 이를 강하게 뒷받침한다. Python 쪽은 unit/integration/coverage workflow는 풍부하지만 같은 수준의 explicit trace-driven conformance harness는 이번 수집 범위에서 직접 확인되지 않았다. 따라서 code evidence 기준으로는 `.NET` 쪽 conformance 가시성이 더 높다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/TRANSPARENCY_FAQ.md#L26-L28  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/ConformanceTestBase.cs#L21-L76  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/tests/test_foundry_evals.py#L96-L109  

### 3. Python package 안정성 분류와 eval feature stage의 차이

Python root/package 다수는 released/stable 상태이지만, `EVALS` feature는 package status 문서와 코드 decorator 양쪽에서 experimental 로 표시된다. 즉 패키지 안정성과 feature 안정성은 동일하지 않다. 이 차이를 문서화하지 않으면 “stable package == stable evaluation API”로 오해할 수 있다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L17-L18  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L87-L93  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L68-L70  

## Java 설계 결정

### 결정 1. 배치 기반 evaluator SPI를 도입한다

Java는 `.NET IAgentEvaluator` 와 Python `Evaluator` 모델을 따라, `List<EvalItem> -> EvalResults` 형태의 배치 evaluator SPI를 가져야 한다. item 단위 호출보다 cloud/provider evaluator와 score aggregation에 유리하다.  
근거:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/IAgentEvaluator.cs#L13-L15  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L687-L718

### 결정 2. Converter를 first-class API로 둔다

Java는 Python `AgentEvalConverter`처럼 runtime `Message`/tool definition을 evaluator schema로 바꾸는 전용 converter layer를 가져야 한다. 이 converter는 단순 포맷터가 아니라, tool-call argument sanitization 같은 outbound data minimization 책임도 져야 한다.  
근거:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L786-L795  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L847-L926  

### 결정 3. Local evaluator와 function-style check를 둘 다 지원한다

Java는 `.NET`처럼 built-in check library를 제공하되, Python `@evaluator`처럼 plain function/lambda scorer를 쉽게 연결할 수 있는 wrapper를 추가하는 편이 좋다. 그래야 CI smoke gate와 실험적 custom judge를 같은 파이프라인에서 사용하기 쉽다.  
근거:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/EvalChecks.cs#L23-L217  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1354-L1497  

### 결정 4. Workflow evaluation은 explicit public API로 설계한다

Java는 Python처럼 `evaluateWorkflow(...)` 를 별도 public API로 제공해야 한다. 결과는 `overall + subResults(per-agent)` 구조를 공식 지원해야 하며, event stream 또는 run result 에서 agent-level interaction extraction helper도 같이 설계해야 한다.  
근거:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1834-L2025  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L934-L1009  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationResults.cs#L59-L69  

### 결정 5. Generated evaluator는 version pinning을 기본 운영 원칙으로 한다

Generated/provider rubric evaluator reference는 항상 version pinning을 권장하고, CI gate 문맥에서는 사실상 필수로 요구해야 한다. latest-floating evaluator는 replay reproducibility를 깨뜨린다.  
근거:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/GeneratedEvaluatorRef.cs#L16-L30  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_foundry_evals.py#L73-L78  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_foundry_evals.py#L249-L257  

### 결정 6. Trace-driven conformance corpus를 유지한다

Java hosting/protocol layer가 생기면 `.NET ConformanceTraces`와 같은 golden/trace-driven corpus를 유지해야 한다. wire-format regression은 mock-only unit test보다 trace corpus replay가 훨씬 강하다.  
근거:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests.csproj#L25-L29  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/ConformanceTestBase.cs#L33-L76  

### 결정 7. Coverage gate는 package maturity와 연결한다

Python처럼 package maturity에 따라 coverage enforcement 강도를 조절하는 정책이 유용하다. Java도 snapshot/stable surface는 blocking gate, alpha/experimental surface는 softer gate 또는 보고-only gate를 둘 수 있다.  
근거:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/DEV_SETUP.md#L187-L190  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-test-coverage.yml#L15-L46  

### 결정 8. Public API는 “평가 실행”과 “품질 게이트”를 분리한다

Java 결과 모델은 Python `raise_for_status()` / `.NET AssertAllPassed()` 처럼 quality gate helper를 제공하되, raw result access와 분리해야 한다. 이렇게 하면 UI/reporting과 CI gating이 같은 result object를 공유할 수 있다.  
근거:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L470-L543  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationResults.cs#L95-L159  

## 구체적인 acceptance scenarios

### Scenario 1. .NET agent evaluation은 query count 불일치를 즉시 거부한다

- Given: pre-existing response 2개와 query 1개
- When: `.NET` `EvaluateAsync(responses, queries, ...)` 경로가 item을 구성한다
- Then: `ArgumentException` 으로 실패해야 한다.  
출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L225-L240

### Scenario 2. .NET local evaluation은 check가 없으면 item을 pass로 보지 않는다

- Given: `.NET LocalEvaluator`와 item
- When: item에 대해 check 목록이 비어 있다
- Then: “pass evidence 없음”으로 간주되어 자동 pass가 되지 않아야 한다는 설계와 동등한 Java contract를 가져야 한다.  
실제 Python 구현은 check가 없으면 `item_passed = bool(check_results)` 때문에 false가 된다. `.NET` LocalEvaluator는 생성 시 checks 배열을 받으며 item마다 metrics를 생성한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1562-L1580  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/LocalEvaluator.cs#L35-L64  

### Scenario 3. Python function evaluator는 float score를 pass/fail로 coercion 한다

- Given: `@evaluator` 로 감싼 함수가 `0.7` 을 반환한다
- When: `_coerce_result()` 가 결과를 normalize 한다
- Then: threshold `0.5` 기준으로 pass가 되어야 한다.  
출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1357-L1373

### Scenario 4. Python converter는 unparseable tool arguments를 그대로 내보내지 않는다

- Given: function_call content의 arguments가 JSON parse 불가능한 string
- When: `AgentEvalConverter.convert_message()` 가 evaluator message로 변환한다
- Then: raw arguments 대신 `{"_raw_arguments":"[unparseable]"}` 를 사용해야 한다.  
출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L786-L795

### Scenario 5. Python evaluate_agent는 responses-only 평가를 허용하지만 queries가 필요하다

- Given: pre-existing `responses` 를 평가하려 한다
- When: `queries` 없이 `evaluate_agent(responses=...)` 를 호출한다
- Then: query가 필요하다는 `ValueError` 가 발생해야 한다.  
출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1766-L1789

### Scenario 6. Python workflow evaluation은 per-agent sub_results를 만든다

- Given: workflow run result에 여러 `AgentExecutor` 이벤트가 있다
- When: `evaluate_workflow(include_per_agent=True, include_overall=True)` 를 호출한다
- Then: provider별 `EvalResults` 안에 executor id keyed `sub_results` 가 포함되어야 한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1956-L2025  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L390-L392  

### Scenario 7. Generated evaluator는 pinned version이 권장된다

- Given: provider registry의 generated rubric evaluator
- When: versionless reference를 사용한다
- Then: 실행은 가능하더라도 reproducibility warning 대상이어야 하며, CI에서는 pinned version을 쓰는 것이 계약상 더 안전하다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/GeneratedEvaluatorRef.cs#L16-L30  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_foundry_evals.py#L249-L257  

### Scenario 8. .NET OpenAI hosting conformance suite는 disk trace를 replay 한다

- Given: `ConformanceTraces/Responses/basic/request.json`
- When: conformance test가 test server에 request를 보낸다
- Then: response object shape, output content, token fields 등 OpenAI wire contract를 구조적으로 검증해야 한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/ConformanceTestBase.cs#L33-L76  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/OpenAIResponsesConformanceTests.cs#L20-L120  

### Scenario 9. Coverage gate는 CI에서 blocking threshold를 가진다

- Given: `.NET` test coverage summary 또는 Python aggregate coverage XML
- When: threshold 이하 coverage가 계산된다
- Then: `.NET`은 80%, Python은 85% 기준으로 gate가 실패해야 한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L19-L21  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L333-L336  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-test-coverage.yml#L15-L46  

## Source inventory

### Production source: .NET evaluation

- `dotnet/src/Microsoft.Agents.AI/Evaluation/IAgentEvaluator.cs`  
  - batch evaluator contract  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/IAgentEvaluator.cs#L9-L32

- `dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs`  
  - agent query evaluation, MEAI adapter, multi-evaluator, pre-existing response evaluation, item building  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L13-L15  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L20-L210  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L220-L368  

- `dotnet/src/Microsoft.Agents.AI/Evaluation/EvalItem.cs`  
  - conversation/query/response/tools/context/expected-output model  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/EvalItem.cs#L9-L170

- `dotnet/src/Microsoft.Agents.AI/Evaluation/EvalCheck.cs`  
  - local check delegate  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/EvalCheck.cs#L5-L10

- `dotnet/src/Microsoft.Agents.AI/Evaluation/EvalChecks.cs`  
  - built-in keyword/tool/args/non-empty checks  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/EvalChecks.cs#L23-L217

- `dotnet/src/Microsoft.Agents.AI/Evaluation/LocalEvaluator.cs`  
  - local scoring to BooleanMetric  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/LocalEvaluator.cs#L10-L65

- `dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationResults.cs`  
  - aggregate status, score assertions, workflow subresults  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationResults.cs#L21-L159

- `dotnet/src/Microsoft.Agents.AI/Evaluation/GeneratedEvaluatorRef.cs`  
  - generated evaluator version pinning guidance  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/GeneratedEvaluatorRef.cs#L6-L54

- `dotnet/src/Microsoft.Agents.AI/Microsoft.Agents.AI.csproj`  
  - evaluation support only on `net8.0+`  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Microsoft.Agents.AI.csproj#L36-L42

### Production source: Python evaluation

- `python/packages/core/agent_framework/_evaluation.py`  
  - provider-agnostic evaluation framework, core types, local evaluator, converter, workflow extraction, orchestration APIs  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L3-L33  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L68-L178  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L181-L260  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L304-L543  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L742-L926  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L934-L1028  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1036-L1497  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1518-L1622  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1630-L2025  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L2058-L2116  

- `python/packages/foundry/agent_framework_foundry/_foundry_evals.py`  
  - provider-specific Foundry evaluator integration, generated evaluator refs, built-in evaluator mapping  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_foundry_evals.py#L3-L24  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_foundry_evals.py#L62-L103  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_foundry_evals.py#L105-L183  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_foundry_evals.py#L185-L212  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_foundry_evals.py#L220-L260  

- `python/PACKAGE_STATUS.md`  
  - EVALS experimental feature stage  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L87-L93

### Test and CI source

- `python/packages/foundry/tests/test_foundry_evals.py`  
  - evaluator resolution, message conversion, tool result conversion tests  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/tests/test_foundry_evals.py#L96-L109  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/tests/test_foundry_evals.py#L116-L217  

- `dotnet/tests/AgentConformance.IntegrationTests/RunTests.cs`  
  - generic agent contract integration tests  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/AgentConformance.IntegrationTests/RunTests.cs#L20-L122

- `dotnet/tests/AgentConformance.IntegrationTests/RunStreamingTests.cs`  
  - generic streaming contract integration tests  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/AgentConformance.IntegrationTests/RunStreamingTests.cs#L20-L117

- `dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests.csproj`  
  - trace corpus copy-to-output  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests.csproj#L25-L29

- `dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/ConformanceTestBase.cs`  
  - disk trace loading and test server setup  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/ConformanceTestBase.cs#L21-L76

- `dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/OpenAIResponsesConformanceTests.cs`  
  - trace-driven response conformance assertions  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/OpenAIResponsesConformanceTests.cs#L20-L120

- `dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/ConformanceTraces/Conversations/error_invalid_json/request.txt`  
  - malformed request golden trace example  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/ConformanceTraces/Conversations/error_invalid_json/request.txt#L1-L6

- `dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/ConformanceTraces/Responses/json_output/response.json`  
  - structured output golden trace example  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/ConformanceTraces/Responses/json_output/response.json#L1-L90

- `.github/workflows/dotnet-build-and-test.yml`  
  - filtered unit/integration solutions, hosted IT split, coverage threshold 80  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L19-L21  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L223-L337

- `dotnet/tests/Foundry.Hosting.IntegrationTests/README.md`  
  - hosted integration scenario organization and CI routing  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Foundry.Hosting.IntegrationTests/README.md#L151-L203

- `python/pyproject.toml`  
  - pytest testpaths, markers  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L179-L192

- `.github/workflows/python-tests.yml`  
  - aggregate unit-style test workflow  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-tests.yml#L17-L60

- `.github/workflows/python-integration-tests.yml`  
  - sharded provider integration jobs and retry/timeouts  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-integration-tests.yml#L41-L67  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-integration-tests.yml#L68-L101  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-integration-tests.yml#L165-L255

- `.github/workflows/python-test-coverage.yml`  
  - coverage threshold 85  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-test-coverage.yml#L15-L46

- `python/DEV_SETUP.md`  
  - maturity-aware coverage enforcement policy  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/DEV_SETUP.md#L176-L190