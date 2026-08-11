# 06 세션과 대화 상태

**접두사** `SES` · **원본 기능** [08 sessions](../upstream/snapshots/d0a4165f/features/08-sessions.md),
[09 history-context-memory](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md)

세션 식별, 스냅샷, 저장소, 실행별 컨텍스트, 이력과 메모리 연동의 계약을 정의한다.
인터셉터 순서와 컴팩션은 [07 인터셉터와 컨텍스트 관리](07-interceptors.md), 도구 실행
루프는 [04 도구 정의와 실행 루프](04-tools.md), 호스팅 경계는
[10 호스팅과 프로토콜](10-hosting.md)이 소유한다.

## 요약

| ID | 요구사항 | 등급 | 단계 |
| --- | --- | --- | --- |
| SES-001 | 공개 세션은 로컬 ID와 서비스 핸들을 분리한다 | 필수 | MVP |
| SES-002 | 서비스 대화 식별자는 권한 경계가 아니다 | 필수 | MVP |
| SES-003 | 세션이 영속 대화 상태의 단일 기준이다 | 필수 | MVP |
| SES-004 | 내구성 스냅샷은 타입·버전 봉투를 강제한다 | 필수 | Core+ |
| SES-005 | 세션 상태는 안정적인 타입 레지스트리로 직렬화한다 | 필수 | Core+ |
| SES-006 | 내구성 저장 전 상태 값을 엄격히 검증한다 | 필수 | Core+ |
| SES-007 | 인메모리 저장소는 분기 안전 복사본을 돌려준다 | 필수 | Core+ |
| SES-008 | 파일 저장소는 원자적으로 교체하고 마지막 기록이 이긴다 | 권장 | Core+ |
| SES-009 | 파일 기반 세션 저장소는 경로 이탈을 막는다 | 필수 | Core+ |
| SES-010 | 파싱 손상과 스키마 불일치를 구분해 복구한다 | 필수 | Core+ |
| SES-011 | 실행마다 독립적인 `SessionContext`를 만든다 | 필수 | MVP |
| SES-012 | 컨텍스트 제공자는 세션 네임스페이스 안에서 상태를 관리한다 | 필수 | Core+ |
| SES-013 | 이력 제공자는 선택적 적재·저장 플래그를 노출한다 | 필수 | Core+ |
| SES-014 | 기본 인메모리 이력 주입 조건을 엄격히 제한한다 | 권장 | Core+ |
| SES-015 | 서비스 호출 단위 이력 저장은 명시적 옵션이다 | 권장 | Core+ |
| SES-016 | 로컬 이력 모드는 기존 서비스 대화와 혼용하지 않는다 | 필수 | Core+ |
| SES-017 | 메시지 주입 큐는 세션에 붙고 대화 연속성을 유지한다 | 필수 | Core+ |
| SES-018 | 파일 이력은 세션 스냅샷과 분리된 채널에 저장한다 | 권장 | Optional |
| SES-019 | 외부 메모리는 명시적 범위와 비신뢰 문맥으로 연동한다 | 권장 | Optional |
| SES-020 | 호스팅 격리는 별도 사용자 컨텍스트가 맡는다 | 필수 | Hosting |

---

## SES-001 공개 세션은 로컬 ID와 서비스 핸들을 분리한다

**요구사항.** 공개 세션 타입은 프레임워크가 소유하는 `sessionId`와 공급자가 돌려주는
불투명한 `serviceSessionId`를 별도 필드로 가져야 한다.

**원본 비교**

- .NET: `AgentSession`과 `ChatClientAgentSession.ConversationId`로 로컬 상태와 서비스 연속 핸들을 나눈다.
- Python: `AgentSession`이 `session_id`, `service_session_id`, `state`를 직접 노출한다.

**판단.** 두 원본의 의도는 같다. Java도 Python처럼 분리된 공개 모델을 택한다. 그래야 로컬
저장소 키와 공급자 이어받기 핸들의 수명주기를 섞지 않는다.

**수용 기준**

- 세션 객체는 비어 있지 않은 `sessionId`를 항상 노출한다.
- `serviceSessionId`는 없어도 되지만, 있으면 `sessionId`와 다른 필드에 저장된다.
- 세션 직렬화 결과에 두 값이 따로 기록된다.

