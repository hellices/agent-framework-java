# 29. Evaluation & Testing

## State

- Document status: upstream snapshot analysis document
- Reference snapshot: `d0a4165f170193ba1d026a259af40d35bb7eaefe`
- Analysis scope: evaluation API/converters/checks/scoring, agent/workflow evaluation, generated/provider evaluator integration, unit/integration/conformance test organization, golden/trace-driven testing, coverage gate
- Out of scope:
  - observability, feature telemetry, logging/sensitive data are owned by the observability document
  - error taxonomy/timeout/security boundary are owned by the errors-resilience-security document
  - general packaging/release/compatibility topics are owned by the packaging-compatibility document; only the scope directly connected to the available runtime/coverage gate of the evaluation feature is covered in limited fashion in this document

## Snapshot summary

The evaluation framework in this snapshot targets “provider-agnostic core + provider-specific evaluator integration” in both languages, but the central axis of each implementation differs. `.NET` standardizes **agent response scoring** through a batch evaluation interface centered on `IAgentEvaluator`, the `AIAgent.EvaluateAsync(...)` extension method, `LocalEvaluator`, `EvalChecks`, and `AgentEvaluationResults`. Python's `agent_framework._evaluation` manages `EvalItem`, `EvalResults`, `LocalEvaluator`, `evaluate_agent`, `evaluate_workflow`, `@evaluator` wrapper, and `AgentEvalConverter` all within one module, while a separate `FoundryEvals` handles provider integration.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/IAgentEvaluator.cs#L9-L32  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L20-L60  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/LocalEvaluator.cs#L10-L65  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L3-L33  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1518-L1622  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1630-L1831  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1834-L2025  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_foundry_evals.py#L3-L24  

The scoring surface also differs. `.NET`'s `LocalEvaluator` reports each `EvalCheck` result as a `BooleanMetric` from `Microsoft.Extensions.AI.Evaluation`, and the aggregate result is surfaced through `AgentEvaluationResults`, which provides quality gate APIs such as `Passed`, `Failed`, `AllPassed`, `AssertAllPassed`, and `AssertScoreAtLeast`. Python defines its own `CheckResult`, `EvalScoreResult`, `EvalItemResult`, and `EvalResults`, and provides gates such as `raise_for_status()`, `assert_score_at_least()`, and `assert_dimension_score_at_least()`. Through the `@evaluator` wrapper, Python can promote bool/float/dict/`CheckResult` return values all to evaluation checks, and applies a default threshold `0.5` rule when the score is numeric.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/LocalEvaluator.cs#L30-L65  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationResults.cs#L21-L70  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationResults.cs#L71-L159  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L304-L354  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L372-L543  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1036-L1058  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1354-L1404  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1415-L1497  

Workflow evaluation is more concrete on the Python side. `evaluate_workflow()` either runs the workflow directly or accepts an existing `WorkflowRunResult`, computes per-agent and overall results together, and populates `sub_results`. On the `.NET` side, `AgentEvaluationResults.SubResults` already provides a structure for workflow evaluation, and the comments in `AgentEvaluationExtensions` also mention workflow runs; however, the only public evaluation entrypoints directly confirmed in this snapshot are the agent/response evaluation methods. Therefore it is more accurate to say that Python has an **explicit public workflow evaluation API**, while `.NET` has a **result model that is ready but for which an explicit workflow evaluation public API has not been directly confirmed in the collected evidence of this snapshot**.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1834-L2025  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L2033-L2055  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationResults.cs#L59-L69  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L13-L15  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L20-L210  

Testing organization is also asymmetric. `.NET` uses project name suffixes to generate a filtered solution separating `*UnitTests*` and `*IntegrationTests*`, and in the OpenAI hosting area copies `ConformanceTraces` as a golden corpus to run disk-based trace-driven conformance tests. Python runs aggregate pytest centered on `packages/**/tests`, splits integration workflows into per-provider jobs, and enforces an 85% threshold separately in a coverage workflow. Golden/trace-driven conformance evidence is far clearer on the `.NET` side based on this collection, while Python demonstrates a stronger package/provider test matrix and coverage discipline.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L223-L337  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests.csproj#L25-L29  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/ConformanceTestBase.cs#L21-L76  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L179-L192  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-tests.yml#L17-L60  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-integration-tests.yml#L41-L67  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-integration-tests.yml#L68-L101  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-test-coverage.yml#L15-L46  

## Original feature purpose and boundary

### 1. Purpose of the Evaluation API

