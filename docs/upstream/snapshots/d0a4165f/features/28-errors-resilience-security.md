# 28. Errors, Resilience, Security

## State

- Document status: upstream snapshot analysis document
- Reference snapshot: `d0a4165f170193ba1d026a259af40d35bb7eaefe`
- Analysis scope: error taxonomy, validation boundary, cancellation, timeout, cleanup, retry/resilience ownership, shell/tool/MCP security controls, policy guardrail limitations
- Out of scope:
  - OpenTelemetry, feature telemetry, logging/sensitive data are owned by the observability document
  - The overall scheme for serialization/versioning is owned by the session document; this document mentions it only minimally as a validation boundary example

## Snapshot Summary

In this snapshot, Python provides a **framework-wide exception hierarchy**, leaves validation/programming errors as built-in exceptions, and explicitly states — in both documentation and code — the rule that only domain failures are promoted to Agent Framework-specific exceptions. In contrast, .NET, based on the collected evidence for this snapshot, centers on **package-local exception hierarchies**, and also actively uses built-in exceptions such as `ArgumentException` and `InvalidOperationException`. In other words, Python is closer to “common taxonomy first”, and .NET is closer to “per-domain taxonomy + mixed use of built-ins”.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/exceptions.py#L15-L39  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/exceptions.py#L44-L146  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L220-L307  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative/Exceptions/DeclarativeWorkflowException.cs#L7-L35  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Purview/Exceptions/PurviewException.cs#L7-L25  

The cancellation/timeout/cleanup paths are relatively clear on both sides. The Python declarative HTTP executor does not catch `CancelledError` and propagates it as-is, preserving cancellation semantics, and the shell executor performs cleanup by killing the entire process tree. The .NET shell standardizes exit code `124` and `TimedOut=true` on stateless timeout, and in persistent shell mode, first attempts a graceful interrupt to keep the session alive, then tears down the session if that fails. On the MCP long-running task side, .NET also attempts remote task cancel on a best-effort basis when local cancellation occurs.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_executors_http.py#L154-L156  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_executors_http.py#L206-L221  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_executor.py#L34-L93  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_killtree.py#L50-L133  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellExecutor.cs#L60-L88  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L260-L309  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellSession.cs#L353-L419  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/TaskAwareMcpClientAIFunction.cs#L81-L145  

Security boundaries are defined especially explicitly in the shell/tool domain. Both Python and .NET explicitly state that regex-based policy/delist is **a UX guardrail, not a security boundary**, and place the actual security boundary at **approval-in-the-loop** and **sandbox/container isolation**. Python additionally provides FIDES-based content labeling and safe declarative state path traversal, while .NET has more prominent warnings on the execution boundary side: single-session ownership, approval wrapper defaults, and caution about tool-name collisions.  
Source:  
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

Within the collected evidence of this snapshot, a **common retry decorator spanning the entire runtime** was not directly confirmed. Instead, at the test/CI level, `.NET`'s `RetryFact` and the `--retries` setting in the Python integration workflow clearly exist. Therefore, in the current snapshot, the safer interpretation of retry ownership is “the caller/test/individual provider level explicitly owns it” rather than “the framework core automatically retries universally”. The existence of a production-wide generic retry layer cannot be confirmed from this snapshot's collected evidence alone.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/AgentConformance.IntegrationTests/RunTests.cs#L20-L23  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/AgentConformance.IntegrationTests/RunStreamingTests.cs#L20-L23  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-integration-tests.yml#L61-L67  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-integration-tests.yml#L95-L101  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-integration-tests.yml#L235-L248  

## Original Feature Purpose and Boundary

### 1. Purpose of Error Taxonomy

The purpose of error taxonomy is not simply “define many exceptions”, but to **separate failure causes into a form that can be captured layer by layer**. Python provides a common exception skeleton for agent/chat client/integration/tool/workflow/middleware/settings, and even presents rules for separating validation bugs from domain failures. .NET, based on the evidence in this snapshot, shows per-package exception trees such as Purview and Declarative Workflows, while general state/input validation beyond that is delegated to built-in exceptions.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/exceptions.py#L15-L39  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/exceptions.py#L44-L146  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/exceptions.py#L169-L263  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L220-L307  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative/Exceptions/DeclarativeActionException.cs#L7-L35  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative/Exceptions/DeclarativeModelException.cs#L7-L35  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Purview/Exceptions/PurviewRequestException.cs#L7-L40  

