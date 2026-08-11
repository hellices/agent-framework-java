# 09 워크플로와 오케스트레이션

**접두사** `WF` · **원본 기능** [14 workflow-graph](../upstream/snapshots/d0a4165f/features/14-workflow-graph.md),
[15 workflow-runtime](../upstream/snapshots/d0a4165f/features/15-workflow-runtime.md),
[16 workflow-checkpoint-hitl](../upstream/snapshots/d0a4165f/features/16-workflow-checkpoint-hitl.md),
[17 workflow-composition](../upstream/snapshots/d0a4165f/features/17-workflow-composition.md),
[18 orchestrations](../upstream/snapshots/d0a4165f/features/18-orchestrations.md),
[19 declarative](../upstream/snapshots/d0a4165f/features/19-declarative.md)

워크플로 하위 프로젝트의 그래프 정의, 런타임, 체크포인트, 사람 개입, 조합, 오케스트레이션,
선언형 표면의 계약을 정의한다. 이 문서의 단계는 대부분 `Workflow`다. 하네스 조립과 호스팅
프로토콜은 다른 문서가 소유한다.

## 요약

| ID | 요구사항 | 등급 | 단계 |
| --- | --- | --- | --- |
| WF-001 | 그래프 정의와 실행 런타임을 분리한다 | 필수 | Workflow |
| WF-002 | 실행자 라우트 등록은 annotation processor 경로를 우선한다 | 권장 | Workflow |
| WF-003 | 미바인딩 실행자가 남으면 빌드를 거부한다 | 필수 | Workflow |
| WF-004 | 시작점에서 도달할 수 없는 실행자가 있으면 빌드를 거부한다 | 필수 | Workflow |
| WF-005 | 출력 지정은 비어 있음, 중복, 겹침을 빌드 시점에 거부한다 | 필수 | Workflow |
| WF-006 | 런타임 edge kind는 `DIRECT`, `FAN_OUT`, `FAN_IN`으로 고정한다 | 필수 | Workflow |
| WF-007 | 엣지 실행 의미를 조건 drop과 fan-in barrier로 고정한다 | 필수 | Workflow |
| WF-008 | 직렬화된 callables는 명시적 재바인딩 없이는 복원하지 않는다 | 필수 | Workflow |
| WF-009 | 워크플로 실행은 1급 run handle로 제어한다 | 필수 | Workflow |
| WF-010 | 상태는 polling과 status event 둘 다로 노출한다 | 권장 | Workflow |
| WF-011 | superstep 수명주기와 체크포인트 순서를 고정한다 | 필수 | Workflow |
| WF-012 | 공유 상태는 scoped API와 pending/committed 버퍼를 함께 가진다 | 필수 | Workflow |
| WF-013 | 같은 상태 키의 다중 쓰기는 명시적 실패로 처리한다 | 필수 | Workflow |
| WF-014 | 메시지 송신은 trace context를 전파하고 예약 이벤트 스푸핑을 막는다 | 필수 | Workflow |
| WF-015 | 공개 cancel API와 stream 소비자 취소를 분리한다 | 필수 | Workflow |
| WF-016 | pending request가 남아 있으면 새 메시지를 기본 거부한다 | 필수 | Workflow |
| WF-017 | 실패 이벤트 순서는 실행자 실패 후 워크플로 실패다 | 필수 | Workflow |
| WF-018 | 체크포인트는 continuation에 필요한 전체 상태를 담는다 | 필수 | Workflow |
| WF-019 | restore는 시그니처를 검증하고 stale event를 먼저 비운다 | 필수 | Workflow |
| WF-020 | latest checkpoint 판정은 정렬 계약에 의존한다 | 필수 | Workflow |
| WF-021 | pending request는 restore 뒤 다시 발행하되 중복되지 않아야 한다 | 필수 | Workflow |
| WF-022 | 외부 응답은 request id와 port id를 함께 검증한다 | 필수 | Workflow |
| WF-023 | 승인 재개는 `original_request` payload를 진실 원천으로 삼는다 | 필수 | Workflow |
| WF-024 | 파일 체크포인트 저장은 경로와 역직렬화 안전장치를 기본 제공한다 | 필수 | Workflow |
| WF-025 | run handle은 재개와 pending request 조회 API를 제공한다 | 권장 | Workflow |
| WF-026 | 하위 워크플로 조합은 host executor 소유권 모델을 따른다 | 필수 | Workflow |
| WF-027 | 자식 출력, intermediate, request 전파 정책은 명시적으로 설정한다 | 필수 | Workflow |
| WF-028 | workflow-as-agent 세션은 continuation 상태를 함께 직렬화한다 | 필수 | Workflow |
| WF-029 | functional workflow는 별도 실험 API로 분리한다 | 권장 | Workflow |
| WF-030 | 오케스트레이션은 공통 output designation helper와 명시적 기본 정책을 가진다 | 필수 | Workflow |
| WF-031 | sequential·concurrent는 패턴별 계약과 request-info wrapper를 제공한다 | 권장 | Workflow |
| WF-032 | handoff는 mesh 기본값, 능력 검증, 필터링, pending-request 차단을 갖는다 | 필수 | Workflow |
| WF-033 | group-chat은 단일 orchestrator 계약과 no-self-echo 규칙을 갖는다 | 필수 | Workflow |
| WF-034 | magentic은 단일 manager 계약과 plan review·replan 흐름을 갖는다 | 필수 | Workflow |
| WF-035 | 선언형 워크플로는 분리된 모듈, 안전한 상태 경로, typed handler SPI를 가진다 | 필수 | Workflow |

### 그래프

---

## WF-001 그래프 정의와 실행 런타임을 분리한다

**요구사항.** Java 워크플로는 immutable graph definition과 mutable run/runtime을 서로 다른 타입으로
분리해야 한다.

**원본 비교**

- .NET: `WorkflowBuilder`가 정의를 만들고 `Workflow`와 `Run`/`StreamingRun`이 실행을 맡는다.
- Python: `WorkflowBuilder`가 `Workflow`를 만들고 runtime이 별도 runner/context로 실행한다.