The fundamental purpose of the Evaluation API is to express “whether an agent or workflow produced a good answer” in a provider-neutral form. To this end, `.NET` uses `EvalItem` and `IAgentEvaluator` to assemble query/response/conversation/tool/context/expected output into batch input that an evaluator can consume. Python equivalently provides `EvalItem`, `EvalResults`, `EvalItemResult`, and `EvalScoreResult` to accept per-provider results in a common form.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/IAgentEvaluator.cs#L9-L32  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/EvalItem.cs#L9-L153  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L181-L260  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L304-L354  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L372-L543  

### 2. Purpose of the Converter

The purpose of the Converter is to transform runtime message/response/tool structures into an evaluator-friendly schema with controlled loss. Python's `AgentEvalConverter` converts text, image-like content, function calls, and function results into typed content dicts that an evaluator can read, and also extracts tool definitions from the agent/default options/MCP function surface. `.NET` assembles minimal conversation and tool definitions into evaluator input within `BuildEvalItem()` and `BuildItemsFromResponses()` rather than having a separate named converter class.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L742-L830  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L847-L926  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L213-L269  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L335-L368  

### 3. Purpose of checks and scoring

Local evaluation is intended for fast inner-loop verification and CI smoke gates. `.NET` provides the `EvalCheck` delegate and `EvalChecks` built-ins, while Python provides `keyword_check`, `tool_called_check`, `tool_calls_present`, `tool_call_args_match`, and the `@evaluator` wrapper. Scoring does not end at a simple bool pass/fail; it extends through threshold gates to include provider eval results and rubric-based generated evaluator scores.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/EvalCheck.cs#L5-L10  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/EvalChecks.cs#L23-L105  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/EvalChecks.cs#L126-L217  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1036-L1277  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1354-L1497  

### 4. Purpose of agent/workflow evaluation

Agent evaluation is intended to examine quality at the query-response level and tool usage correctness, while workflow evaluation is intended to separate multi-agent orchestration results into **per-agent + overall** views. Python implements this explicitly in `evaluate_workflow()`, and the `.NET` result type exposes `SubResults` to accommodate workflow breakdown.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1834-L2025  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L2033-L2055  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationResults.cs#L59-L69  

### 5. Purpose of generated/provider evaluator integration

The purpose of generated/provider evaluator integration is to connect cloud judge / rubric evaluator / built-in provider evaluators into the same evaluation pipeline, going beyond local boolean checks. `.NET` connects to the `Microsoft.Extensions.AI.Evaluation` ecosystem through an overload that wraps `IEvaluator` with `MeaiEvaluatorAdapter`, while Python's `FoundryEvals` manages built-in evaluator short names, conversation/tool/ground-truth requirements, and generated rubric evaluator references.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L63-L101  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/GeneratedEvaluatorRef.cs#L6-L54  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_foundry_evals.py#L62-L103  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_foundry_evals.py#L105-L183  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_foundry_evals.py#L220-L260  

### 6. Purpose of test organization

The purpose of test organization in this snapshot is threefold.
1. Fast unit correctness verification
2. Establishing provider-backed integration confidence
3. Fixing wire-level conformance and regression corpus  
`.NET`'s strengths are suffix-based project separation and a trace-driven conformance suite, while Python's strengths are package-wide aggregate tests, provider-sharded integration jobs, and a separate coverage workflow.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L223-L337  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/ConformanceTestBase.cs#L21-L76  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L179-L192  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-tests.yml#L17-L60  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-integration-tests.yml#L41-L67  

### 7. Purpose of golden/trace-driven testing

The purpose of golden/trace-driven testing is to fix “whether the actual wire format and framework output continue to match.” The `.NET` OpenAI hosting test project copies `ConformanceTraces/**` to output, and the test base reads request/response traces from disk and replays them against a test server. Cases such as malformed requests and structured JSON output are also included in the corpus.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests.csproj#L25-L29  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/ConformanceTestBase.cs#L33-L76  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/OpenAIResponsesConformanceTests.cs#L20-L120  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/ConformanceTraces/Conversations/error_invalid_json/request.txt#L1-L6  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/ConformanceTraces/Responses/json_output/response.json#L1-L90  

## Public API

### .NET public API

1. `IAgentEvaluator`  
   - batch-oriented evaluator interface  
   - `EvaluateAsync(IReadOnlyList<EvalItem> items, string evalName = ..., CancellationToken cancellationToken = default)`  
   Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/IAgentEvaluator.cs#L17-L32

2. `AIAgent.EvaluateAsync(...)` extension methods  
   - runs queries directly for evaluation
   - wraps `IEvaluator` with an adapter for evaluation
   - runs multiple evaluators sequentially
   - re-evaluates a pre-existing `AgentResponse`  
   Source:  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L20-L60  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L63-L101  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L104-L149  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L151-L210  