### 2. Purpose of Validation Boundary

The validation boundary is an operational contract that determines “which errors to wrap in a framework-specific exception and which errors to expose as built-in”. The Python coding standard says to use built-ins such as `ValueError`, `TypeError`, and `RuntimeError` for constructor arguments, invalid state, and configuration mistakes, and to promote only domain-level external failures to the AF exception branch. The .NET collected evidence similarly uses built-in exceptions for option validation and incorrect object state.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L223-L227  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L278-L307  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L87-L98  

### 3. Purpose of Cancellation / Timeout / Cleanup

The purpose of this layer is not to “detect failures quickly”, but to **preserve cancellation intent**, **leave no child processes or remote tasks behind**, and **leave the session in a recoverable state whenever possible**. The Python declarative executor excludes cancel from exception translation to preserve intent, and the shell executor performs a tree kill. .NET shell distinguishes stateless and persistent modes and uses different cleanup strategies, and MCP tasks perform remote cancel on a best-effort basis.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_executors_http.py#L154-L156  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_executor.py#L52-L53  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_killtree.py#L3-L17  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellExecutor.cs#L60-L67  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/TaskAwareMcpClientAIFunction.cs#L110-L145  

### 4. Boundary of Retry / Resilience Ownership

The resilience directly confirmed in the collected evidence of this snapshot is primarily **translation (wrapping), timeout, cancel propagation, cleanup, and fallback**. For example, the Python declarative HTTP executor wraps transport failures as `DeclarativeActionError` but does not retry, and the .NET MCP wrapper falls back to a non-augmented tool call when the task API is unavailable. In contrast, a common runtime policy for “how many times to retry” is not directly confirmed.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_executors_http.py#L206-L221  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/TaskAwareMcpClientAIFunction.cs#L87-L107  

### 5. Purpose of Shell / Tool / MCP Security Controls

The purpose of this scope lies not in capability containment per se, but in **making execution boundaries explicit**, **approval defaults**, **sandbox recommendations**, and **a contract that does not hide residual risk**. Python demonstrates shell policy limitations, unsafe opt-out acknowledgement, FIDES labeling, and declarative path safety. .NET explicitly states shell approval wrapping, single-session ownership for the persistent executor, auto-approval conflict warnings, and Docker isolation.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_policy.py#L8-L35  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_tool.py#L127-L166  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/security.py#L43-L66  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/security.py#L91-L124  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellExecutor.cs#L75-L88  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L32-L50  

## Public APIs

### .NET Public APIs

1. `DeclarativeWorkflowException`, `DeclarativeActionException`, `DeclarativeModelException`  
   - Separates declarative workflow/action/model errors.  
   Source:  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative/Exceptions/DeclarativeWorkflowException.cs#L7-L35  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative/Exceptions/DeclarativeActionException.cs#L7-L35  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative/Exceptions/DeclarativeModelException.cs#L7-L35  

2. `PurviewException` family  
   - Separates Purview failure modes into `PurviewAuthenticationException`, `PurviewPaymentRequiredException`, `PurviewRateLimitException`, `PurviewRequestException`, and others.  
   Source:  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Purview/Exceptions/PurviewException.cs#L7-L25  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Purview/Exceptions/PurviewAuthenticationException.cs#L7-L26  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Purview/Exceptions/PurviewPaymentRequiredException.cs#L7-L24  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Purview/Exceptions/PurviewRateLimitException.cs#L7-L26  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Purview/Exceptions/PurviewRequestException.cs#L11-L40  

3. `ShellExecutor.RunAsync(...)`, `ShellExecutor.AsAIFunction(..., bool requireApproval = true)`  
   - Defines the contract for shell execution and approval-wrapped AIFunction exposure.  
   Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellExecutor.cs#L49-L91

4. `LocalShellExecutorOptions`  
   - Sets execution boundaries including `Policy`, `Timeout`, and `AcknowledgeUnsafe`.  
   Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutorOptions.cs#L12-L90

5. `ShellPolicy`  
   - A deny/allow/custom callback-based policy pre-filter.  
   Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellPolicy.cs#L99-L180  
   https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellPolicy.cs#L183-L220

