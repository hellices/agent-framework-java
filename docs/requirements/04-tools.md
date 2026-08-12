# 04 도구 정의와 실행 루프

**접두사** `TOOL` · **원본 기능** [05 function-tools](../upstream/snapshots/d0a4165f/features/05-function-tools.md),
[06 tool-approval](../upstream/snapshots/d0a4165f/features/06-tool-approval.md)

도구를 정의하고, 선택하고, 실행하고, 승인하고, 결과를 정규화하는 계약을 정의한다.
에이전트 실행 진입점은 [01 에이전트 실행과 모델 호출](01-agent-execution.md), MCP 원격 도구와
서버 호스팅은 [05 MCP 연동](05-mcp.md), 세션 영속화는 [06 세션과 대화 상태](06-sessions.md)가
소유한다.

## 채택 범위

이 문서의 `등급`은 [README](README.md#요구사항-등급) 정의대로 기능을 만들기로 했을 때의 강제력이고, 채택 여부는 [호환성 매트릭스](../upstream/snapshots/d0a4165f/compatibility-matrix.md)를 따른다.

- 함수 도구(`TOOL01`~`TOOL03`)와 승인 코어(`APP01`, `APP02`)는 모두 채택 `필수`다.

## 요약

| ID | 요구사항 | 채택 | 등급 | 단계 |
| --- | --- | --- | --- | --- |
| TOOL-001 | 도구는 코어 정의 타입으로 표현한다 | 필수 | 필수 | MVP |
| TOOL-002 | 도구 실행 루프는 Java 코어가 단독 소유한다 | 필수 | 필수 | MVP |
| TOOL-003 | 입력 스키마는 추론하되 명시 스키마가 이긴다 | 필수 | 필수 | MVP |
| TOOL-004 | 컨텍스트 인자는 스키마 밖에서 주입한다 | 필수 | 필수 | MVP |
| TOOL-005 | 모든 호출 경로에서 인자를 검증한다 | 필수 | 필수 | MVP |
| TOOL-006 | 선언 전용 도구와 추가 도구는 로컬 실행하지 않는다 | 필수 | 필수 | Core+ |
| TOOL-007 | 도구 선택은 `ToolMode`로 고정한다 | 필수 | 필수 | MVP |
| TOOL-008 | 실행 옵션이 기본 옵션을 이긴다 | 필수 | 필수 | MVP |
| TOOL-009 | 호출 설정은 정규화된 타입과 안전 기본값을 가진다 | 필수 | 필수 | MVP |
| TOOL-010 | 반복 제한과 호출 예산의 의미를 고정한다 | 필수 | 필수 | MVP |
| TOOL-011 | 호출 예산은 배치 뒤 best-effort로 차단한다 | 필수 | 필수 | MVP |
| TOOL-012 | 병렬 실행은 실행 가능 배치만 다루고 순서를 보존한다 | 필수 | 필수 | MVP |
| TOOL-013 | 도구 결과는 `Content` 목록으로 정규화한다 | 필수 | 필수 | MVP |
| TOOL-014 | 도구 정의와 런타임 카운터를 분리한다 | 필수 | 권장 | Core+ |
| TOOL-015 | 스트리밍도 같은 도구 루프를 탄다 | 필수 | 필수 | MVP |
| TOOL-016 | 승인 요청과 응답은 코어 콘텐츠다 | 필수 | 필수 | Core+ |
| TOOL-017 | 승인 응답은 원래 요청에만 결합한다 | 필수 | 필수 | Core+ |
| TOOL-018 | 승인 거부는 합성 종료 결과를 남긴다 | 필수 | 필수 | Core+ |
| TOOL-019 | 상시 승인은 정확한 인자와 호스트 경계로 매칭한다 | 필수 | 필수 | Core+ |
| TOOL-020 | 승인 큐는 세션 위에서 하나씩 진행한다 | 필수 | 필수 | Core+ |
| TOOL-021 | 자동 승인 연쇄는 상한으로 끊는다 | 필수 | 권장 | Core+ |

---

## TOOL-001 도구는 코어 정의 타입으로 표현한다

**요구사항.** Java 코어는 도구를 코어 소유 `FunctionTool` 타입으로 정의하고, 공개 API는 외부
공급자 SDK 도구 타입을 1차 계약으로 삼지 않아야 한다.

**원본 비교**

- .NET: core는 외부 `AITool`·`AIFunction`를 받아 실행 스택에 연결한다.
- Python: core가 `FunctionTool`과 `@tool`을 직접 제공한다.

**판단.** Python을 택한다. 스키마, 검증, 결과 정규화를 코어가 같이 소유해야 동작이 공급자와
분리된다. 그래야 Java 구현이 도구 의미를 한 곳에서 고정할 수 있다.

**수용 기준**

- 공개 API에 `FunctionTool` 또는 동등한 코어 정의 타입이 있다.
- 코어 도구 루프는 외부 공급자 SDK 타입 없이 도구 정의를 읽고 실행할 수 있다.

**근거** [05 공개 API·타입](../upstream/snapshots/d0a4165f/features/05-function-tools.md),
[05 Java 결정](../upstream/snapshots/d0a4165f/features/05-function-tools.md)

---

## TOOL-002 도구 실행 루프는 Java 코어가 단독 소유한다

**요구사항.** 도구 호출을 반복해서 모델에 재주입하는 실행 루프는 Java 코어만 소유해야 하며,
외부 프레임워크나 공급자 SDK의 자동 도구 실행과 이중으로 돌지 않아야 한다.

**원본 비교**

- .NET: 하위 `FunctionInvokingChatClient`가 도구 실행 정책을 크게 소유하고 agent core는 seam만 남긴다.
- Python: `FunctionInvocationLayer`가 core 안에서 도구 루프를 직접 수행한다.

**판단.** Python을 택한다. Java 코어가 루프를 직접 소유해야 예산, 승인, 스트리밍, 관찰성을 한
규칙으로 묶을 수 있다. .NET식 이중 소유는 Java에서 외부 자동 실행과 충돌하기 쉽다.

**수용 기준**

- 같은 모델 응답에 포함된 도구 호출이 코어 루프와 하위 SDK 루프에서 두 번 실행되지 않는다.
- 코어 도구 루프를 켠 실행에서는 하위 SDK의 자동 도구 실행이 비활성화되거나 우회된다.
- 예산과 승인 판단은 모두 코어 루프 안에서만 내려진다.

**근거** [05 공개 API·타입](../upstream/snapshots/d0a4165f/features/05-function-tools.md),
[05 Invocation configuration / layers / budgets](../upstream/snapshots/d0a4165f/features/05-function-tools.md)

---

## TOOL-003 입력 스키마는 추론하되 명시 스키마가 이긴다

**요구사항.** 도구 입력 스키마는 신뢰할 수 있는 Java 타입·parameter metadata에서 추론하되,
정확한 이름이나 generic type을 알 수 없으면 추측하지 않고 명시 스키마를 요구해야 한다.
명시 스키마가 있으면 추론 결과를 완전히 덮어쓴다.

**원본 비교**

- .NET: first-class schema 추론 helper가 없고 외부에서 준비한 도구 정의를 받는다.
- Python: 시그니처 추론과 명시 Pydantic·JSON Schema override를 모두 지원한다.

**판단.** Python의 편의는 채택하되 runtime reflection만 표준 경로로 두지 않는다. Java
parameter name은 `-parameters` 또는 명시 annotation 없이는 보장되지 않고 generic type은
소거될 수 있다. annotation processor, 명시 metadata, pluggable schema generator를 같은
계약으로 수용하고 불충분한 metadata는 fail-closed한다.

**수용 기준**

- 단순 함수 정의만으로 객체형 입력 스키마가 생성된다.
- 명시 스키마를 주면 설명, 필수 필드, 제약이 추론값이 아니라 명시값으로 나온다.
- 명시 스키마와 추론 스키마를 부분 병합하지 않는다.
- parameter name이나 generic type을 신뢰할 수 없을 때 `arg0` 같은 추정 schema를 공개하지 않고
  명시 schema를 요구한다.
- core schema API는 Jackson, Spring, provider SDK의 type token을 노출하지 않는다.

**근거** [05 Function tool definition / decorator / schema generation](../upstream/snapshots/d0a4165f/features/05-function-tools.md)

---

## TOOL-004 컨텍스트 인자는 스키마 밖에서 주입한다

**요구사항.** 실행 컨텍스트, 런타임 메타데이터, 프레임워크 내부 인자는 입력 스키마에서 제외하고
런타임에만 주입해야 한다.

**원본 비교**

- .NET: wrapper가 ambient `FunctionInvocationContext`와 `AIFunctionArguments`를 따로 전달한다.
- Python: context parameter를 스키마에서 빼고 invoke 시 주입한다.

**판단.** 동일한 의도다. 모델이 채우면 안 되는 값은 스키마에 드러나면 안 된다. 그래야 사용자가
보낸 인자와 프레임워크가 넣는 인자의 경계가 흐려지지 않는다.

**수용 기준**

- 컨텍스트 인자는 생성된 JSON 스키마 `properties`에 나타나지 않는다.
- 도구 본문은 런타임에 컨텍스트 객체를 주입받을 수 있다.
- 사용자가 컨텍스트 인자 이름으로 값을 보내도 실행 인자로 채택되지 않는다.

**근거** [05 Function tool definition / decorator / schema generation](../upstream/snapshots/d0a4165f/features/05-function-tools.md),
[05 Argument validation / parsing](../upstream/snapshots/d0a4165f/features/05-function-tools.md)

---

## TOOL-005 모든 호출 경로에서 인자를 검증한다

**요구사항.** 도구 호출은 인자 전달 경로와 무관하게 같은 스키마 검증을 수행해야 하며, 선언되지
않은 런타임 키는 명시적으로 거부해야 한다.

**원본 비교**

- .NET: repo-local core에 Python식 인자 스키마 검증기가 없다.
- Python: Pydantic 경로와 explicit JSON Schema 경로 모두에서 인자를 검증하고 예기치 않은 runtime kwargs를 거부한다.

**판단.** Python을 택한다. 검증 경로가 둘 이상이면 호출 방식에 따라 다른 버그가 생긴다. Java는
raw 결과 모드여도 입력 검증만큼은 항상 유지해야 한다.

**수용 기준**

- 필수 인자가 빠지면 실행 전에 실패한다.
- `additionalProperties: false`인 스키마에 없는 키를 주면 실패한다.
- 결과 파싱 우회를 켜도 입력 검증은 그대로 수행된다.

**근거** [05 Argument validation / parsing](../upstream/snapshots/d0a4165f/features/05-function-tools.md),
[05 구체 acceptance scenarios](../upstream/snapshots/d0a4165f/features/05-function-tools.md)

---

## TOOL-006 선언 전용 도구와 추가 도구는 로컬 실행하지 않는다

**요구사항.** 로컬 구현이 없는 선언 전용 도구와 실행 시점에 추가한 declaration-only 도구는 모델에
노출만 하고 Java 코어가 직접 실행하지 않아야 한다.

**원본 비교**

- .NET: constructor tools와 middleware seam을 제공하지만 declaration-only local execution 의미는 드러나지 않는다.
- Python: `func=None` 도구와 `additional_tools`를 declaration-only로 취급하고 local execution하지 않는다.

**판단.** Python을 택한다. 원격 도구와 hosted 도구를 섞으려면 “노출”과 “로컬 실행”을 분리해야
한다. 그래야 MCP와 approval이 같은 루프에 들어와도 경계가 보존된다.

**수용 기준**

- 로컬 본문이 없는 도구도 모델 선택 대상 목록에는 들어간다.
- 모델이 이런 도구를 호출해도 Java 코어는 가짜 성공 `function_result`를 만들지 않는다.
- `additionalTools`는 실행 전용 노출 목록이며 로컬 함수 본문 등록을 요구하지 않는다.

**근거** [05 공개 API·타입](../upstream/snapshots/d0a4165f/features/05-function-tools.md),
[05 구체 acceptance scenarios](../upstream/snapshots/d0a4165f/features/05-function-tools.md)

---

## TOOL-007 도구 선택은 `ToolMode`로 고정한다

**요구사항.** 도구 선택 정책은 `auto`·`required`·`none`과 선택적 허용 목록·강제 함수명을 담는
공급자 중립 `ToolMode` 타입으로 표현해야 한다.

**원본 비교**

- .NET: selection semantics를 하위 `ChatOptions.ToolMode`에 위임한다.
- Python: `ToolMode`와 `validate_tool_mode()`로 조합 규칙을 core가 검증한다.

**판단.** Python을 택한다. Java는 정적 타입으로 조합 오류를 빨리 드러낼 수 있다. provider-neutral
객체가 있어야 특정 SDK 의미를 공개 API에 새기지 않는다.

**수용 기준**

- 공개 타입이 `auto`·`required`·`none`을 구분한다.
- `requiredFunctionName`은 `required`일 때만 허용된다.
- 잘못된 `allowedTools` 조합은 모델 호출 전에 실패한다.

**근거** [05 Tool modes / selection](../upstream/snapshots/d0a4165f/features/05-function-tools.md),
[05 Java 결정](../upstream/snapshots/d0a4165f/features/05-function-tools.md)

---

## TOOL-008 실행 옵션이 기본 옵션을 이긴다

**요구사항.** 에이전트 기본 도구 옵션과 실행 시점 도구 옵션이 충돌하면 실행 시점 값이 이겨야
하며, 도구 목록은 중복 없이 병합해야 한다.

**원본 비교**

- .NET: agent-level default와 request-level 값을 병합해 전달한다.
- Python: `merge_chat_options()`가 run-level `tool_choice` 우선과 tools dedupe를 테스트로 고정한다.

**판단.** 동일하다. 요청별 정책이 기본 정책을 덮지 못하면 host가 위험한 실행을 막을 수 없다.
도구 목록 dedupe도 중복 노출과 중복 실행 혼란을 줄인다.

**수용 기준**

- 같은 `toolChoice`를 양쪽에 주면 실행 시점 값이 적용된다.
- 실행 시점에 지정하지 않은 키는 기본값이 유지된다.
- 같은 이름의 도구가 양쪽에 있어도 최종 목록에는 한 번만 들어간다.

**근거** [05 Tool modes / selection](../upstream/snapshots/d0a4165f/features/05-function-tools.md),
[05 구체 acceptance scenarios](../upstream/snapshots/d0a4165f/features/05-function-tools.md)

---

## TOOL-009 호출 설정은 정규화된 타입과 안전 기본값을 가진다

**요구사항.** 도구 호출 설정은 `enabled`, 반복 제한, 호출 예산, 연속 오류 제한, unknown-call 정책,
상세 오류 노출, 추가 도구를 담는 정규화된 타입이어야 하며, 생략 시 안전 기본값으로 채워져야 한다.

**원본 비교**

- .NET: Python식 통합 설정 객체는 없고 하위 client 정책에 책임이 분산된다.
- Python: `FunctionInvocationConfiguration` 정규화 함수가 기본값과 수치 제약을 고정한다.

**판단.** Python을 택한다. 설정이 분산되면 어떤 실행이 어떤 안전장치를 켰는지 알 수 없다.
Java는 한 타입에 모아 문서와 테스트를 같이 고정해야 한다.

**수용 기준**

- 설정을 생략하면 `enabled=true`와 `includeDetailedErrors=false`가 채워진다.
- `maxIterations`와 `maxConsecutiveErrorsPerRequest`는 1 미만 값을 거부한다.
- `maxFunctionCalls`는 생략하거나 양수로만 설정한다. Java public API가 `null`을 유효한 옵션
  값으로 요구하지 않는다.

**근거** [05 공개 API·타입](../upstream/snapshots/d0a4165f/features/05-function-tools.md),
[05 Invocation configuration / layers / budgets](../upstream/snapshots/d0a4165f/features/05-function-tools.md)

---

## TOOL-010 반복 제한과 호출 예산의 의미를 고정한다

**요구사항.** `maxIterations`는 모델 왕복 횟수 제한이고, `maxFunctionCalls`는 실행된 함수 호출 수
제한이며, 두 값의 의미를 서로 대체해서는 안 된다.

**원본 비교**

- .NET: repo-local core에 동일한 예산 타입과 의미가 직접 드러나지 않는다.
- Python: `max_iterations`와 `max_function_calls`를 분리하고 테스트로 의미를 고정한다.

**판단.** Python을 택한다. 반복 수와 호출 수를 섞으면 비용 예측이 깨진다. Java는 API 이름만이
아니라 의미도 테스트로 못 박아야 한다.

**수용 기준**

- `maxIterations`는 모델 응답을 한 번 더 받기 전에 차감된다.
- `maxFunctionCalls`는 실제 실행된 도구 호출 수를 누적한다.
- 같은 실행에서 두 한도는 독립적으로 작동한다.

**근거** [05 Invocation configuration / layers / budgets](../upstream/snapshots/d0a4165f/features/05-function-tools.md),
[05 구체 acceptance scenarios](../upstream/snapshots/d0a4165f/features/05-function-tools.md)

---

## TOOL-011 호출 예산은 배치 뒤 best-effort로 차단한다

**요구사항.** `maxFunctionCalls`는 병렬 배치 실행 중간에 call을 잘라내지 않고, 배치가 끝난 뒤
best-effort로 더 이상의 도구 실행을 막는 의미로 고정해야 한다.

**원본 비교**

- .NET: repo-local core에 병렬 예산 차단 의미가 직접 정의돼 있지 않다.
- Python: `max_function_calls`를 parallel batch 뒤에 검사하는 best-effort limit으로 구현하고 테스트한다.

**판단.** Python을 택한다. 배치 도중 일부 call만 잘라내면 순서와 결과가 불안정해진다. 예산 의미를
API와 수용 기준에 분명히 써 두는 편이 운영상 더 정직하다.

**수용 기준**

- 한 배치가 시작될 때 예산 안이면 그 배치의 모든 실행 가능 call이 끝까지 실행된다.
- 예산 초과가 배치 후 드러나면 다음 반복부터 `toolChoice=none` 또는 동등한 차단이 적용된다.
- 한도가 5이고 3개 병렬 call 두 배치가 연속으로 나오면 총 6회 실행 뒤 차단될 수 있다.

**근거** [05 Parallel calls와 execution result semantics](../upstream/snapshots/d0a4165f/features/05-function-tools.md),
[05 Java 결정](../upstream/snapshots/d0a4165f/features/05-function-tools.md)

---

## TOOL-012 병렬 실행은 실행 가능 배치만 다루고 순서를 보존한다

**요구사항.** 병렬 실행은 승인 대기나 미지정 도구가 없는 실행 가능 배치에만 적용해야 하며,
완료 순서와 무관하게 결과 그룹 순서는 입력 call 순서를 보존해야 한다.

**원본 비교**

- .NET: repo-local core는 병렬 실행과 결과 flattening 의미를 직접 정의하지 않는다.
- Python: 실행 가능 배치만 `asyncio.gather()`로 병렬 처리하고 입력 순서를 유지한다.

**판단.** Python을 택한다. 순서를 잃으면 모델에 되돌리는 대화 이력이 달라진다. 승인 대기 call과
실행 가능 call을 같은 병렬 그룹으로 섞는 것도 제어 흐름을 모호하게 만든다.

**수용 기준**

- 승인 필요 call, declaration-only call, unknown call이 섞인 그룹은 그대로 병렬 실행되지 않는다.
- 병렬 실행 결과 그룹의 배열 순서는 입력 call 순서와 같다.
- 개별 call 완료 순서가 달라도 결과 병합 순서는 바뀌지 않는다.

**근거** [05 Parallel calls와 execution result semantics](../upstream/snapshots/d0a4165f/features/05-function-tools.md)

---

## TOOL-013 도구 결과는 `Content` 목록으로 정규화한다

**요구사항.** 도구 반환값은 기본적으로 `Content` 목록 또는 명시적 `ToolResult`로
정규화해야 하며, raw 결과 우회는 명시 opt-in일 때만 허용해야 한다. typed handler의
`null` 반환은 성공 결과로 간주하지 않는다.

**원본 비교**

- .NET: repo-local core는 결과 정규화 규칙보다 하위 함수 호출 seam에 집중한다.
- Python: `None`, 문자열, 단일 `Content`, 임의 객체를 `list[Content]`로 정규화하고 `SKIP_PARSING`을 별도 opt-in으로 둔다.

**판단.** Python의 콘텐츠 중심 의미는 유지하되 `None`을 Java `null`로 직역하지 않는다.
`void`/`Consumer` convenience adapter는 원본의 `None → [""]` 의미를 명시적으로 만들고,
`ToolHandler`가 `null`을 반환하면 구현 오류다. engine 조립은 임의 객체를 JSON-safe
콘텐츠로 바꾸는 기본 result mapper를 제공하며 adapter는 이를 교체할 수 있다.

**수용 기준**

- `void` convenience handler는 빈 문자열 텍스트 콘텐츠 한 개로 정규화된다.
- typed handler가 `null`을 반환하면 `IllegalStateException`으로 실패한다.
- 문자열 반환은 텍스트 콘텐츠 한 개가 된다.
- 임의 객체 반환은 기본 result mapper를 통해 JSON 텍스트 또는 동등한 JSON-safe 구조화
  콘텐츠로 정규화되며, 명시 mapper가 있으면 기본값을 대체한다.
- raw 결과 우회를 켜지 않으면 원시 JVM 객체가 그대로 상위 루프로 올라가지 않는다.

**근거** [05 Parallel calls와 execution result semantics](../upstream/snapshots/d0a4165f/features/05-function-tools.md)

---

## TOOL-014 도구 정의와 런타임 카운터를 분리한다

**요구사항.** 도구 정의는 불변이어야 하고, 호출 횟수·예외 횟수 같은 런타임 카운터는 정의 객체와
분리된 실행 상태에 보관해야 한다.

**원본 비교**

- .NET: repo-local core는 function tool definition 자체의 persistent counter를 만들지 않는다.
- Python: `FunctionTool` 인스턴스에 호출 횟수와 예외 횟수가 누적된다.

**판단.** 두 원본과 다르게 Java 관습을 택한다. singleton 도구 정의에 mutable counter를 붙이면
세션 간 상태가 새어 나온다. 정의와 상태를 분리하면 재사용과 테스트가 단순해진다.

**수용 기준**

- 같은 도구 정의 객체를 여러 실행에서 재사용해도 정의 객체 자체의 public state는 변하지 않는다.
- `maxInvocations`·`maxInvocationExceptions` 판단은 실행 상태 또는 scoped copy로 이뤄진다.
- 한 실행의 카운터가 다른 실행에 자동으로 이어지지 않는다.

**근거** [05 상태·영속화](../upstream/snapshots/d0a4165f/features/05-function-tools.md),
[05 Java 결정](../upstream/snapshots/d0a4165f/features/05-function-tools.md)

---

## TOOL-015 스트리밍도 같은 도구 루프를 탄다

**요구사항.** 스트리밍 경로와 비스트리밍 경로는 같은 도구 반복, 예산, 승인 규칙을 공유해야 하며,
스트림을 끝까지 소비하면 같은 최종 결과를 복원할 수 있어야 한다.

**원본 비교**

- .NET: lower-level seam은 스트리밍 규칙을 직접 정의하지 않는다.
- Python: streaming과 non-streaming function invocation loops가 같은 budget 로직을 공유한다.

**판단.** Python을 택한다. 두 경로의 의미가 다르면 같은 에이전트를 호출해도 결과가 달라진다.
도구 루프는 I/O 형태가 아니라 실행 의미로 고정해야 한다.

**수용 기준**

- 스트리밍과 비스트리밍에서 `maxIterations`·`maxFunctionCalls`·approval 규칙이 동일하게 적용된다.
- 스트림을 끝까지 소비해 복원한 최종 응답은 비스트리밍 최종 응답과 같은 도구 결과를 가진다.
- 스트리밍 경로만 별도의 예산 해석을 두지 않는다.

**근거** [05 동시성·스트리밍·취소](../upstream/snapshots/d0a4165f/features/05-function-tools.md),
[05 Invocation configuration / layers / budgets](../upstream/snapshots/d0a4165f/features/05-function-tools.md)

---

## TOOL-016 승인 요청과 응답은 코어 콘텐츠다

**요구사항.** 승인 요청과 승인 응답은 Java 코어가 소유하는 콘텐츠 타입이어야 하며, 승인 요청은
반드시 추가 사용자 입력 요구로 표면화돼야 한다.

**원본 비교**

- .NET: approval type 본체는 외부 타입에 가깝고 repo-local code는 wrapper와 harness에 집중한다.
- Python: core가 approval request·response content를 직접 정의하고 user-input-request로 노출한다.

**판단.** Python을 택한다. 승인 타입을 코어가 소유해야 provider-neutral API가 된다. 그래야 caller,
세션, 로그가 같은 타입으로 승인 상태를 다룬다.

**수용 기준**

- 코어 콘텐츠 모델에 approval request와 approval response 타입이 있다.
- approval request는 request id와 tool call을 담는다.
- 최종 응답과 스트리밍 업데이트는 approval request를 user-input-request 집합으로 노출한다.

**근거** [06 공개 API·타입](../upstream/snapshots/d0a4165f/features/06-tool-approval.md),
[06 Java 결정](../upstream/snapshots/d0a4165f/features/06-tool-approval.md)

---

## TOOL-017 승인 응답은 원래 요청에만 결합한다

**요구사항.** 승인 응답은 Java 코어가 직전에 표면화한 요청에만 결합해야 하며, request id만 같고
도구명이나 인자를 바꾼 응답도 원래 요청의 tool call로 다시 결합해야 한다.

**원본 비교**

- .NET: forged response를 drop하고 substituted response는 surfaced request의 tool call로 rebound한다.
- Python: core와 middleware가 approval response id를 stored request와 맞춰 resolve한다.

**판단.** 두 원본의 공통 안전장치를 채택한다. 승인 응답이 caller가 보낸 payload 그대로 실행되면
승인 화면과 실제 실행이 분리된다. 그것은 보안 취약점이다.

**수용 기준**

- 알 수 없는 request id를 가진 approval response는 도구 실행으로 전달되지 않는다.
- request id만 같고 tool name·arguments가 바뀐 approval response도 실행 시 원래 surfaced tool call을 사용한다.
- 위 조건은 스트리밍 재개 경로에서도 동일하다.

**근거** [06 Middleware / state / rules 상세 실행 흐름](../upstream/snapshots/d0a4165f/features/06-tool-approval.md),
[06 구체 acceptance scenarios](../upstream/snapshots/d0a4165f/features/06-tool-approval.md)

---

## TOOL-018 승인 거부는 합성 종료 결과를 남긴다

**요구사항.** 승인 거부는 도구 호출을 조용히 지우지 말고, caller·모델·로그가 모두 볼 수 있는
합성 `function_result` 오류로 남겨야 한다.

**원본 비교**

- .NET: queued denial 흐름은 보이지만 final user-visible error materialization은 repo-local 층에 완전히 고정되지 않는다.
- Python: denied approval을 `Error: Tool call invocation was rejected by user.` synthetic result로 바꾼다.

**판단.** Python을 택한다. 거부를 삭제하면 왜 실행이 멈췄는지 사라진다. 더 안전한 기본값은
명시적 실패 아티팩트를 남기는 것이다.

**수용 기준**

- `approved=false` 응답을 주면 최종 대화 이력에 synthetic `function_result` 오류가 추가된다.
- 거부된 도구의 실제 본문은 호출되지 않는다.
- 거부 오류 문자열은 안정된 텍스트로 고정된다.

**근거** [06 Python core approval resume](../upstream/snapshots/d0a4165f/features/06-tool-approval.md),
[06 Denial / error / resume flow](../upstream/snapshots/d0a4165f/features/06-tool-approval.md),
[06 Java 결정](../upstream/snapshots/d0a4165f/features/06-tool-approval.md)

---

## TOOL-019 상시 승인은 정확한 인자와 호스트 경계로 매칭한다

**요구사항.** 상시 승인 규칙은 도구명, 정규화된 정확한 인자, 선택적 호스트 경계 값으로 매칭해야
하며, `null` 인자와 빈 객체 인자를 같은 뜻으로 취급하지 않아야 한다.

**원본 비교**

- .NET: `ToolName`과 exact serialized `Arguments`만 rule key로 가진다.
- Python: `tool_name`, exact `arguments`, `server_label`까지 포함해 rule을 매칭한다.

**판단.** Python을 택하고 host boundary를 필수 선택 항목으로 남긴다. 같은 이름의 도구가 다른
MCP 서버에 있을 수 있기 때문이다. 빈 인자를 wildcard로 넓히면 승인 누수가 생긴다.

**수용 기준**

- 규칙 키는 도구명과 exact-arguments를 포함한다.
- `arguments=null`은 tool-wide rule이고 `arguments={}`는 no-arg exact rule이다.
- 같은 도구명이라도 host boundary가 다르면 자동 승인되지 않는다.

**근거** [06 Tool approval rule / standing approval 모델](../upstream/snapshots/d0a4165f/features/06-tool-approval.md),
[06 Argument-aware approvals](../upstream/snapshots/d0a4165f/features/06-tool-approval.md),
[06 Java 결정](../upstream/snapshots/d0a4165f/features/06-tool-approval.md)

---

## TOOL-020 승인 큐는 세션 위에서 하나씩 진행한다

**요구사항.** 상시 승인과 대기열 기능을 제공하는 승인 미들웨어는 세션을 필수로 요구해야 하며,
여러 미해결 승인 요청이 있으면 한 번에 하나씩만 caller에 표면화해야 한다.

**원본 비교**

- .NET: higher-level `ToolApprovalAgent`가 세션 상태에 rules·queue·responses를 저장하고 one-at-a-time surface를 수행한다.
- Python: `ToolApprovalMiddleware`가 session 없이는 실패하고 queued request를 하나씩만 다시 보여 준다.

**판단.** 동일하다. 큐와 상시 규칙은 run 간 상태를 필요로 한다. 세션 없이 허용하면 중간 승인
상태를 잃어버린다.

**수용 기준**

- 승인 미들웨어를 세션 없이 실행하면 즉시 실패한다.
- unresolved approval이 여러 개면 첫 번째 하나만 caller로 돌려준다.
- 마지막 승인까지 해결되기 전에는 실제 도구 실행이 재개되지 않는다.

**근거** [06 Python opt-in `ToolApprovalMiddleware`](../upstream/snapshots/d0a4165f/features/06-tool-approval.md),
[06 Session 연계](../upstream/snapshots/d0a4165f/features/06-tool-approval.md),
[06 Denial / error / resume flow](../upstream/snapshots/d0a4165f/features/06-tool-approval.md)

---

## TOOL-021 자동 승인 연쇄는 상한으로 끊는다

**요구사항.** 자동 승인 규칙은 상시 승인 규칙보다 뒤에서 평가해야 하며, 자동 승인만으로 이어지는
내부 재호출 연쇄는 별도 상한으로 끊어야 한다.

**원본 비교**

- .NET: standing rules를 heuristic auto-approval보다 먼저 평가하고 `MaxAutoApprovalIterations` cap을 둔다.
- Python: standing rules와 heuristic callback을 제공하지만 .NET식 별도 runaway cap은 드러나지 않는다.

**판단.** .NET의 cap과 두 원본의 우선순위 아이디어를 함께 채택한다. 휴리스틱 규칙은 편리하지만
무한 내부 재호출을 만들 수 있다. 비용 폭주를 막는 상한이 필요하다.

**수용 기준**

- 상시 승인 규칙이 매치되면 휴리스틱 auto-approval rule은 평가하지 않는다.
- 자동 승인만 반복되면 설정된 상한 도달 뒤 다음 승인 요청을 caller에 표면화한다.
- 스트리밍 경로도 같은 상한을 사용한다.

**근거** [06 확장점](../upstream/snapshots/d0a4165f/features/06-tool-approval.md),
[06 동시성·스트리밍·취소](../upstream/snapshots/d0a4165f/features/06-tool-approval.md),
[06 구체 acceptance scenarios](../upstream/snapshots/d0a4165f/features/06-tool-approval.md)

---

## 이 문서가 다루지 않는 것

| 주제 | 소유 문서 |
| --- | --- |
| 에이전트 공개 진입점과 모델 포트 | [01 에이전트 실행과 모델 호출](01-agent-execution.md) |
| 메시지, 콘텐츠, 응답 타입 | [02 메시지와 콘텐츠 모델](02-message-content.md) |
| 구조화 출력 스키마와 파싱 | [03 구조화 출력](03-structured-output.md) |
| MCP 원격 도구 transport와 서버 호스팅 | [05 MCP 연동](05-mcp.md) |
| 세션 저장소와 상태 직렬화 | [06 세션과 대화 상태](06-sessions.md) |
| 인터셉터 체인과 컨텍스트 전파 | [07 인터셉터와 컨텍스트 관리](07-interceptors.md) |