3. `EvalItem`  
   - holds query, response, conversation, tools, context, expected output, expected tool calls, and splitter  
   - supports multimodal conversation and per-turn splitting  
   Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/EvalItem.cs#L9-L170

4. `EvalCheck` / `EvalChecks`  
   - `KeywordCheck`, `ToolCalledCheck`, `ToolCallsPresent`, `ToolCallArgsMatch`, `NonEmpty` and other built-in local checks  
   Source:  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/EvalCheck.cs#L5-L10  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/EvalChecks.cs#L23-L217  

5. `LocalEvaluator`  
   - runs local checks without an API call  
   Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/LocalEvaluator.cs#L10-L65

6. `AgentEvaluationResults`  
   - provider metadata, item results, workflow `SubResults`, detailed item scores, gate helpers  
   Source:  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationResults.cs#L21-L70  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationResults.cs#L71-L159  

7. `GeneratedEvaluatorRef`  
   - reference to a generated rubric evaluator already present in the provider registry  
   - version pinning strongly recommended  
   Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/GeneratedEvaluatorRef.cs#L6-L54

### Python public API

1. `evaluate_agent(...)`  
   - runs queries or re-evaluates pre-existing responses  
   - supports expected output / expected tool calls / repetitions / conversation split  
   Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1630-L1831

2. `evaluate_workflow(...)`  
   - performs per-agent + overall evaluation against the result of `workflow.run()` or an existing `WorkflowRunResult`  
   Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1834-L2025

3. `LocalEvaluator`  
   - runs sync/async checks with a local provider  
   Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1518-L1622

4. `keyword_check`, `tool_called_check`, `tool_calls_present`, `tool_call_args_match`, `@evaluator`  
   - built-in checks and function wrapper  
   Source:  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1061-L1277  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1354-L1497  

5. `EvalItem`, `EvalResults`, `EvalItemResult`, `EvalScoreResult`, `ExpectedToolCall`, `ConversationSplit`  
   - provider-neutral evaluation data/score/result model  
   Source:  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L76-L178  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L181-L260  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L304-L543  

6. `AgentEvalConverter`  
   - converts message/response/tool definitions into evaluator items  
   Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L742-L926

7. `FoundryEvals`, `GeneratedEvaluatorRef`, `evaluate_traces`, `evaluate_foundry_target`  
   - provider integration surface  
   Source:  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_foundry_evals.py#L3-L24  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_foundry_evals.py#L62-L103  

## Detailed execution flow

### 1. .NET agent evaluation execution flow

1. The caller provides queries/evaluator to `AIAgent.EvaluateAsync(...)`.  
2. The internal `RunAgentForEvalAsync()` validates repetition and expected output/tool call counts.  
3. `agent.RunAsync(...)` is executed for each query.  
4. `BuildEvalItem()` bundles the user query, response messages, raw response, and tool definitions to create an `EvalItem`.  
5. The evaluator performs `EvaluateAsync(items, evalName, cancellationToken)` in batch.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L20-L60  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L272-L333  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L335-L368  

### 2. .NET pre-existing response re-evaluation flow

`.NET` can re-evaluate an existing `AgentResponse`. This path reconstructs `EvalItem` via `BuildItemsFromResponses()` without re-running the agent, and a count mismatch in query/response/expected-output/expected-tool-calls fails immediately with `ArgumentException`.  
Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L167-L210  
https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L220-L269

### 3. .NET built-in check and local scoring flow

`.NET`'s `EvalChecks` creates patterns such as string containment, tool-called presence, expected tool args subset match, and non-empty response as `EvalCheck` delegates. `LocalEvaluator` performs each check per item and records a `BooleanMetric`, assigning `EvaluationRating.Good` on success and `EvaluationRating.Unacceptable` on failure. These results are aggregated into `AgentEvaluationResults`.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/EvalChecks.cs#L23-L105  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/EvalChecks.cs#L126-L217  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/LocalEvaluator.cs#L30-L65  

### 4. .NET scoring/gate flow

`AgentEvaluationResults` provides a quality gate API beyond being a simple result container.
- `AllPassed`
- `AssertAllPassed()`
- `AssertScoreAtLeast(...)`  
It can also hold Foundry-style provider metadata (`ReportUrl`, `EvalId`, `RunId`, `Status`, `Error`), `SubResults` for workflow breakdown, per-evaluator summary, and detailed item scores.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationResults.cs#L31-L69  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationResults.cs#L71-L159  

### 5. .NET provider evaluator integration flow

`.NET` connects evaluators in two ways.
1. AF-native `IAgentEvaluator`
2. the path that wraps `Microsoft.Extensions.AI.Evaluation.IEvaluator` with `MeaiEvaluatorAdapter`  
This structure places local evaluators and external judge evaluators under the same `EvaluateAsync(...)` entry point.  
Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L63-L101