**근거** [08 상태/스냅샷](../upstream/snapshots/d0a4165f/features/08-sessions.md),
[08 Java 설계 결정](../upstream/snapshots/d0a4165f/features/08-sessions.md)

---

## SES-002 서비스 대화 식별자는 권한 경계가 아니다

**요구사항.** `serviceSessionId`는 이어받기용 내부 메타데이터일 뿐이며, 사용자 격리나
권한 검증 기준으로 사용하지 않아야 한다.

**원본 비교**

- .NET: 호스팅 경계는 `HostedSessionContext.UserId`와 사용자 파티션 저장소가 맡고 `ConversationId`는 아니다.
- Python: `service_session_id`는 trusted application state이지만 authorization boundary가 아니라고 명시한다.

**판단.** 동일하다. 이 값을 권한 경계로 쓰면 공급자 형식 변화가 보안 규칙이 된다. 더 안전한
기본값은 별도 호스트 신원 컨텍스트를 두는 것이다.

**수용 기준**

- 코어 세션 API에 `serviceSessionId` 기반 접근 제어 로직이 없다.
- 같은 `serviceSessionId`라도 호스트 사용자 컨텍스트가 다르면 같은 세션으로 취급되지 않는다.
- 문서와 API 이름이 `serviceSessionId`를 권한 토큰처럼 설명하지 않는다.

**근거** [08 오류·검증·보안](../upstream/snapshots/d0a4165f/features/08-sessions.md),
[09 경계](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md)

---

## SES-003 세션이 영속 대화 상태의 단일 기준이다

**요구사항.** 영속 대화 상태는 세션 또는 세션이 명시적으로 참조하는 외부 저장소에만 두어야
하며, 제공자 인스턴스 필드나 전역 캐시에 세션별 상태를 숨겨 두지 않는다.

**원본 비교**

- .NET: provider-local state는 `StateBag`와 `ProviderSessionState<TState>`에 둔다.
- Python: provider state는 `AgentSession.state[source_id]` 또는 외부 backend에 두고 provider 인스턴스에는 두지 않는다.

**판단.** 동일하다. 세션이 단일 기준이어야 재시작과 재개가 가능하다. 인스턴스 필드에 상태를
두면 테스트는 통과해도 프로세스 경계에서 상태가 증발한다.

**수용 기준**

- 세션별 provider 상태를 저장하는 공개 확장점은 세션 state 네임스페이스 또는 외부 저장소 참조뿐이다.
- 같은 provider 인스턴스를 두 세션에 재사용해도 세션별 상태가 섞이지 않는다.
- 프로세스 재시작 후 세션을 복원하면 이전 turn의 provider 상태를 다시 읽을 수 있다.

**근거** [08 목적·경계](../upstream/snapshots/d0a4165f/features/08-sessions.md),
[09 상태/목적](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md),
[09 상태·영속화](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md)

---

## SES-004 내구성 스냅샷은 타입·버전 봉투를 강제한다

**요구사항.** 내구성 세션 스냅샷은 최소한 `type`, `version`, `sessionId`,
`serviceSessionId`, `state`를 담는 봉투 형식이어야 한다.

**원본 비교**

- .NET: inspected core session path는 plain JSON object round-trip이며 명시적 version 봉투가 확인되지 않는다.
- Python: `_SessionSnapshot`이 `type="session"`과 `version="1.0"`을 포함한 고정 봉투를 사용한다.

**판단.** Python 방식을 택한다. 버전이 없으면 스키마 이행과 오류 분류가 불가능하다. Java는
장기 호환을 위해 plain object보다 봉투를 기본값으로 둔다.

**수용 기준**

- 직렬화된 스냅샷 최상위 객체에 `type`과 `version` 필드가 있다.
- 역직렬화는 `type`과 `version`을 먼저 검사한다.
- JSON과 선택적 이진 코덱이 같은 논리 봉투를 공유한다.

**근거** [08 상태/스냅샷](../upstream/snapshots/d0a4165f/features/08-sessions.md),
[08 Java 설계 결정](../upstream/snapshots/d0a4165f/features/08-sessions.md)

---

## SES-005 세션 상태는 안정적인 타입 레지스트리로 직렬화한다

**요구사항.** 커스텀 세션 상태는 안정적인 타입 ID와 codec 쌍을 등록하는 레지스트리로
직렬화해야 하며, 프레임워크 소유 기본 타입은 미리 등록해야 한다.