**판단.** 동일한 방향이다. 정의와 실행을 분리해야 checkpoint compatibility를 graph identity로 판단할 수
있다. 정의 객체가 독립 identity를 가지면 restore, observability, reflection API를 단순화할 수 있다.

**수용 기준**

- build 결과 정의 객체만으로는 실행 중 메시지와 상태를 직접 보관하지 않는다.
- 실행을 시작하면 별도 run handle 또는 runner 상태 객체가 만들어진다.
- checkpoint compatibility 검사는 현재 runtime이 아니라 정의 시그니처를 기준으로 한다.

**근거** [14 그래프](../upstream/snapshots/d0a4165f/features/14-workflow-graph.md), [15 런타임](../upstream/snapshots/d0a4165f/features/15-workflow-runtime.md)

---

## WF-002 실행자 라우트 등록은 annotation processor 경로를 우선한다

**요구사항.** Java 실행자 라우트 등록의 주 경로는 runtime reflection이 아니라 annotation processor
기반 생성 코드여야 한다.

**원본 비교**

- .NET: Roslyn generator가 route와 protocol 코드를 생성한다.
- Python: `@handler` annotation을 runtime introspection으로 읽는다.

**판단.** Java는 .NET 경로를 택한다. cold start, 검증 품질, AOT 친화성을 모두 고려하면 생성 코드가
낫다. Python식 introspection은 편하지만 Java에서 reflection-only 경로를 표준으로 두기엔 비용이 크다.

**수용 기준**

- 공식 가이드는 annotation processor 등록 경로를 기본 경로로 문서화한다.
- 생성 코드는 입력 타입, 출력 타입, route 등록을 포함한다.
- reflection-only 경로가 있더라도 부가 경로로만 취급된다.

**근거** [14 Executor 프로토콜과 타입 시스템](../upstream/snapshots/d0a4165f/features/14-workflow-graph.md), [19 .NET generator / source generator와 Python 구현 차이](../upstream/snapshots/d0a4165f/features/19-declarative.md)

---

## WF-003 미바인딩 실행자가 남으면 빌드를 거부한다

**요구사항.** 그래프 정의에 concrete binding이 없는 실행자가 남아 있으면 build는 실패해야 한다.

**원본 비교**

- .NET: placeholder/unbound executor를 추적하고 `Build()`에서 거부한다.
- Python: builder가 최종 `Workflow` 생성 전에 graph validator를 호출한다.

**판단.** .NET 테스트가 더 직접적이므로 그 계약을 채택한다. 미바인딩 실행자를 허용하면 첫 메시지를
받을 때까지 오류가 숨는다. build 단계에서 실패시키는 편이 명확하다.

**수용 기준**

- 미바인딩 실행자가 하나라도 남으면 build가 예외로 끝난다.
- 실패한 정의 객체는 실행 시작점을 만들지 않는다.
- 오류 메시지에 어떤 실행자가 바인딩되지 않았는지 식별자가 포함된다.

**근거** [14 Graph validation](../upstream/snapshots/d0a4165f/features/14-workflow-graph.md)

---

## WF-004 시작점에서 도달할 수 없는 실행자가 있으면 빌드를 거부한다

**요구사항.** 시작 실행자에서 도달할 수 없는 실행자가 정의에 남아 있으면 build는 실패해야 한다.

**원본 비교**

- .NET: BFS 기반 reachability 검사로 unreachable executor를 거부한다.
- Python: validator가 graph connectivity를 검사 대상으로 둔다.

**판단.** 동일하다. dead node를 남기면 output designation과 checkpoint state가 쓸데없이 복잡해진다.
workflow는 연결된 그래프여야 한다.

**수용 기준**

- 시작점에서 닿지 않는 실행자가 있으면 build가 실패한다.
- direct, fan-out, fan-in으로 연결된 모든 경로가 검사 대상이다.
- 도달성 검사는 실행 전에 끝난다.

**근거** [14 Graph validation](../upstream/snapshots/d0a4165f/features/14-workflow-graph.md)

---

## WF-005 출력 지정은 비어 있음, 중복, 겹침을 빌드 시점에 거부한다

**요구사항.** 명시적 output/intermediate designation은 비어 있거나 중복되거나 서로 겹치면 build 시점에
실패해야 한다.

**원본 비교**

- .NET: 기본 designation은 builder가 정하지만 validation은 제한적이다.
- Python: explicit output designation이 비어 있음, 중복, overlap일 때 build를 거부한다.

**판단.** Python의 더 엄격한 검증을 채택한다. output visibility 계약이 모호하면 agent adapter와
orchestration terminal/intermediate semantics가 즉시 깨진다.

**수용 기준**

- explicit output 집합이 비어 있으면 build가 실패한다.
- 같은 실행자가 output과 intermediate 양쪽에 동시에 들어가면 build가 실패한다.
- 중복 지정은 build가 실패한다.

**근거** [14 Graph validation](../upstream/snapshots/d0a4165f/features/14-workflow-graph.md), [18 오케스트레이션](../upstream/snapshots/d0a4165f/features/18-orchestrations.md)

---

## WF-006 런타임 edge kind는 `DIRECT`, `FAN_OUT`, `FAN_IN`으로 고정한다

**요구사항.** Java 런타임 edge model은 `DIRECT`, `FAN_OUT`, `FAN_IN` 세 종류만 공개하고,
switch나 multi-selection은 builder lowering으로 처리해야 한다.

**원본 비교**

- .NET: 런타임 edge kind를 `Direct`, `FanOut`, `FanIn`으로 제한한다.
- Python: `SwitchCaseEdgeGroup` 등 더 풍부한 코어 edge 계층을 가진다.

**판단.** Java는 .NET의 좁은 런타임 모델을 택한다. 런타임 상태와 관찰성은 단순할수록 좋다. DSL의
풍부함은 builder가 낮추면 된다.

**수용 기준**

- 공개 런타임 edge enum에 switch 전용 kind가 없다.
- switch 또는 multi-selection builder API는 build 후 fan-out 계열 구조로 낮아진다.
- checkpoint state는 세 edge kind만 가정하고 직렬화할 수 있다.

