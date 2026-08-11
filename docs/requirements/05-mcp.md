# 05 MCP 연동

**접두사** `MCP` · **원본 기능** [07 mcp-client-tools](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md),
[24 mcp-hosting](../upstream/snapshots/d0a4165f/features/24-mcp-hosting.md)

MCP 서버를 원격 도구로 연결하고, 필요하면 Java 에이전트나 워크플로를 MCP 도구로 노출하는 계약을
정의한다. 로컬 도구 실행 루프와 승인 규칙은 [04 도구 정의와 실행 루프](04-tools.md), 일반 호스팅과
프로토콜 어댑터는 [10 호스팅과 프로토콜](10-hosting.md), 워크플로 런타임은
[09 워크플로와 오케스트레이션](09-workflows.md)이 소유한다.

## 채택 범위

이 문서의 `등급`은 [README](README.md#요구사항-등급) 정의대로 기능을 만들기로 했을 때의 강제력이고, 채택 여부는 [호환성 매트릭스](../upstream/snapshots/d0a4165f/compatibility-matrix.md)를 따른다.

- MCP 클라이언트 도구(`MCP01`, `MCP02`)는 채택 `필수`다.
- MCP 서버 호스팅 helper(`MCPH01`)에 해당하는 `MCP-015`~`MCP-019`는 채택 `선택`이다.

## 요약

| ID | 요구사항 | 채택 | 등급 | 단계 |
| --- | --- | --- | --- | --- |
| MCP-001 | transport 도구와 연결 어댑터를 함께 둔다 | 필수 | 필수 | Core+ |
| MCP-002 | 연결 소유권에 따라 수명주기를 나눈다 | 필수 | 필수 | Core+ |
| MCP-003 | 연결 검증과 재연결을 표준화한다 | 필수 | 필수 | Core+ |
| MCP-004 | 도구 발견은 prefix와 충돌 검사를 포함한다 | 필수 | 필수 | Core+ |
| MCP-005 | 호출 인자와 메타데이터를 분리한다 | 필수 | 필수 | Core+ |
| MCP-006 | prompts는 함수처럼 노출하고 resources는 payload로 둔다 | 필수 | 필수 | Core+ |
| MCP-007 | sampling은 기본 거부다 | 필수 | 필수 | Optional |
| MCP-008 | sampling 요청 수와 토큰 수를 제한한다 | 필수 | 필수 | Optional |
| MCP-009 | 연결 소유권에 따라 추적 전파를 구분한다 | 필수 | 필수 | Core+ |
| MCP-010 | HTTP 헤더는 같은 origin에만 전파한다 | 필수 | 필수 | Core+ |
| MCP-011 | task-required 도구는 작업 수명주기로 우회한다 | 필수 | 필수 | Optional |
| MCP-012 | 작업 생성 뒤에는 원 호출을 재발행하지 않는다 | 필수 | 필수 | Optional |
| MCP-013 | 취소와 시간 제한은 원격 취소로 연결한다 | 필수 | 필수 | Optional |
| MCP-014 | 작업 종료 상태를 명시적 결과로 바꾼다 | 필수 | 필수 | Optional |
| MCP-015 | 범용 hosting helper는 별도 아티팩트로 분리한다 | 선택 | 필수 | Hosting |
| MCP-016 | hosting helper는 서버 호스트 책임을 가져오지 않는다 | 선택 | 필수 | Hosting |
| MCP-017 | hosted agent/workflow adapter는 스키마와 세션 규칙을 고정한다 | 선택 | 필수 | Hosting |
| MCP-018 | hosting 결과 매핑은 최종 결과 한 번만 만든다 | 선택 | 필수 | Hosting |
| MCP-019 | prompts/resources/sampling/tasks 호스팅은 helper 범위가 아니다 | 선택 | 필수 | Hosting |

---

## MCP-001 transport 도구와 연결 어댑터를 함께 둔다

**요구사항.** Java는 stdio·streamable HTTP·WebSocket을 직접 여는 high-level MCP 도구 타입과,
이미 연결된 `McpClient`를 감싸는 low-level 연결 어댑터를 함께 제공해야 한다.

**원본 비교**

- .NET: transport-specific tool class는 없고 connected `McpClient`를 감싸는 task-aware wrapping이 중심이다.
- Python: `MCPStdioTool`, `MCPStreamableHTTPTool`, `MCPWebsocketTool`를 직접 제공한다.

**판단.** 둘을 합친 hybrid를 택한다. transport별 수명주기와 보안 차이는 Python처럼 타입으로 드러내고,
기존 연결을 재사용하는 seam은 .NET처럼 별도로 둔다.

**수용 기준**

- high-level API에 stdio·HTTP·WebSocket용 MCP 도구 타입이 있다.
- low-level API에 이미 연결된 `McpClient` 또는 동등 타입을 감싸는 어댑터가 있다.
- low-level 어댑터는 transport 매개변수를 새로 받지 않는다.

**근거** [07 공개 API·타입](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md),
[07 Java 결정](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md)

---

## MCP-002 연결 소유권에 따라 수명주기를 나눈다

**요구사항.** high-level transport 도구는 연결 생성·닫기·재연결을 소유해야 하고, low-level 연결
어댑터는 주입받은 연결을 열거나 닫지 않아야 한다.

**원본 비교**

- .NET: wrapper는 이미 연결된 `McpClient`를 입력으로 받으며 lifecycle을 소유하지 않는다.
- Python: `MCPTool` base와 transport subclass가 connect·close lifecycle을 직접 관리한다.

**판단.** 동일한 경계 분리가 필요하다. 연결을 연 쪽이 닫는다는 소유권 규칙이 명확해야 자원 누수와
중복 close를 막을 수 있다.

**수용 기준**

- high-level transport 도구는 자체 `connect()`·`close()` 또는 동등 수명주기 API를 가진다.
- low-level 어댑터는 주입받은 연결을 닫지 않는다.
- 같은 연결 객체를 여러 어댑터가 공유해도 close 책임은 연결 공급자에 남는다.

**근거** [07 Client lifecycle](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md),
[24 Client / server boundary](../upstream/snapshots/d0a4165f/features/24-mcp-hosting.md)

---

## MCP-003 연결 검증과 재연결을 표준화한다

**요구사항.** high-level transport 도구는 연결 유효성을 확인하고, 연결 손실이 감지되면 한 번의
재연결 절차를 거친 뒤 다시 시도해야 하며, close는 캐시와 보조 상태를 모두 비워야 한다.

**원본 비교**

- .NET: repo-local MCP surface는 transport reconnect helper를 직접 노출하지 않는다.
- Python: `_ensure_connected()`가 ping 기반 유효성 확인과 reconnect를 수행하고 `close()`가 캐시를 비운다.

**판단.** Python을 택한다. 네트워크 MCP는 일시적 단절이 흔하다. 단, 재시도는 제한되어야 한다.
close가 불완전하면 sampling counter와 discovery cache가 오염된다.

**수용 기준**

- 연결 검증 실패 시 도구는 한 번 재연결을 시도한 뒤 원 요청을 재시도한다.
- ping 미지원 서버는 capability cache로 기록해 매번 실패를 반복하지 않는다.
- `close()` 뒤에는 세션, capability cache, sampling counter, pending reload state가 비워진다.

**근거** [07 Client lifecycle](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md)

---

## MCP-004 도구 발견은 prefix와 충돌 검사를 포함한다

**요구사항.** MCP 도구 발견은 페이지네이션을 따라 끝까지 읽어야 하고, 로컬 이름 prefix를 적용하되,
정규화 후 이름 충돌이 나면 실패해야 한다.

**원본 비교**

- .NET: connected client tool wrapping은 server tool 목록을 `AIFunction` 목록으로 변환한다.
- Python: `tools/list`를 paginated로 읽고 prefix 적용, allowlist 안전성, normalized-name collision 검사를 수행한다.

**판단.** Python을 택한다. 이름 충돌을 허용하면 allowlist와 approval rule이 엉뚱한 도구에 매칭될
수 있다. 도구 발견은 보안 경계다.

**수용 기준**

- 도구 발견은 `nextCursor`가 없을 때까지 `tools/list`를 반복한다.
- 설정한 prefix가 로컬 도구명에 반영된다.
- 서로 다른 원격 도구 두 개가 같은 로컬 이름으로 정규화되면 발견 단계에서 실패한다.

**근거** [07 Tool discovery / invocation](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md),
[07 구체 acceptance scenarios](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md)

---

## MCP-005 호출 인자와 메타데이터를 분리한다

**요구사항.** MCP 도구 호출은 선언된 입력 스키마와 명시 허용한 추가 인자만 서버로 보내야 하며,
트레이스·런타임 메타데이터는 별도 `_meta` 경로로 병합해야 한다.

**원본 비교**

- .NET: repo-local wrapper는 호출 인자 필터링보다 task wrapping에 집중한다.
- Python: `_prepare_call_kwargs()`가 declared schema, extra arg names, `_meta`, OTel metadata를 분리 처리한다.

**판단.** Python을 택한다. 인자와 메타데이터가 섞이면 서버 스키마 검증과 관찰성이 같이 깨진다.
Java는 모델이 채우는 값과 프레임워크가 붙이는 값을 분리해야 한다.

**수용 기준**

- 입력 스키마에 없는 일반 인자는 서버로 전달되지 않는다.
- 허용한 추가 인자 이름만 예외적으로 전달된다.
- 사용자 `_meta`와 프레임워크 메타데이터는 덮어쓰지 않고 병합된다.

**근거** [07 Tool discovery / invocation](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md)

---

## MCP-006 prompts는 함수처럼 노출하고 resources는 payload로 둔다

**요구사항.** MCP prompt는 로컬 함수형 도구처럼 노출해야 하고, MCP resource는 top-level discovery
surface로 승격하지 말고 tool·prompt 결과 payload 안의 콘텐츠로만 다뤄야 한다.

**원본 비교**

- .NET: resources는 skill-loading boundary에 가깝고 prompt hosting surface는 보이지 않는다.
- Python: prompts를 `FunctionTool`로 노출하고 resources는 payload conversion boundary에 둔다.

**판단.** Python을 택한다. prompt는 모델이 선택할 수 있는 호출 surface다. resource는 payload로 두는
편이 discovery 폭을 불필요하게 넓히지 않는다.

**수용 기준**

- `prompts/list` 결과는 로컬 함수형 도구 목록으로 변환된다.
- client MCP surface에 `loadResources()` 같은 top-level resource discovery API는 없다.
- tool·prompt 결과 안의 `ResourceLink`·embedded resource는 콘텐츠로 보존된다.

**근거** [07 Resources / prompts 경계](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md),
[07 Java 결정](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md)

---

## MCP-007 sampling은 기본 거부다

**요구사항.** 서버가 시작한 `sampling/createMessage` 요청은 명시 승인 callback이 없으면 기본적으로
거부해야 한다.

**원본 비교**

- .NET: reviewed package에 Python의 sampling approval callback 대응 surface가 보이지 않는다.
- Python: sampling approval callback이 없으면 request를 거부하고 로컬 LLM 호출을 하지 않는다.

**판단.** Python을 택한다. 외부 MCP 서버는 신뢰할 수 없는 제3자일 수 있다. 기본 허용은 confused
deputy 위험을 만든다.

**수용 기준**

- 승인 callback이 없으면 sampling request는 오류 응답으로 끝난다.
- 위 경우 로컬 chat client는 호출되지 않는다.
- 승인 callback이 예외를 던져도 결과는 거부다.

**근거** [07 Sampling approval callback](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md),
[07 Java 결정](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md)

---

## MCP-008 sampling 요청 수와 토큰 수를 제한한다

**요구사항.** sampling은 연결 단위 요청 수 상한과 토큰 상한을 가져야 하며, 승인된 요청도 이 상한을
넘겨서는 안 된다.

**원본 비교**

- .NET: repo-local MCP surface에 동일한 sampling budget API가 보이지 않는다.
- Python: `sampling_max_requests`와 `sampling_max_tokens`를 두고 reconnect 시 counter를 reset한다.

**판단.** Python을 택한다. 기본 거부만으로는 충분하지 않다. 승인된 sampling도 폭주할 수 있으므로
수량과 크기를 함께 제한해야 한다.

**수용 기준**

- 승인된 sampling request의 `maxTokens`는 configured cap으로 clamp된다.
- sampling request 횟수가 상한을 넘으면 이후 요청은 거부된다.
- 연결을 새로 열면 sampling request counter가 초기화된다.

**근거** [07 Sampling approval callback](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md)

---

## MCP-009 연결 소유권에 따라 추적 전파를 구분한다

**요구사항.** Java가 직접 연 연결에서는 trace context와 transport 속성을 MCP 메타데이터·헤더에
전파해야 하고, host나 공급자가 관리하는 연결에서는 기존 연결이 가진 전파만 존중하며 Java 코어가
새 transport-level 전파를 가정해 덮어쓰지 않아야 한다.

**원본 비교**

- .NET: repo-local MCP package에는 Python처럼 explicit OTel span instrumentation이 보이지 않는다.
- Python: `create_mcp_client_span(...)`와 `_meta` 병합으로 client-owned 연결의 tracing을 직접 수행한다.

**판단.** 두 원본 차이를 그대로 반영한다. Java가 transport를 열면 전파 책임도 진다. 반대로 이미
열린 연결을 감쌀 때는 provider-managed boundary를 존중해야 한다.

**수용 기준**

- stdio·HTTP·WebSocket high-level 도구는 initialize, discovery, call, prompt 요청마다 trace span 또는 동등한 관찰 이벤트를 남긴다.
- high-level 도구는 trace context를 MCP `_meta` 또는 transport 헤더에 주입한다.
- low-level connected-client adapter는 transport 헤더를 새로 합성하지 않고 주입받은 연결의 전파 정책을 그대로 사용한다.

**근거** [07 Trace / error / cancellation / security](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md)

---

## MCP-010 HTTP 헤더는 같은 origin에만 전파한다

**요구사항.** streamable HTTP transport의 동적 헤더 주입은 같은 origin에만 적용해야 하며,
동시 호출 사이에서 한 호출의 헤더 스냅샷이 다른 호출에 섞이면 안 된다.

**원본 비교**

- .NET: outbound declarative bridge가 cross-origin credential stripping과 auto-redirect 금지를 구현한다.
- Python: `header_provider`가 same-origin policy와 concurrent snapshot isolation을 테스트로 고정한다.

**판단.** 두 원본의 안전 기본값을 합친다. HTTP 인증 헤더는 가장 쉽게 새는 정보다. redirect와
동시성 모두를 같이 막아야 한다.

**수용 기준**

- 같은 origin 요청에만 동적 인증 헤더가 붙는다.
- cross-origin redirect나 alternate origin 요청에는 `Authorization`·`Cookie`류 헤더가 재주입되지 않는다.
- 동시에 두 호출을 보내도 각 호출은 자기 헤더 스냅샷만 사용한다.

**근거** [07 Trace / error / cancellation / security](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md),
[24 Auth / error / 보안](../upstream/snapshots/d0a4165f/features/24-mcp-hosting.md)

---

## MCP-011 task-required 도구는 작업 수명주기로 우회한다

**요구사항.** `taskSupport == required`인 MCP 도구는 일반 `tools/call` 경로로 실행하지 말고,
작업 생성·poll·결과 조회 수명주기로 자동 우회해야 한다.

**원본 비교**

- .NET: `ToolTaskSupport.Required`인 도구만 `TaskAwareMcpClientAIFunction`으로 감싼다.
- Python: required tool을 발견 시 기록하고 `call_tool()`이 자동으로 `call_tool_as_task()`로 보낸다.

**판단.** 동일하다. 호출자에게 required task를 수동 분기시키면 실수하기 쉽다. task-required는
도구 정의의 계약이므로 런타임이 자동으로 지켜야 한다.

**수용 기준**

- task-required 도구는 일반 inline call path를 타지 않는다.
- optional 또는 inline 도구는 기존 `tools/call` path를 유지한다.
- required task 여부는 discovery 결과 metadata에서 읽는다.

**근거** [07 MCP tasks / long-running operations](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md),
[07 구체 acceptance scenarios](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md)

---

## MCP-012 작업 생성 뒤에는 원 호출을 재발행하지 않는다

**요구사항.** 작업 생성 응답에서 task id를 얻은 뒤에는 같은 도구 호출을 다시 `tools/call`로
재발행하지 말아야 하며, polling 간격은 서버 제안값을 안전 범위로 clamp해야 한다.

**원본 비교**

- .NET: task-aware wrapper가 `CallToolAsTaskAsync()` 뒤에 poll과 result fetch를 이어 간다.
- Python: `call_tool_as_task()`가 create 이후 `tasks/get`·`tasks/result`만 사용하고 poll interval을 clamp한다.

**판단.** 두 원본 공통 의미를 고정한다. create 이후 원 호출을 다시 보내면 서버에서 중복 작업이
생길 수 있다. polling 간격도 서버가 과도한 값을 줄 수 있으므로 제한해야 한다.

**수용 기준**

- task id를 받은 뒤에는 같은 logical call에 대해 두 번째 `tools/call`이 발행되지 않는다.
- server `pollInterval`은 최소·최대 안전 범위 안으로 clamp된다.
- create 결과가 plain non-task result면 task path는 즉시 inline fallback으로 끝난다.

**근거** [07 MCP tasks / long-running operations](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md),
[07 Java 결정](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md)

---

## MCP-013 취소와 시간 제한은 원격 취소로 연결한다

**요구사항.** 로컬 취소와 `maxTaskWait` 만료는 기본적으로 best-effort 원격 `tasks/cancel`로 이어져야
하며, 이 동작은 옵션으로만 끌 수 있어야 한다.

**원본 비교**

- .NET: `CancelRemoteTaskOnLocalCancellation=true`가 기본값이고 취소 시 `CancelTaskAsync()`를 시도한다.
- Python: local cancellation과 deadline abandonment 모두 best-effort remote cancel을 시도한다.

**판단.** 동일하다. 로컬에서만 취소하면 서버 작업이 계속 돌 수 있다. 기본값은 정리 쪽이어야 한다.
옵트아웃은 특수 서버 호환성용 예외로만 둔다.

**수용 기준**

- 로컬 취소가 발생하면 기본 설정에서 원격 `tasks/cancel`이 시도된다.
- 원격 취소 비활성 옵션을 켜면 위 호출이 발행되지 않는다.
- `maxTaskWait` 초과는 명시 오류로 끝나며 best-effort 원격 취소를 동반한다.

**근거** [07 MCP tasks / long-running operations](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md),
[07 .NET 구현과 테스트](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md)

---

## MCP-014 작업 종료 상태를 명시적 결과로 바꾼다

**요구사항.** MCP 작업의 `completed` 외 terminal status와 malformed result payload는 모두 명시적 오류로
바꿔야 하며, 성공 경로의 최종 결과 shape는 inline 도구 호출과 같아야 한다.

**원본 비교**

- .NET: completed가 아니면 `OperationCanceledException` 또는 `InvalidOperationException`을 surface한다.
- Python: `failed`·`cancelled`·`input_required`와 malformed payload를 `ToolExecutionException`으로 바꾼다.

**판단.** 두 원본의 공통 의미를 채택한다. task path도 inline call과 같은 최종 결과 모양을 내야
상위 도구 루프가 구분 코드를 갖지 않는다.

**수용 기준**

- `completed` 결과는 inline call과 같은 `Content` 또는 동등 결과 shape로 변환된다.
- `failed`, `cancelled`, `input_required`는 성공 결과로 위장되지 않는다.
- malformed task result payload는 명시 실패가 된다.

**근거** [07 MCP tasks / long-running operations](../upstream/snapshots/d0a4165f/features/07-mcp-client-tools.md)

---

## MCP-015 범용 hosting helper는 별도 아티팩트로 분리한다

**요구사항.** 범용 MCP server hosting helper는 Java 코어와 분리된 별도 아티팩트로 제공해야 하며,
이 비대칭은 Python upstream에는 helper가 있고 .NET repo-local source에는 대응 범용 helper가 없다는
사실을 그대로 반영해야 한다.

**원본 비교**

- .NET: repo-local 범용 MCP server hosting helper가 보이지 않고 outbound client bridge와 task wrapper만 있다.
- Python: `agent-framework-hosting-mcp`가 app-owned MCP server용 generic helper를 제공한다.

**판단.** Python helper는 유용하지만 코어에 넣으면 호스트 책임을 끌어온다. 따라서 Java는 helper를
채택하되 별도 `Hosting` 아티팩트로 분리한다. .NET과의 비대칭도 문서에 숨기지 않는다.

**수용 기준**

- MCP server hosting helper는 core MCP client 모듈과 별도 아티팩트다.
- core MCP client 모듈은 hosting helper에 의존하지 않는다.
- hosting helper 없이도 remote MCP client integration을 사용할 수 있다.

**근거** [24 요약](../upstream/snapshots/d0a4165f/features/24-mcp-hosting.md),
[24 성숙도](../upstream/snapshots/d0a4165f/features/24-mcp-hosting.md),
[24 .NET / Python 차이](../upstream/snapshots/d0a4165f/features/24-mcp-hosting.md)

---

## MCP-016 hosting helper는 서버 호스트 책임을 가져오지 않는다

**요구사항.** MCP hosting helper는 tool schema와 run/result 변환만 소유해야 하며, 서버 객체 생성,
handler 등록, transport, auth, session trust, concurrency, deployment 책임을 가져오지 않아야 한다.

**원본 비교**

- .NET: repo-local surface는 hosting helper보다 outbound MCP client bridge에 가깝다.
- Python: README와 AGENTS가 helper가 server·transport·auth를 소유하지 않는다고 명시한다.

**판단.** Python 경계를 택한다. `AgentEngine`이 호스트 책임을 가져오지 않는다는 원칙에 맞다.
helper는 얇은 seam이어야 한다.

**수용 기준**

- helper public API에 generic MCP `Server` 생성이나 handler registration API가 없다.
- helper public API에 stdio·HTTP transport launcher가 없다.
- auth/authz, session id trust, concurrency policy는 helper 설정이 아니라 host 애플리케이션 계약으로 남는다.

**근거** [24 경계](../upstream/snapshots/d0a4165f/features/24-mcp-hosting.md),
[24 Client / server boundary](../upstream/snapshots/d0a4165f/features/24-mcp-hosting.md)

---

## MCP-017 hosted agent/workflow adapter는 스키마와 세션 규칙을 고정한다

**요구사항.** hosted agent adapter와 workflow adapter는 입력 스키마 생성 규칙, chat option 매핑,
`session_id_parameter` 의미, unsupported workflow continuation 오류를 고정해야 한다.

**원본 비교**

- .NET: 범용 hosted agent/workflow adapter는 없고 declarative bridge가 remote MCP 호출만 다룬다.
- Python: `AgentMCPTool`와 `WorkflowMCPTool`가 schema derivation, session persistence, workflow input validation을 제공한다.

**판단.** Python을 택한다. host가 매번 schema와 세션 의미를 재정의하면 tool contract가 흔들린다.
특히 workflow external input은 조용히 성공시키면 안 된다.

**수용 기준**

- agent adapter는 extra parameters와 chat option parameters를 schema와 실행 양쪽에 같은 이름으로 반영한다.
- `sessionIdParameter`를 쓰면 같은 key로 세션을 읽고 실행 뒤 다시 저장한다.
- workflow adapter는 입력 타입이 하나가 아니거나 external input continuation이 필요하면 명시 오류를 낸다.

**근거** [24 Tools / resources / prompts exposure](../upstream/snapshots/d0a4165f/features/24-mcp-hosting.md),
[24 Session / lifecycle](../upstream/snapshots/d0a4165f/features/24-mcp-hosting.md),
[24 Python 구현과 테스트](../upstream/snapshots/d0a4165f/features/24-mcp-hosting.md)

---

## MCP-018 hosting 결과 매핑은 최종 결과 한 번만 만든다

**요구사항.** MCP hosting helper의 결과 매핑은 한 번의 최종 `CallToolResult`만 만들어야 하며,
부분 tool result 스트리밍을 helper 계약에 포함하지 않아야 한다.

**원본 비교**

- .NET: declarative bridge도 completed `CallToolResult.ContentBlock`를 최종 `AIContent`로 변환한다.
- Python: `mcp_from_run(...)`는 final `ContentBlock[]`만 만들고 partial result streaming을 하지 않는다.

**판단.** 동일하다. transport가 스트리밍을 지원해도 tool result 계약까지 증분으로 넓히면 host와
client 의미가 불필요하게 복잡해진다.

**수용 기준**

- helper API는 증분 `CallToolResult` chunk를 내지 않는다.
- 최종 결과 매핑은 텍스트, URI 리소스 링크, 이미지·오디오, 기타 바이너리 리소스를 보존한다.
- `function_call` 같은 unsupported content는 MCP tool result payload에서 제외된다.

**근거** [24 Streaming / result mapping](../upstream/snapshots/d0a4165f/features/24-mcp-hosting.md),
[24 Resources exposure](../upstream/snapshots/d0a4165f/features/24-mcp-hosting.md)

---

## MCP-019 prompts/resources/sampling/tasks 호스팅은 helper 범위가 아니다

**요구사항.** 범용 MCP hosting helper는 `prompts/*`, `resources/*`, sampling callback contract,
long-running task/progress hosting API를 제공하지 않아야 한다.

**원본 비교**

- .NET: `IMcpToolHandler`는 tool invocation만 정의하고 hosting용 prompts/resources API는 없다.
- Python: hosting helper는 tool adapters와 run/result conversion만 export하고 sampling·tasks는 app-owned boundary로 남긴다.

**판단.** 동일하다. 도구 노출 seam과 MCP 전체 서버 프로토콜을 섞으면 책임이 과도하게 커진다.
필요하면 별도 아티팩트로 설계해야 한다.

**수용 기준**

- helper public API에 `prompts/list`, `prompts/get`, `resources/list`, `resources/read` helper가 없다.
- sampling-only 콘텐츠는 `CallToolResult.content`에 실리지 않는다.
- long-running tasks와 progress notifications는 helper가 아니라 host 프로토콜 로직 또는 client wrapper가 소유한다.

**근거** [24 경계](../upstream/snapshots/d0a4165f/features/24-mcp-hosting.md),
[24 Prompts exposure](../upstream/snapshots/d0a4165f/features/24-mcp-hosting.md),
[24 Sampling / tasks boundary](../upstream/snapshots/d0a4165f/features/24-mcp-hosting.md)

---

## 이 문서가 다루지 않는 것

| 주제 | 소유 문서 |
| --- | --- |
| 로컬 함수 도구 정의, 예산, 승인 루프 | [04 도구 정의와 실행 루프](04-tools.md) |
| 에이전트 공개 실행 API와 모델 포트 | [01 에이전트 실행과 모델 호출](01-agent-execution.md) |
| 워크플로 그래프와 체크포인트 런타임 | [09 워크플로와 오케스트레이션](09-workflows.md) |
| 일반 호스팅 수명주기와 프로토콜 어댑터 | [10 호스팅과 프로토콜](10-hosting.md) |
| 공급자별 모델 통합과 인프라 어댑터 | [12 공급자 통합](12-providers.md) |