### 6. Python AgentEvalConverter flow

Python's converter provides a separate serialization path for evaluators.
- text content: `{"type":"text","text":...}`
- image/media content: `input_image`
- function call: `tool_call`
- function result: `tool_result`  
In particular, if function-call arguments are a string and JSON parsing fails, they are sanitized to `{"_raw_arguments":"[unparseable]"}`. This is a defensive transformation to avoid passing raw/unparseable tool arguments directly to an external evaluation service. Subsequently, `to_eval_item()` assembles input messages + response messages + typed tools into an `EvalItem`.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L768-L830  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L847-L926  

### 7. Python local check/score coercion flow

Python's local evaluation proceeds in two stages.
1. The check function returns one of `CheckResult | bool | float | dict | awaitable`.
2. `_coerce_result()` normalizes this to a `CheckResult`.  
A float score of `>= 0.5` is treated as pass. A `dict` may have a `score` or `passed` key. `@evaluator` introspects the function signature and automatically injects `query`, `response`, `expected_output`, `expected_tool_calls`, `conversation`, `tools`, and `context`.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1036-L1277  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1354-L1497  

### 8. Python LocalEvaluator execution flow

1. `LocalEvaluator(*checks)` accepts a list of sync/async checks.  
2. All checks are executed for each item via `asyncio.gather()`.  
3. An item passes only if “at least one check was executed and all checks passed.” If there are no checks at all, the item is not considered a pass.  
4. Each check has a 1.0/0.0 score and a reason sample in an `EvalScoreResult`.  
5. The aggregate result is reflected in `result_counts`, `per_evaluator`, `items`, and `error` of `EvalResults`.  
Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1518-L1622

### 9. Python evaluate_agent execution flow

1. Singular `queries`, `expected_output`, `responses`, and flat `expected_tool_calls` are normalized to list/nested list form.  
2. `num_repetitions` and length consistency are validated first with `ValueError`.  
3. If `responses` are provided, items are created using the converter without re-running the agent.  
4. If the combination is `queries + agent`, the actual agent is executed repeatedly to create items.  
5. Expected output/tool calls/split strategy are then stamped, and evaluators are executed via `_run_evaluators()`.  
6. Bare check callables are auto-wrapped into `LocalEvaluator` during `_resolve_evaluators()`.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1630-L1831  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L2058-L2065  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L2090-L2116  

### 10. Python evaluate_workflow execution flow

1. Either `workflow_result` or `queries` is required.  
2. In run mode, results of `workflow.run(query)` are accumulated; in post-hoc mode, an existing `WorkflowRunResult` is used.  
3. `_extract_agent_eval_data()` pairs `executor_invoked` / `executor_completed` events, skips internal executors, and extracts agent-specific query/response.  
4. A list of `EvalItem` is created per executor id, and each agent is evaluated separately per evaluator.  
5. If `include_overall`, the final workflow output is also evaluated as a separate `EvalItem`.  
6. Results are returned as one `EvalResults` per provider, with `sub_results` filled in as a per-agent breakdown.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1834-L2025  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L934-L1009  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1012-L1028  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L2033-L2055  

### 11. Python FoundryEvals provider integration flow

`FoundryEvals` resolves built-in evaluator short names to fully-qualified evaluator names and manages the agent/tool/ground-truth evaluator sets. Generated rubric evaluators are referenced via `GeneratedEvaluatorRef`, and version pinning is strongly recommended. A versionless ref points to the latest at runtime, which can break reproducibility and is therefore a warning target. Test consistency is reinforced by `test_foundry_evals.py`.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_foundry_evals.py#L62-L103  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_foundry_evals.py#L105-L183  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_foundry_evals.py#L185-L212  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_foundry_evals.py#L220-L260  

### 12. .NET unit/integration/conformance test organization flow

`.NET` CI generates separate solutions from a single filtered solution using the `*UnitTests*` and `*IntegrationTests*` project name filters. Unit tests run with coverage, integration tests run only on non-PR events, and the `FoundryHostedAgents` category is separated into a distinct higher-cost job.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L223-L337  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Foundry.Hosting.IntegrationTests/README.md#L151-L159  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Foundry.Hosting.IntegrationTests/README.md#L161-L203  

### 13. .NET golden/trace-driven conformance flow

The OpenAI hosting unit test project copies the `ConformanceTraces` folder to build output. `ConformanceTestBase` loads trace files, starts a test server, sends requests to the actual endpoint, and inspects the response wire shape. This approach treats the snapshot corpus as golden data. Both malformed request traces and structured JSON response traces are part of the corpus.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests.csproj#L25-L29  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/ConformanceTestBase.cs#L33-L76  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/OpenAIResponsesConformanceTests.cs#L20-L120  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/ConformanceTraces/Conversations/error_invalid_json/request.txt#L1-L6  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/ConformanceTraces/Responses/json_output/response.json#L1-L90  