**근거** [14 공개 API·타입](../upstream/snapshots/d0a4165f/features/14-workflow-graph.md), [14 Java 결정](../upstream/snapshots/d0a4165f/features/14-workflow-graph.md)

---

## WF-007 엣지 실행 의미를 조건 drop과 fan-in barrier로 고정한다

**요구사항.** direct edge의 조건이 거짓이면 메시지는 drop되지만 라우팅 오류로 보지 않고, fan-in은
모든 source가 최소 한 번 메시지를 낸 뒤에만 sink를 실행해야 한다.

**원본 비교**

- .NET: direct edge false condition을 drop으로 처리하고 fan-in runner가 source별 버퍼를 유지한다.
- Python: single edge false condition을 drop으로 처리하고 fan-in buffer가 준비 완료를 판단한다.

**판단.** 동일하다. 조건 거짓은 정상 라우팅 결과이고, fan-in은 barrier semantics가 핵심이다. 둘을
실패로 오해하면 workflow convergence와 checkpoint 재현성이 깨진다.

**수용 기준**

- direct edge 조건이 false면 downstream 전달은 생기지 않지만 workflow 실패도 생기지 않는다.
- fan-in sink는 모든 source가 메시지를 낸 전에는 실행되지 않는다.
- fan-in ready가 되면 sink는 source 집합당 한 번만 활성화된다.

**근거** [14 Edge 그룹과 라우팅 실행](../upstream/snapshots/d0a4165f/features/14-workflow-graph.md)

---

## WF-008 직렬화된 callables는 명시적 재바인딩 없이는 복원하지 않는다

**요구사항.** edge와 workflow 정의를 직렬화할 때 live callable은 저장하지 않고, 복원 시에는 symbolic
name을 명시적 registry에 다시 바인딩할 때만 사용해야 한다.

**원본 비교**

- .NET: restore compatibility는 workflow signature를 검사하지만 callable 복원 표면은 좁다.
- Python: callable 대신 이름만 저장하고, 복원 불가 시 fail-closed placeholder를 설치한다.

**판단.** Python의 fail-closed 모델을 택한다. 직렬화된 정의가 조용히 다른 routing을 수행하는 것이
가장 위험하다. registry rebinding이 없으면 명시적으로 실패해야 한다.

**수용 기준**

- 정의 직렬화 결과에 live lambda나 method reference가 그대로 저장되지 않는다.
- 복원 후 필요한 callable을 찾지 못하면 첫 호출에서 명시적 예외가 난다.
- 자동 fallback routing이나 무음 no-op 복원은 허용되지 않는다.

**근거** [14 상태·영속화](../upstream/snapshots/d0a4165f/features/14-workflow-graph.md), [14 Java 결정](../upstream/snapshots/d0a4165f/features/14-workflow-graph.md)

### 런타임

---

## WF-009 워크플로 실행은 1급 run handle로 제어한다

**요구사항.** Java 워크플로 실행은 스트림, 상태 조회, 응답 제출, 재개, 취소를 묶은 1급 run handle을
공개해야 한다.

**원본 비교**

- .NET: `Run`과 `StreamingRun`이 `GetStatusAsync`, `ResumeAsync`, `SendResponseAsync`, `CancelRunAsync`를 나눈다.
- Python: `Workflow.run(...)` 하나가 streaming과 non-streaming, responses, checkpoint restore를 모두 받는다.

**판단.** Java는 .NET의 명시적 handle 구조를 택하되 control plane을 한 handle에 모은다. 운영 제어는
명시적일수록 좋고, unified overload만으로는 재개와 응답 제출 계약이 흐려진다.

**수용 기준**

- 공개 API에 workflow run handle 타입이 존재한다.
- handle은 상태 조회, 응답 제출, 취소, 스트림 소비 API를 가진다.
- non-streaming helper가 있더라도 내부적으로 같은 handle 계약 위에 구현된다.

**근거** [15 런타임](../upstream/snapshots/d0a4165f/features/15-workflow-runtime.md), [16 Java 결정](../upstream/snapshots/d0a4165f/features/16-workflow-checkpoint-hitl.md)

---

## WF-010 상태는 polling과 status event 둘 다로 노출한다

**요구사항.** Java는 run 상태를 polling API와 명시적 status event 두 경로로 모두 노출해야 한다.

**원본 비교**

- .NET: 상태는 `GetStatusAsync()` polling이 중심이다.
- Python: `status` events와 `status_timeline()`이 중심이다.

**판단.** 둘을 함께 제공하는 절충안이 가장 실용적이다. 운영 제어 plane은 polling이 편하고, audit와
telemetry는 status event가 편하다.

**수용 기준**

- run handle에서 현재 상태를 동기적으로 확인할 수 있다.
- 스트림 또는 결과 객체에 상태 전이 이벤트가 포함된다.
- 최종 상태는 polling 결과와 status timeline에서 일치한다.

**근거** [15 메시지, 이벤트, 상태 timeline](../upstream/snapshots/d0a4165f/features/15-workflow-runtime.md), [15 Java 결정](../upstream/snapshots/d0a4165f/features/15-workflow-runtime.md)

---

## WF-011 superstep 수명주기와 체크포인트 순서를 고정한다

**요구사항.** 한 superstep의 공용 수명주기는 시작 이벤트, 메시지 전달과 joined work 실행, 상태 commit,
체크포인트, 완료 이벤트 순서로 고정되어야 한다.

**원본 비교**

- .NET: superstep 시작 후 deliveries와 joined subworkflow, checkpoint, complete event 순으로 진행한다.
- Python: `superstep_started`, iteration 실행, state commit, checkpoint, `superstep_completed` 순으로 진행한다.

**판단.** 동일하다. 이 순서가 바뀌면 이벤트와 checkpoint가 서로 다른 현실을 가리키게 된다.
resume 가능한 시스템에서는 특히 중요하다.

**수용 기준**

- `superstep_started`가 `superstep_completed`보다 먼저 나온다.
- 완료 이벤트가 나왔을 때 그 step의 상태 commit과 checkpoint가 이미 끝나 있다.
- 같은 step의 checkpoint보다 늦은 started event가 같은 step 번호로 다시 나오지 않는다.