6. `ShellResult`  
   - A result envelope with `ExitCode`, `Duration`, `Truncated`, and `TimedOut`.  
   Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellResult.cs#L8-L23

### Python Public APIs

1. `AgentFrameworkException` and branch hierarchy  
   - Includes `AgentException`, `ChatClientException`, `IntegrationException`, `ToolException`, `MiddlewareException`, `WorkflowException`, `SettingNotFoundError`, and others.  
   Source:  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/exceptions.py#L15-L39  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/exceptions.py#L44-L146  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/exceptions.py#L169-L263  

2. `UserInputRequiredException`  
   - An exception for propagating user-input requests such as approval/oauth from sub-agent/tool invocations to the upper response, rather than swallowing them as a generic tool error.  
   Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/exceptions.py#L184-L210

3. `OpenAIContentFilterException`  
   - Structurally preserves Azure OpenAI content filter information.  
   Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/openai/agent_framework_openai/_exceptions.py#L49-L90

4. `PurviewServiceError` family  
   - Includes `PurviewAuthenticationError`, `PurviewPaymentRequiredError`, `PurviewRateLimitError`, `PurviewRequestError`, and others.  
   Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/purview/agent_framework_purview/_exceptions.py#L15-L32

5. `ShellPolicy`, `LocalShellTool`  
   - Exposes `denylist`, `allowlist`, `custom`, `approval_mode`, `acknowledge_unsafe`, and `timeout`.  
   Source:  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_policy.py#L70-L125  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_tool.py#L141-L176  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_tool.py#L232-L277  

6. `agent_framework.security` export surface  
   - `SecureMCPToolProxy`, label/middleware/security tool APIs are exported. However, the full details of the internal implementation behavior cannot be confirmed from the collected evidence alone.  
   Source:  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/security.py#L43-L66  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L98-L103  

## Detailed Execution Flows

### 1. Python Error Taxonomy and Validation Boundary Flow

1. All AF branch exceptions share `AgentFrameworkException` as a common parent.  
2. The base constructor logs at debug level by default, but when `log_level=None`, it can also be used as a quiet control-flow exception.  
3. The coding standard mandates using built-in exceptions first for cases such as invalid constructor arguments, wrong object state, and request validation failures, and promoting only external/domain failures to AF branch exceptions.  
4. Therefore, the validation boundary of the Python implementation reads as: “programming errors/configuration errors use built-ins; service/domain failures use AF taxonomy”.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/exceptions.py#L15-L39  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L220-L227  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L278-L307  

### 2. Python User-Input / Tool Escalation Flow

`UserInputRequiredException` is a control-flow exception to prevent content items such as `oauth_consent_request` and `function_approval_request` — emitted by a sub-agent/tool — from being converted into a generic tool failure. This exception preserves `contents` and is created with `log_level=None` to avoid noisy logs.  
Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/exceptions.py#L184-L210

### 3. Python Declarative HTTP Executor Cancellation / Wrapping Flow

1. The executor dispatches the request.  
2. `httpx.TimeoutException` or `TimeoutError` is translated into `DeclarativeActionError`.  
3. General `httpx.HTTPError` and arbitrary transport exceptions are also wrapped as the same domain error.  
4. However, `asyncio.CancelledError` is intentionally not caught, so cancellation propagates upward in its original form.  
5. This implementation separates “failure translation” from “cancellation preservation”.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_executors_http.py#L154-L156  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_executors_http.py#L206-L221  

### 4. Python Shell Timeout / Cleanup Flow

1. `run_stateless()` spawns a subprocess and waits for stdout/stderr collection via `asyncio.wait_for(proc.communicate(), timeout=...)`.  
2. On timeout, it calls `kill_process_tree(proc)`.  
3. After the kill, it drains as much queued output as possible and places it in the result envelope.  
4. `kill_process_tree()` terminates then kills the parent and descendants via the psutil path if available, and uses a stdlib fallback otherwise.  
5. On the Windows fallback, `taskkill.exe` is resolved to an absolute path to reduce the risk of PATH poisoning.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_executor.py#L34-L93  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_killtree.py#L10-L17  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_killtree.py#L39-L61  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_killtree.py#L64-L133  

### 5. .NET Shell Validation / Timeout / Cleanup Flow

1. The `LocalShellExecutor` constructor filters invalid options first.
   - If `MaxOutputBytes <= 0`, throws `ArgumentOutOfRangeException`
   - If both `Shell` and `ShellArgv` are provided simultaneously, throws `ArgumentException`