### 14. Python unit/integration/coverage organization flow

Python declares `packages/**/tests` and `packages/**/ag_ui_tests` as default test paths in `pyproject.toml` and uses an integration marker. The PR workflow runs aggregate pytest, and the integration workflow splits jobs into unit-only / OpenAI / Azure OpenAI / misc provider, and so on. Coverage is computed in a separate workflow that performs aggregate testing, coverage XML generation, and threshold checking.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L179-L192  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-tests.yml#L17-L60  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-integration-tests.yml#L41-L67  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-integration-tests.yml#L68-L101  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-integration-tests.yml#L165-L255  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-test-coverage.yml#L19-L46  

## State and configuration

### Evaluation feature maturity and available scope

- Python's `EVALS` feature has experimental status at the package level. Even if the root package as a whole carries a stable classifier, the eval feature surface has a separate experimental stage.  
  Source:  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L87-L93  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L68-L70  

- The `.NET` core package is a released package, but evaluation support is compiled only for `net8.0+`. The `Evaluation/**` source is excluded for legacy TFMs.  
  Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Microsoft.Agents.AI.csproj#L36-L42

### Test organization configuration

- Python test discovery is centered on `packages/**/tests`.
- Python integration is separated by the `integration` marker.
- `.NET` separates unit/integration through solution filtering.
- High-cost Foundry hosted integration tests have a separate job and separate infra prerequisites.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L179-L192  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L223-L337  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Foundry.Hosting.IntegrationTests/README.md#L34-L60  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Foundry.Hosting.IntegrationTests/README.md#L151-L203  

### Coverage gate configuration

- The `.NET` workflow uses `COVERAGE_THRESHOLD: 80`, generates a coverage report, and checks the threshold with a PowerShell script.  
  Source:  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L19-L21  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L317-L336  

- Python uses `COVERAGE_THRESHOLD: 85` in a separate coverage workflow and applies a blocking gate to Beta/Production/Stable packages as described in the DEV_SETUP document. Alpha is non-blocking, and DevUI/Lab are excluded from aggregate enforcement.  
  Source:  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-test-coverage.yml#L15-L46  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/DEV_SETUP.md#L176-L190  

## Errors and security

### 1. Distinction between evaluation failure and quality gate failure

The evaluation result model in this snapshot separates “whether the evaluation API executed successfully” from “whether the quality criteria were passed.”
- Python provides `EvalResults.status`, `error`, `items[*].status`, and `raise_for_status()`.
- `.NET` provides `AgentEvaluationResults.Status`, `Error`, `AssertAllPassed()`, and `AssertScoreAtLeast()`.  
That is, infra failures in the evaluator run itself and model quality failures are treated separately.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L372-L543  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationResults.cs#L34-L47  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationResults.cs#L95-L159  

### 2. Protection of sensitive arguments in the Converter path

When function-call arguments fail JSON parsing, Python `AgentEvalConverter` sanitizes them to a `"[unparseable]"` placeholder rather than passing the raw string directly to the external evaluator. This means the evaluation converter is not a simple formatter but a boundary that limits the amount of information in the payload sent to the external judge service.  
Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L786-L795

### 3. Validation boundary

- Python `evaluate_agent()` handles `queries`/`responses`/`expected_output`/`expected_tool_calls`/`num_repetitions` consistency errors with `ValueError`.
- `.NET` `RunAgentForEvalAsync()` and `BuildItemsFromResponses()` handle count mismatches and invalid repetitions with `ArgumentException`.  
This means the evaluation API does not wrap user input consistency problems in a domain-specific eval exception.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1754-L1809  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1910-L1923  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L225-L240  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L281-L300  

### 4. Nature of test data in the conformance corpus

`.NET`'s `ConformanceTraces` is a golden corpus that contains both malformed requests and realistic responses. This data is for the test harness rather than the product runtime, but it functions as a quality/security-sensitive validation asset in the sense of fixing wire-level regression. It is notable that this corpus also covers actual request validation failure paths.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/ConformanceTraces/Conversations/error_invalid_json/request.txt#L1-L6  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/OpenAIConversationsConformanceTests.cs#L1140-L1162  

## .NET implementation

### 1. Public surface centered on agent evaluation

The `.NET` public evaluation surface directly confirmed in this snapshot is centered on agent query execution and pre-existing response evaluation. `IAgentEvaluator` provides a batch contract, and `AgentEvaluationExtensions` supports all of the single evaluator, MEAI adapter, multiple evaluators, and pre-existing responses paths.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/IAgentEvaluator.cs#L9-L32  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L20-L210  