**근거** [15 Superstep / Iteration 루프](../upstream/snapshots/d0a4165f/features/15-workflow-runtime.md)

---

## WF-012 공유 상태는 scoped API와 pending/committed 버퍼를 함께 가진다

**요구사항.** 공유 상태 API는 executor scope를 표현할 수 있어야 하고, 내부 구현은 pending과 committed
버퍼를 분리해 superstep 경계에서만 commit해야 한다.

**원본 비교**

- .NET: scope 이름을 받는 state API와 staged update publish 모델을 가진다.
- Python: flat key API지만 `_pending`과 `_committed` 두 층 버퍼를 가진다.

**판단.** Java는 .NET의 scoped API와 Python의 두 층 버퍼 모델을 결합한다. scope가 있어야 multi-agent
workflow의 충돌을 다룰 수 있고, pending/committed 분리가 있어야 superstep 원자성이 선다.

**수용 기준**

- 공개 상태 API가 최소한 executor 또는 scope 단위를 표현할 수 있다.
- 같은 step 안에서 쓴 값은 commit 전까지 pending 버퍼에만 머문다.
- 다음 superstep 시작 시 읽히는 값은 직전 commit 결과다.

**근거** [15 Shared state와 run context](../upstream/snapshots/d0a4165f/features/15-workflow-runtime.md)

---

## WF-013 같은 상태 키의 다중 쓰기는 명시적 실패로 처리한다

**요구사항.** 같은 superstep에서 충돌하는 다중 쓰기가 발생하면 Java는 last-write-wins로 조용히 덮지 말고
명시적 런타임 실패를 보고해야 한다.

**원본 비교**

- .NET: fan-out된 다중 writer conflict를 runtime error로 surface하는 테스트가 있다.
- Python: flat state는 같은 step의 여러 쓰기를 last write wins로 설명한다.

**판단.** 더 안전한 기본값을 택해 .NET 계약을 채택한다. last-write-wins는 병렬 fan-out에서 조용한
손실을 만든다. 충돌은 실패로 드러내는 편이 낫다.

**수용 기준**

- 같은 키에 대한 충돌 쓰기가 발생하면 workflow는 명시적 오류를 낸다.
- 충돌이 없을 때만 상태 commit이 성공한다.
- 충돌 오류는 어느 key와 어느 writers가 충돌했는지 식별할 수 있다.

**근거** [14 오류·검증·보안](../upstream/snapshots/d0a4165f/features/14-workflow-graph.md), [15 .NET 테스트](../upstream/snapshots/d0a4165f/features/15-workflow-runtime.md)

---

## WF-014 메시지 송신은 trace context를 전파하고 예약 이벤트 스푸핑을 막는다

**요구사항.** runtime `send_message`는 trace context를 envelope에 넣어야 하고, executor가 `output`,
`intermediate`, lifecycle 같은 예약 이벤트를 직접 스푸핑하면 framework가 이를 거부해야 한다.

**원본 비교**

- .NET: `SendMessageAsync`가 trace carrier를 주입한다.
- Python: `send_message()`가 trace context를 주입하고 `add_event()`가 reserved event를 warning으로 바꾼다.

**판단.** 두 계약을 함께 가져간다. trace 전파는 관찰성의 기본이고, 예약 이벤트 보호는 event channel
무결성의 기본이다.

**수용 기준**

- executor가 보낸 메시지에 trace context가 없으면 observability 모드에서 검출 가능하다.
- executor가 예약 이벤트 타입을 직접 넣으면 원 이벤트는 무시되거나 warning으로 대체된다.
- framework가 생성한 output/intermediate 이벤트와 executor가 스푸핑한 이벤트를 구분할 수 있다.

**근거** [14 오류·검증·보안](../upstream/snapshots/d0a4165f/features/14-workflow-graph.md), [15 메시지 송신과 trace context 전파](../upstream/snapshots/d0a4165f/features/15-workflow-runtime.md)

---

## WF-015 공개 cancel API와 stream 소비자 취소를 분리한다

**요구사항.** 워크플로를 실제로 중단하는 공개 `cancel()`과 스트림 소비를 멈추는 consumer cancellation은
서로 다른 효과를 가져야 한다.

**원본 비교**

- .NET: `WatchStreamAsync()` 취소는 스트림만 끝내고 workflow 취소는 `CancelRunAsync()`가 맡는다.
- Python: 공개 cancel API는 보이지 않고 internal cancellation 처리만 보인다.

**판단.** Java는 .NET 모델을 채택한다. 소비자와 실행 소유자의 권한을 분리해야 한다. 스트림 소비를
끝냈다고 workflow까지 사라지면 hosting과 UI 통합이 어렵다.

**수용 기준**

- 스트림 소비 토큰 취소만으로 workflow 상태가 `CANCELLED`나 종료로 바뀌지 않는다.
- 공개 cancel API를 호출하면 이후 상태가 취소 또는 종료로 전이한다.
- 취소된 run은 성공 상태로 보고되지 않는다.

**근거** [15 동시성·스트리밍·취소](../upstream/snapshots/d0a4165f/features/15-workflow-runtime.md)

---

## WF-016 pending request가 남아 있으면 새 메시지를 기본 거부한다

**요구사항.** pending external request가 남아 있는 run에 새 메시지를 보내는 것은 기본적으로 거부하고,
허용하려면 명시적 override나 resume token이 필요해야 한다.

**원본 비교**

- .NET: explicit resume/response 경로를 중심으로 설계한다.
- Python: pending request가 있어도 fresh message를 warning과 함께 허용한다.

**판단.** user가 강조한 겹침 정책에 따라 더 안전한 쪽을 택한다. Python 테스트가 보여 주듯 old response가
moved-on state에 적용될 수 있다. 기본값으로는 허용하면 안 된다.

**수용 기준**

- pending request가 남은 run에 새 메시지를 보내면 기본 설정에서 즉시 실패한다.
- override 모드를 켜지 않으면 old request response와 새 메시지를 같은 상태에서 섞지 않는다.
- 허용 모드가 있어도 사용 여부가 호출 API에서 명시적으로 드러난다.

