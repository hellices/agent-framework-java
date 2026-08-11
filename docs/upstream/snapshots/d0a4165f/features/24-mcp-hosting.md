# 24. MCP hosting

## 범위

이 문서는 Microsoft Agent Framework의 **MCP(Model Context Protocol) server hosting과 client/server boundary**만 다룬다. 대상은 다음이다.

- MCP server hosting helper의 범위와 한계
- tools exposure와 tool schema/result mapping
- resources/prompts exposure의 부재 또는 경계
- stdio / streamable HTTP transport ownership
- session/lifecycle와 session key policy
- sampling / MCP tasks의 hosting 경계
- auth / error / stream mapping
- .NET / Python 구현 차이
- Java 설계 결정

반대로 **MCP client tools 자체의 상세 동작**은 별도 “07 문서” 범위로 본다. 이 문서에서는 client-side MCP task wrapper나 remote MCP invocation은 **hosting boundary를 설명하는 데 필요한 최소한**만 다룬다. 또한 generic hosting lifecycle, OpenAI Responses, A2A, AG-UI, Foundry hosted runtime, DevUI, Telegram/ChatKit은 각각 별도 문서 범위다. (출처: [Python hosting-mcp README scope](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L1-L20), [Python hosting-mcp AGENTS boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/AGENTS.md#L19-L24), [.NET IMcpToolHandler scope](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative/IMcpToolHandler.cs#L10-L41))

## 요약

이 저장소 기준으로 **generic MCP server hosting helper는 Python에만 명시적으로 존재**하고, `.NET`에는 대응하는 범용 MCP hosting 패키지가 보이지 않는다. Python의 `agent-framework-hosting-mcp`는 app-owned MCP server를 위한 **tool adapter와 conversion helper**만 제공하며, `Server`, handler registration, stdio/streamable HTTP transport, authentication, authorization, session trust, concurrency, deployment는 모두 애플리케이션 책임으로 남긴다. 따라서 Python MCP hosting은 “MCP tool surface를 Agent Framework run surface에 붙이는 seam”이지 full host framework가 아니다. 반면 `.NET` repo-local source에서 MCP와 가장 가까운 것은 `Microsoft.Agents.AI.Mcp`의 **client-side long-running task wrapper**와 `Microsoft.Agents.AI.Workflows.Declarative.Mcp.DefaultMcpToolHandler`의 **outbound MCP client bridge**다. 이것들은 hosting adapter라기보다 server가 외부 MCP server를 **호출**하는 쪽이며, 특히 `.NET` declarative handler는 `HttpTransportMode.StreamableHttp`를 강제하고 credential origin pinning을 넣어 cross-origin credential leakage를 막는다. tools exposure는 양 언어 모두 핵심이지만, prompts/resources exposure는 repo-local hosting surface에 거의 없다. Python helper는 오직 `Tool` 하나를 생성·호출하는 API만 export하고, `.NET` declarative MCP handler도 실제로는 `InvokeToolAsync(...)`와 reserved `tools/list`만 다루며 `prompts/*`나 `resources/*` hosting API는 없다. MCP sampling과 experimental tasks도 hosting helper의 core concern이 아니라 별도 client/protocol concern으로 남아 있으며, Python README는 sampling-only `ToolUseContent`를 `CallToolResult.content`에 넣지 말라고 명시하고, tasks/progress/streaming partial result도 app-owned concern으로 둔다. (출처: [Python hosting-mcp README overview](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L1-L20), [Python hosting-mcp AGENTS boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/AGENTS.md#L1-L24), [Python __all__ exports only tools](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/__init__.py#L1-L22), [.NET Mcp package description](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/Microsoft.Agents.AI.Mcp.csproj#L19-L22), [.NET DefaultMcpToolHandler summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L21-L29), [reserved tools/list operation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L33-L38), [.NET StreamableHttp pinning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L233-L247), [Python sampling/tasks boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L148-L174))

---

## 목적

MCP hosting support의 목적은 Agent Framework target을 **native MCP SDK가 이해하는 `Tool` surface**로 노출하거나, 역으로 MCP `tools/call` style input/result를 Agent Framework run/result로 연결하는 것이다. 여기서 핵심은 “하나의 agent 또는 workflow를 하나의 MCP tool처럼 보이게 하는 것”이지, MCP 전체 server protocol(`prompts/list`, `resources/read`, sampling, transport session lifecycle)을 모두 프레임워크가 소유하는 것이 아니다.

Python README는 이를 매우 분명하게 말한다. package는:

- `mcp_to_run(...)`
- `mcp_from_run(...)`
- `AgentMCPTool`
- `WorkflowMCPTool`

만 제공하고, low-level MCP `Server`, request context, transport, authentication, deployment는 surrounding application이 책임진다. `.NET` 쪽에서 가장 가까운 `DefaultMcpToolHandler`도 declarative workflow 안에서 MCP tool invocation을 추상화하는 client bridge이며, MCP server 자체를 만들어 주지는 않는다. (출처: [Python hosting-mcp README public surface](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L5-L20), [Python hosting-mcp AGENTS summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/AGENTS.md#L1-L18), [.NET IMcpToolHandler abstracts invoke only](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative/IMcpToolHandler.cs#L10-L41), [.NET DefaultMcpToolHandler purpose](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L21-L29))

## 경계

### 이 기능이 하는 일
- agent 또는 workflow에서 MCP `Tool` schema를 derive/generate
- MCP `tools/call` arguments를 Agent Framework run values로 변환
- Agent Framework `AgentResponse` 또는 `WorkflowRunResult`를 MCP `ContentBlock`들로 변환
- app-owned MCP server에 연결될 helper API 제공
- outbound MCP tool invocation을 위해 streamable HTTP client를 구성하는 bridge 제공(.NET declarative workflow path)  
  (출처: [Python AgentMCPTool summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_agent_tool.py#L22-L29), [Python WorkflowMCPTool summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_workflow_tool.py#L23-L29), [.NET DefaultMcpToolHandler InvokeToolAsync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L66-L102))

### 이 기능이 하지 않는 일
- generic MCP server object 생성
- handler registration
- stdio transport 관리
- streamable HTTP session lifecycle 전부
- authentication / authorization policy
- session identifier 신뢰 정책
- concurrency control
- prompts/resources registry exposure
- sampling callback contract
- MCP tasks lifecycle의 host-owned orchestration

Python README와 AGENTS 문서는 이 경계를 직접 명시하고, `CallToolResult`가 single final result라는 점도 분명히 적는다. `.NET` declarative handler도 `IMcpToolHandler` 하나만 노출하며 `prompts/*`/`resources/*` host API는 없다. (출처: [Python hosting-mcp README boundaries](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L18-L20), [Python AGENTS boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/AGENTS.md#L19-L24), [Python CallToolResult final result note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L154-L159), [.NET IMcpToolHandler tool-only contract](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative/IMcpToolHandler.cs#L17-L41))

---

## 성숙도

| 영역 | .NET | Python |
|---|---|---|
| Generic MCP hosting helper | 범용 package 없음 | `agent-framework-hosting-mcp` = `alpha` |
| MCP client/task wrapper | `Microsoft.Agents.AI.Mcp` = `alpha` | 별도 full hosting이 아니라 helper/agent integrations 분산 |
| Declarative workflow MCP bridge | 존재 (`DefaultMcpToolHandler`) | N/A |
| First-party app-owned MCP server example | 범용 helper 없음 | integration test로 `StreamableHTTPSessionManager` 예시 |

출처:
- [Python PACKAGE_STATUS hosting-mcp alpha](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L37-L40)
- [.NET Mcp csproj alpha](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/Microsoft.Agents.AI.Mcp.csproj#L1-L22)
- [.NET declarative MCP csproj description](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/Microsoft.Agents.AI.Workflows.Declarative.Mcp.csproj#L20-L26)

---

## 공개 API·타입

## Python hosting helper
- `AgentMCPTool`
- `WorkflowMCPTool`
- `mcp_to_run`
- `mcp_from_run`  
  (출처: [Python hosting-mcp __all__](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/__init__.py#L1-L22))

## .NET repo-local MCP-adjacent surface
- `IMcpToolHandler`
- `DefaultMcpToolHandler`
- `McpTaskOptions`
- `McpClientTaskExtensions.ListAgentToolsWithTaskSupportAsync(...)`
- internal `TaskAwareMcpClientAIFunction`  
  (출처: [.NET IMcpToolHandler](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative/IMcpToolHandler.cs#L10-L41), [.NET DefaultMcpToolHandler](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L29-L64), [.NET McpTaskOptions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/McpTaskOptions.cs#L7-L39), [.NET client task extensions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/McpClientTaskExtensions.cs#L13-L60), [.NET TaskAwareMcpClientAIFunction](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/TaskAwareMcpClientAIFunction.cs#L15-L147))

## 공개 surface에서 빠져 있는 것
repo-local hosting/public surface에는 다음이 없다.

- MCP prompt hosting API
- MCP resource hosting API
- generic stdio server host
- generic streamable HTTP server host

Python helper의 public export가 tool adapters only라는 점과 `.NET` tool-only `IMcpToolHandler`는 이 부재를 뒷받침한다. (출처: [Python hosting-mcp __all__ only tools](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/__init__.py#L16-L22), [.NET IMcpToolHandler tool-only contract](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative/IMcpToolHandler.cs#L17-L41))

---

## Tools / resources / prompts exposure

## Tools exposure

### Python
Python helper의 핵심은 “agent 하나 또는 workflow 하나를 MCP `Tool` 하나로 노출”하는 것이다.

- `AgentMCPTool`는 target agent의 name/description을 기반으로 `Tool` definition을 생성한다.
- `parameters`는 app-owned extra JSON Schema properties를 추가한다.
- `chat_option_parameters`는 schema에도 들어가고 명시적으로 Agent Framework chat options로 복사된다.
- `session_id_parameter`가 있으면 tool call마다 `AgentState` get/run/set을 수행한다.  
  (출처: [AgentMCPTool constructor and schema rules](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_agent_tool.py#L31-L87), [Tool schema generation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_agent_tool.py#L89-L120), [call_tool with session persistence](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_agent_tool.py#L134-L183))

`WorkflowMCPTool`는 workflow start executor의 input type에서 `inputSchema`를 derive한다. object-shaped input은 top-level JSON object schema가 되고, primitive input은 configurable `argument_name` property 아래에 래핑된다. (출처: [WorkflowMCPTool schema derivation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_workflow_tool.py#L62-L90), [primitive wrapping and validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_workflow_tool.py#L91-L99))

### .NET
`.NET` declarative MCP bridge는 server-side tool **노출**이 아니라, declarative workflow에서 remote MCP server의 tool을 **호출**하는 surface다. 다만 reserved `toolName == "tools/list"`를 통해 remote MCP server의 tool discovery 결과를 JSON text로 반환하는 special operation을 지원한다. 즉 `.NET` repo-local MCP exposure는 “내가 MCP server가 된다”보다 “내 workflow가 외부 MCP server의 tools를 본다”에 가깝다. (출처: [DefaultMcpToolHandler ListToolsToolName](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L33-L38), [list tools branch in InvokeToolAsync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L76-L82), [SerializeToolsList](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L389-L421))

## Resources exposure

Python `mcp_from_run(...)`는 Agent Framework output content를 MCP result blocks로 바꾸면서:

- external URI → `ResourceLink`
- binary non-image/audio → `EmbeddedResource(BlobResourceContents)`
- optional app-owned `additional_properties["uri"]`를 binary resource uri로 사용, 없으면 `af://binary` fallback

을 수행한다. 하지만 이는 **tool result payload 안의 resource-like content**일 뿐, MCP server의 `resources/list` 또는 `resources/read` endpoint를 노출하는 기능은 아니다. (출처: [Python mcp_from_run resource mapping](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_conversion.py#L65-L155), [README note on app-owned binary resource URI](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L143-L146))

`.NET` declarative bridge는 inbound `CallToolResult.ContentBlock`를 `AIContent`로 바꾸면서 `ResourceLinkBlock`을 `UriContent`로 매핑한다. 이 역시 **resource result consumption**이지 resources exposure API는 아니다. (출처: [DefaultMcpToolHandler ConvertContentBlock](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L345-L365), [CreateAdditionalProperties for ResourceLinkBlock](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L367-L387))

## Prompts exposure

repo-local hosting surface에는 MCP `prompts/list`, `prompts/get`, prompt template hosting helper가 보이지 않는다.

- Python hosting-mcp public API는 `Tool` adapters와 run/result conversion만 export한다.
- `.NET` `IMcpToolHandler`도 `InvokeToolAsync(...)` 하나만 정의한다.

따라서 prompt exposure는 이 범위에서는 **지원되지 않거나 app-owned custom MCP SDK composition**에 맡겨진 것으로 보는 것이 코드 우선 해석이다. (출처: [Python hosting-mcp __all__](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/__init__.py#L16-L22), [.NET IMcpToolHandler single method](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative/IMcpToolHandler.cs#L17-L41))

---

## Client / server boundary

## Python

Python helper package는 boundary를 명확히 긋는다.

- helper가 소유하는 것:
  - tool schema derive
  - call arguments → run values conversion
  - final result → `ContentBlock[]` conversion
  - optional `AgentState` get/run/set
- app이 소유하는 것:
  - native MCP `Server`
  - handler registration
  - request context
  - stdio 또는 streamable HTTP transport
  - auth/authz
  - session key trust
  - concurrency
  - deployment

README와 AGENTS 문서가 모두 이 점을 반복한다. (출처: [Python hosting-mcp README boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L18-L20), [Python AGENTS boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/AGENTS.md#L19-L24), [AgentMCPTool schema/execute only](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_agent_tool.py#L22-L29))

## .NET

`.NET`에는 repo-local 범용 MCP server hosting helper가 보이지 않는다. 대신 존재하는 것은:

1. `Microsoft.Agents.AI.Mcp`의 **client-side** long-running task wrapper
2. `DefaultMcpToolHandler`의 **declarative workflow outbound MCP client bridge**

즉 `.NET` repo-local MCP support는 “MCP server를 host한다”보다는 “existing MCP server에 안전하게 client로 연결하거나 workflow에서 호출한다” 쪽이다. 이 문서에서 이를 상세 구현 대신 **boundary reference**로만 다루는 이유도 여기에 있다. (출처: [.NET Mcp package description](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/Microsoft.Agents.AI.Mcp.csproj#L19-L22), [.NET TaskAwareMcpClientAIFunction summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/TaskAwareMcpClientAIFunction.cs#L15-L41), [.NET DefaultMcpToolHandler summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L21-L29))

---

## 상세 실행 흐름

## Python agent tool adapter

1. host가 `AgentMCPTool(target, ...)`를 만든다.
2. `list_tools()`는 target agent metadata에서 native MCP `Tool` definition 하나를 생성한다.
3. `call_tool(name, arguments)`가 호출되면:
   - tool name 검증
   - `mcp_to_run(arguments, argument_name=..., chat_option_arguments=...)`
   - `session_id_parameter`가 있으면 session load
   - `target.run(...)`
   - `mcp_from_run(result)`로 `ContentBlock[]` 생성  
   (출처: [AgentMCPTool.list_tools](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_agent_tool.py#L89-L120), [AgentMCPTool.call_tool](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_agent_tool.py#L134-L183), [mcp_to_run](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_conversion.py#L19-L62))

## Python workflow tool adapter

1. host가 `WorkflowMCPTool(target, ...)`를 만든다.
2. `_input_adapter(workflow)`가 start executor input type 하나를 읽는다.
3. `list_tools()`는 input type의 JSON Schema를 derive한다.
4. `call_tool(...)`는 arguments를 그 타입으로 validate한 뒤 `workflow.run(..., stream=False)`를 호출한다.
5. `mcp_from_run(result)`는 completed outputs만 `ContentBlock[]`로 만든다.
6. workflow가 external input을 요구하면 adapter는 `ValueError`를 던지고 host가 continuation contract를 설계해야 한다.  
   (출처: [WorkflowMCPTool._input_adapter](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_workflow_tool.py#L83-L90), [Workflow schema generation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_workflow_tool.py#L62-L81), [workflow input and call_tool](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_workflow_tool.py#L91-L147), [external input error](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_workflow_tool.py#L100-L121))

## Python end-to-end streamable HTTP example

integration test는 실제 app-owned MCP server composition 예를 보여 준다.

1. native `Server("hosting-mcp-integration")`
2. `@server.list_tools()` / `@server.call_tool()`에 `AgentMCPTool` 연결
3. `StreamableHTTPSessionManager`로 streamable HTTP transport lifecycle 구성
4. Starlette `Mount("/", app=session_manager.handle_request)`
5. 별도의 `MCPStreamableHTTPTool` client로 HTTP 호출

즉 helper는 transport를 만들지 않지만, **streamable HTTP와 실제 잘 맞물린다**는 것을 integration test가 입증한다. (출처: [Python MCP integration test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/tests/hosting_mcp/test_integration.py#L55-L118))

## .NET declarative workflow bridge

1. workflow action 또는 handler가 `IMcpToolHandler.InvokeToolAsync(serverUrl, serverLabel, toolName, arguments, headers, connectionName, ct)`를 호출한다.
2. `DefaultMcpToolHandler`는 `(url,label,connection,headersHash)` cache key로 `McpClient`를 재사용한다.
3. `httpClientProvider`가 있으면 per-server auth `HttpClient`를 주입받고, 없으면 owned `HttpClient`를 만든다.
4. owned client는:
   - `UseCookies=false`
   - `AllowAutoRedirect=false`
   - origin pinning handler
   - `HttpTransportMode.StreamableHttp`
   를 사용한다.
5. `toolName == "tools/list"`면 `ListToolsAsync()`를 실행해 JSON text result를 만든다.
6. 일반 tool이면 `CallToolAsync()`를 수행하고 `CallToolResult.ContentBlock`들을 `AIContent`로 변환한다. (출처: [cache key and GetOrCreateClientAsync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L150-L189), [CreateClientAsync transport options](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L191-L247), [tools/list branch](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L76-L82), [call tool branch](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L84-L102))

---

## stdio / streamable HTTP

## Python

Python hosting helper는 transport를 소유하지 않는다. README와 AGENTS 모두 **stdio 또는 streamable HTTP transport는 app-owned**라고 명시한다. 실 integration test는 `StreamableHTTPSessionManager`를 사용해 HTTP path를 보여 주지만, 패키지 자체가 stdio helper를 제공하지는 않는다. (출처: [Python README transport ownership](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L171-L174), [Python AGENTS transport boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/AGENTS.md#L21-L24), [Python integration test StreamableHTTPSessionManager](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/tests/hosting_mcp/test_integration.py#L66-L88))

## .NET

repo-local `.NET` code에서 generic MCP server host의 stdio/HTTP transport 선택 surface는 보이지 않는다. 오히려 `DefaultMcpToolHandler`는 **client side outbound transport**로 `StreamableHttp`를 강제한다. 이는 server-advertised cross-origin SSE endpoint나 redirect를 피하기 위한 보안 선택이다. 따라서 `.NET` MCP는 현재 transport 관점에서도 **hosting보다 outbound client bridge**에 가깝다. (출처: [DefaultMcpToolHandler TransportMode.StreamableHttp](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L233-L247), [origin pinning rationale](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L425-L449))

---

## Session / lifecycle

## Python

### Agent session persistence
`AgentMCPTool`는 `session_id_parameter`를 설정하면 one mutable conversation key semantics로 동작한다.

- call마다 `AgentState.get_or_create_session(session_id)`
- `target.run(..., session=session)`
- 완료 후 `state.set_session(session_id, session)`

branching(`previous_response_id` style)은 제공하지 않으며, 애플리케이션이 source/destination ids를 별도 계약으로 설계해야 한다. 또한 같은 session id에 대한 concurrent calls serialize도 host 책임이다. (출처: [README session_id_parameter section](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L111-L136), [AgentMCPTool session path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_agent_tool.py#L168-L183))

### Workflow lifecycle
`WorkflowMCPTool`는 workflow instance statefulness 때문에 concurrent independent calls가 필요하면 `WorkflowState(..., cache_target=False)` factory path를 쓰라고 문서화한다. checkpoint restoration과 human-in-the-loop continuation은 application-owned contract다. (출처: [README workflow host note](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L98-L109), [WorkflowMCPTool init doc](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_workflow_tool.py#L31-L50))

## .NET

`.NET` `DefaultMcpToolHandler`의 lifecycle은 “client cache + owned HttpClient lifecycle”이다.

- cache key별 `McpClient` 재사용
- owned `HttpClient`는 handler disposal 시에만 dispose
- user-provided `HttpClient`는 dispose하지 않음
- `DisposeAsync()`가 모든 cached clients와 owned HttpClients를 정리

즉 이것은 server session이 아니라 **outbound MCP connection/session lifecycle**이다. (출처: [client cache fields](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L41-L44), [DisposeAsync lifecycle](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L121-L148))

---

## Sampling / tasks boundary

## Python

Python hosting helper는 sampling과 experimental MCP tasks를 **server hosting concern으로 소유하지 않는다**.

- `mcp_from_run(...)`는 `CallToolResult.content`를 대상으로 하므로 sampling-only `ToolUseContent`를 넣지 않는다.
- MCP sampling callbacks는 separate response contract를 사용해야 한다.
- `CallToolResult`는 single final result이며 partial streaming result로 변환되지 않는다.
- progress notifications와 experimental tasks도 application-owned protocol concern이다. (출처: [README ToolUseContent boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L148-L152), [README final result and tasks boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L154-L159), [AGENTS same boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/AGENTS.md#L47-L53))

## .NET

repo-local `.NET`에는 MCP tasks가 존재하지만 `Microsoft.Agents.AI.Mcp` client package 소유다. `McpTaskOptions`와 `TaskAwareMcpClientAIFunction`은:

- `CallToolAsTaskAsync`
- `PollTaskUntilCompleteAsync`
- `GetTaskResultAsync`
- local cancellation 시 optional remote `tasks/cancel`

을 구현하지만, 이것은 **hosting helper가 아니라 client-side tool wrapper**다. 따라서 이 문서에서는 존재와 경계만 기록한다. (출처: [McpTaskOptions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/McpTaskOptions.cs#L7-L39), [TaskAwareMcpClientAIFunction summary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/TaskAwareMcpClientAIFunction.cs#L15-L41), [poll/cancel logic](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/TaskAwareMcpClientAIFunction.cs#L110-L146), [ListAgentToolsWithTaskSupportAsync wrapping policy](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/McpClientTaskExtensions.cs#L19-L33))

---

## Streaming / result mapping

## Python

### No partial MCP tool result streaming
Python helper는 tool result를 streaming partial chunks로 바꾸지 않는다. `mcp_from_run(...)`는 final `ContentBlock[]`만 리턴한다. streamable HTTP transport가 여러 MCP messages를 실을 수 있어도, 그것이 곧 `CallToolResult.content` incremental streaming으로 바뀌는 것은 아니다. (출처: [README no partial content blocks](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L154-L159))

### Result content mapping
`mcp_from_run(...)`는 다음을 수행한다.

- `text` → `TextContent`
- external `uri` → `ResourceLink`
- inline image/audio data → `ImageContent` / `AudioContent`
- other binary data → `EmbeddedResource(BlobResourceContents)`
- unsupported content (예: `function_call`)는 tool result에서 omit

따라서 MCP server hosting helper는 “tool result가 resource-like blocks를 가질 수는 있지만, resources server surface를 제공하지는 않는다”는 경계를 유지한다. (출처: [mcp_from_run mapping](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_conversion.py#L65-L155))

## .NET

`.NET` declarative MCP bridge도 `CallToolResult.ContentBlock`를 최종 `AIContent`로 변환한다.

- error result이면 text content `"Error: ..."`를 만든다.
- normal result이면 every `ContentBlock`를 `ToAIContent()` 또는 fallback `UriContent` / `TextContent`로 바꾼다.

역시 중요한 점은 **one completed result mapping**이지 tool result streaming host가 아니라는 점이다. (출처: [PopulateResultContent error path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L305-L343), [ConvertContentBlock](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L345-L365))

---

## Auth / error / 보안

## Python

- helper는 request authentication/authorization을 하지 않는다.
- session id를 쓸 때 host가 authenticate/authorize해야 한다.
- MCP tool schema는 app-owned contract이므로 option type/range 검증도 native MCP schema가 책임진다.
- invalid argument selection이나 invalid data URI는 `ValueError`로 surface된다. (출처: [README auth/session trust](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L124-L136), [README schema validation ownership](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L53-L56), [mcp_to_run validation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_conversion.py#L44-L62), [mcp_from_run invalid data URI](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_conversion.py#L107-L113))

## .NET

`.NET` `DefaultMcpToolHandler`는 auth와 error handling에서 더 적극적이다.

### Per-server auth
- `httpClientProvider(serverUrl, ct)`를 통해 per-server authenticated `HttpClient`를 주입받을 수 있다. (출처: [httpClientProvider docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L47-L64))

### Credential leakage 방지
owned `HttpClient`는:
- `UseCookies=false`
- `AllowAutoRedirect=false`
- `OriginPinningHandler`
- `TransportMode = StreamableHttp`

를 사용한다. `OriginPinningHandler`는 cross-origin request에 `Authorization`, `Proxy-Authorization`, `Cookie`를 제거한다. 즉 malicious MCP server가 cross-origin SSE endpoint나 redirect를 광고해도 credentials가 새 origin으로 가지 않게 방어한다. (출처: [handler creation with safe defaults](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L206-L247), [OriginPinningHandler docs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L425-L449), [StripCredentialHeadersOnCrossOrigin](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L485-L513))

### Error mapping
- `CallToolResult.IsError == true`면 content blocks에서 text를 모아 `"Error: ..."` 형태의 `TextContent`로 변환한다.
- non-error but unknown content block은 fallback `TextContent(block.ToString())`가 된다. (출처: [PopulateResultContent error mapping](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L305-L326), [ConvertContentBlock fallback](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L345-L364))

---

## .NET / Python 차이

1. **범용 server hosting helper 존재 여부**
   - Python: 명시적 `AgentMCPTool` / `WorkflowMCPTool` helper 존재
   - .NET: 범용 server hosting helper 없음, outbound client bridge와 task wrapper만 repo-local로 존재  
   (출처: [Python hosting-mcp __all__](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/__init__.py#L16-L22), [.NET IMcpToolHandler only](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative/IMcpToolHandler.cs#L17-L41))

2. **transport ownership**
   - Python: stdio/streamable HTTP 모두 host app ownership
   - .NET: repo-local implementation은 outbound `StreamableHttp` client bridge만 보유  
   (출처: [Python README transport ownership](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L171-L174), [.NET StreamableHttp transport](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L233-L247))

3. **surface area**
   - Python: tool schema derive + run conversion + final result conversion
   - .NET: declarative workflow invoke bridge + client-side task support  
   (출처: [Python AgentMCPTool/WorkflowMCPTool](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L58-L96), [.NET TaskAwareMcpClientAIFunction](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/TaskAwareMcpClientAIFunction.cs#L15-L41))

4. **prompts/resources exposure**
   - Python: tool exposure only, resources/prompts hosting helper 없음
   - .NET: tool invocation only, `tools/list` special-case만 존재  
   (출처: [Python __all__ tool-only exports](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/__init__.py#L16-L22), [.NET ListToolsToolName](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L33-L38))

5. **sampling/tasks**
   - Python hosting helper: sampling/tasks app-owned boundary
   - .NET: tasks support exists but client package 소유, hosting helper 소유 아님  
   (출처: [Python tasks boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L154-L159), [.NET McpTaskOptions](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/McpTaskOptions.cs#L7-L39))

---

## .NET 구현과 테스트

## 구현
- `.NET` repo-local MCP-adjacent hosting surface는 `IMcpToolHandler` / `DefaultMcpToolHandler`다.
- 이는 declarative workflow에서 remote MCP server tool invocation을 client-bridge 형태로 수행한다.
- `StreamableHttp` 강제, header hash별 client cache, origin pinning, `tools/list` special operation, `CallToolResult` → `AIContent` mapping을 구현한다. (출처: [DefaultMcpToolHandler summary and fields](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L21-L44), [InvokeToolAsync](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L66-L102), [CreateClientAsync secure transport](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L206-L247))

## 테스트
- `McpTaskOptionsTests`는 client-side task wrapper의 sane defaults를 검증한다. (출처: [McpTaskOptionsTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Mcp.UnitTests/McpTaskOptionsTests.cs#L7-L18))
- `ListAgentToolsWithTaskSupportTests`는 task-capable MCP tool wrapping policy를 검증한다. 이는 hosting helper가 아니라 client boundary evidence다. (출처: [ListAgentToolsWithTaskSupportTests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Mcp.UnitTests/ListAgentToolsWithTaskSupportTests.cs#L15-L54))
- `TaskAwareMcpClientAIFunctionTests`는 task-aware invocation, TTL propagation, cancellation -> `tasks/cancel`, failed task handling을 검증한다. 역시 client-side concern이며 이 문서에서는 경계 reference로만 쓴다. (출처: [TaskAwareMcpClientAIFunctionTests happy path](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Mcp.UnitTests/TaskAwareMcpClientAIFunctionTests.cs#L17-L35), [TTL propagation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Mcp.UnitTests/TaskAwareMcpClientAIFunctionTests.cs#L37-L67), [cancellation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Mcp.UnitTests/TaskAwareMcpClientAIFunctionTests.cs#L69-L117))

---

## Python 구현과 테스트

## 구현
- `AgentMCPTool`와 `WorkflowMCPTool`가 generic MCP server hosting seam이다.
- `mcp_to_run()`은 MCP JSON arguments를 `AgentRunArgs`로 바꾼다.
- `mcp_from_run()`은 AF response를 `ContentBlock[]`로 바꾼다.
- integration test는 native MCP `Server` + `StreamableHTTPSessionManager`와 실제 조합되는지 검증한다. (출처: [Python hosting-mcp __init__](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/__init__.py#L1-L22), [AgentMCPTool implementation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_agent_tool.py#L22-L183), [WorkflowMCPTool implementation](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_workflow_tool.py#L23-L147), [mcp_from_run conversion](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_conversion.py#L65-L155), [integration test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/tests/hosting_mcp/test_integration.py#L55-L118))

## 테스트
- `test_agent_tool.py`는 schema generation, extra parameters, session persistence, required session parameter semantics를 검증한다. (출처: [agent tool tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/tests/hosting_mcp/test_agent_tool.py#L47-L156))
- `test_workflow_tool.py`는 object/primitive schema derive, structured output serialization, external input rejection, multiple input types rejection을 검증한다. (출처: [workflow tool tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/tests/hosting_mcp/test_workflow_tool.py#L47-L131))
- `test_conversion.py`는 text/resource/image/audio/binary mapping과 invalid data URI handling을 검증한다. (출처: [conversion tests](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/tests/hosting_mcp/test_conversion.py#L10-L144))
- `test_integration.py`는 app-owned MCP server + streamable HTTP path에서 hosted agent tool 호출이 실제 HTTP로 round-trip 되는지 검증한다. (출처: [integration test](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/tests/hosting_mcp/test_integration.py#L55-L118))

---

## 문서 차이

가장 큰 차이는 **“MCP hosting”이라는 이름이 암시하는 범위와 실제 코드 범위의 차이**다. Python 패키지 이름은 `hosting-mcp`지만, README와 AGENTS 문서가 명시하듯 full host를 주지 않고 tool adapter와 conversion seam만 준다. 즉 package 이름만 보면 server lifecycle/transport까지 포함할 것 같지만, 실제로는 deliberately thin adapter다. (출처: [Python hosting-mcp README first paragraph](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L1-L20), [Python AGENTS boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/AGENTS.md#L19-L24))

또한 `.NET` 쪽은 이와 반대로 package 이름 `Microsoft.Agents.AI.Mcp`가 있어도 범용 server hosting surface를 제공하지 않는다. 실제 repo-local code는 client-side task wrapper와 declarative workflow outbound bridge다. 따라서 code-first 관점에서는 “.NET MCP hosting helper”보다 “.NET MCP client/server boundary helpers”라고 부르는 편이 더 정확하다. (출처: [.NET Mcp csproj description](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/Microsoft.Agents.AI.Mcp.csproj#L19-L22), [.NET IMcpToolHandler contract](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative/IMcpToolHandler.cs#L10-L41))

---

## Java 결정

## 권장 아키텍처

### 1. `agent-framework-hosting-mcp`
- Python helper와 같은 역할
- `AgentMcpToolAdapter`, `WorkflowMcpToolAdapter`
- `mcpToRun`, `mcpFromRun`
- tool exposure only
- prompts/resources hosting은 포함하지 않음

### 2. `agent-framework-hosting-mcp-spring`
- Spring MVC/WebFlux에 MCP SDK `Server`를 조합하는 host binder
- stdio / streamable HTTP는 host profile/launcher가 선택
- auth filter, session id trust policy, concurrency policy는 host가 주입

### 3. `agent-framework-mcp-client`
- MCP task-aware client wrapper
- sampling/tasks 등 client-side protocol concern을 분리
- 본 문서의 hosting artifact와 분리

## 정책
- 기본 public boundary는 vendor SDK type 전체를 노출하기보다 JSON-like schema/result adapter 중심이 좋다.
- prompt/resource exposure는 tools와 별도 artifact 또는 별도 interface로 분리해야 한다.
- streamable HTTP / stdio lifecycle은 transport launcher가, tool mapping은 hosting adapter가 소유하도록 분리하는 것이 좋다.

---

## Acceptance scenarios

1. **Tool exposure**
   - agent 하나를 MCP `Tool` 하나로 노출할 수 있어야 한다.
   - workflow 하나를 start executor input schema 기반 `Tool` 하나로 노출할 수 있어야 한다.
   - app-owned 추가 parameters와 chat option parameters가 drift 없이 schema와 실행 양쪽에 반영되어야 한다.

2. **Transport boundary**
   - helper는 stdio/streamable HTTP transport를 강제하지 않고, app-owned MCP SDK composition에 붙을 수 있어야 한다.
   - streamable HTTP path에서 실제 HTTP round-trip이 가능해야 한다.

3. **Session**
   - `session_id_parameter`를 사용할 경우 같은 key 아래 mutable conversation semantics로 state를 이어갈 수 있어야 한다.
   - 같은 session key에 대한 concurrency serialization은 host가 담당해야 함이 문서와 API에서 명확해야 한다.

4. **Workflow**
   - workflow input type이 정확히 하나일 때만 schema derivation이 가능해야 한다.
   - external input을 요구하는 workflow는 adapter가 silent success가 아니라 explicit error를 내야 한다.

5. **Resources/prompts boundary**
   - tool result 안의 resource-like content(`ResourceLink`, `EmbeddedResource`)는 지원하되, `resources/*` / `prompts/*` server surface는 별도 contract로 분리되어야 한다.

6. **Sampling/tasks boundary**
   - hosting helper는 sampling-only `ToolUseContent`를 `CallToolResult.content`에 넣지 않아야 한다.
   - long-running tasks/progress notifications는 hosting helper가 아니라 app-owned protocol logic 또는 client-side wrapper에 남아 있어야 한다.

7. **Security**
   - authenticated server/client credentials가 cross-origin redirect나 server-advertised alternate origin에 유출되지 않아야 한다.
   - session identifiers는 bearer credential로 취급되지 않아야 한다.

---

## Source inventory

### Docs
- [python/packages/hosting-mcp/README.md](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L1-L175)
- [python/packages/hosting-mcp/AGENTS.md](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/AGENTS.md#L1-L54)

### Python production source
- [python/packages/hosting-mcp/agent_framework_hosting_mcp/__init__.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/__init__.py#L1-L22)
- [python/packages/hosting-mcp/agent_framework_hosting_mcp/_agent_tool.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_agent_tool.py#L22-L184)
- [python/packages/hosting-mcp/agent_framework_hosting_mcp/_workflow_tool.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_workflow_tool.py#L23-L147)
- [python/packages/hosting-mcp/agent_framework_hosting_mcp/_conversion.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/agent_framework_hosting_mcp/_conversion.py#L19-L155)

### Python tests
- [python/packages/hosting-mcp/tests/hosting_mcp/test_agent_tool.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/tests/hosting_mcp/test_agent_tool.py#L47-L156)
- [python/packages/hosting-mcp/tests/hosting_mcp/test_workflow_tool.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/tests/hosting_mcp/test_workflow_tool.py#L47-L131)
- [python/packages/hosting-mcp/tests/hosting_mcp/test_conversion.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/tests/hosting_mcp/test_conversion.py#L10-L144)
- [python/packages/hosting-mcp/tests/hosting_mcp/test_integration.py](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/tests/hosting_mcp/test_integration.py#L55-L118)

### .NET production source
- [dotnet/src/Microsoft.Agents.AI.Mcp/Microsoft.Agents.AI.Mcp.csproj](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/Microsoft.Agents.AI.Mcp.csproj#L1-L39)
- [dotnet/src/Microsoft.Agents.AI.Mcp/McpTaskOptions.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/McpTaskOptions.cs#L7-L39)
- [dotnet/src/Microsoft.Agents.AI.Mcp/McpClientTaskExtensions.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/McpClientTaskExtensions.cs#L13-L60)
- [dotnet/src/Microsoft.Agents.AI.Mcp/TaskAwareMcpClientAIFunction.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Mcp/TaskAwareMcpClientAIFunction.cs#L15-L147)
- [dotnet/src/Microsoft.Agents.AI.Workflows.Declarative/IMcpToolHandler.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative/IMcpToolHandler.cs#L10-L41)
- [dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L21-L514)

### .NET tests
- [dotnet/tests/Microsoft.Agents.AI.Mcp.UnitTests/McpTaskOptionsTests.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Mcp.UnitTests/McpTaskOptionsTests.cs#L7-L18)
- [dotnet/tests/Microsoft.Agents.AI.Mcp.UnitTests/ListAgentToolsWithTaskSupportTests.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Mcp.UnitTests/ListAgentToolsWithTaskSupportTests.cs#L15-L54)
- [dotnet/tests/Microsoft.Agents.AI.Mcp.UnitTests/TaskAwareMcpClientAIFunctionTests.cs](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Mcp.UnitTests/TaskAwareMcpClientAIFunctionTests.cs#L17-L159)

## 결론

repo-local MCP hosting은 “프레임워크가 MCP server 전체를 소유한다”는 모델이 아니라, **tool exposure seam**과 **outbound client bridge**를 분리하는 모델이다. Python은 app-owned MCP server에 붙는 `Tool` adapter와 conversion helper를 정교하게 제공하고, `.NET`은 범용 server host 대신 remote MCP invocation과 long-running task client boundary를 더 강하게 구현한다. Java에서는 이를 따라 **tool-centric hosting adapter**, **transport-aware host binder**, **client-side task/sampling artifact**를 분리하는 것이 가장 자연스럽다. (출처: [Python hosting-mcp boundary](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/README.md#L18-L20), [.NET DefaultMcpToolHandler streamable HTTP + pinning](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Workflows.Declarative.Mcp/DefaultMcpToolHandler.cs#L206-L247), [Python integration over StreamableHTTPSessionManager](https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/packages/hosting-mcp/tests/hosting_mcp/test_integration.py#L76-L118))