### 2. Built-in local checks and score gate

`.NET` handles local evaluation with `EvalChecks` and `LocalEvaluator`. Built-in checks are general-purpose patterns such as keyword, tool-called, tool-args, and non-empty, and results are aggregated based on `BooleanMetric`. CI gates can be configured with APIs such as `AgentEvaluationResults.AssertAllPassed()` or `AssertScoreAtLeast()`.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/EvalChecks.cs#L23-L217  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/LocalEvaluator.cs#L30-L65  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationResults.cs#L95-L159  

### 3. Generated/provider evaluator integration

`.NET` models cloud-side rubric evaluator references via `GeneratedEvaluatorRef` and treats version pinning as the key to reproducibility. It also supports connecting `Microsoft.Extensions.AI.Evaluation` evaluators directly through an `IEvaluator` adapter overload.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/GeneratedEvaluatorRef.cs#L16-L54  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L63-L101  

### 4. Implementation status related to workflow evaluation

`AgentEvaluationResults.SubResults` provides a structure for workflow evaluation breakdown, and class comments also describe helpers that presuppose workflow evaluations. However, an explicit workflow evaluation public method inside `AgentEvaluationExtensions` was not directly confirmed in the collected evidence of this snapshot. Therefore, while `.NET` accounts for workflow evaluation at the result model level, an explicit `evaluate_workflow()` public API comparable to Python's has not been directly confirmed in the current snapshot source.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationResults.cs#L59-L69  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L13-L15  

### 5. Test organization and conformance

`.NET` clearly separates tests into unit/integration/hosted-integration/conformance. In particular, the OpenAI hosting conformance suite uses a golden-style approach that replays a trace corpus, and Foundry hosted integration tests are described in a separate README covering infra bootstrap and scenario gating.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L223-L337  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/ConformanceTestBase.cs#L21-L76  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Foundry.Hosting.IntegrationTests/README.md#L1-L33  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Foundry.Hosting.IntegrationTests/README.md#L151-L203  

## Python implementation

### 1. Provider-agnostic core evaluation framework

Python places conversation split, expected tool calls, converter, local evaluator, workflow extraction, result gating, and public orchestration functions all in `agent_framework._evaluation`. This structure demonstrates eval functionality in a highly cohesive manner.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L3-L33  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L76-L178  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L181-L260  

### 2. Agent/workflow evaluation exposed as a separate public API

Python exposes `evaluate_agent()` and `evaluate_workflow()` as separate public APIs. Workflow evaluation supports both post-hoc mode and run+evaluate mode, and carries per-agent sub-results within the standard result model.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1630-L1831  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1834-L2025  

### 3. Local checks and function wrapper are more flexible

Python allows not only built-in helpers but also arbitrary functions to be wrapped with `@evaluator` for use as evaluation checks. Accepting all of bool/float/dict/`CheckResult` makes it more flexible than `.NET`.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1354-L1497  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1518-L1622  

### 4. Provider integration is more explicit

Python's `FoundryEvals` has a clearly defined evaluator class and related helpers in code, managing built-in evaluator mapping, tool-aware evaluator set, ground-truth evaluator set, and generated rubric evaluator references all in one place.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_foundry_evals.py#L105-L183  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_foundry_evals.py#L220-L260  

### 5. Strong test matrix and coverage discipline

Despite the eval feature being experimental, Python's test infrastructure is strong. Aggregate tests, provider-sharded integration, retry/timeouts, a separate coverage workflow, and maturity-aware threshold documentation all exist.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-tests.yml#L17-L60  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-integration-tests.yml#L41-L67  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-integration-tests.yml#L68-L101  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-test-coverage.yml#L15-L46  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/DEV_SETUP.md#L176-L190  

### 6. Python conformance harness status

Based on the collected evidence of this snapshot, an explicit trace-driven wire conformance harness equivalent to `.NET OpenAI ConformanceTraces` was not directly confirmed on the Python side. What was confirmed in Python is evaluation unit tests, package tests, integration workflows, and coverage gates. Therefore, rather than “Python has no conformance,” the accurate statement is that **an explicit golden-wire conformance harness cannot be confirmed based on this snapshot's evidence**.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/tests/test_foundry_evals.py#L96-L109  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/tests/test_foundry_evals.py#L116-L217  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-tests.yml#L45-L47  

## Test evidence

### 1. Python evaluation unit tests