**근거** [15 Java 결정](../upstream/snapshots/d0a4165f/features/15-workflow-runtime.md), [16 Java 결정](../upstream/snapshots/d0a4165f/features/16-workflow-checkpoint-hitl.md)

---

## WF-017 실패 이벤트 순서는 실행자 실패 후 워크플로 실패다

**요구사항.** 실행자 실패가 발생하면 `executor_failed`가 먼저 관찰되고, 그 다음에 workflow-level 실패가
승격되어야 한다.

**원본 비교**

- .NET: workflow error event와 executor failure event를 모두 surface할 수 있다.
- Python: tests가 `executor_failed`가 `failed`보다 먼저 나와야 함을 고정한다.

**판단.** Python의 더 구체적인 이벤트 순서를 채택한다. 디버깅과 재시도 정책은 root cause executor를
먼저 알아야 한다.

**수용 기준**

- 실패한 run의 이벤트 스트림에서 executor failure가 workflow failure보다 앞선다.
- executor failure 이벤트에는 실패한 executor id가 포함된다.
- workflow failure 이벤트는 같은 실패를 다시 요약하되 executor failure를 대체하지 않는다.

**근거** [14 구체 acceptance scenarios](../upstream/snapshots/d0a4165f/features/14-workflow-graph.md), [15 구체 acceptance scenarios](../upstream/snapshots/d0a4165f/features/15-workflow-runtime.md)

### 체크포인트와 사람 개입

---

## WF-018 체크포인트는 continuation에 필요한 전체 상태를 담는다

**요구사항.** 체크포인트 payload는 workflow signature, runner queue, pending external requests,
shared state, edge state, executor snapshot을 함께 담아야 한다.

**원본 비교**

- .NET: `Checkpoint`가 workflow info, runner state, state data, edge state, parent를 담는다.
- Python: `WorkflowCheckpoint`가 signature hash, messages, state, pending requests, iteration, lineage를 담는다.

**판단.** 동일한 continuation contract를 채택한다. checkpoint와 HITL을 따로 설계하면 restore 뒤 pending
request를 제대로 재발행할 수 없다.

**수용 기준**

- 체크포인트 하나만으로 restore 후 pending request를 다시 보여 줄 수 있다.
- shared state와 edge/executor 상태가 함께 복원된다.
- lineage 또는 parent pointer가 있어 최신 checkpoint 체인을 추적할 수 있다.

**근거** [16 체크포인트와 사람 개입](../upstream/snapshots/d0a4165f/features/16-workflow-checkpoint-hitl.md)

---

## WF-019 restore는 시그니처를 검증하고 stale event를 먼저 비운다

**요구사항.** checkpoint restore는 현재 workflow 정의 시그니처를 먼저 검증하고, 기존 event buffer를
비운 뒤 상태를 적용해야 한다.

**원본 비교**

- .NET: restore 전에 event stream buffer를 비우고 workflow compatibility를 검사한다.
- Python: `graph_signature_hash`가 다르면 restore를 거부한다.

**판단.** 두 구현의 안전 장치를 모두 채택한다. 정의가 바뀐 checkpoint를 복원하거나 stale event를 남기면
caller가 서로 다른 타임라인을 보게 된다.

**수용 기준**

- 현재 workflow signature와 checkpoint signature가 다르면 restore가 실패한다.
- restore 직후 checkpoint 이후에 생성된 오래된 이벤트가 다시 흘러나오지 않는다.
- restore는 상태 적용 전에 event buffer 초기화를 수행한다.

**근거** [14 상태·영속화](../upstream/snapshots/d0a4165f/features/14-workflow-graph.md), [16 .NET 체크포인트 생성과 복원](../upstream/snapshots/d0a4165f/features/16-workflow-checkpoint-hitl.md)

---

## WF-020 latest checkpoint 판정은 정렬 계약에 의존한다

**요구사항.** checkpoint store는 인덱스를 oldest-first, newest-last 순서로 돌려줘야 하고, latest
checkpoint 조회는 그 마지막 항목을 사용해야 한다.

**원본 비교**

- .NET: store ordering contract를 명시하고 마지막 항목을 latest로 취급한다.
- Python: lineage와 timestamp를 갖지만 latest ordering을 public contract로 덜 강조한다.

**판단.** .NET의 더 엄격한 저장소 계약을 채택한다. latest 판정이 구현체마다 다르면 restore API가
결정적이지 않다.

**수용 기준**

- checkpoint store contract 문서에 oldest-first/newest-last가 명시된다.
- `latestCheckpoint()`는 인덱스의 마지막 요소를 사용한다.
- 정렬 계약을 깨는 store 구현은 테스트에서 실패한다.

**근거** [16 .NET 체크포인트 생성과 복원](../upstream/snapshots/d0a4165f/features/16-workflow-checkpoint-hitl.md)

---

## WF-021 pending request는 restore 뒤 다시 발행하되 중복되지 않아야 한다

**요구사항.** pending request가 있는 checkpoint에서 restore하면 request 정보가 다시 발행되어야 하고,
이미 처리한 요청은 중복 발행되지 않아야 한다.

**원본 비교**

- .NET: resume 후 pending request를 republish하고 duplicate 없는 completion을 테스트로 고정한다.
- Python: `responses + checkpoint_id` 경로에서 이미 응답한 request_info를 다시 내보내지 않는다.

**판단.** 동일하다. caller는 restore 뒤 다시 질문을 받아야 하지만, 같은 질문을 두 번 처리하게 하면 안
된다.

**수용 기준**

- pending request가 있는 checkpoint를 restore하면 같은 request id가 다시 보인다.
- restore 직후 상태는 pending request 상태를 반영한다.
- 같은 request에 응답을 제공한 뒤에는 같은 request_info가 다시 중복 노출되지 않는다.

**근거** [16 pending request 재발행과 approval persistence](../upstream/snapshots/d0a4165f/features/16-workflow-checkpoint-hitl.md)

---

## WF-022 외부 응답은 request id와 port id를 함께 검증한다

