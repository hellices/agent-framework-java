# 03 구조화 출력

**접두사** `OUT` · **원본 기능** [04 structured-output](../upstream/snapshots/d0a4165f/features/04-structured-output.md)

구조화 출력 요청, 스키마 표현, 네이티브·폴백 경로, 파싱과 검증, 스트리밍 제약의 코어 계약을
정의한다. 메시지 모델은 [02 메시지와 콘텐츠 모델](02-message-content.md), 일반 실행과
옵션 병합은 [01 에이전트 실행과 모델 호출](01-agent-execution.md)이 소유한다.

## 채택 범위

이 문서의 `등급`은 [README](README.md#요구사항-등급) 정의대로 기능을 만들기로 했을 때의 강제력이고, 채택 여부는 [호환성 매트릭스](../upstream/snapshots/d0a4165f/compatibility-matrix.md)를 따른다.

- 구조화 출력 기능(`SO01`, `SO02`)은 모두 채택 `필수`다.

## 요약

| ID | 요구사항 | 채택 | 등급 | 단계 |
| --- | --- | --- | --- | --- |
| OUT-001 | 구조화 출력은 명시적으로 요청한다 | 필수 | 필수 | MVP |
| OUT-002 | 타입 검증 경로와 JSON 전용 경로를 함께 지원한다 | 필수 | 필수 | MVP |
| OUT-003 | 비객체 목표 타입은 래퍼 스키마로 요청한다 | 필수 | 필수 | MVP |
| OUT-004 | response format 우선순위를 고정한다 | 필수 | 필수 | MVP |
| OUT-005 | 네이티브 지원 여부는 최선 노력 계약으로 본다 | 필수 | 필수 | MVP |
| OUT-006 | 타입 값 파싱은 응답 접근 시점에 수행한다 | 필수 | 필수 | MVP |
| OUT-007 | 구조화 파싱 대상은 마지막 assistant 텍스트 본문이다 | 필수 | 필수 | MVP |
| OUT-008 | 검증 오류와 JSON 파싱 오류를 구분한다 | 필수 | 필수 | MVP |
| OUT-009 | 비어 있거나 null인 structured payload는 명시적으로 실패한다 | 필수 | 필수 | MVP |
| OUT-010 | 유효한 JSON 텍스트면 네이티브 미지원이어도 폴백 파싱한다 | 필수 | 필수 | MVP |
| OUT-011 | 래퍼가 기대됐는데 bare JSON이 오면 원본 JSON으로 재시도한다 | 필수 | 권장 | Core+ |
| OUT-012 | 구조화 스트리밍 값은 최종 응답에서만 읽는다 | 필수 | 필수 | Core+ |

---

## OUT-001 구조화 출력은 명시적으로 요청한다

**요구사항.** 구조화 출력은 일반 실행과 분리된 명시적 요청이어야 하며, 호출자가 스키마나
response format을 주지 않으면 코어는 자유 텍스트 실행으로 동작해야 한다.

**원본 비교**

- .NET: `RunAsync<T>()`와 `ResponseFormat`으로 구조화 출력을 명시적으로 요청한다.
- Python: `ChatOptions["response_format"]`를 넣어야 `value`가 의미를 가진다.

**판단.** 동일하다. 구조화 출력을 숨은 기본값으로 두면 일반 텍스트 경로가 예기치 않게 깨진다.
호출자가 요청했을 때만 구조화 계약을 켜야 한다.

**수용 기준**

- 구조화 요청 없이 실행한 응답은 typed value가 자동 생성되지 않는다.
- 구조화 요청을 주면 실행 옵션에 schema 또는 response format 정보가 기록된다.
- 일반 실행 API는 구조화 요청이 없어도 그대로 사용할 수 있다.

**근거** [04 공개 API·타입](../upstream/snapshots/d0a4165f/features/04-structured-output.md),
[04 목적](../upstream/snapshots/d0a4165f/features/04-structured-output.md)

---

## OUT-002 타입 검증 경로와 JSON 전용 경로를 함께 지원한다

**요구사항.** 코어는 강한 타입 검증 경로와 JSON만 보장하는 경로를 함께 지원해야 한다.

**원본 비교**

- .NET: 제네릭 타입 `T`와 serializer options로 typed schema를 요청한다.
- Python: `response_format`에 Pydantic type 또는 JSON schema mapping을 넣을 수 있다.

**판단.** 두 원본의 장점을 합친다. Java는 typed route와 JSON-only route를 분리해야 한다.
모든 호출자에게 강한 바인딩을 강요하면 가벼운 JSON 수집 용도가 막힌다.

**수용 기준**

- 타입 검증 경로는 호출자가 지정한 Java 타입 또는 동등한 스키마 타입으로 값을 돌려준다.
- JSON 전용 경로는 유효한 JSON이면 tree/map 값으로 돌려준다.
- JSON 전용 경로는 별도 타입 검증이 없어도 사용할 수 있다.

**근거** [04 Structured response type/schema](../upstream/snapshots/d0a4165f/features/04-structured-output.md),
[04 확장점](../upstream/snapshots/d0a4165f/features/04-structured-output.md),
[04 Java 결정](../upstream/snapshots/d0a4165f/features/04-structured-output.md)

---

## OUT-003 비객체 목표 타입은 래퍼 스키마로 요청한다

**요구사항.** primitive, 배열, enum 같은 비객체 목표 타입은 request 단계에서 래퍼 객체
스키마로 감싸 요청하고, 파싱 시에는 다시 원래 타입으로 풀어야 한다.

**원본 비교**

- .NET: non-object schema를 wrapper object로 감싸고 `IsWrappedInObject`로 추적한다.
- Python: 별도 wrapper schema를 만들지 않고 최종 텍스트를 그대로 파싱한다.

**판단.** .NET 방식을 택한다. Java에서는 wire-level 요청 계약을 명시하는 편이 낫다. 비객체를
그대로 요청하면 공급자마다 기대 형식이 모호해진다.

**수용 기준**

- 객체 타입 요청은 래퍼 없이 원래 스키마를 사용한다.
- primitive, 배열, enum 타입 요청은 래퍼 여부를 식별할 수 있다.
- 파싱 성공 결과는 래퍼 객체가 아니라 원래 목표 타입 값이다.

**근거** [04 Structured response type/schema](../upstream/snapshots/d0a4165f/features/04-structured-output.md),
[04 .NET 구현과 테스트](../upstream/snapshots/d0a4165f/features/04-structured-output.md),
[04 Java 결정](../upstream/snapshots/d0a4165f/features/04-structured-output.md)

---

## OUT-004 response format 우선순위를 고정한다

**요구사항.** response format이 여러 층에서 주어지면 실행 시점 override가 가장 우선하고,
그다음이 실행별 chat options, 마지막이 에이전트 기본값이어야 한다.

**원본 비교**

- .NET: 초기화 옵션, invocation 옵션, run options의 세 층을 지원하고 마지막 값이 이긴다.
- Python: `response_format`은 `ChatOptions`에 담기며 기본 옵션과 실행 옵션 모두에서 전달된다.

**판단.** .NET의 우선순위 규칙을 채택한다. 구조화 출력도 일반 옵션 병합처럼 우선순위가
고정되어야 한다. 그래야 호출자가 어떤 스키마가 실제 적용됐는지 추론할 수 있다.

**수용 기준**

- 에이전트 기본값만 있으면 그 값이 적용된다.
- 실행 옵션에 다른 response format을 주면 기본값을 덮어쓴다.
- 같은 실행에서 상위 override와 하위 기본값이 충돌하면 상위 override가 이긴다.

**근거** [04 Provider capability](../upstream/snapshots/d0a4165f/features/04-structured-output.md),
[04 .NET 구현과 테스트](../upstream/snapshots/d0a4165f/features/04-structured-output.md)

---

## OUT-005 네이티브 지원 여부는 최선 노력 계약으로 본다

**요구사항.** 코어는 구조화 출력을 요청할 수만 있고 공급자가 이를 네이티브로 지킬 것을
강제하지 않아야 하며, 미지원 보완을 위해 숨은 재시도나 추가 모델 호출을 하지 않아야 한다.

**원본 비교**

- .NET: `ResponseFormat`은 구현이 무시할 수 있다고 문서화한다.
- Python: 네이티브 structured capability 프로토콜이 없고, 결과적으로 provider가 `response_format`을 honor하느냐에 달린다.

**판단.** 동일하다. 구조화 지원은 공급자 capability다. 코어가 몰래 두 번째 호출을 하면
비용, 지연, 감사 가능성이 모두 깨진다. AgentEngine이 호스트 책임을 가져오지도 말아야 한다.

**수용 기준**

- 구조화 요청은 하나의 모델 호출에만 전달된다.
- 공급자가 response format을 무시해도 코어는 자동 재프롬프트를 하지 않는다.
- 네이티브 미지원 여부는 파싱 성공 또는 실패로만 드러난다.

**근거** [04 Provider capability](../upstream/snapshots/d0a4165f/features/04-structured-output.md),
[04 Native vs fallback behavior](../upstream/snapshots/d0a4165f/features/04-structured-output.md)

---

## OUT-006 타입 값 파싱은 응답 접근 시점에 수행한다

**요구사항.** 구조화 출력의 타입 값 파싱은 실행 완료 시점이 아니라 응답의 typed accessor를
읽는 시점에 수행해야 한다.

**원본 비교**

- .NET: `AgentResponse<T>.Result`를 읽을 때 텍스트를 deserialize한다.
- Python: `ChatResponse.value`와 `AgentResponse.value`는 property 접근 전까지 파싱하지 않는다.

**판단.** 동일하다. 실행과 파싱을 분리하면 인터셉터와 후처리기가 raw response를 먼저 볼 수
있고, 호출자는 필요할 때만 parse 비용을 낸다.

**수용 기준**

- malformed structured payload여도 실행 자체는 응답 객체를 돌려줄 수 있다.
- parse 오류는 typed accessor를 읽는 시점에 발생한다.
- 실행 후 훅은 typed accessor 호출 전에도 같은 응답 객체를 관찰할 수 있다.

**근거** [04 Parsing / validation](../upstream/snapshots/d0a4165f/features/04-structured-output.md),
[04 오류와 .NET/Python drift](../upstream/snapshots/d0a4165f/features/04-structured-output.md)

---

## OUT-007 구조화 파싱 대상은 마지막 assistant 텍스트 본문이다

**요구사항.** 구조화 파서는 display용 `response.text` 전체가 아니라 마지막 non-empty
assistant message의 텍스트 콘텐츠 본문만 이어 붙인 값을 대상으로 삼아야 한다.

**원본 비교**

- .NET: `AgentResponse<T>.Result`는 response `Text` 전체를 대상으로 파싱한다.
- Python: 마지막 non-empty assistant message의 text contents direct concat만 파싱한다.

**판단.** Python 쪽을 택한다. display projection은 줄바꿈이나 공백을 끼워 넣을 수 있어 JSON을
손상시킨다. 도구 메시지나 이전 assistant 메시지를 섞지 않는 편이 더 안전하다.

**수용 기준**

- trailing tool message가 있어도 구조화 파싱 대상에서 제외된다.
- assistant 메시지가 여러 개면 마지막 non-empty assistant 메시지만 파싱한다.
- 하나의 assistant 메시지가 여러 text chunk로 나뉘어도 direct concat 결과로 파싱한다.

**근거** [04 Native vs fallback behavior](../upstream/snapshots/d0a4165f/features/04-structured-output.md),
[04 오류와 .NET/Python drift](../upstream/snapshots/d0a4165f/features/04-structured-output.md),
[04 Python 구현과 테스트](../upstream/snapshots/d0a4165f/features/04-structured-output.md)

---

## OUT-008 검증 오류와 JSON 파싱 오류를 구분한다

**요구사항.** 타입 검증 경로는 바인딩·검증 오류를 별도로 보고해야 하고, JSON 전용 경로는
유효하지 않은 JSON일 때만 파싱 오류를 보고해야 한다.

**원본 비교**

- .NET: empty text, null deserialize 같은 실패를 명시적 예외로 올린다.
- Python: mapping parse는 `ValueError`, schema mismatch는 `ValidationError`로 구분한다.

**판단.** Python의 오류 분류와 .NET의 명시적 실패를 합친다. 호출자는 "JSON 자체가 깨졌는지"
"JSON은 맞지만 타입이 안 맞는지"를 구별할 수 있어야 복구 전략을 세울 수 있다.

**수용 기준**

- 유효하지 않은 JSON은 JSON 파싱 오류로 보고된다.
- 타입 검증 경로에서 필수 필드 누락이나 타입 불일치는 검증 오류로 보고된다.
- JSON 전용 경로는 유효한 JSON이면 스키마 불일치 오류를 내지 않는다.

**근거** [04 Structured response type/schema](../upstream/snapshots/d0a4165f/features/04-structured-output.md),
[04 Parsing / validation](../upstream/snapshots/d0a4165f/features/04-structured-output.md),
[04 Python 구현과 테스트](../upstream/snapshots/d0a4165f/features/04-structured-output.md)

---

## OUT-009 비어 있거나 null인 structured payload는 명시적으로 실패한다

**요구사항.** 구조화 출력이 요청된 경우, 파싱 대상 텍스트가 비어 있거나 JSON 결과가 `null`이면
typed accessor는 값을 돌려주지 말고 명시적으로 실패해야 한다.

**원본 비교**

- .NET: empty text와 `null` deserialize를 모두 예외로 처리한다.
- Python: empty text면 `None`을 돌려준다.

**판단.** .NET 쪽을 택한다. 구조화 출력 요청은 "값이 있어야 한다"는 계약이다. 빈 값이나
`null`을 성공으로 처리하면 조용한 실패가 된다.

**수용 기준**

- 파싱 대상 텍스트가 비어 있으면 typed accessor가 실패한다.
- JSON payload가 `null`이면 typed accessor가 실패한다.
- 실패는 `Optional.empty()` 같은 조용한 성공으로 표현되지 않는다.

**근거** [04 Parsing / validation](../upstream/snapshots/d0a4165f/features/04-structured-output.md),
[04 오류와 .NET/Python drift](../upstream/snapshots/d0a4165f/features/04-structured-output.md),
[04 .NET 구현과 테스트](../upstream/snapshots/d0a4165f/features/04-structured-output.md)

---

## OUT-010 유효한 JSON 텍스트면 네이티브 미지원이어도 폴백 파싱한다

**요구사항.** 공급자가 네이티브 structured mode를 강하게 지원하지 않아도 최종 assistant
텍스트가 유효한 JSON이면, 코어는 추가 모델 호출 없이 typed value 또는 JSON value를 만들어야 한다.

**원본 비교**

- .NET: schema를 요청한 뒤 최종 텍스트를 deserialize하는 경로가 기본이다.
- Python: provider가 native structured mode를 구현하지 않아도 valid JSON text면 `value`를 만든다.

**판단.** 동일한 핵심 동작이다. 구조화 출력의 마지막 방어선은 공급자 capability 표기가 아니라
최종 텍스트 파싱 가능성이다.

**수용 기준**

- fake client가 단순 JSON 텍스트만 돌려줘도 typed 또는 JSON value를 만들 수 있다.
- 이 경로에서도 추가 모델 호출이 발생하지 않는다.
- JSON이 유효하지 않으면 성공으로 폴백하지 않고 명시적으로 실패한다.

**근거** [04 Provider capability](../upstream/snapshots/d0a4165f/features/04-structured-output.md),
[04 Native vs fallback behavior](../upstream/snapshots/d0a4165f/features/04-structured-output.md),
[04 구체 acceptance scenarios](../upstream/snapshots/d0a4165f/features/04-structured-output.md)

---

## OUT-011 래퍼가 기대됐는데 bare JSON이 오면 원본 JSON으로 재시도한다

**요구사항.** 비객체 목표 타입 때문에 래퍼 스키마를 요청했더라도, 공급자가 bare primitive,
bare array, bare enum JSON을 돌려주면 코어는 원본 JSON으로 한 번 더 파싱을 시도해야 한다.

**원본 비교**

- .NET: 래퍼가 기대됐는데 bare primitive가 와도 original JSON fallback으로 파싱한다.
- Python: 대응하는 래퍼 재시도 경로는 이 스냅샷에서 확인되지 않는다.

**판단.** .NET의 호환성 폴백을 채택한다. 공급자 drift를 흡수하되, JSON 자체가 틀렸을 때는
실패하게 두는 선에서만 관용을 둔다.

**수용 기준**

- 래퍼가 기대된 `int` 응답에 `42`가 오면 `42`로 파싱된다.
- 래퍼가 기대된 배열 응답에 bare JSON 배열이 오면 배열 값으로 파싱된다.
- 유효하지 않은 JSON에는 이 폴백을 적용하지 않는다.

**근거** [04 Native vs fallback behavior](../upstream/snapshots/d0a4165f/features/04-structured-output.md),
[04 구체 acceptance scenarios](../upstream/snapshots/d0a4165f/features/04-structured-output.md),
[04 .NET 구현과 테스트](../upstream/snapshots/d0a4165f/features/04-structured-output.md)

---

## OUT-012 구조화 스트리밍 값은 최종 응답에서만 읽는다

**요구사항.** 구조화 스트리밍은 typed update API를 따로 두지 않고, structured value는 스트림
종료 뒤 final response에서만 읽어야 한다.

**원본 비교**

- .NET: `RunStreamingAsync<T>()` 같은 typed streaming public API는 없다.
- Python: streaming structured output도 `get_final_response()` 뒤의 `value`에서만 읽는다.

**판단.** 두 원본의 보수적 공통분모를 취한다. 업데이트마다 구조화 값을 만들면 중간 JSON 조각과
완료 JSON을 구분하기 어렵다.

**수용 기준**

- 스트리밍 update 타입에는 최종 structured value accessor가 없다.
- 스트림 종료 뒤 얻은 final response에서는 structured value를 읽을 수 있다.
- 부분 업데이트만 받은 상태에서는 structured value를 성공으로 보고하지 않는다.

**근거** [04 동시성·스트리밍·취소](../upstream/snapshots/d0a4165f/features/04-structured-output.md),
[04 구체 acceptance scenarios](../upstream/snapshots/d0a4165f/features/04-structured-output.md),
[04 Python 구현과 테스트](../upstream/snapshots/d0a4165f/features/04-structured-output.md)

---

## 이 문서가 다루지 않는 것

| 주제 | 소유 문서 |
| --- | --- |
| 메시지 역할과 콘텐츠 taxonomy | [02 메시지와 콘텐츠 모델](02-message-content.md) |
| 일반 실행 진입점과 취소 모델 | [01 에이전트 실행과 모델 호출](01-agent-execution.md) |
| 도구 호출 루프와 도구 승인 | [04 도구 정의와 실행 루프](04-tools.md) |
| 세션 저장과 응답 캐시 영속화 | [06 세션과 대화 상태](06-sessions.md) |
| 공급자별 wire format 인코딩 | [12 공급자 통합](12-providers.md) |