`test_foundry_evals.py` verifies `_resolve_evaluator()` short-name mapping, `AgentEvalConverter.convert_message()` transformations for text/tool_call/tool_result, structured object tool result preservation, and so on. That is, provider integration and converter correctness are pinned fairly broadly at the unit level.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/tests/test_foundry_evals.py#L96-L109  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/tests/test_foundry_evals.py#L116-L217  

### 2. .NET agent contract integration tests

`RunTests` and `RunStreamingTests` in `AgentConformance.IntegrationTests` check no-message, string input, `ChatMessage`, multi-message, and session history preservation on top of a generic fixture. This is the layer that pins the **agent contract** itself rather than provider-specific behavior.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/AgentConformance.IntegrationTests/RunTests.cs#L20-L122  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/AgentConformance.IntegrationTests/RunStreamingTests.cs#L20-L117  

### 3. .NET trace-driven conformance tests

`OpenAIResponsesConformanceTests` reads expected text from a request trace, sends a request to a test server, and verifies field presence/object/type/token accounting/wire shape of the response. This is a trace-driven structural conformance check rather than a simple snapshot comparison.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/OpenAIResponsesConformanceTests.cs#L20-L120  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/ConformanceTestBase.cs#L33-L76  

### 4. Coverage gate evidence

- `.NET`: `COVERAGE_THRESHOLD: 80` is declared in the CI workflow environment and gated using a summary JSON.  
- Python: `COVERAGE_THRESHOLD: 85` is declared in the coverage workflow, and the DEV_SETUP document explains differences in enforcement by maturity.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L19-L21  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L333-L336  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-test-coverage.yml#L15-L46  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/DEV_SETUP.md#L187-L190  

## Differences between documentation and code

### 1. Difference between .NET evaluation extension documentation and actual public method surface

The `AgentEvaluationExtensions` class comment mentions evaluation of “agents, responses, and workflow runs.” However, the method scope directly confirmed in this snapshot is limited to agent queries and pre-existing responses paths, and no explicit workflow evaluation public method was confirmed. By contrast, the result type `SubResults` anticipates workflow evaluation. Therefore the documentation description is slightly broader than the implementation evidence.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L13-L15  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L20-L210  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationResults.cs#L59-L69  

### 2. Asymmetry between the repository FAQ's general claim of “conformance testing in both .NET and Python” and the collected evidence

The FAQ states that the framework performs conformance testing across both `.NET` and Python implementations. The collected evidence of this snapshot strongly supports this for `.NET` with an explicit `ConformanceTraces`-based test suite. On the Python side, unit/integration/coverage workflows are abundant, but an explicit trace-driven conformance harness of the same level was not directly confirmed within this collection's scope. Therefore, based on code evidence, `.NET` conformance visibility is higher.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/TRANSPARENCY_FAQ.md#L26-L28  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/ConformanceTestBase.cs#L21-L76  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/tests/test_foundry_evals.py#L96-L109  

### 3. Difference between Python package stability classification and eval feature stage

Many Python root/packages are in a released/stable state, but the `EVALS` feature is marked as experimental in both the package status document and code decorators. That is, package stability and feature stability are not the same. Without documenting this difference, one might mistakenly assume “stable package == stable evaluation API.”  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L17-L18  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L87-L93  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L68-L70  

## Java design decisions

### Decision 1. Introduce a batch-based evaluator SPI

Java must follow the `.NET IAgentEvaluator` and Python `Evaluator` models and have a batch evaluator SPI of the form `List<EvalItem> -> EvalResults`. This is more advantageous than per-item calls for cloud/provider evaluators and score aggregation.  
Evidence:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/IAgentEvaluator.cs#L13-L15  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L687-L718

### Decision 2. Treat the Converter as a first-class API

Java must have a dedicated converter layer, like Python's `AgentEvalConverter`, that transforms runtime `Message`/tool definitions into an evaluator schema. This converter is not a simple formatter; it must also bear responsibility for outbound data minimization such as tool-call argument sanitization.  
Evidence:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L786-L795  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L847-L926  

### Decision 3. Support both a local evaluator and function-style checks

Java should provide a built-in check library like `.NET`, and additionally add a wrapper that makes it easy to connect plain function/lambda scorers like Python's `@evaluator`. This makes it easier to use CI smoke gates and experimental custom judges in the same pipeline.  
Evidence:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/EvalChecks.cs#L23-L217  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1354-L1497  

### Decision 4. Design workflow evaluation as an explicit public API

Java must provide `evaluateWorkflow(...)` as a separate public API, as Python does. The result must officially support an `overall + subResults(per-agent)` structure, and an agent-level interaction extraction helper from an event stream or run result must also be designed together.  
Evidence:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1834-L2025  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L934-L1009  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationResults.cs#L59-L69  

### Decision 5. Treat version pinning as the default operational principle for generated evaluators