2. In the stateless run, a linked cancellation token combines caller cancellation and timeout.  
3. On timeout, the process tree is killed and exit code `124` and `TimedOut=true` are placed in the result.  
4. In the persistent session, on timeout, the current command is first interrupted to give the session a chance to survive.  
5. The sentinel is then checked again; if recovered, the same session is retained; otherwise the session is closed so that the next call spawns a new shell.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L87-L98  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L246-L309  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellSession.cs#L353-L419  

### 6. .NET MCP Long-Running Task Resilience Flow

1. The wrapper attaches task metadata TTL if `DefaultTimeToLive` is set.  
2. If `CallToolAsTaskAsync()` throws `MethodNotFound`, it is treated as server capability drift and falls back to a non-augmented tool call.  
3. If the poll result is `Completed`, the result is retrieved; if `Cancelled`, an `OperationCanceledException` is thrown.  
4. On local cancellation, if the `CancelRemoteTaskOnLocalCancellation` option is enabled, remote cancel is attempted on a best-effort basis within a 5-second budget.  
5. A cancel failure does not obscure the original cancellation reason.  
Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/TaskAwareMcpClientAIFunction.cs#L81-L145

### 7. Python Declarative State Path Safety Flow

1. State traversal distinguishes between dict paths and object attribute paths.  
2. Dict lookup allows arbitrary non-empty keys, but object attribute access allows only safe declarative identifier formats.  
3. Dangerous segments such as `__class__` are blocked with a warning and a default return value.  
4. `Workflow.Inputs` is treated as read-only and throws `ValueError` on modification.  
This path is not direct code execution control but a security control intended to mitigate reflective exfiltration via declarative workflow state access.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_declarative_base.py#L385-L405  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_declarative_base.py#L419-L438  

### 8. Retry / Resilience Ownership Flow

The runtime resilience directly confirmed in the collected evidence is:
- translating failures into domain errors
- writing the timeout envelope
- process/session cleanup
- task capability mismatch fallback
- remote cancel best-effort  
That is as far as it goes.  
In contrast, “automatic retry” was not directly confirmed as a cross-cutting policy in the collected runtime production source. The level at which retry is confirmed is the test/CI layer.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_executors_http.py#L206-L221  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/TaskAwareMcpClientAIFunction.cs#L98-L107  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/AgentConformance.IntegrationTests/RunTests.cs#L20-L23  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-integration-tests.yml#L61-L67  

## State and Configuration

### Error Taxonomy State

- A common exception hierarchy exists in the Python core.
- Python provider/domain packages add specialized exceptions on top of this hierarchy.
- In .NET, package-local exception trees are directly confirmed based on the evidence collected for this snapshot.
- Whether a `.NET` global common exception taxonomy is defined at the Python level cannot be confirmed from the collected evidence of this snapshot alone.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/exceptions.py#L44-L146  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/purview/agent_framework_purview/_exceptions.py#L15-L32  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Purview/Exceptions/PurviewException.cs#L7-L25  

### Timeout Default Configuration

- The `.NET` local shell's `DefaultTimeout` constant is 30 seconds, but the default value of `LocalShellExecutorOptions.Timeout` is `null`, which means “timeout disabled”. That is, 30 seconds is a recommended value, not an automatic default.  
  Source:  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L54-L60  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutorOptions.cs#L75-L80  

- The Python `LocalShellTool` has `timeout: float | None = 30.0` on its constructor surface.  
  Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_tool.py#L141-L156

### Approval / Unsafe Opt-Out Default Configuration

- The Python `LocalShellTool` defaults to `approval_mode="always_require"`, and switching to `never_require` requires `acknowledge_unsafe=True`.  
  Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_tool.py#L127-L166

- `.NET ShellExecutor.AsAIFunction()` defaults to `requireApproval=true`.  
  Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellExecutor.cs#L75-L88

### Policy Default Configuration

- Both Python and .NET use a **default empty policy**.
- That is, if an operator does not explicitly provide a denylist/allowlist, non-empty commands are allowed by default.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_policy.py#L23-L35  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_policy.py#L85-L86  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellPolicy.cs#L121-L127  

## Errors and Security

### Distinction Between Validation Errors and Domain Errors

