# 07 인터셉터와 컨텍스트 관리

**접두사** `INT` · **원본 기능** [10 middleware](../upstream/snapshots/d0a4165f/features/10-middleware.md),
[11 compaction](../upstream/snapshots/d0a4165f/features/11-compaction.md)

에이전트 실행, 모델 호출, 도구 호출, 세션 전후 처리의 인터셉터 계약과 문맥 압축 규칙을
정의한다. 세션 스냅샷과 저장소는 [06 세션과 대화 상태](06-sessions.md), 도구 승인과 호출
루프는 [04 도구 정의와 실행 루프](04-tools.md), 하네스의 기본 조립은
[08 하네스 기능](08-harness.md)이 소유한다.

## 채택 범위

이 문서의 `등급`은 [README](README.md#요구사항-등급) 정의대로 기능을 만들기로 했을 때의 강제력이고, 채택 여부는 [호환성 매트릭스](../upstream/snapshots/d0a4165f/compatibility-matrix.md)를 따른다.

- 인터셉터 seam(`MID01`)은 채택 `필수`다.
- 컴팩션 기능군(`CMP01`~`CMP03`)에 대응하는 `INT-014`~`INT-021`은 채택 `선택`이다.

## 요약

| ID | 요구사항 | 채택 | 등급 | 단계 |
| --- | --- | --- | --- | --- |
| INT-001 | 공개 확장점은 책임별 타입 인터셉터로 고정한다 | 필수 | 필수 | MVP |
| INT-002 | 인터셉터는 DI·트랜잭션·보안·AOP를 재구현하지 않는다 | 필수 | 필수 | MVP |
| INT-003 | 등록 순서가 전처리 순서와 감싸기 방향을 결정한다 | 필수 | 필수 | MVP |
| INT-004 | 인터셉터 컨텍스트는 명시적이며 변경 지점이 제한된다 | 필수 | 필수 | MVP |
| INT-005 | 지원하지 않는 seam 설치는 즉시 실패한다 | 필수 | 필수 | MVP |
| INT-006 | `next`를 호출하지 않으면 단축 종료한다 | 필수 | 필수 | MVP |
| INT-007 | 후처리에서 결과를 대체할 수 있다 | 필수 | 필수 | MVP |
| INT-008 | 도구 예외 처리 규칙은 실행 전후를 구분한다 | 필수 | 권장 | Core+ |
| INT-009 | 세션 상태 기계는 코어가 소유하고 인터셉터는 관여 지점만 가진다 | 필수 | 필수 | Core+ |
| INT-010 | 다중 seam 기능은 opaque bundle로만 배포한다 | 필수 | 권장 | Core+ |
| INT-011 | 도구 집합 변경은 다음 반복에만 반영한다 | 필수 | 권장 | Core+ |
| INT-012 | 스트리밍 재작성은 finalization 경계 안에서만 허용한다 | 필수 | 필수 | Core+ |
| INT-013 | 취소는 모든 인터셉터와 컴팩션으로 전파한다 | 필수 | 필수 | MVP |
| INT-014 | 컴팩션은 도구 호출과 도구 결과의 원자성을 보존한다 | 선택 | 필수 | Core+ |
| INT-015 | 컴팩션은 증분 업데이트에서도 식별자와 계수를 안정적으로 유지한다 | 선택 | 필수 | Core+ |
| INT-016 | 컴팩션 내부 상태는 명시적 index와 trace metadata로 표현한다 | 선택 | 필수 | Core+ |
| INT-017 | 컴팩션 시작 조건과 종료 조건을 분리한다 | 선택 | 권장 | Core+ |
| INT-018 | group 기반과 turn 기반 sliding 정책을 분리한다 | 선택 | 권장 | Core+ |
| INT-019 | tool-result compaction은 요약으로 대체하되 원문을 다시 노출하지 않는다 | 선택 | 권장 | Core+ |
| INT-020 | summarization compaction은 실패 시 원본을 복구한다 | 선택 | 필수 | Core+ |
| INT-021 | compaction provider는 실행 전 projection과 선택적 저장 후 훅을 제공한다 | 선택 | 권장 | Core+ |
| INT-022 | 로깅과 텔레메트리 인터셉터는 민감 데이터 노출을 opt-in으로 둔다 | 필수 | 권장 | Optional |

---

## INT-001 공개 확장점은 책임별 타입 인터셉터로 고정한다

**요구사항.** 공개 인터셉터 API는 최소한 `AgentExecutionInterceptor`,
`ModelCallInterceptor`, `ToolCallInterceptor`, `SessionInterceptor`처럼 책임이 분리된 타입으로
제공해야 하며, 모든 것을 받는 단일 범용 middleware 타입을 공개 1급 확장점으로 두지 않는다.

**원본 비교**

- .NET: agent decorator, function invocation wrapper, chat-client decorator처럼 seam이 나뉜다.
- Python: `AgentMiddleware`, `ChatMiddleware`, `FunctionMiddleware`의 3-seam typed pipeline이 중심이다.

**판단.** Python의 typed seam을 공개 API로 채택하고, .NET의 prebuilt decorator 패턴은 편의
구현으로만 둔다. 책임이 섞인 범용 middleware는 순서와 상태 소유권을 흐린다.

**수용 기준**

- 공개 인터셉터 API는 실행, 모델, 도구, 세션 seam을 타입으로 구분한다.
- 한 인터셉터가 지원하지 않는 seam에 암묵적으로 설치되지 않는다.
- 편의 wrapper가 있더라도 최종적으로는 위 타입 seam 중 하나에 매핑된다.

**근거** [10 목적·경계](../upstream/snapshots/d0a4165f/features/10-middleware.md),
[10 API·타입](../upstream/snapshots/d0a4165f/features/10-middleware.md),
[10 Java typed interceptor 결정과 Spring 통합 경계](../upstream/snapshots/d0a4165f/features/10-middleware.md)

---

## INT-002 인터셉터는 DI·트랜잭션·보안·AOP를 재구현하지 않는다

**요구사항.** 인터셉터 계층은 실행 전후 관찰과 변형만 책임지며, DI 컨테이너, 트랜잭션
경계, 호스트 보안 정책, 범용 AOP를 대체하지 않는다.

**원본 비교**

- .NET: builder와 decorator가 실행 계약만 고정하고 hosted identity와 approval state machine은 별도 계층이 맡는다.
- Python: typed pipeline이 execution control에 집중하고 supported-category enforcement로 책임 범위를 제한한다.

**판단.** 두 원본 모두 seam은 좁다. Java도 같은 경계를 유지해야 한다. AgentEngine이 호스트
책임을 끌어오면 테스트 가능한 계약이 무너진다.

**수용 기준**

- 인터셉터 API가 트랜잭션 전파나 권한 판정 전용 메서드를 노출하지 않는다.
- 호스트 보안 정보는 세션 또는 호스팅 컨텍스트 입력으로만 들어오며 인터셉터가 자체 생성하지 않는다.
- 문서가 Spring AOP나 BeanPostProcessor가 실행 순서를 재해석하지 말아야 함을 명시한다.

**근거** [10 목적·경계](../upstream/snapshots/d0a4165f/features/10-middleware.md),
[10 Java typed interceptor 결정과 Spring 통합 경계](../upstream/snapshots/d0a4165f/features/10-middleware.md)

---

## INT-003 등록 순서가 전처리 순서와 감싸기 방향을 결정한다

**요구사항.** 인터셉터는 등록 순서대로 전처리하고 역순으로 후처리해야 하며, 먼저 등록한
인터셉터가 가장 바깥을 감싸는 규칙을 모든 seam에서 고정해야 한다.

**원본 비교**

- .NET: builder는 factory를 역순 적용해 먼저 `Use()`한 것이 outermost가 된다.
- Python: seam별 pipeline이 registration order pre / reverse order post를 테스트로 고정한다.

**판단.** 동일하다. 이 규칙이 흔들리면 로깅, 승인, redaction 순서가 환경마다 달라진다. Java는
builder와 런타임 모두 같은 ordering semantics를 써야 한다.

**수용 기준**

- 두 인터셉터 `A`, `B`를 그 순서로 등록하면 호출 순서는 `A-pre → B-pre → handler → B-post → A-post`다.
- agent, model, tool, session seam 모두 같은 순서를 사용한다.
- 런타임이 컨테이너 정렬 결과를 다시 뒤집지 않는다.

**근거** [10 실행 흐름](../upstream/snapshots/d0a4165f/features/10-middleware.md),
[10 Acceptance scenarios](../upstream/snapshots/d0a4165f/features/10-middleware.md)

---

## INT-004 인터셉터 컨텍스트는 명시적이며 변경 지점이 제한된다

**요구사항.** 각 seam은 메시지, 옵션, 세션, 메타데이터, 결과, 스트림 훅을 담은 명시적
컨텍스트 객체를 사용해야 하며 전역 스레드 로컬에 의존하지 않는다. 입력과 core-owned 상태는
불변이고, 결과 교체와 extension attribute 변경은 typed API로만 허용한다.

**원본 비교**

- .NET: typed context object보다 decorator와 callback shape가 중심이지만 실행 옵션과 function context를 명시적으로 전달한다.
- Python: `AgentContext`, `ChatContext`, `FunctionInvocationContext`가 metadata와 mutable result를 공유 표면으로 둔다.

**판단.** Python의 명시적 context 의미는 채택하되 shared mutable object를 직역하지 않는다.
Java 비동기 실행과 스트리밍에서 임의 setter와 `Map<String, Object>`는 경쟁 조건과 namespace
충돌을 만든다. 불변 request snapshot, controlled result replacement, typed `ContextKey<T>`가
같은 확장성을 더 안전하게 제공한다.

**수용 기준**

- 각 seam에 대응하는 공개 컨텍스트 타입이 있다.
- 인터셉터는 컨텍스트를 통해 결과를 읽고 교체할 수 있다.
- 실행 중 필요한 메타데이터를 읽기 위해 thread-local API를 호출할 필요가 없다.
- core-owned 입력과 세션 identity를 인터셉터가 임의 setter로 바꿀 수 없다.
- extension attribute는 값 타입을 식별하는 typed key를 사용하고 key 충돌 시 실패한다.

**근거** [10 API·타입](../upstream/snapshots/d0a4165f/features/10-middleware.md),
[10 상태](../upstream/snapshots/d0a4165f/features/10-middleware.md),
[10 Java typed interceptor 결정과 Spring 통합 경계](../upstream/snapshots/d0a4165f/features/10-middleware.md)

---

## INT-005 지원하지 않는 seam 설치는 즉시 실패한다

**요구사항.** 특정 host나 seam이 없는 곳에 인터셉터를 설치하려 하면 조용히 건너뛰지 말고
초기화 단계에서 명시적으로 실패해야 한다.

**원본 비교**

- .NET: `FunctionInvokingChatClient`가 없는 agent에 function middleware를 붙이면 build가 실패한다.
- Python: unsupported category에 bundle이나 middleware를 부분 설치하려 하면 `MiddlewareException`이 난다.

**판단.** 동일하다. 조용한 skip은 보안 기능이 꺼진 채 지나가는 가장 위험한 실패 모드다.
Java도 지원 불가를 빨리 드러내야 한다.

**수용 기준**

- 지원하지 않는 seam에 인터셉터를 등록하면 애플리케이션 시작 또는 builder 단계에서 실패한다.
- 실패는 warning 로그만 남기고 계속 진행하지 않는다.
- 실패 메시지에 어떤 seam이 지원되지 않는지 포함된다.

**근거** [10 오류](../upstream/snapshots/d0a4165f/features/10-middleware.md),
[10 Acceptance scenarios](../upstream/snapshots/d0a4165f/features/10-middleware.md)

---

## INT-006 `next`를 호출하지 않으면 단축 종료한다

**요구사항.** 인터셉터가 `next`를 호출하지 않으면 이후 인터셉터와 실제 handler는 실행되지
않아야 한다.

**원본 비교**

- .NET: function middleware가 `next`를 호출하지 않으면 tool이 실행되지 않고 middleware 반환값이 최종 결과가 된다.
- Python: `call_next()`를 호출하지 않으면 이후 middleware와 handler가 모두 건너뛴다.

**판단.** 동일하다. 단축 종료는 승인, 차단, 캐시, 사전 응답의 핵심 계약이다. Java도 모든 seam에
동일한 short-circuit 규칙을 둔다.

**수용 기준**

- 단축 종료 인터셉터 뒤의 handler 호출 횟수는 0이다.
- 단축 종료한 응답이 최종 결과로 관찰된다.
- 단축 종료 후에도 이미 들어온 상위 인터셉터의 후처리는 역순으로 실행된다.

**근거** [10 목적·경계](../upstream/snapshots/d0a4165f/features/10-middleware.md),
[10 오류](../upstream/snapshots/d0a4165f/features/10-middleware.md),
[10 Acceptance scenarios](../upstream/snapshots/d0a4165f/features/10-middleware.md)

---

## INT-007 후처리에서 결과를 대체할 수 있다

**요구사항.** 인터셉터는 하위 실행이 끝난 뒤 컨텍스트의 결과를 읽고 다른 결과로 교체할 수
있어야 한다.

**원본 비교**

- .NET: function middleware가 tool 결과를 바꾸면 최종 `FunctionResultContent`도 바뀐다.
- Python: post-execution middleware가 `context.result`를 새 값으로 갈아끼울 수 있다.

**판단.** 동일하다. 결과 대체가 없으면 redaction, normalization, synthetic fallback을 한 곳에
모을 수 없다. Java는 컨텍스트 결과를 가변으로 두되 교체 지점을 명시한다.

**수용 기준**

- 하위 handler가 만든 결과를 상위 인터셉터가 교체할 수 있다.
- 교체된 결과가 최종 반환값과 후속 인터셉터 입력으로 사용된다.
- 결과 교체가 원본 결과 객체의 제자리 변형에만 의존하지 않는다.

**근거** [10 상태](../upstream/snapshots/d0a4165f/features/10-middleware.md),
[10 Acceptance scenarios](../upstream/snapshots/d0a4165f/features/10-middleware.md)

---

## INT-008 도구 예외 처리 규칙은 실행 전후를 구분한다

**요구사항.** 도구 인터셉터는 도구 내부 예외를 대체 결과로 바꿀 수 있지만, 실제 도구 호출
전에 발생한 인터셉터 자체 예외는 호출자에게 그대로 전파해야 한다.

**원본 비교**

- .NET: pre-invocation exception은 surface되고 tool 내부 예외는 middleware가 catch해 결과로 바꿀 수 있다.
- Python: 일반 도구 예외는 error content로 바꾸지만 `UserInputRequiredException` 같은 제어 예외는 별도 경로로 유지한다.

**판단.** 두 원본 모두 예외를 구분한다. Java도 인터셉터 버그와 도구 실패를 같은 수준으로
삼키지 않아야 한다. 그래야 운영자가 잘못된 interceptor를 빨리 찾는다.

**수용 기준**

- 인터셉터의 pre-next 예외는 최종 실행 실패로 전파된다.
- 도구 구현 예외는 인터셉터가 잡아 대체 `tool result`로 전환할 수 있다.
- 사용자 입력 요구 같은 제어 예외는 일반 오류 결과로 강등되지 않는다.

**근거** [10 오류](../upstream/snapshots/d0a4165f/features/10-middleware.md)

---

## INT-009 세션 상태 기계는 코어가 소유하고 인터셉터는 관여 지점만 가진다

**요구사항.** 메시지 주입, 승인 바인딩, 호출 단위 persistence 같은 세션 상태 기계는 코어가
소유해야 하며, 인터셉터는 이를 우회 재구성하지 않고 정해진 seam에서만 관찰·확장해야 한다.

**원본 비교**

- .NET: approval binding, message injection, per-service-call persistence가 default stack의 전용 decorator로 배선된다.
- Python: approval, message injection, agent-hooks가 typed middleware와 session state machine을 조합한다.

**판단.** 동일하다. 상태 기계를 interceptor 바깥으로 분산시키면 세션 일관성이 깨진다. Java는
core runtime이 상태 소유권을 유지하고 인터셉터는 연결점만 제공한다.

**수용 기준**

- 메시지 주입 큐나 승인 응답 바인딩을 임의 interceptor가 자체 저장소로 대체할 수 없다.
- 기본 기능은 코어 제공 bundle 또는 built-in interceptor로 조립된다.
- 세션 상태 기계의 저장 위치와 순서는 코어 문서 한 곳에서 정의된다.

**근거** [10 목적·경계](../upstream/snapshots/d0a4165f/features/10-middleware.md),
[10 실행 흐름](../upstream/snapshots/d0a4165f/features/10-middleware.md),
[10 Java typed interceptor 결정과 Spring 통합 경계](../upstream/snapshots/d0a4165f/features/10-middleware.md)

---

## INT-010 다중 seam 기능은 opaque bundle로만 배포한다

**요구사항.** agent, model, tool seam을 함께 다뤄야 하는 기능은 부분 설치를 허용하지 않는
opaque bundle로 배포해야 한다.

**원본 비교**

- .NET: agent-hooks에 정확히 대응하는 공개 bundle 개념은 확인되지 않는다.
- Python: `MiddlewareBundle`이 여러 seam feature를 부분 설치 불가의 opaque 단위로 묶는다.

**판단.** Python 방식을 채택한다. 여러 seam이 함께 움직이는 기능을 개별 interceptor 목록으로
퍼뜨리면 순서와 설치 누락을 보장할 수 없다. Java도 bundle을 1급 개념으로 둔다.

**수용 기준**

- bundle은 전체 seam 집합으로만 등록된다.
- bundle 내부 interceptor 목록을 호출자가 부분 교체하거나 일부만 비활성화할 수 없다.
- 지원하지 않는 seam에 bundle을 설치하면 명시적으로 실패한다.

**근거** [10 API·타입](../upstream/snapshots/d0a4165f/features/10-middleware.md),
[10 실행 흐름](../upstream/snapshots/d0a4165f/features/10-middleware.md),
[10 Acceptance scenarios](../upstream/snapshots/d0a4165f/features/10-middleware.md)

---

## INT-011 도구 집합 변경은 다음 반복에만 반영한다

**요구사항.** 도구 인터셉터가 visible tool set을 추가·제거할 수 있더라도, 그 변화는 현재
배치가 아니라 다음 모델 반복에만 반영되어야 하며 호출자 원본 목록을 직접 바꾸지 않는다.

**원본 비교**

- .NET: inspected 범위에서 동등한 progressive tool mutation 공개 계약은 확인되지 않는다.
- Python: `add_tools/remove_tools`는 다음 iteration에 적용되고 caller의 원본 tool list는 변하지 않는다.

**판단.** Python의 명시적 계약을 채택한다. 현재 배치 중간에 tool set이 바뀌면 모델이 본 schema와
실제 실행기가 어긋난다. 다음 반복 적용이 더 안전하다.

**수용 기준**

- 현재 tool batch 실행 중 추가한 도구는 그 배치에서 바로 호출되지 않는다.
- 다음 모델 반복에서는 추가·제거된 도구 집합이 반영된다.
- 호출자가 넘긴 원본 도구 컬렉션은 실행 후에도 그대로다.

**근거** [10 상태](../upstream/snapshots/d0a4165f/features/10-middleware.md),
[10 확장점](../upstream/snapshots/d0a4165f/features/10-middleware.md),
[10 Acceptance scenarios](../upstream/snapshots/d0a4165f/features/10-middleware.md)

---

## INT-012 스트리밍 재작성은 finalization 경계 안에서만 허용한다

**요구사항.** 스트리밍 인터셉터는 스트림 시작 전에 훅을 등록할 수 있지만, verdict나
finalization 이후에는 콘텐츠를 다시 rewrite하지 못해야 한다.

**원본 비교**

- .NET: streaming middleware와 logging/telemetry가 cancellation과 stream execution을 감싼다.
- Python: `ResponseStream`이 hook 등록과 sealing 규칙을 제공하고 gated stream은 verdict 후 rewrite를 막는다.

**판단.** Python의 sealing 규칙을 필수로 채택한다. 스트림이 확정된 뒤 다시 고치면 감사 로그와
사용자 응답이 갈라진다. Java는 스트리밍 경계를 인터셉터 계약에 포함해야 한다.

**수용 기준**

- 스트리밍 실행 중 인터셉터는 transform/result/cleanup hook을 등록할 수 있다.
- finalization 이후 새 rewrite hook 등록은 실패하거나 무시된다.
- verdict 이후 콘텐츠를 다시 바꾸는 회귀 테스트가 실패한다.

**근거** [10 동시성·스트리밍·취소](../upstream/snapshots/d0a4165f/features/10-middleware.md),
[10 보안](../upstream/snapshots/d0a4165f/features/10-middleware.md),
[10 Acceptance scenarios](../upstream/snapshots/d0a4165f/features/10-middleware.md)

---

## INT-013 취소는 모든 인터셉터와 컴팩션으로 전파한다

**요구사항.** 취소 신호는 agent, model, tool, session 인터셉터와 compaction 전략까지
끊기지 않고 전달되어야 하며, cancellation 예외를 성공으로 바꾸지 않는다.

**원본 비교**

- .NET: middleware와 compaction surface 전반이 `CancellationToken`을 받고 summarization cancel을 전파한다.
- Python: middleware 서명에 explicit token은 없지만 async cancellation과 stream cleanup semantics에 의존한다.

**판단.** Java는 .NET식 명시적 취소를 택한다. Java 표준에는 Python식 암묵 취소가 약하므로
명시 인자가 더 안전하다. compaction까지 같은 규칙을 써야 중간 요약이 남지 않는다.

**수용 기준**

- 네 seam 인터셉터 API와 compaction API가 모두 취소 인자를 받는다.
- 취소된 summarization compaction은 요약을 확정하지 않는다.
- 취소된 실행은 성공 응답이나 성공 metric으로 기록되지 않는다.

**근거** [10 동시성·스트리밍·취소](../upstream/snapshots/d0a4165f/features/10-middleware.md),
[11 동시성·스트리밍·취소](../upstream/snapshots/d0a4165f/features/11-compaction.md)

---

## INT-014 컴팩션은 도구 호출과 도구 결과의 원자성을 보존한다

**요구사항.** compaction은 도구 호출 선언과 해당 도구 결과를 하나의 불가분 그룹으로 다뤄야
하며, 비인접 결과라도 call id가 모호하지 않으면 같은 그룹으로 묶어야 한다.

**원본 비교**

- .NET: `ToolCall` group이 assistant tool call과 following tool results의 atomicity를 보장한다.
- Python: annotation grouping이 non-adjacent unambiguous result까지 declaration group에 다시 연결한다.

**판단.** 두 원본의 공통 핵심은 원자성이다. 사용자 지시대로 이 규칙을 수용 기준으로 고정한다.
부분 삭제는 단순 품질 문제가 아니라 API correctness 문제다.

**수용 기준**

- tool call만 남기고 해당 tool result를 제거하는 compaction 결과가 나오지 않는다.
- 비인접 tool result라도 call id가 유일하면 같은 compaction group으로 묶인다.
- 중복 call id로 연결이 모호하면 강제로 잘못 묶지 않는다.

**근거** [11 목적·경계](../upstream/snapshots/d0a4165f/features/11-compaction.md),
[11 Grouping / annotation / indexing](../upstream/snapshots/d0a4165f/features/11-compaction.md),
[11 Acceptance scenarios](../upstream/snapshots/d0a4165f/features/11-compaction.md)

---

## INT-015 컴팩션은 증분 업데이트에서도 식별자와 계수를 안정적으로 유지한다

**요구사항.** 기존 대화 앞부분이 그대로일 때 compaction 재실행은 새 tail만 증분 갱신해야 하며,
이미 계산된 group 식별자와 token 계수를 불필요하게 다시 쓰지 않아야 한다.

**원본 비교**

- .NET: `CompactionMessageIndex.Update(...)`가 suffix append 또는 전체 rebuild를 선택한다.
- Python: incremental annotation이 prefix token count와 IDs를 보존한다.

**판단.** 동일하다. 전체 rebuild만 있으면 긴 세션에서 비용이 커지고 traceability가 깨진다.
Java도 증분 경로를 기본 지원하되 prefix 불일치 시 전체 재구축으로 되돌아가야 한다.

**수용 기준**

- prefix가 그대로인 재실행에서 기존 group ID가 바뀌지 않는다.
- 새 메시지 tail만 추가된 경우 기존 token count가 그대로 유지된다.
- prefix가 잘렸거나 달라지면 전체 rebuild가 발생한다.

**근거** [11 Grouping / annotation / indexing](../upstream/snapshots/d0a4165f/features/11-compaction.md),
[11 Acceptance scenarios](../upstream/snapshots/d0a4165f/features/11-compaction.md)

---

## INT-016 컴팩션 내부 상태는 명시적 index와 trace metadata로 표현한다

**요구사항.** Java compaction의 canonical internal state는 명시적 group index여야 하며,
summary 추적 정보는 메시지 metadata에도 남겨 원본과 요약의 관계를 추적할 수 있어야 한다.

**원본 비교**

- .NET: `CompactionMessageIndex`와 `CompactionMessageGroup`이 canonical state이고 summary marker만 message에 남긴다.
- Python: 메시지 annotation이 group, token, summary linkage를 폭넓게 담는다.

**판단.** 두 원본을 절충한다. index는 계산과 메트릭에 유리하고, message trace metadata는 감사와
복구에 유리하다. Java는 index를 내부 기준으로 삼되 trace metadata도 남긴다.

**수용 기준**

- compaction 엔진은 flat message list만으로 상태를 관리하지 않고 명시적 group index를 사용한다.
- summary message에는 자신이 요약한 원본 group 또는 message 식별자가 기록된다.
- 원본 메시지에서도 어떤 summary가 자신을 대체했는지 역참조할 수 있다.

**근거** [11 상태와 스냅샷 모델](../upstream/snapshots/d0a4165f/features/11-compaction.md),
[11 Grouping / annotation / indexing](../upstream/snapshots/d0a4165f/features/11-compaction.md),
[11 Java 결정](../upstream/snapshots/d0a4165f/features/11-compaction.md)

---

## INT-017 컴팩션 시작 조건과 종료 조건을 분리한다

**요구사항.** compaction 전략은 시작 predicate와 종료 target을 분리해서 표현해야 하며,
종료 target을 주지 않으면 trigger의 inverse를 기본으로 사용해야 한다.

**원본 비교**

- .NET: `CompactionStrategy`가 `trigger`와 optional `target`을 분리한다.
- Python: threshold semantics가 각 전략 생성자에 내장돼 있고 explicit trigger algebra는 없다.

**판단.** .NET 모델을 채택한다. 시작과 멈춤을 분리하면 조합과 테스트가 단순해진다. Java는
trigger/target을 공용 추상화로 두고 Python식 편의 생성자는 그 위에 얹는 편이 낫다.

**수용 기준**

- compaction 전략 생성 시 trigger를 필수로 받는다.
- target을 생략하면 기본 target이 trigger의 inverse로 설정된다.
- 토큰, 메시지, turn, group, tool-call 존재 여부를 기준으로 trigger를 조합할 수 있다.

**근거** [11 Token estimation / metrics / triggers](../upstream/snapshots/d0a4165f/features/11-compaction.md),
[11 Java 결정](../upstream/snapshots/d0a4165f/features/11-compaction.md)

---

## INT-018 group 기반과 turn 기반 sliding 정책을 분리한다

**요구사항.** sliding compaction은 group 기반 정책과 turn 기반 정책을 별도 전략으로 제공해야
하며, 하나의 이름 아래에 서로 다른 semantics를 숨기지 않는다.

**원본 비교**

- .NET: `SlidingWindowCompactionStrategy`가 최근 turn 기준이다.
- Python: `SlidingWindowStrategy`가 최근 non-system group 기준이다.

**판단.** 같은 이름인데 동작이 다른 것이 가장 위험하다. Java는 둘 중 하나를 임의 선택하지 않고
전략 이름부터 분리한다. 그래야 테스트와 사용자가 의미를 오해하지 않는다.

**수용 기준**

- group 기반 sliding과 turn 기반 sliding이 서로 다른 타입 또는 명시적 모드로 구분된다.
- 같은 입력에서 두 전략의 결과가 다를 수 있음을 테스트가 보여 준다.
- 문서가 기본 sliding semantics를 애매하게 설명하지 않는다.

**근거** [11 Sliding window](../upstream/snapshots/d0a4165f/features/11-compaction.md),
[11 문서 차이와 SDK 간 차이](../upstream/snapshots/d0a4165f/features/11-compaction.md),
[11 Java 결정](../upstream/snapshots/d0a4165f/features/11-compaction.md)

---

## INT-019 tool-result compaction은 요약으로 대체하되 원문을 다시 노출하지 않는다

**요구사항.** 오래된 tool-result group을 summary message로 대체할 수는 있지만, summary는
길이 제한이 있는 synthetic content여야 하며 이미 제외된 원문 payload를 다시 verbatim으로 복원하면 안 된다.

**원본 비교**

- .NET: 오래된 tool-call group을 YAML-like summary block으로 바꾸고 replacement summary group을 삽입한다.
- Python: 오래된 tool-call group을 한 줄 bracket summary로 바꾸고 큰 payload를 bounded string으로 자른다.

**판단.** 두 원본 모두 “요약으로 대체”라는 원칙은 같다. Java는 형식보다 안전성을 우선한다.
원문을 다시 통째로 넣으면 compaction 이득도 없고 민감정보 노출도 늘어난다.

**수용 기준**

- tool-result compaction 뒤에는 원래 group 대신 synthetic summary message가 들어간다.
- 큰 tool result는 요약에 전부 들어가지 않고 길이 제한 또는 truncation marker를 가진다.
- 이미 excluded된 원문 payload가 summary 생성 과정에서 다시 포함되지 않는다.

**근거** [11 Tool-result compaction](../upstream/snapshots/d0a4165f/features/11-compaction.md),
[11 Acceptance scenarios](../upstream/snapshots/d0a4165f/features/11-compaction.md)

---

## INT-020 summarization compaction은 실패 시 원본을 복구한다

**요구사항.** summarization compaction은 요약 생성 실패 시 원본 group exclusion을 모두 되돌려야
하며, 취소는 삼키지 말고 전파해야 한다.

**원본 비교**

- .NET: summarizer 예외 시 excluded groups를 복구하고 cancellation은 전파한다.
- Python: summarizer 예외나 empty summary면 `False`를 돌리고 originals를 유지한다.

**판단.** 두 원본의 공통 핵심은 “실패해도 원본 보존”이다. 사용자 지시대로 취소와 실패 모두에서
원본 복구를 고정한다. empty summary도 성공으로 취급하지 않는다.

**수용 기준**

- summarizer가 예외를 던지면 compaction 전 included/excluded 상태가 그대로 복구된다.
- 빈 문자열 또는 공백 요약은 성공으로 확정되지 않는다.
- 취소 예외는 summary fallback으로 바뀌지 않고 호출자에게 전파된다.

**근거** [11 Summarization](../upstream/snapshots/d0a4165f/features/11-compaction.md),
[11 동시성·스트리밍·취소](../upstream/snapshots/d0a4165f/features/11-compaction.md),
[11 오류·검증·복구 동작](../upstream/snapshots/d0a4165f/features/11-compaction.md),
[11 Acceptance scenarios](../upstream/snapshots/d0a4165f/features/11-compaction.md)

---

## INT-021 compaction provider는 실행 전 projection과 선택적 저장 후 훅을 제공한다

**요구사항.** compaction provider는 최소한 실행 전 projection 훅을 제공해야 하고, 저장소가
원본 excluded message를 보존해야 하는 경로를 위해 선택적 저장 후 compaction 훅을 둘 수 있어야 한다.

**원본 비교**

- .NET: `CompactionProvider`는 pre-invocation provider이며 remote-managed session이면 skip한다.
- Python: `CompactionProvider`가 `before_run`과 `after_run` 둘 다 제공하고 after path에서 excluded originals를 storage에 남긴다.

**판단.** 두 모델을 합친다. 실행 전 projection은 공통으로 필요하다. 저장 후 훅은 모든 경로에
필수는 아니므로 선택적으로 둔다. 다만 원본 보존 경로가 필요하면 코어가 지원해야 한다.

**수용 기준**

- 실행 전 provider가 compacted projection만 다음 단계에 전달할 수 있다.
- 저장 후 훅을 켜면 excluded originals를 storage에 남기고 다음 load 정책이 이를 선택적으로 숨길 수 있다.
- 세션이나 메시지가 없으면 compaction provider는 부작용 없이 passthrough한다.

**근거** [11 Session / context integration](../upstream/snapshots/d0a4165f/features/11-compaction.md),
[11 상태·영속화](../upstream/snapshots/d0a4165f/features/11-compaction.md),
[11 Java 결정](../upstream/snapshots/d0a4165f/features/11-compaction.md)

---

## INT-022 로깅과 텔레메트리 인터셉터는 민감 데이터 노출을 opt-in으로 둔다

**요구사항.** built-in logging과 telemetry 인터셉터는 기본적으로 민감한 메시지, 도구 인자,
도구 결과를 내보내지 않아야 하며, 전체 payload 노출은 명시적 opt-in일 때만 허용해야 한다.

**원본 비교**

- .NET: `LoggingAgent` trace 로그와 `OpenTelemetryAgent.EnableSensitiveData`가 민감 데이터 노출 위험을 직접 경고한다.
- Python: middleware 샘플이 judge client 전송과 gated stream rewrite에 대한 데이터 유출·간접 주입 위험을 경고한다.

**판단.** 동일한 안전 원칙을 문서화한다. 관찰성은 중요하지만 기본값은 안전해야 한다. 민감정보
노출은 명시적 opt-in 없이는 켜지지 않아야 한다.

**수용 기준**

- 기본 logging/telemetry interceptor 설정으로는 raw message 내용과 tool arguments가 외부 sink에 기록되지 않는다.
- 민감 데이터 기록 옵션은 기본값이 `false`다.
- 민감 데이터 옵션을 켜면 문서와 runtime 경고가 함께 제공된다.

**근거** [10 보안](../upstream/snapshots/d0a4165f/features/10-middleware.md),
[11 보안](../upstream/snapshots/d0a4165f/features/11-compaction.md)

---

## 이 문서가 다루지 않는 것

| 주제 | 소유 문서 |
| --- | --- |
| 세션 식별자, 스냅샷, 저장소 | [06 세션과 대화 상태](06-sessions.md) |
| 도구 승인 payload와 함수 호출 루프 | [04 도구 정의와 실행 루프](04-tools.md) |
| 하네스가 기본 interceptor와 compaction provider를 조립하는 방법 | [08 하네스 기능](08-harness.md) |
| 워크플로 그래프의 재시도와 장기 실행 정책 | [09 워크플로와 오케스트레이션](09-workflows.md) |
| 호스트별 인증, 사용자 격리, 전송 프로토콜 | [10 호스팅과 프로토콜](10-hosting.md) |