**원본 비교**

- .NET: `StateBag`는 typed access를 주지만 inspected core path에 Python식 공개 type-id registry는 확인되지 않는다.
- Python: `register_state_type()`가 stable type id, codec pair, framework 기본 타입 선등록을 강제한다.

**판단.** Python 방식을 택한다. Java 기본 JSON 매퍼만으로는 cold-start 복원 시 타입을
안전하게 되살리기 어렵다. 명시적 레지스트리가 더 예측 가능하다.

**수용 기준**

- 같은 타입을 두 번 다른 type id로 등록할 수 없다.
- 같은 type id를 다른 타입에 재사용할 수 없다.
- 프레임워크 기본 메시지 타입은 추가 등록 없이 직렬화·복원된다.

**근거** [08 공개 API·타입](../upstream/snapshots/d0a4165f/features/08-sessions.md),
[08 확장점](../upstream/snapshots/d0a4165f/features/08-sessions.md),
[08 Java 설계 결정](../upstream/snapshots/d0a4165f/features/08-sessions.md)

---

## SES-006 내구성 저장 전 상태 값을 엄격히 검증한다

**요구사항.** 내구성 저장소는 등록되지 않은 런타임 객체, 잘못된 codec 결과, 복원 불가능한
값을 저장 전에 거부해야 한다.

**원본 비교**

- .NET: 민감한 세션 복원을 경고하고 session type gate는 두지만, Python 수준의 durable-state validator는 이 범위에서 확인되지 않는다.
- Python: duplicate type id, 잘못된 encoder output, unsupported object, non-finite float를 fail-fast로 막는다.

**판단.** Python의 엄격함을 채택한다. 저장 시점 검증이 느슨하면 손상된 스냅샷이 뒤늦게
배포 환경에서만 폭발한다. 더 안전한 기본값은 저장 전 거부다.

**수용 기준**

- 등록되지 않은 커스텀 객체가 state에 있으면 저장이 실패한다.
- encoder가 선언한 type id와 다른 값을 내면 저장이 실패한다.
- 실패한 저장은 기존 스냅샷 파일을 바꾸지 않는다.

**근거** [08 오류·검증·보안](../upstream/snapshots/d0a4165f/features/08-sessions.md),
[08 acceptance scenarios](../upstream/snapshots/d0a4165f/features/08-sessions.md)

---

## SES-007 인메모리 저장소는 분기 안전 복사본을 돌려준다

**요구사항.** 인메모리 세션 저장소는 저장 시점과 조회 시점 모두 복사본을 사용해야 하며,
live reference를 그대로 돌려주지 않는다.

**원본 비교**

- .NET: inspected 범위에서 동등한 공개 인메모리 session store 계약은 확인되지 않는다.
- Python: `SessionStore.set()`과 `get()`이 모두 deepcopy를 써서 branch-like continuation을 격리한다.

**판단.** Python 방식을 채택한다. Java에서도 조회 결과를 그대로 수정하면 저장본이 오염되는
버그가 가장 흔하다. 세션 분기 재실행을 안전하게 하려면 복사 의미론이 기본이어야 한다.

**수용 기준**

- 저장한 세션 객체를 호출자가 수정해도 저장소 내부 값은 바뀌지 않는다.
- `get()` 결과를 수정한 뒤 다시 `get()` 하면 원본 저장 상태가 유지된다.
- 존재하지 않는 key 조회는 빈 복사본이 아니라 `null` 또는 명시적 부재를 돌려준다.

**근거** [08 상세 실행 흐름](../upstream/snapshots/d0a4165f/features/08-sessions.md),
[08 동시성·취소](../upstream/snapshots/d0a4165f/features/08-sessions.md),
[08 acceptance scenarios](../upstream/snapshots/d0a4165f/features/08-sessions.md)

---

## SES-008 파일 저장소는 원자적으로 교체하고 마지막 기록이 이긴다

**요구사항.** 파일 기반 세션 저장소는 임시 파일에 먼저 쓴 뒤 원자적 교체로 반영해야 하며,
동시 기록 의미론은 last-writer-wins로 고정한다.

**원본 비교**