**요구사항.** 외부 응답은 request id만이 아니라 원 요청의 port id까지 일치할 때만 수락해야 한다.

**원본 비교**

- .NET: forged response가 request id는 같아도 port id가 다르면 거부한다.
- Python: pending request map과 response type을 검증하지만 port-level correlation surface는 더 얇다.

**판단.** 더 엄격한 .NET 계약을 채택한다. request id만 확인하면 다른 실행 경로에 위조 응답이 들어갈 수
있다.

**수용 기준**

- request id는 같고 port id가 다른 응답은 거부된다.
- 위조 응답을 거부한 뒤에도 정당한 응답은 같은 request에 대해 나중에 수락될 수 있다.
- unknown request id 응답도 거부된다.

**근거** [15 오류·검증·보안](../upstream/snapshots/d0a4165f/features/15-workflow-runtime.md), [16 request-response 코어 흐름](../upstream/snapshots/d0a4165f/features/16-workflow-checkpoint-hitl.md)

---

## WF-023 승인 재개는 `original_request` payload를 진실 원천으로 삼는다

**요구사항.** tool approval, MCP approval, agent approval의 재개는 mutable workflow state가 아니라
응답 payload 안의 `original_request`를 유일한 진실 원천으로 사용해야 한다.

**원본 비교**

- .NET: function/MCP approval snapshot을 저장해 재개 시 그 snapshot을 사용한다.
- Python: declarative approval tests가 `original_request` payload만 신뢰하고 stale state를 무시함을 고정한다.

**판단.** Python의 더 엄격한 회귀 계약을 명세로 채택한다. 승인 대기 중 상태가 바뀌어도 승인된 원 요청이
바뀌면 안 된다. concurrent approvals의 swap도 막아야 한다.

**수용 기준**

- stale state에 다른 arguments가 있어도 재개 실행은 `original_request`의 arguments를 사용한다.
- 동시에 두 승인 요청이 pending이어도 응답이 서로 뒤바뀌지 않는다.
- 승인 거부 시 원 도구 호출은 실행되지 않는다.

**근거** [16 체크포인트와 사람 개입](../upstream/snapshots/d0a4165f/features/16-workflow-checkpoint-hitl.md), [19 선언형](../upstream/snapshots/d0a4165f/features/19-declarative.md)

---

## WF-024 파일 체크포인트 저장은 경로와 역직렬화 안전장치를 기본 제공한다

**요구사항.** 파일 체크포인트 저장소는 저장 루트 밖 경로를 거부하고, atomic write와 제한된
역직렬화 타입 집합을 기본 내장해야 한다.

**원본 비교**

- .NET: 파일 이름 escape와 ordered index를 제공한다.
- Python: `_validate_file_path`, atomic `os.replace`, restricted decode를 제공한다.

**판단.** Java는 Python의 path validation과 restricted decode를 기본으로 채택하고, .NET처럼 파일 이름
정규화도 함께 가져간다. 파일 저장소는 보안 경계다.

**수용 기준**

- checkpoint id가 루트 밖 파일로 해석되면 저장과 조회가 모두 실패한다.
- 저장은 atomic replace 계열 동작으로 완료된다.
- load는 허용된 타입 집합 밖의 객체를 역직렬화하지 않는다.

**근거** [16 상태·영속화](../upstream/snapshots/d0a4165f/features/16-workflow-checkpoint-hitl.md)

---

## WF-025 run handle은 재개와 pending request 조회 API를 제공한다

**요구사항.** run handle은 `resumeFrom(checkpoint)`, `respond(requestId, payload)`, `listPendingRequests()`,
`latestCheckpoint()`에 해당하는 운영 API를 제공해야 한다.

**원본 비교**

- .NET: checkpointable run이 checkpoints, last checkpoint, restore API를 직접 가진다.
- Python: `Workflow.run(checkpoint_id=..., responses=...)` 조합으로 같은 일을 수행한다.

**판단.** Java는 handle 중심 제어 plane을 택한다. runtime 운영은 별도 parameter 조합보다 명시적 메서드가
안전하다.

**수용 기준**

- run handle에서 latest checkpoint 식별자를 조회할 수 있다.
- pending request 목록을 request id와 타입 정보와 함께 조회할 수 있다.
- checkpoint restore와 response 제출이 별도 메서드로 노출된다.

**근거** [16 Java 결정](../upstream/snapshots/d0a4165f/features/16-workflow-checkpoint-hitl.md)

### 조합

---

## WF-026 하위 워크플로 조합은 host executor 소유권 모델을 따른다

**요구사항.** 자식 워크플로를 부모 그래프에 삽입할 때는 shared child instance를 직접 재사용하지 말고,
ownership token을 가진 host executor가 자식 lifecycle과 응답 포트 변환을 소유해야 한다.

**원본 비교**

- .NET: `SubworkflowBinding`과 `WorkflowHostExecutor`가 ownership token과 qualified port를 사용한다.
- Python: `WorkflowExecutor`가 child workflow를 executor처럼 감싸고 request/response를 재형식화한다.

**판단.** Java는 .NET의 ownership model을 채택한다. child workflow를 일반 executor처럼 공유하면 lifecycle,
checkpoint, overlap 경계가 흐려진다.

**수용 기준**

- 부모가 child workflow instance를 직접 공유 실행하지 않는다.
- 자식 외부 요청 포트는 부모 경계에서 qualified id로 변환된다.
- restore 시 child run stack과 pending response 포트 상태도 함께 복원된다.

**근거** [17 조합](../upstream/snapshots/d0a4165f/features/17-workflow-composition.md), [16 subworkflow request-response](../upstream/snapshots/d0a4165f/features/16-workflow-checkpoint-hitl.md)

---

## WF-027 자식 출력, intermediate, request 전파 정책은 명시적으로 설정한다

**요구사항.** 하위 워크플로 조합은 child output을 parent message로 보낼지 direct output으로 yield할지,
child intermediate를 어떻게 보일지, child request를 parent가 가로챌지 외부로 전파할지 명시적으로
설정해야 한다.

**원본 비교**