The Python coding standard requires using built-in exceptions for validation/programming mistakes and AF exception branches for external service/domain failures. This rule is the core boundary of the exception taxonomy. `.NET` also uses built-in exceptions for shell option validation.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L223-L227  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L91-L98  

### Cancellation Is Treated Differently from Failure

The Python declarative HTTP executor does not wrap cancellation as a domain error. The `.NET` MCP wrapper also uses `OperationCanceledException` when a remote task ends with a `Cancelled` status. Such implementations mean that cancellation is treated as a control signal different from ordinary failure.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_executors_http.py#L154-L156  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/TaskAwareMcpClientAIFunction.cs#L116-L126  

### Timeout Has a Separate Result Envelope

The `.NET` shell does not end a timeout only as an exception; it standardizes it with `ShellResult.TimedOut=true` and `ExitCode=124`. The Python shell also attempts to collect as much output as possible after a timeout and return it as an envelope.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellResult.cs#L11-L23  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellExecutor.cs#L61-L64  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_executor.py#L70-L93  

### Cleanup Is Post-Failure Hygiene, Not a Security Boundary

Process tree kill, session teardown, and remote task cancel are all resilience behaviors intended to “prevent damage from spreading” and “prevent resource leaks”. This does not itself constitute an authorization/security control. The actual security boundaries are approval and sandbox.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_killtree.py#L3-L17  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L43-L50  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_policy.py#L31-L35  

### Limitations of Policy Guardrails

Both Python and .NET explicitly explain that regex pattern filtering is vulnerable to the following bypass techniques.
- variable expansion
- interpreter escape
- command substitution / encoded payload
- envvar splicing
- alternative tools
- PowerShell native destructive verbs  
That is, the policy filter is an operator UX guardrail, not a defense against hostile model input.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_policy.py#L8-L35  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellPolicy.cs#L104-L118  

### Scope of Tool/MCP Security Controls

- Python exports `SecureMCPToolProxy` and includes it in the FIDES security surface, but detailed runtime behavior cannot be confirmed from the collected evidence alone.  
- What is directly confirmed in `.NET` MCP is operational safety behavior such as TTL, capability drift fallback, and remote cancel; a separate MCP authorization/security model cannot be confirmed from the collected evidence alone.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/security.py#L43-L66  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L98-L103  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/TaskAwareMcpClientAIFunction.cs#L81-L145  

## .NET Implementation

### 1. Per-Package Exception Hierarchy

The .NET implementation directly confirmed in this snapshot centers on per-package exception hierarchies such as `PurviewException` and `DeclarativeWorkflowException`. `PurviewRequestException` preserves the HTTP status code so that callers can branch without parsing the message.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Purview/Exceptions/PurviewException.cs#L7-L25  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Purview/Exceptions/PurviewRequestException.cs#L13-L23  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative/Exceptions/DeclarativeWorkflowException.cs#L7-L35  

### 2. Use of Built-In Validation Exceptions

`LocalShellExecutor` does not create custom framework exceptions for option validation, but uses `ArgumentOutOfRangeException` and `ArgumentException`. This effectively forms an operational boundary similar to the Python rule of “expose developer input errors as built-ins”.  
Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L87-L98

### 3. Shell Security Boundary and Session Ownership

The `.NET` shell implementation explicitly states the following.
- Since the local shell executes directly on the host, approval-in-the-loop is the security boundary.
- The persistent executor is owned by a single conversation/single user.
- Singleton/shared use is inappropriate due to shared mutable state.
- Approval wrapping of `AsAIFunction()` is the default.
- Care must be taken in choosing tool names, as tool name collision can cause auto-approval rules to be applied incorrectly.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L15-L17  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L32-L50  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellExecutor.cs#L34-L47  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellExecutor.cs#L75-L88  

### 4. Timeout / cleanup semantics

The `.NET` shell returns timeout as a standardized envelope and attempts session recovery in persistent mode. This is a stronger operational stability contract than a simple process kill.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L260-L309  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellSession.cs#L356-L419  

### 5. MCP long-running task resilience

The `.NET` MCP side includes defensive implementations for cancellation and capability drift. If the task API is unavailable, it falls back to an inner call; if there is a local cancellation, it attempts remote cancel on a best-effort basis.  
Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/TaskAwareMcpClientAIFunction.cs#L87-L145

## Python Implementation

### 1. Common Exception Hierarchy and Branch Rules