- .NET: temp file 후 overwrite rename으로 torn write를 막고 last-writer-wins를 문서와 테스트로 고정한다.
- Python: `FileSessionStore`도 temp file과 replace를 사용하고 cross-process coordination은 하지 않는다.

**판단.** 동일하다. 분산 잠금까지 코어가 책임질 필요는 없다. 그러나 부분 파일 노출은 막아야
한다. Java도 단순하고 검증 가능한 원자 교체를 기본값으로 둔다.

**수용 기준**

- 저장 도중 프로세스가 중단돼도 절반만 써진 스냅샷 파일이 최종 경로에 남지 않는다.
- 같은 세션에 대한 두 기록이 경쟁하면 마지막으로 성공한 기록이 최종 상태가 된다.
- 저장 구현이 기존 파일을 제자리에서 덮어쓰지 않는다.

**근거** [08 상세 실행 흐름](../upstream/snapshots/d0a4165f/features/08-sessions.md),
[08 동시성·취소](../upstream/snapshots/d0a4165f/features/08-sessions.md)

---

## SES-009 파일 기반 세션 저장소는 경로 이탈을 막는다

**요구사항.** 파일 기반 세션 저장소는 세션 ID를 안전한 파일명으로 인코딩하고, 최종 경로가
저장소 루트 밖으로 나가면 거부해야 한다.

**원본 비교**

- .NET: hosted file store가 agent/user/conversation 파티션 경로를 만들고 traversal rejection을 테스트한다.
- Python: safe filename stem과 resolved-path containment 검사, symlink escape rejection을 구현한다.

**판단.** 동일하다. 세션 ID는 외부 입력일 수 있으므로 파일명으로 그대로 쓰면 안 된다. 루트
containment 검사는 Java 파일 저장소의 필수 보안 규칙이다.

**수용 기준**

- `../` 같은 세션 ID로 저장해도 루트 밖 파일이 생성되지 않는다.
- symlink를 이용해 루트 밖으로 빠지려 하면 저장과 조회가 실패한다.
- 안전하지 않은 세션 ID는 인코딩된 파일명으로 저장된다.

**근거** [08 오류·검증·보안](../upstream/snapshots/d0a4165f/features/08-sessions.md),
[08 acceptance scenarios](../upstream/snapshots/d0a4165f/features/08-sessions.md)

---

## SES-010 파싱 손상과 스키마 불일치를 구분해 복구한다

**요구사항.** 스냅샷 바이트를 파싱할 수 없을 때만 quarantine를 수행하고, version mismatch,
state schema mismatch, decoder failure에서는 원본 파일을 보존한 채 실패해야 한다.

**원본 비교**

- .NET: core session path의 동등한 quarantine 정책은 확인되지 않는다.
- Python: decode 불가 bytes만 quarantine하고 version/schema/decoder 문제는 원본을 남긴다.

**판단.** Python 정책을 채택한다. 파싱 손상과 호환성 오류는 운영 대응이 다르다. 둘을 같은
손상으로 취급하면 복구 가능한 스냅샷까지 잃는다.

**수용 기준**

- 잘못된 JSON 또는 이진 파싱 실패 시 원본 파일은 quarantine 위치로 이동한다.
- 지원하지 않는 `version`으로 읽기 실패하면 원본 파일은 그대로 남는다.
- decoder 예외로 읽기 실패하면 원본 파일은 그대로 남는다.

**근거** [08 상세 실행 흐름](../upstream/snapshots/d0a4165f/features/08-sessions.md),
[08 오류·검증·보안](../upstream/snapshots/d0a4165f/features/08-sessions.md),
[08 Java 설계 결정](../upstream/snapshots/d0a4165f/features/08-sessions.md)

---

## SES-011 실행마다 독립적인 `SessionContext`를 만든다

**요구사항.** 각 실행은 세션 ID, 서비스 핸들, 입력 메시지, 문맥 메시지, 메타데이터, 응답
슬롯을 가진 독립적인 `SessionContext`를 새로 만들어 사용해야 한다.

**원본 비교**

- .NET: 동일 문제를 `ChatHistoryProvider`, `AIContextProvider`, `MessageInjectingChatClient` 등 분리된 객체로 푼다.
- Python: `SessionContext`가 run마다 새로 생성되고 messages, middleware, metadata, response를 한곳에 모은다.