- .NET: host executor 옵션이 child output을 message 또는 output으로 전달한다.
- Python: `allow_direct_output`과 `propagate_request`가 이를 제어하고 intermediate를 별도로 재발행한다.

**판단.** Python의 더 명시적 public knobs를 채택한다. 조합 경계에서 무엇이 terminal인지 숨으면 agent
adapter와 parent workflow 관찰성이 불안정해진다.

**수용 기준**

- child output forwarding 정책이 공개 옵션으로 드러난다.
- child intermediate output은 parent terminal policy와 독립적으로 intermediate로 유지할 수 있다.
- child request는 parent interception 또는 external propagation 중 하나를 선택할 수 있다.

**근거** [17 Python graph composition](../upstream/snapshots/d0a4165f/features/17-workflow-composition.md)

---

## WF-028 workflow-as-agent 세션은 continuation 상태를 함께 직렬화한다

**요구사항.** workflow-as-agent 세션은 checkpoint pointer, pending request map, option flags를 함께
직렬화해야 하며, 내부 hidden yields는 최종 agent response에 넣지 않아야 한다.

**원본 비교**

- .NET: `WorkflowSession`이 last checkpoint와 pending requests를 세션에 저장한다.
- Python: `Workflow.as_agent()` tests가 hidden yields suppression과 intermediate forwarding을 고정한다.

**판단.** 두 구현의 핵심을 합친다. agent session이 continuation 상태를 모르고 있으면 multi-turn resume이
불가능하고, hidden yields를 노출하면 graph 내부 구현이 사용자 계약을 오염시킨다.

**수용 기준**

- 세션 직렬화 payload에 workflow checkpoint pointer와 pending request 정보가 포함된다.
- hidden yield는 final agent response 본문에 포함되지 않는다.
- 내부 agent id나 name이 안정적이지 않으면 checkpoint resume이 실패한다.

**근거** [17 workflow-as-agent](../upstream/snapshots/d0a4165f/features/17-workflow-composition.md)

---

## WF-029 functional workflow는 별도 실험 API로 분리한다

**요구사항.** functional workflow는 graph workflow와 분리된 실험 API로 두고, deterministic step cache,
`WorkflowInterrupted` 같은 비지역 제어 신호, code-shape signature hash를 기본 계약으로 가져야 한다.

**원본 비교**

- .NET: inspected 범위에 동등한 public functional API가 없다.
- Python: `@workflow`, `@step`, `WorkflowInterrupted`, step cache, signature hash를 실험 기능으로 제공한다.

**판단.** 안정도 차이가 커서 분리가 맞다. functional API를 core graph와 같은 안정도로 약속하면 checkpoint
호환성과 streaming semantics를 과장하게 된다.

**수용 기준**

- functional workflow 패키지는 graph workflow와 다른 실험 안정도 표기를 가진다.
- step cache key는 step name과 call index를 포함한다.
- restore는 workflow signature hash가 다르면 실패한다.
- `except Exception:`이 HITL interruption을 잡지 못한다.

**근거** [17 Python functional workflow API](../upstream/snapshots/d0a4165f/features/17-workflow-composition.md)

### 오케스트레이션

---

## WF-030 오케스트레이션은 공통 output designation helper와 명시적 기본 정책을 가진다

**요구사항.** 모든 orchestration builder는 공통 participant-output designation helper를 공유하고,
각 패턴의 기본 terminal/intermediate 정책을 명시적 상수나 enum으로 고정해야 한다.

**원본 비교**

- .NET: `OrchestrationBuilderBase`와 패턴별 builder가 기본 designation을 암묵적으로 만든다.
- Python: `_participant_output_config.py`가 공통 validation과 designation 계산을 담당한다.

**판단.** Java는 공통 helper를 별도 shared module로 끌어내고, 기본 정책도 암묵 규칙이 아니라 명시적
계약으로 둔다. 현재 원본 간 drift를 그대로 답습하면 parity가 깨진다.

**수용 기준**

- sequential, concurrent, handoff, group-chat, magentic이 같은 designation helper를 사용한다.
- 각 builder는 자신의 default output policy를 코드에서 식별 가능한 상수나 enum으로 가진다.
- unknown participant, overlap, invalid designation은 공통 규칙으로 실패한다.

**근거** [18 오케스트레이션](../upstream/snapshots/d0a4165f/features/18-orchestrations.md)

---

## WF-031 sequential·concurrent는 패턴별 계약과 request-info wrapper를 제공한다

**요구사항.** Sequential builder는 full-conversation 기본값과 chain-only 옵션을, Concurrent builder는
병렬 fan-out과 aggregator 계약을 제공해야 하며, 둘 다 generic request-info wrapper를 노출해야 한다.

**원본 비교**

- .NET: sequential은 full conversation vs chain-only를, concurrent는 custom aggregator를 제공한다.
- Python: sequential과 concurrent 모두 `with_request_info(...)`를 제공하고 concurrent callback aggregator를 지원한다.

**판단.** Java는 두 패턴의 builder contract를 분리하되 request-info wrapper는 공통 사용자 경험으로 가져간다.
이 wrapper가 있어야 specialized handoff/magentic과 역할 차이가 분명해진다.

**수용 기준**

- sequential 기본 모드에서 다음 participant는 이전 전체 대화를 받는다.
- sequential chain-only 모드에서 다음 participant는 직전 agent 응답만 받는다.
- concurrent는 participant들을 병렬로 실행하고 aggregator 출력 하나로 수렴한다.
- sequential과 concurrent builder 모두 특정 participant subset에 request-info wrapper를 적용할 수 있다.

**근거** [18 Sequential](../upstream/snapshots/d0a4165f/features/18-orchestrations.md), [18 Concurrent](../upstream/snapshots/d0a4165f/features/18-orchestrations.md)

---

## WF-032 handoff는 mesh 기본값, 능력 검증, 필터링, pending-request 차단을 갖는다

**요구사항.** Handoff builder는 explicit graph가 없으면 기본 mesh topology를 만들고, participant 능력을
빌드 시점에 검증하며, handoff call artifacts를 필터링하고, pending request가 남은 동안 handoff를 막아야 한다.