Python has a clear common exception hierarchy and branch symmetry.
- `AgentException`
- `ChatClientException`
- `IntegrationException`
- `WorkflowException`
- `ToolException`
- `MiddlewareException`  
Each branch shares repeating patterns such as `InvalidAuth`, `InvalidRequest`, `InvalidResponse`, and `ContentFilter`.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/exceptions.py#L44-L146  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L220-L276  

### 2. Built-In Validation Priority Principle

The Python coding standard explicitly mandates using built-in exceptions for configuration/parameter validation. Therefore, API users benefit from “validation bugs and domain failures not mixing on the same exception surface”.  
Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L223-L227  
https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L278-L307

### 3. Content Filter / Integration-Specific Exceptions

`OpenAIContentFilterException` structurally exposes content filter codes and per-category filter results. The Purview side also separates auth/payment/rate-limit/request into distinct types.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/openai/agent_framework_openai/_exceptions.py#L55-L90  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/purview/agent_framework_purview/_exceptions.py#L15-L32  

### 4. Declarative Cancellation Preservation

The Python declarative HTTP executor preserves workflow cancellation semantics by not wrapping cancel. This design makes the boundary “cancel is not a failure” explicit at the production code level.  
Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_executors_http.py#L154-L156  
https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_executors_http.py#L214-L221

### 5. Shell/tool security posture

The Python shell tool keeps policy as a pre-filter only, and places the actual boundary at approval and sandbox. `approval_mode="never_require"` is not permitted without an explicit `acknowledge_unsafe=True`.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_policy.py#L8-L35  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_tool.py#L127-L166  

### 6. FIDES and State Path Safety

Python also provides content labeling and declarative state path safety as security controls outside of shell/tool. However, the FIDES-related APIs are at the experimental feature stage.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/security.py#L91-L124  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/security.py#L212-L260  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_declarative_base.py#L392-L405  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L98-L103  

## Test Evidence

### .NET Test Evidence

- `ShellPolicy` default construction allows non-empty commands.
- Tests intentionally document that even with an operator denylist, bypasses such as `${RM:=rm} -rf /` are possible.
- `TimedOut=true` and `ExitCode=124` are guaranteed on timeout.
- A rejected command throws `ShellCommandRejectedException`.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Tools.Shell.UnitTests/LocalShellExecutorTests.cs#L31-L63  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Tools.Shell.UnitTests/LocalShellExecutorTests.cs#L147-L165  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Tools.Shell.UnitTests/LocalShellExecutorTests.cs#L180-L231  

### Python Test Evidence

- Tests verify that the `ShellPolicy()` default is an empty denylist.
- `LocalShellTool(approval_mode="never_require")` fails without `acknowledge_unsafe`.
- Tests intentionally verify that representative denylist bypasses continue to be permitted, directly documenting in the tests that “this is a guardrail, not a boundary”.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/tests/test_policy.py#L23-L68  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/tests/test_policy.py#L71-L80  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/tests/test_security.py#L57-L107  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/tests/test_security.py#L124-L165  

- Declarative state path safety tests collectively verify dunder-based reflective escape blocking, read-only input mutation blocking, and valid dict-key traversal preservation.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/tests/test_declarative_state_path_safety.py#L89-L129  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/tests/test_declarative_state_path_safety.py#L160-L218  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/tests/test_declarative_state_path_safety.py#L235-L258  

- Core security tests verify the FIDES feature stage marker, label combine behavior, variable store behavior, and legacy label-key compatibility.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/test_security.py#L78-L99  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/test_security.py#L101-L144  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/test_security.py#L146-L199  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/tests/test_security.py#L240-L252  

### Retry-Related Test/CI Evidence

- `.NET` conformance/integration contract tests use `RetryFact`.
- The Python integration workflow configures pytest retries.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/AgentConformance.IntegrationTests/RunTests.cs#L20-L23  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/AgentConformance.IntegrationTests/RunStreamingTests.cs#L20-L23  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-integration-tests.yml#L61-L67  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-integration-tests.yml#L95-L101  

## Documentation and Code Differences

### 1. Difference Between the FAQ's General Statements and the Shell Code's Concrete Boundaries