**판단.** Java는 Python식 단일 실행 컨텍스트를 기본 모델로 택한다. Java 타입 시스템에서
명시적 컨텍스트가 있어야 인터셉터와 provider가 같은 실행 단위를 공유한다.

**수용 기준**

- 두 번의 실행은 서로 다른 `SessionContext` 인스턴스를 사용한다.
- `SessionContext`는 실행 종료 후 최종 응답 객체를 담는다.
- 컨텍스트 생성 없이 provider 훅이나 인터셉터가 세션 정보를 읽는 경로가 없다.

**근거** [09 상태/목적](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md),
[09 API·타입](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md),
[09 Java 결정](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md)

---

## SES-012 컨텍스트 제공자는 세션 네임스페이스 안에서 상태를 관리한다

**요구사항.** `ContextProvider`는 `beforeRun`과 `afterRun` 훅으로만 실행에 참여하고,
provider별 영속 상태는 세션 state의 고정 네임스페이스 키 아래에만 저장해야 한다.

**원본 비교**

- .NET: `AIContextProvider`와 `ProviderSessionState<TState>` 조합으로 provider 상태를 session state에 둔다.
- Python: `ContextProvider`가 `AgentSession.state[source_id]`에 provider-scoped state를 저장한다.

**판단.** 동일하다. provider가 자기 필드에 세션 상태를 잡으면 세션 재개와 병렬 실행이
깨진다. 네임스페이스 키를 고정하면 저장소와 테스트가 상태 소유권을 쉽게 판별한다.

**수용 기준**

- provider 구현은 세션별 영속 상태를 세션 state 네임스페이스 아래에만 저장한다.
- `beforeRun`과 `afterRun`만으로 같은 provider 기능을 구현할 수 있다.
- 서로 다른 provider source key가 같은 state slot을 공유하지 않는다.

**근거** [09 상태/목적](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md),
[09 확장점](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md),
[09 상태·영속화](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md)

---

## SES-013 이력 제공자는 선택적 적재·저장 플래그를 노출한다

**요구사항.** `HistoryProvider`는 기본 이력 인터페이스 하나로 `loadMessages`,
`storeInputs`, `storeContextMessages`, `storeContextFrom`, `storeOutputs` 같은 선택적
정책 플래그를 노출해야 한다.

**원본 비교**

- .NET: `ChatHistoryProvider`가 load/store 지점을 분리하되 Python식 공개 플래그 세트는 없다.
- Python: `HistoryProvider`가 하나의 계약으로 primary history, audit sink, evaluation sink를 모두 표현한다.

**판단.** Python 방식을 택한다. Java에서도 같은 storage adapter를 primary history와 audit
sink에 재사용하는 편이 단순하다. 플래그가 없으면 비슷한 provider 타입이 계속 늘어난다.

**수용 기준**

- 하나의 `HistoryProvider` 구현이 입력만 저장하거나 출력만 저장하도록 설정될 수 있다.
- `storeContextFrom`이 지정되면 해당 source의 context message만 저장된다.
- 플래그 설정은 provider 서브타입 추가 없이 생성자 또는 빌더에서 끝난다.

**근거** [09 상태/목적](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md),
[09 API·타입](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md),
[09 Java 결정](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md)

---

## SES-014 기본 인메모리 이력 주입 조건을 엄격히 제한한다

**요구사항.** 기본 인메모리 이력 제공자는 세션이 있고, load-enabled history provider가
없고, 서비스 저장 대화가 없을 때만 자동 주입해야 한다.

**원본 비교**

- .NET: 서비스 저장 경로와 로컬 history provider 경로를 분리한다.
- Python: 실제 production code가 위 조건에서만 `InMemoryHistoryProvider`를 자동 추가한다.

**판단.** Python code와 테스트를 따른다. 자동 주입 조건이 넓으면 서비스 저장 이력과 로컬
이력이 중복된다. 코드가 문서를 이긴다는 원칙에 따라 좁은 조건을 고정한다.

**수용 기준**

- load-enabled history provider가 이미 있으면 기본 인메모리 이력을 추가하지 않는다.
- 서비스가 이력을 저장하는 세션이면 기본 인메모리 이력을 추가하지 않는다.
- 위 두 조건이 모두 거짓이고 세션이 있으면 기본 인메모리 이력이 추가된다.