**원본 비교**

- .NET: default mesh, handoff filtering, pending request blocks handoff를 구현한다.
- Python: participants must be `Agent`이고 per-service-call history persistence를 요구하며 default mesh를 만든다.

**판단.** 두 원본의 안전 장치를 합친다. handoff는 shared conversation과 continuation 상태를 많이 다루므로
능력 검증과 artifact filtering이 필수다.

**수용 기준**

- explicit handoff graph가 없으면 모든 participant 사이의 기본 mesh가 생성된다.
- handoff target은 원 사용자 문맥은 받되 source agent의 handoff tool/function artifacts는 받지 않는다.
- participant 능력 제약을 만족하지 않으면 build가 실패한다.
- pending request가 있는 상태에서는 handoff가 발생하지 않는다.

**근거** [18 Handoff](../upstream/snapshots/d0a4165f/features/18-orchestrations.md)

---

## WF-033 group-chat은 단일 orchestrator 계약과 no-self-echo 규칙을 갖는다

**요구사항.** Group-chat builder는 정확히 하나의 orchestrator 또는 manager source를 요구하고, 현재
speaker의 응답을 자기 자신에게 다시 broadcast하지 말아야 하며, 같은 speaker를 즉시 다시 고르면 종료해야 한다.

**원본 비교**

- .NET: `GroupChatHost`가 no-self-echo와 same-speaker termination guard를 구현한다.
- Python: builder가 exactly-one orchestrator config와 participant uniqueness를 검증한다.

**판단.** 두 원본의 계약을 합친다. group-chat의 핵심은 중앙 선택자다. orchestrator source가 둘 이상이면
의미가 모호해지고, self-echo는 무한 루프와 토큰 낭비를 부른다.

**수용 기준**

- orchestrator/manager 구성은 정확히 하나만 허용된다.
- 현재 speaker의 메시지는 다른 participant에게만 broadcast된다.
- manager가 같은 speaker를 즉시 다시 선택하면 workflow는 종료 또는 fail-safe 경로로 전이한다.
- 기본 terminal output 정책이 host/orchestrator 쪽에 고정된다.

**근거** [18 Group-chat](../upstream/snapshots/d0a4165f/features/18-orchestrations.md)

---

## WF-034 magentic은 단일 manager 계약과 plan review·replan 흐름을 갖는다

**요구사항.** Magentic builder는 manager source를 정확히 하나만 허용하고, plan review pause,
progress ledger 기반 stall/loop 감지, reset+replan 경로를 공개 계약으로 가져야 한다.

**원본 비교**

- .NET: `RequirePlanSignoff` 기본 true와 progress ledger failure recovery를 가진다.
- Python: exactly-one manager source를 강제하고 plan review는 opt-in이다.

**판단.** Java는 exactly-one manager source contract를 강하게 채택하고, plan review와 replan 흐름은
명시적 정책으로 노출한다. planning-centric orchestration이므로 manager SPI가 핵심이다.

**수용 기준**

- manager source는 instance, factory, manager-agent, manager-agent-factory 중 정확히 하나만 허용된다.
- plan review가 켜져 있으면 initial plan 뒤 외부 review request가 발생한다.
- progress ledger가 stall 또는 loop를 감지하면 reset+replan 경로로 전이한다.
- 기본 terminal output 정책이 manager 전용으로 고정된다.

**근거** [18 Magentic](../upstream/snapshots/d0a4165f/features/18-orchestrations.md)

### 선언형

---

## WF-035 선언형 워크플로는 분리된 모듈, 안전한 상태 경로, typed handler SPI를 가진다

**요구사항.** 선언형 표면은 workflow declarative와 agent-assets declarative를 분리된 모듈로 두고,
`schema AST → normalized model → graph lowering` 파이프라인, safe state path와 Env allowlist,
typed HTTP/MCP/agent handler SPI, build-time handler 존재 검증, 승인 재개 시 secret redaction과
`original_request` 기반 재실행을 함께 제공해야 한다.

**원본 비교**

- .NET: workflow declarative가 typed handler interface와 source-generated JSON metadata를 중심에 둔다.
- Python: workflow/agent asset을 분리하고 path safety, Env allowlist, handler 존재 검증, approval binding tests를 가진다.

**판단.** 선언형은 하나의 기능이 아니라 여러 보안 경계의 묶음이다. user 지시대로 workflow 하위 프로젝트에
포함하되, 모듈 분리와 보안 기본값을 강하게 둔다. 코드와 테스트가 문서보다 더 강한 근거이므로 안전한 기본값을
선택한다.

**수용 기준**

- workflow declarative와 agent-asset declarative가 다른 모듈 또는 안정도 표기를 가진다.
- workflow YAML은 비어 있는 actions를 허용하지 않고, 필요한 HTTP/MCP handler가 없으면 build가 실패한다.
- 상태 경로는 unsafe attribute traversal을 거부하고 `Env` 노출은 allowlist 또는 safe mode로 제어한다.
- MCP approval surface는 secret header 값을 노출하지 않는다.
- approval resume는 `original_request` payload의 함수명과 인자를 사용한다.

**근거** [19 Declarative](../upstream/snapshots/d0a4165f/features/19-declarative.md), [16 declarative approval/HITL 흐름](../upstream/snapshots/d0a4165f/features/16-workflow-checkpoint-hitl.md)

---

## 이 문서가 다루지 않는 것

| 주제 | 소유 문서 |
| --- | --- |
| 단일 에이전트 실행과 모델 호출 | [01 에이전트 실행과 모델 호출](01-agent-execution.md) |
| 일반 도구 호출 루프와 하네스 승인 계층 | [04 도구 정의와 실행 루프](04-tools.md), [08 하네스 기능](08-harness.md) |
| 세션 저장소와 대화 이력 | [06 세션과 대화 상태](06-sessions.md) |
| 호스팅 프로토콜과 원격 실행 어댑터 | [10 호스팅과 프로토콜](10-hosting.md) |
| 운영 관찰성, 배포, 보안 운영 | [11 운영 품질](11-operations.md) |