`TRANSPARENCY_FAQ` recommends containerization/sandboxing, security measures, and human oversight. However, the production code states much more strongly that “regex policy is not a security control”, and also leaves the default denylist empty. When writing operational documentation, the code-level threat model should take priority over the FAQ's general statements.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/TRANSPARENCY_FAQ.md#L77-L87  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_policy.py#L8-L35  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellPolicy.cs#L104-L127  

### 2. Potential Misunderstanding of .NET Default Timeout

The `.NET` code has a constant `LocalShellExecutor.DefaultTimeout = 30s`, but the actual option default is `null`, so the timeout is not applied automatically. That is, the constant is a “recommended value”, not an “effective default”. This difference is easy to misunderstand when only looking at the API surface.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L54-L60  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutorOptions.cs#L75-L80  

### 3. Whether Generic Runtime Retry Is Guaranteed

The repository clearly has test/CI retry configurations, but no framework-wide generic retry layer was directly confirmed in the collected production source. Therefore, writing “the framework provides automatic retry” could be an overstatement. A more accurate statement based on this snapshot's evidence is: “cleanup/fallback/cancel handling exists; generic retry cannot be confirmed”.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/AgentConformance.IntegrationTests/RunTests.cs#L20-L23  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-integration-tests.yml#L61-L67  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_executors_http.py#L206-L221  

## Java Design Decisions

### Decision 1. Introduce a common exception taxonomy while keeping validation in the built-in family

Java would benefit from having a common branch hierarchy like Python.
- `FrameworkException`
- `AgentException`
- `ChatClientException`
- `IntegrationException`
- `WorkflowException`
- `ToolException`  
However, invalid argument / invalid state / programmer errors should retain built-in/standard exceptions such as `IllegalArgumentException` and `IllegalStateException`. This makes the validation boundary clear.  
Evidence:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L223-L227  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/exceptions.py#L44-L146  

### Decision 2. Exclude cancellation from failure translation

The Java workflow/tool transport layer must not wrap `CancellationException` or thread interruption as a generic domain exception. Cancellation is a control signal different from ordinary failure and must be propagated as-is.  
Evidence:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_executors_http.py#L154-L156  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/TaskAwareMcpClientAIFunction.cs#L116-L126  

### Decision 3. Reflect timeout separately in the result envelope in addition to exceptions

Shell/code execution tools must explicitly include the timeout status in the result structure. For example, a convention such as `timedOut=true` and `exitCode=124` makes it easy for the upper agent/harness to handle deterministically.  
Evidence:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellResult.cs#L11-L23  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellExecutor.cs#L61-L64  

### Decision 4. Perform cleanup at the process-tree / remote-task level

The Java shell/tool runtime must not only kill the parent process but also clean up descendants, and long-running MCP task types must also attempt remote cancel on a best-effort basis when local cancellation occurs.  
Evidence:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_killtree.py#L50-L133  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/TaskAwareMcpClientAIFunction.cs#L123-L145  

### Decision 5. Restrict the persistent executor to a session-owned resource

The Java persistent shell/code executor must be designed as a resource owned by a single conversation/session. Allowing singleton sharing will cause state leakage and command interleaving problems.  
Evidence:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L32-L41  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellExecutor.cs#L34-L47  

### Decision 6. Document shell policy only as a guardrail, not a security feature

In Java as well, regex-based policy/denylist/allowlist must be documented only as a UX guardrail. The fact that approval and sandbox are the actual security boundaries must be made consistent across documentation, code, and tests.  
Evidence:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_policy.py#L8-L35  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellPolicy.cs#L104-L118  

### Decision 7. Unsafe opt-out must always require explicit acknowledgement

If Java shell/tool APIs have approval-required defaults, the opt-out path that enables unattended mode must require an explicit acknowledgement flag.  
Evidence:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_tool.py#L159-L166  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/ShellExecutor.cs#L75-L88  

### Decision 8. If a declarative/state API exists, enforce safe path traversal

If Java provides declarative workflow/state path interpolation, it must distinguish between dict/map keyed paths and object/property paths, and must prohibit paths corresponding to reflective escapes (such as `__class__`-style segments).  
Evidence:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_declarative_base.py#L392-L405  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/tests/test_declarative_state_path_safety.py#L95-L103  

### Decision 9. Keep retry as a caller-owned policy, not core implicit behavior

