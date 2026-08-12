# 02 메시지와 콘텐츠 모델

**접두사** `MSG` · **원본 기능** [02 message-content](../upstream/snapshots/d0a4165f/features/02-message-content.md)

역할, 메시지, 멀티모달 콘텐츠, 응답, 스트리밍 업데이트, 사용량 메타데이터의 코어 데이터
계약을 정의한다. 실행 진입점은 [01 에이전트 실행과 모델 호출](01-agent-execution.md),
구조화 파싱은 [03 구조화 출력](03-structured-output.md), 도구 실행 의미는
[04 도구 정의와 실행 루프](04-tools.md)가 소유한다.

## 채택 범위

이 문서의 `등급`은 [README](README.md#요구사항-등급) 정의대로 기능을 만들기로 했을 때의 강제력이고, 채택 여부는 [호환성 매트릭스](../upstream/snapshots/d0a4165f/compatibility-matrix.md)를 따른다.

- 메시지·콘텐츠 기능(`MSG01`~`MSG04`)은 모두 채택 `필수`다.

## 요약

| ID | 요구사항 | 채택 | 등급 | 단계 |
| --- | --- | --- | --- | --- |
| MSG-001 | 코어가 대화 타입을 직접 소유한다 | 필수 | 필수 | MVP |
| MSG-002 | 역할은 알려진 값과 사용자 정의 값을 함께 허용한다 | 필수 | 필수 | MVP |
| MSG-003 | 입력을 메시지 목록으로 정규화한다 | 필수 | 필수 | MVP |
| MSG-004 | 텍스트 투영은 텍스트 콘텐츠만 본다 | 필수 | 필수 | MVP |
| MSG-005 | 코어가 기본 멀티모달 콘텐츠 종류를 제공한다 | 필수 | 필수 | Core+ |
| MSG-006 | URI와 바이너리 콘텐츠를 생성 시 검증한다 | 필수 | 필수 | Core+ |
| MSG-007 | 추가 속성과 원시 표현을 보존한다 | 필수 | 필수 | MVP |
| MSG-008 | 메시지 출처는 콘텐츠와 분리해 태깅한다 | 필수 | 필수 | Core+ |
| MSG-009 | 선두 지시문은 중복 삽입하지 않는다 | 필수 | 권장 | Core+ |
| MSG-010 | 사용량은 표준 필드와 확장 필드를 함께 가진다 | 필수 | 필수 | MVP |
| MSG-011 | 응답과 업데이트는 같은 메타데이터 축을 공유한다 | 필수 | 필수 | MVP |
| MSG-012 | 업데이트 시퀀스에서 최종 응답을 복원한다 | 필수 | 필수 | MVP |
| MSG-013 | 스트림 래퍼는 최종 응답과 변환 훅을 제공한다 | 필수 | 권장 | Core+ |

---

## MSG-001 코어가 대화 타입을 직접 소유한다

**요구사항.** Java 코어는 외부 AI SDK 타입을 그대로 공개하지 않고 `Message`, `Content`,
`ChatResponse`, `AgentResponse`, `ChatResponseUpdate`, `AgentResponseUpdate`,
`ResponseStream` 계열 타입을 직접 정의해야 한다.

**원본 비교**

- .NET: `ChatMessage`와 `AIContent`는 외부 `Microsoft.Extensions.AI` 타입에 맡기고 `AgentResponse` 계열만 감싼다.
- Python: `Content`, `Message`, `ChatResponse`, `AgentResponse`, `ResponseStream`를 코어가 직접 정의한다.

**판단.** Python 쪽 결정을 택한다. Java 공개 API가 외부 SDK 타입에 잠기면 콘텐츠 taxonomy와
직렬화 규칙을 코어가 통제할 수 없다. 코어가 타입을 소유해야 이후 approval, MCP, hosted
asset 같은 기능도 같은 모델 위에 올릴 수 있다.

**수용 기준**

- 코어 공개 패키지에 메시지·콘텐츠·응답·업데이트 타입이 모두 정의된다.
- 코어 모듈의 공개 시그니처에 공급자 SDK 타입이 직접 나타나지 않는다.
- 공급자 어댑터는 코어 타입과 공급자 타입 사이 변환만 담당한다.

**근거** [02 공개 API·타입](../upstream/snapshots/d0a4165f/features/02-message-content.md),
[02 Java 결정](../upstream/snapshots/d0a4165f/features/02-message-content.md)

---

## MSG-002 역할은 알려진 값과 사용자 정의 값을 함께 허용한다

**요구사항.** `Role`은 `system`, `user`, `assistant`, `tool`을 알려진 값으로 제공하되,
호환성을 위해 임의 문자열 역할도 허용해야 한다.

**원본 비교**

- .NET: `ChatRole`은 사용자 정의 문자열 role을 round-trip할 수 있다.
- Python: `RoleLiteral`에 알려진 값이 있지만 `Message(role: str)`도 허용한다.

**판단.** 동일한 의도다. 알려진 값은 기본 분기와 문서화를 쉽게 하고, 사용자 정의 값 허용은
공급자 확장과 중간 표현을 막지 않는다.

**수용 기준**

- 알려진 네 역할을 상수나 동등한 공개 계약으로 제공한다.
- 사용자 정의 역할 문자열을 담은 메시지가 손실 없이 직렬화·역직렬화된다.
- 사용자 정의 역할이 있어도 업데이트→응답 복원 규칙이 깨지지 않는다.

**근거** [02 Message / Role 모델](../upstream/snapshots/d0a4165f/features/02-message-content.md)

---

## MSG-003 입력을 메시지 목록으로 정규화한다

**요구사항.** 코어는 문자열, 단일 `Content`, 단일 `Message`, 메시지 시퀀스를 일관된
`List<Message>`로 정규화해야 하며, 문자열과 단일 `Content`는 사용자 메시지 하나로 올려야
한다. 입력 없음은 전용 no-input 진입점 또는 빈 목록으로 표현하고 `null`은 거부한다.

**원본 비교**

- .NET: 문자열 실행 오버로드는 문자열을 `user` 메시지 하나로 감싼다.
- Python: `normalize_messages()`가 `None`, `str`, `Content`, `Message`, 혼합 시퀀스를 모두 정규화한다.

**판단.** Python의 편의 입력 범위는 채택하되 `None`을 Java `null`로 직역하지 않는다.
Java에서 `null`→빈 목록 변환은 호출 실수를 숨긴다. no-input overload와 `List.of()`가 같은
의도를 non-null 계약으로 표현할 수 있다.

**수용 기준**

- no-input 진입점과 빈 목록 입력의 정규화 결과는 빈 목록이다.
- `null` 입력은 public boundary에서 즉시 실패한다.
- 문자열 입력 정규화 결과는 `user` 역할의 텍스트 메시지 하나다.
- 단일 `Content` 입력 정규화 결과는 `user` 역할의 메시지 하나다.
- 기존 `Message` 목록 입력은 순서를 바꾸지 않고 유지된다.

**근거** [02 Normalization과 메시지 조립](../upstream/snapshots/d0a4165f/features/02-message-content.md),
[02 구체 acceptance scenarios](../upstream/snapshots/d0a4165f/features/02-message-content.md)

---

## MSG-004 텍스트 투영은 텍스트 콘텐츠만 본다

**요구사항.** `Message.text`와 response-level text projection은 텍스트 콘텐츠만 순서대로
합치고, 비텍스트 콘텐츠를 해석하거나 문자열로 바꾸지 않아야 한다.

**원본 비교**

- .NET: `AgentResponse.Text`와 `AgentResponseUpdate.Text`는 `TextContent`만 합친다.
- Python: `Message.text`와 `ChatResponse.text`도 `text` content만 모아 투영한다.

**판단.** 동일하다. 텍스트 투영은 사람이 읽는 보조 뷰일 뿐 원본 콘텐츠를 대체하지 않는다.
비텍스트를 임의 문자열로 섞으면 멀티모달 fidelity가 깨지고 구조화 파싱도 불안정해진다.

**수용 기준**

- 비텍스트 콘텐츠만 가진 메시지의 `text`는 빈 문자열이다.
- 텍스트와 비텍스트가 섞여 있으면 `text`에는 텍스트 조각만 나타난다.
- 같은 순서의 텍스트 콘텐츠는 투영 결과에서도 같은 순서로 유지된다.

**근거** [02 Content 모델과 Multimodal 표현](../upstream/snapshots/d0a4165f/features/02-message-content.md),
[02 Response / Update / Usage / Finish Reason](../upstream/snapshots/d0a4165f/features/02-message-content.md)

---

## MSG-005 코어가 기본 멀티모달 콘텐츠 종류를 제공한다

**요구사항.** `Content` 계층은 최소한 텍스트, 추론 텍스트, 바이너리·URI 미디어,
함수·도구 호출과 결과, 사용량, 호스티드 자산 참조를 구분된 종류로 표현해야 한다.

**원본 비교**

- .NET: 코어는 자체 taxonomy를 정의하지 않고 외부 `AIContent` 계층을 opaque하게 보존한다.
- Python: 하나의 `Content` 합집합 모델로 텍스트, 미디어, 도구 호출, usage, hosted asset 등을 직접 표현한다.

**판단.** Python의 breadth를 택하되 Java에서는 알려진 core 종류와 provider extension
envelope를 함께 둔다. 순수 sealed hierarchy는 새 provider content가 들어올 때마다 core
수정을 요구하므로 사용하지 않는다. core가 공통 종류를 이해하면서도 미지의 종류를 손실 없이
보존해야 한다.

**수용 기준**

- 위 여섯 범주가 서로 구분되는 discriminator 또는 subtype으로 표현된다.
- 텍스트 콘텐츠와 도구 호출 콘텐츠가 같은 타입 분기 안에 섞이지 않는다.
- 각 콘텐츠는 코어 JSON 표현에서 자신의 종류를 식별할 수 있다.
- 새 provider content는 core hierarchy 수정이나 전역 factory 등록 없이 typed extension
  envelope로 round-trip된다.

**근거** [02 Content 모델과 Multimodal 표현](../upstream/snapshots/d0a4165f/features/02-message-content.md),
[02 Java 결정](../upstream/snapshots/d0a4165f/features/02-message-content.md)

---

## MSG-006 URI와 바이너리 콘텐츠를 생성 시 검증한다

**요구사항.** data URI와 외부 URI 콘텐츠는 생성 시 빈 값과 잘못된 형식을 거부해야 하며,
유효한 경우 media type을 함께 보존해야 한다.

**원본 비교**

- .NET: 이 스냅샷의 agent core에서 동등한 URI 검증 로직은 확인되지 않는다.
- Python: `_validate_uri()`가 빈 URI, 잘못된 data URI, scheme 없는 URI를 거부하고 media type을 다룬다.

**판단.** Python 쪽을 채택한다. URI를 느슨하게 받으면 런타임 뒤쪽에서 늦게 실패하거나 잘못된
외부 참조가 세션에 저장된다. 더 안전한 기본값이 맞다.

**수용 기준**

- 빈 URI 문자열로 URI 콘텐츠를 만들면 즉시 실패한다.
- 형식이 잘못된 data URI로 URI 콘텐츠를 만들면 즉시 실패한다.
- 유효한 URI 또는 data URI를 만들면 media type을 조회할 수 있다.

**근거** [02 Content 모델과 Multimodal 표현](../upstream/snapshots/d0a4165f/features/02-message-content.md),
[02 오류·검증·보안](../upstream/snapshots/d0a4165f/features/02-message-content.md)

---

## MSG-007 추가 속성과 원시 표현을 보존한다

**요구사항.** 모든 메시지·콘텐츠·응답·업데이트 타입은 JSON-safe 추가 속성과 선택적
provider 원시 표현을 구분해 보존해야 한다. 추가 속성은 영속 가능한 typed extension 값이고,
원시 표현은 adapter-local transient diagnostic handle이며 등록된 codec 없이는 세션에
직렬화하지 않는다.

**원본 비교**

- .NET: `AgentResponse`와 `AgentResponseUpdate`가 `AdditionalProperties`와 `RawRepresentation`을 가진다.
- Python: `Message`, `Content`, `ChatResponse`, `AgentResponse`와 update 타입이 모두 같은 두 필드를 가진다.

**판단.** 공급자 메타데이터와 디버그용 원본을 보존한다는 의미는 동일하다. 그러나 Python
객체나 .NET SDK 객체를 Java `Object` 필드 하나로 직역하면 classloader, native image,
직렬화 안전성 문제가 생긴다. 내구성 extension value와 일시적 native handle을 별도
수명주기로 다룬다.

**수용 기준**

- 어댑터가 넣은 `additionalProperties` key/value를 같은 객체에서 다시 읽을 수 있다.
- `rawRepresentation`이 있어도 텍스트 투영과 응답 복원 규칙은 달라지지 않는다.
- 코어 공개 요구사항은 `rawRepresentation`의 구체 타입을 전제하지 않는다.
- 미등록 provider SDK 객체가 세션 snapshot에 자동 직렬화되지 않는다.
- 추가 속성 키는 namespace와 값 타입을 식별하며 다른 adapter와 충돌하면 실패한다.

**근거** [02 상태·영속화](../upstream/snapshots/d0a4165f/features/02-message-content.md),
[02 Java 결정](../upstream/snapshots/d0a4165f/features/02-message-content.md)

---

## MSG-008 메시지 출처는 콘텐츠와 분리해 태깅한다

**요구사항.** 메시지 출처는 콘텐츠 본문이 아니라 message-level attribution으로 저장해야
하며, 출처 표식이 없으면 `External`로 간주해야 한다.

**원본 비교**

- .NET: `_attribution`이 없으면 source type을 `External`로 해석하고, 있으면 clone 후 태깅한다.
- Python: `_attribution`을 `additional_properties`에 넣어 source type, source id, origin session ids를 추적한다.

**판단.** 동일한 의도다. 출처를 콘텐츠 안에 섞으면 같은 본문을 서로 다른 맥락에서 재사용할
수 없고, 무표식 입력의 기본값을 `External`로 두는 편이 더 안전하다.

**수용 기준**

- attribution이 없는 메시지의 source type은 `External`이다.
- source type으로 `External`, `AIContextProvider`, `ChatHistory`를 구분할 수 있다.
- 같은 콘텐츠라도 서로 다른 출처 태그를 별도로 기록할 수 있다.

**근거** [02 Message / Role 모델](../upstream/snapshots/d0a4165f/features/02-message-content.md),
[02 상태·영속화](../upstream/snapshots/d0a4165f/features/02-message-content.md),
[02 구체 acceptance scenarios](../upstream/snapshots/d0a4165f/features/02-message-content.md)

---

## MSG-009 선두 지시문은 중복 삽입하지 않는다

**요구사항.** 같은 역할과 같은 텍스트의 선두 지시문이 이미 있으면, 코어는 같은 지시문을
다시 prepend하지 않아야 한다.

**원본 비교**

- .NET: 같은 helper 또는 같은 중복 제거 규칙은 이 스냅샷에서 확인되지 않는다.
- Python: `prepend_instructions_to_messages()`가 같은 선두 instruction이면 삽입을 건너뛴다.

**판단.** Python 쪽을 채택한다. 중복 시스템 프롬프트는 토큰을 낭비하고 동작도 바꾼다.
중복 제거는 기능 추가가 아니라 안전한 기본값이다.

**수용 기준**

- 같은 역할과 같은 텍스트의 선두 지시문이 이미 있으면 메시지 목록이 바뀌지 않는다.
- 선두 메시지의 역할이나 텍스트가 다르면 새 지시문을 앞에 추가한다.
- 사용자 정의 역할 지시문에도 같은 규칙을 적용한다.

**근거** [02 Normalization과 메시지 조립](../upstream/snapshots/d0a4165f/features/02-message-content.md),
[02 Python 구현과 테스트](../upstream/snapshots/d0a4165f/features/02-message-content.md),
[02 구체 acceptance scenarios](../upstream/snapshots/d0a4165f/features/02-message-content.md)

---

## MSG-010 사용량은 표준 필드와 확장 필드를 함께 가진다

**요구사항.** 사용량 메타데이터는 최소한 입력·출력·총 토큰 수를 표준 필드로 제공해야 하며,
캐시·추론 토큰 수와 공급자 고유 수치는 확장 필드에 보관해야 한다.

**원본 비교**

- .NET: 사용량은 response/update 모델에 연결되지만 구체 필드 집합은 외부 `UsageDetails` 타입에 맡긴다.
- Python: `UsageDetails`가 input/output/total/cache/reasoning token count와 확장 key를 함께 허용한다.

**판단.** 두 원본의 공통분모를 취한다. Java는 표준 필드를 명시하되 확장 맵을 남겨 공급자별
과금 수치를 잃지 않게 한다.

**수용 기준**

- 입력·출력·총 토큰 수를 개별 필드로 읽을 수 있다.
- 캐시·추론 토큰 수가 있으면 별도 필드나 동등한 표준 키로 읽을 수 있다.
- 알려지지 않은 공급자 사용량 키도 손실 없이 보존된다.

**근거** [02 공개 API·타입](../upstream/snapshots/d0a4165f/features/02-message-content.md),
[02 Response / Update / Usage / Finish Reason](../upstream/snapshots/d0a4165f/features/02-message-content.md)

---

## MSG-011 응답과 업데이트는 같은 메타데이터 축을 공유한다

**요구사항.** `AgentResponse`와 `AgentResponseUpdate`는 `agentId`, `responseId`,
`messageId`, `authorName`, `createdAt`, `finishReason`, `continuationToken`,
`additionalProperties`, `rawRepresentation` 메타데이터를 손실 없이 표현해야 한다.

**원본 비교**

- .NET: `AgentResponse`와 `AgentResponseUpdate`가 거의 같은 메타데이터 필드를 가진다.
- Python: `ChatResponse`, `AgentResponse`, `ChatResponseUpdate`, `AgentResponseUpdate`가 같은 축의 필드를 노출한다.

**판단.** 동일하다. 스트리밍 복원, 관찰성, 이어받기 실행이 모두 이 축에 의존한다.

**수용 기준**

- complete response를 update 시퀀스로 바꿨다가 다시 모으면 `agentId`와 `responseId`가 유지된다.
- 사용자 정의 역할과 `authorName`이 update 경로를 거쳐도 유지된다.
- `continuationToken`과 `finishReason`은 없을 수 있지만 있으면 손실되지 않는다.

**근거** [02 공개 API·타입](../upstream/snapshots/d0a4165f/features/02-message-content.md),
[02 Response / Update / Usage / Finish Reason](../upstream/snapshots/d0a4165f/features/02-message-content.md)

---

## MSG-012 업데이트 시퀀스에서 최종 응답을 복원한다

**요구사항.** 코어는 update 시퀀스를 complete response로 복원해야 하며, message boundary는
`messageId`를 우선 사용하고 없으면 역할 변화를 보조 신호로 사용해야 하며, usage 조각은
response-level usage로 합산해야 한다.

**원본 비교**

- .NET: `messageId`로 logical message 경계를 복원하고 usage를 합산한다.
- Python: `message_id` 또는 role 변화로 경계를 판단하고 usage content를 response-level usage로 누적한다.

**판단.** 두 원본을 합친다. `messageId` 우선은 정확하고, 역할 변화 fallback은 공급자 drift에
강하다. usage를 조각에서 응답 수준으로 접어 올려야 스트리밍과 비스트리밍이 같은 메타데이터를 본다.

**수용 기준**

- 같은 `messageId`를 가진 연속 업데이트는 하나의 메시지로 복원된다.
- `messageId`가 없고 역할이 바뀌면 새 메시지가 시작된다.
- usage 조각 여러 개를 보내면 최종 응답 usage에 합산된다.

**근거** [02 Message-Content-Stream Model](../upstream/snapshots/d0a4165f/features/02-message-content.md),
[02 .NET 구현과 테스트](../upstream/snapshots/d0a4165f/features/02-message-content.md),
[02 Python 구현과 테스트](../upstream/snapshots/d0a4165f/features/02-message-content.md)

---

## MSG-013 스트림 래퍼는 최종 응답과 변환 훅을 제공한다

**요구사항.** 스트리밍 API는 업데이트 반복과 별도로 `getFinalResponse()`를 제공해야 하며,
스트림 변환 뒤에도 내부 스트림의 finalizer·cleanup·result hook 순서를 보존해야 한다.

**원본 비교**

- .NET: response/update 변환 helper는 있지만 범용 `ResponseStream` 래퍼는 확인되지 않는다.
- Python: `ResponseStream`가 finalizer, `map()`, `flat_map()`, cleanup hook, result hook 순서를 테스트로 고정한다.

**판단.** Python 쪽을 채택한다. Java 스트리밍도 업데이트 이터레이션과 최종 응답 조회를 함께
가져야 상위 계층이 안전하게 조합할 수 있다. 다만 이는 코어 스트림 계약이지 호스트 런타임
기능은 아니다.

**수용 기준**

- 스트림을 끝까지 소비한 뒤 `getFinalResponse()`를 호출하면 같은 최종 응답을 얻는다.
- `map()`이나 동등한 변환 뒤에도 최종 응답 계산이 유지된다.
- 내부 스트림에 등록한 cleanup 또는 result hook이 변환 래퍼 때문에 사라지지 않는다.

**근거** [02 Message-Content-Stream Model](../upstream/snapshots/d0a4165f/features/02-message-content.md),
[02 동시성·스트리밍·취소](../upstream/snapshots/d0a4165f/features/02-message-content.md),
[02 구체 acceptance scenarios](../upstream/snapshots/d0a4165f/features/02-message-content.md)

---

## 이 문서가 다루지 않는 것

| 주제 | 소유 문서 |
| --- | --- |
| 실행 진입점과 취소 전달 | [01 에이전트 실행과 모델 호출](01-agent-execution.md) |
| 구조화 출력 요청과 스키마 파싱 | [03 구조화 출력](03-structured-output.md) |
| 도구 호출 알고리즘과 승인 정책 | [04 도구 정의와 실행 루프](04-tools.md) |
| 세션 저장 형식과 이력 보관 | [06 세션과 대화 상태](06-sessions.md) |
| 인터셉터와 컨텍스트 전달 | [07 인터셉터와 컨텍스트 관리](07-interceptors.md) |