**근거** [09 실행 흐름](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md),
[09 문서 차이](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md)

---

## SES-015 서비스 호출 단위 이력 저장은 명시적 옵션이다

**요구사항.** 도구 루프 중간 호출까지 이력을 저장하는 동작은 기본값이 아니어야 하며,
명시적으로 켠 경우에만 서비스 호출 단위 persistence를 수행해야 한다.

**원본 비교**

- .NET: `RequirePerServiceCallChatHistoryPersistence` 옵션과 전용 decorator로 opt-in한다.
- Python: `require_per_service_call_history_persistence=True`일 때만 middleware가 per-call persistence를 소유한다.

**판단.** 동일하다. 기본값을 end-of-run atomicity로 두는 편이 단순하다. 중간 저장은 비싸고
경계가 복잡하므로 명시적 opt-in이 맞다.

**수용 기준**

- 옵션이 꺼져 있으면 run 종료 시점에만 history provider가 저장된다.
- 옵션이 켜져 있으면 각 서비스 호출 뒤 history provider 또는 서비스 대화 핸들이 갱신된다.
- 옵션 on/off가 같은 세션 API를 공유해도 동작 차이는 문서화된 한 가지뿐이다.

**근거** [09 chat history persistence consistency](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md),
[09 Java 결정](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md)

---

## SES-016 로컬 이력 모드는 기존 서비스 대화와 혼용하지 않는다

**요구사항.** 로컬 이력 기반의 서비스 호출 단위 persistence가 켜진 실행에 이미 실제 서비스
대화 식별자가 있으면, 실행 전에 명시적으로 실패해야 한다.

**원본 비교**

- .NET: local history provider와 real `ConversationId`가 동시에 있으면 conflict로 실패한다.
- Python: local-history mode에서 existing service-managed conversation이 있으면 즉시 에러다.

**판단.** 동일하다. 두 경로를 섞으면 어느 이력이 진실인지 모호해진다. 더 안전한 기본값은
조용한 혼용이 아니라 빠른 실패다.

**수용 기준**

- 로컬 per-call persistence 모드에서 실제 서비스 대화 ID가 입력되면 모델 호출 전에 실패한다.
- 실패 시 로컬 history provider와 서비스 모두에 새 이력이 저장되지 않는다.
- 서비스가 history를 저장하는 모드에서는 위 검사가 적용되지 않는다.

**근거** [09 Python: per-service-call history consistency](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md),
[09 오류·보안](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md),
[09 acceptance scenarios](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md)

---

## SES-017 메시지 주입 큐는 세션에 붙고 대화 연속성을 유지한다

**요구사항.** 모델 호출 사이에 추가 메시지를 넣는 큐는 세션 state에 저장해야 하며, 큐가
비워질 때까지 follow-up 호출을 돌고 최신 서비스 대화 핸들을 다음 호출로 전달해야 한다.

**원본 비교**

- .NET: `MessageInjectingChatClient`가 session `StateBag` 큐와 `ConversationId` propagation을 소유한다.
- Python: `message_injection.pending_messages`를 세션 state에 두고 내부 loop에서 queue drain과 follow-up 호출을 수행한다.

**판단.** 동일하다. 큐가 세션 밖에 있으면 재개 시점과 병렬 호출에서 상태가 끊긴다. 연속성
핸들 전달까지 세션 주도 규칙으로 고정해야 한다.

**수용 기준**

- 큐에 넣은 메시지는 다음 모델 호출에 포함된다.
- actionable follow-up이 없고 큐에 새 메시지가 남아 있으면 내부 follow-up 호출이 다시 발생한다.
- 첫 호출이 새 `serviceSessionId`를 만들면 다음 follow-up 호출은 그 값을 사용한다.

**근거** [09 API·타입](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md),
[09 Python: message injection queue](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md),
[09 .NET: split pipeline](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md)

---

## SES-018 파일 이력은 세션 스냅샷과 분리된 채널에 저장한다

**요구사항.** 파일 기반 이력 저장이 필요하면 세션 스냅샷 파일에 덧붙이지 말고, append-only
이력 채널을 별도 형식으로 운영해야 한다.

**원본 비교**

- .NET: inspected 범위에서 core용 별도 file history channel은 확인되지 않는다.
- Python: `FileHistoryProvider`가 JSONL 또는 length-prefixed MessagePack append-only file을 사용한다.