Since generic runtime retry was not directly demonstrated in this snapshot, it is safer for Java to have callers/test/individual provider adapters or app policies explicitly own it, rather than inserting silent retries in the core library. Test/CI retry and runtime retry must be documented separately.  
Evidence:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/AgentConformance.IntegrationTests/RunTests.cs#L20-L23  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-integration-tests.yml#L61-L67  

## Concrete Acceptance Scenarios

### Scenario 1. Validation errors are exposed as built-in exceptions

- Given: `.NET LocalShellExecutor` is constructed with both `Shell` and `ShellArgv` provided simultaneously
- When: the constructor executes
- Then: `ArgumentException` must be raised, not a framework-specific shell exception.  
Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Tools.Shell/LocalShellExecutor.cs#L91-L98

### Scenario 2. Timeout is returned as a standard envelope

- Given: `.NET LocalShellExecutor` is created in stateless mode with a timeout of 250ms
- When: `sleep 30` or `Start-Sleep -Seconds 30` is executed
- Then: the result must be `TimedOut=true` and `ExitCode=124`.  
Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Tools.Shell.UnitTests/LocalShellExecutorTests.cs#L200-L210

### Scenario 3. Disabled timeout is actually disabled

- Given: `.NET LocalShellExecutor` with `Timeout = null`
- When: a short `echo ok` command is executed
- Then: the result must be `TimedOut=false`.  
Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Tools.Shell.UnitTests/LocalShellExecutorTests.cs#L213-L226

### Scenario 4. Cancel is not wrapped as a domain error

- Given: Python declarative HTTP executor
- When: the underlying handler raises `asyncio.CancelledError`
- Then: the executor must not wrap it as `DeclarativeActionError` and must propagate it as-is.  
Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_executors_http.py#L154-L156  
https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_executors_http.py#L214-L221

### Scenario 5. Shell policy default is permissive and is not a security boundary

- Given: a default `ShellPolicy()` or a default shell policy with no denylist
- When: a non-empty command such as `rm -rf /` is evaluated
- Then: policy alone may not reject it, and this is the intended default behavior.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/tests/test_policy.py#L23-L28  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Tools.Shell.UnitTests/LocalShellExecutorTests.cs#L147-L153  

### Scenario 6. Policy bypass is a known residual risk

- Given: operator-supplied denylist
- When: variable indirection techniques such as `${RM:=rm} -rf /` are used
- Then: the denylist may miss this, and tests intentionally fix this residual risk.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/tests/test_security.py#L152-L165  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Tools.Shell.UnitTests/LocalShellExecutorTests.cs#L157-L165  

### Scenario 7. Unsafe unattended shell use requires explicit acknowledgement

- Given: Python `LocalShellTool`
- When: `approval_mode="never_require"` and `acknowledge_unsafe=False`
- Then: the constructor must throw `ValueError`.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/tests/test_security.py#L99-L107  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/tools/agent_framework_tools/shell/_tool.py#L159-L166  

### Scenario 8. Declarative state reflective escape is blocked

- Given: a plain object is stored in Python declarative state
- When: a reflective path such as `Local.obj.__class__.__init__.__globals__.os.environ` is queried
- Then: the result must be a default value/None, and sensitive environment data must not be exposed.  
Source:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/tests/test_declarative_state_path_safety.py#L95-L103  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/declarative/agent_framework_declarative/_workflows/_declarative_base.py#L392-L405  

### Scenario 9. MCP local cancellation attempts remote cancel on a best-effort basis

- Given: a `.NET` long-running MCP task wrapper with `CancelRemoteTaskOnLocalCancellation=true`
- When: the local cancellation token is cancelled
- Then: the wrapper must attempt remote cancel within a maximum budget of 5 seconds and then rethrow the original cancellation.  
Source: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/TaskAwareMcpClientAIFunction.cs#L123-L145

## Source inventory

### Production source: Python

- `python/packages/core/agent_framework/exceptions.py`  
  - Common exception hierarchy, `UserInputRequiredException`, workflow/tool/middleware/settings branches  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/exceptions.py#L15-L39  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/exceptions.py#L44-L146  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/core/agent_framework/exceptions.py#L169-L263  

- `python/CODING_STANDARD.md`  
  - validation vs domain failure boundary, exception hierarchy design principles, no compatibility aliases  
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
  - threat model, no default patterns, guardrail limitations, evaluation order  
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

### Tests and CI

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

### Parent Documents

- general security / sandboxing guidance  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/TRANSPARENCY_FAQ.md#L77-L87