Version pinning is always recommended for generated/provider rubric evaluator references, and in a CI gate context it must be treated as effectively required. A latest-floating evaluator breaks replay reproducibility.  
Evidence:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/GeneratedEvaluatorRef.cs#L16-L30  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_foundry_evals.py#L73-L78  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_foundry_evals.py#L249-L257  

### Decision 6. Maintain a trace-driven conformance corpus

When a Java hosting/protocol layer is introduced, a golden/trace-driven corpus equivalent to `.NET ConformanceTraces` must be maintained. Trace corpus replay is far stronger than mock-only unit tests for wire-format regression.  
Evidence:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests.csproj#L25-L29  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/ConformanceTestBase.cs#L33-L76  

### Decision 7. Link coverage gate to package maturity

A policy that adjusts the strength of coverage enforcement by package maturity, as Python does, is useful. Java can also apply a blocking gate to snapshot/stable surfaces and a softer gate or reporting-only gate to alpha/experimental surfaces.  
Evidence:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/DEV_SETUP.md#L187-L190  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-test-coverage.yml#L15-L46  

### Decision 8. Public API separates “evaluation execution” from “quality gate”

The Java result model must provide quality gate helpers like Python's `raise_for_status()` / `.NET AssertAllPassed()`, but must separate them from raw result access. This allows UI/reporting and CI gating to share the same result object.  
Evidence:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L470-L543  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationResults.cs#L95-L159  

## Concrete acceptance scenarios

### Scenario 1. .NET agent evaluation immediately rejects a query count mismatch

- Given: 2 pre-existing responses and 1 query
- When: the `.NET` `EvaluateAsync(responses, queries, ...)` path assembles items
- Then: it must fail with `ArgumentException`.  
Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/AgentEvaluationExtensions.cs#L225-L240

### Scenario 2. .NET local evaluation does not treat an item as passed when there are no checks

- Given: `.NET LocalEvaluator` and an item
- When: the check list for the item is empty
- Then: Java must have a contract equivalent to the design that no automatic pass occurs, being treated as “no pass evidence.”  
The actual Python implementation results in false when there are no checks due to `item_passed = bool(check_results)`. `.NET` LocalEvaluator accepts an array of checks at construction and generates metrics per item.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1562-L1580  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/LocalEvaluator.cs#L35-L64  

### Scenario 3. Python function evaluator coerces a float score to pass/fail

- Given: a function wrapped with `@evaluator` returns `0.7`
- When: `_coerce_result()` normalizes the result
- Then: it must be a pass based on the threshold `0.5`.  
Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1357-L1373

### Scenario 4. Python converter does not pass through unparseable tool arguments as-is

- Given: the arguments in function_call content are a JSON-unparseable string
- When: `AgentEvalConverter.convert_message()` converts to an evaluator message
- Then: it must use `{"_raw_arguments":"[unparseable]"}` instead of the raw arguments.  
Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L786-L795

### Scenario 5. Python evaluate_agent supports responses-only evaluation but requires queries

- Given: pre-existing `responses` are to be evaluated
- When: `evaluate_agent(responses=...)` is called without `queries`
- Then: a `ValueError` indicating that queries are required must be raised.  
Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1766-L1789

### Scenario 6. Python workflow evaluation produces per-agent sub_results

- Given: the workflow run result has multiple `AgentExecutor` events
- When: `evaluate_workflow(include_per_agent=True, include_overall=True)` is called
- Then: executor id-keyed `sub_results` must be included in the `EvalResults` per provider.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L1956-L2025  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/_evaluation.py#L390-L392  

### Scenario 7. A pinned version is recommended for generated evaluators

- Given: a generated rubric evaluator in the provider registry
- When: a versionless reference is used
- Then: even if execution succeeds, it must be subject to a reproducibility warning, and using a pinned version is contractually safer in CI.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Evaluation/GeneratedEvaluatorRef.cs#L16-L30  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/foundry/agent_framework_foundry/_foundry_evals.py#L249-L257  

### Scenario 8. .NET OpenAI hosting conformance suite replays a disk trace

- Given: `ConformanceTraces/Responses/basic/request.json`
- When: a conformance test sends a request to the test server
- Then: it must structurally verify the OpenAI wire contract, including response object shape, output content, token fields, and so on.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/ConformanceTestBase.cs#L33-L76  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/OpenAIResponsesConformanceTests.cs#L20-L120  

### Scenario 9. Coverage gate has a blocking threshold in CI

- Given: a `.NET` test coverage summary or a Python aggregate coverage XML
- When: coverage below the threshold is computed
- Then: the gate must fail at 80% for `.NET` and at 85% for Python.  
Source:  
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