**판단.** Python 방식을 택한다. 세션 스냅샷과 append log를 한 파일에 섞으면 복구와 compaction
경계가 흐려진다. Java는 저장 목적이 다른 두 채널을 분리한다.

**수용 기준**

- 세션 스냅샷 파일과 이력 파일의 형식 또는 경로 규칙이 다르다.
- 이력 저장은 append-only이며 기존 메시지를 제자리 수정하지 않는다.
- 이력 파일 파손이 세션 스냅샷 복원 경로를 직접 깨뜨리지 않는다.

**근거** [09 상태·영속화](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md),
[09 Java 결정](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md)

---

## SES-019 외부 메모리는 명시적 범위와 비신뢰 문맥으로 연동한다

**요구사항.** 외부 메모리 provider는 저장 범위와 검색 범위를 분리해서 받아야 하며, 검색한
메모리는 instruction이 아니라 비신뢰 context message로 주입해야 한다.

**원본 비교**

- .NET: Foundry, Mem0, chat-history memory providers가 explicit scope와 user-message injection 패턴을 따른다.
- Python: Mem0, CosmosMemory, FoundryMemory가 storage scope와 retrieval scope를 분리하고 untrusted context message로 넣는다.

**판단.** 동일하다. retrieval 결과를 instruction으로 승격하면 간접 prompt injection 경계가
무너진다. 범위 분리도 안전한 기본값이다.

**수용 기준**

- 메모리 provider는 저장 범위와 검색 범위를 별도 인자로 받거나 별도 초기화 정책을 가진다.
- 검색 결과는 system/instruction 채널이 아니라 context message 채널에 들어간다.
- 범위 초기화가 실패하면 provider는 명시적 예외를 던지거나 비활성화된다.

**근거** [09 경계](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md),
[09 확장점](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md),
[09 provider memory(Mem0/Cosmos/Foundry 등)의 공통 경계](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md),
[09 Java 결정](../upstream/snapshots/d0a4165f/features/09-history-context-memory.md)

---

## SES-020 호스팅 격리는 별도 사용자 컨텍스트가 맡는다

**요구사항.** 다중 사용자 호스팅 모듈은 write-once 사용자 컨텍스트와 사용자 파티션 저장소로
세션을 격리해야 하며, 이 책임을 코어 `AgentEngine`이나 `serviceSessionId`에 넘기지 않는다.

**원본 비교**

- .NET: `HostedSessionContext`와 user-partitioned `AgentSessionStore`가 이 책임을 맡는다.
- Python: core session은 auth boundary가 아니라고만 정의하며 동등한 hosted identity runtime은 이 범위에서 확인되지 않는다.

**판단.** `.NET`의 호스팅 경계만 채택하되 책임은 Hosting 단계로 제한한다. 사용자 격리는
호스트의 일이다. 코어가 tenant 정책을 알면 경계가 흐려진다.

**수용 기준**

- 호스팅 모듈은 세션을 사용자별 파티션 아래에 저장한다.
- 같은 세션을 다른 사용자 컨텍스트로 재개하면 명시적으로 실패한다.
- 코어 세션 타입은 호스트 사용자 ID 필드를 필수로 요구하지 않는다.

**근거** [08 상세 실행 흐름](../upstream/snapshots/d0a4165f/features/08-sessions.md),
[08 오류·검증·보안](../upstream/snapshots/d0a4165f/features/08-sessions.md),
[08 Java 설계 결정](../upstream/snapshots/d0a4165f/features/08-sessions.md)

---

## 이 문서가 다루지 않는 것

| 주제 | 소유 문서 |
| --- | --- |
| 인터셉터 순서와 스트리밍 경계 | [07 인터셉터와 컨텍스트 관리](07-interceptors.md) |
| 도구 정의, 호출 루프, 승인 흐름 | [04 도구 정의와 실행 루프](04-tools.md) |
| 하네스가 기본 provider를 조립하는 방법 | [08 하네스 기능](08-harness.md) |
| 워크플로 체크포인트와 장기 실행 복원 | [09 워크플로와 오케스트레이션](09-workflows.md) |
| HTTP 요청 식별과 호스팅 프로토콜 | [10 호스팅과 프로토콜](10-hosting.md) |
