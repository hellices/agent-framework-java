# 08 하네스 기능

**접두사** `HAR` · **원본 기능** [12 harness](../upstream/snapshots/d0a4165f/features/12-harness.md),
[13 skills-background-code](../upstream/snapshots/d0a4165f/features/13-skills-background-code.md)

하네스 조립, 반복 정책, 내장 제공자, 스킬, 승인 기본 조립, 선택적 실행 모듈의 계약을 정의한다.
하네스는 실행 커널이 아니라 의견이 담긴 조립 계층이다. 모델 호출, 세션, 워크플로 커널은
각 소유 문서가 맡고, 승인 상태 기계·standing rule 의미·예산 계산은 [04 도구 정의와 실행 루프](04-tools.md)와 [07 인터셉터와 컨텍스트 관리](07-interceptors.md)가
소유한다. 이 문서는 하네스가 그 계약을 어떤 기본 조합으로 켜는지만 다룬다.

## 채택 범위

이 문서의 `등급`은 [README](README.md#requirement-grades) 정의대로 기능을 만들기로 했을 때의 강제력이고, 채택 여부는 [호환성 매트릭스](../upstream/snapshots/d0a4165f/compatibility-matrix.md)를 따른다.

- 하네스(`HAR01`, `HAR02`), 스킬(`SKL01`), 백그라운드(`BKG01`)는 모두 채택 `보류`다.
- 따라서 이 문서 전체는 MVP 이후에만 검토하는 보류 범위다.

## 요약

| ID | 요구사항 | 채택 | 등급 | 단계 |
| --- | --- | --- | --- | --- |
| HAR-001 | 하네스는 조립 계층으로만 둔다 | 보류 | 필수 | Core+ |
| HAR-002 | 기본 하네스 조립은 보수적 opt-in 정책을 따른다 | 보류 | 필수 | Core+ |
| HAR-003 | 하네스는 잘못된 조합을 생성 시점에 거부한다 | 보류 | 필수 | Core+ |
| HAR-004 | 자동 반복은 predicate 미들웨어 seam으로 시작한다 | 보류 | 권장 | Core+ |
| HAR-005 | 반복은 승인 대기에서 즉시 멈춘다 | 보류 | 필수 | Core+ |
| HAR-006 | Todo 제공자는 세션 상태 저장만 코어에 포함한다 | 보류 | 필수 | Core+ |
| HAR-007 | Todo 조작 결과는 안정된 계약을 가진다 | 보류 | 권장 | Core+ |
| HAR-008 | Mode 제공자는 기본 `plan` 모드와 외부 변경 알림을 제공한다 | 보류 | 필수 | Core+ |
| HAR-009 | File memory는 세션 범위를 기본값으로 하고 예약 이름을 막는다 | 보류 | 필수 | Core+ |
| HAR-010 | File access는 별도 선택 모듈로만 제공한다 | 보류 | 선택 | Optional |
| HAR-011 | Tool approval은 대기열과 standing rule을 세션에 유지한다 | 보류 | 필수 | Core+ |
| HAR-012 | Tool approval 규칙은 인자와 호스트 경계를 정확히 구분한다 | 보류 | 필수 | Core+ |
| HAR-013 | 승인 재진입은 같은 요청 예산 안에서 계산한다 | 보류 | 권장 | Core+ |
| HAR-014 | Skills는 progressive disclosure와 세 도구 표면을 유지한다 | 보류 | 권장 | Optional |
| HAR-015 | Skill script 실행은 기본 승인 필요다 | 보류 | 필수 | Optional |
| HAR-016 | 파일 기반 skills와 상세 오류는 신뢰 경계를 넘지 않게 다룬다 | 보류 | 필수 | Optional |
| HAR-017 | Background agents는 MVP에 넣지 않고 나중에도 polling registry로 시작한다 | 보류 | 권장 | Optional |
| HAR-018 | 셸 실행은 별도 tools 모듈에서 수동 조립한다 | 보류 | 선택 | Optional |
| HAR-019 | 셸과 로컬 실행의 거부 목록은 가드레일로만 문서화한다 | 보류 | 필수 | Optional |
| HAR-020 | LocalCodeAct는 샌드박스로 취급하지 않고 코어에서 제외한다 | 보류 | 선택 | Optional |
| HAR-021 | 샌드박스형 CodeAct 백엔드는 별도 선택 모듈로 분리한다 | 보류 | 선택 | Optional |

---

## HAR-001 하네스는 조립 계층으로만 둔다

**요구사항.** Java 하네스는 실행 커널을 다시 구현하지 않고 기존 에이전트, 컨텍스트 제공자,
미들웨어, 승인 계층을 조립하는 표면으로만 제공해야 한다.

**원본 비교**

- .NET: `HarnessAgent`가 chat client stack, context providers, decorator를 조립한다.
- Python: `create_harness_agent`가 history, providers, middleware, tools를 조립한다.

**판단.** 두 원본 모두 하네스를 orchestration kernel이 아니라 opinionated assembly layer로 둔다.
Java도 이 경계를 유지해야 `AgentEngine`이 호스트 책임을 가져오지 않는다. 하네스가 커널까지
흡수하면 세션, 워크플로, 공급자 경계가 흐려진다.

**수용 기준**

- 하네스 API는 기존 `Agent`와 컨텍스트 제공자 조합 결과를 반환한다.
- 하네스 모듈이 독자적인 모델 호출 루프나 세션 저장 형식을 새로 정의하지 않는다.
- 하네스 없이도 같은 코어 `Agent`를 직접 조립해 실행할 수 있다.

**근거** [12 하네스 조립](../upstream/snapshots/d0a4165f/features/12-harness.md)

---

## HAR-002 기본 하네스 조립은 보수적 opt-in 정책을 따른다

**요구사항.** 기본 하네스 조립은 todo, mode, file memory, approval만 자동 포함하고, skills,
file access, background agents, shell, code execution은 명시적으로 요청할 때만 붙여야 한다.

**원본 비교**

- .NET: 현재 작업 디렉터리 기반 skills를 기본으로 붙이고 file access, background는 옵션으로 둔다.
- Python: skills, file access, background, shell을 모두 명시적 opt-in으로 둔다.

**판단.** Java는 Python 쪽 기본값을 택한다. 더 안전한 기본값이 우선이고, 하네스는 연구용 편의
계층이라 자동 확장을 최소화하는 편이 낫다. 파일 접근과 스크립트 실행을 기본 활성화하면 호스트가
모르는 신뢰 경계를 만든다.

**수용 기준**

- 기본 하네스 생성만으로 skills, file access, background, shell 도구가 노출되지 않는다.
- 해당 기능은 전용 옵션이나 모듈 의존성을 준 경우에만 조립된다.
- 기본 조립 결과에 todo, mode, file memory, approval이 포함된다.

**근거** [12 하네스 조립](../upstream/snapshots/d0a4165f/features/12-harness.md), [13 공통 비교](../upstream/snapshots/d0a4165f/features/13-skills-background-code.md)

---

## HAR-003 하네스는 잘못된 조합을 생성 시점에 거부한다

**요구사항.** 하네스 옵션이 서로 모순되면 Java는 첫 실행 전이 아니라 생성 시점에 실패해야 한다.

**원본 비교**

- .NET: 잘못된 context/output token 조합을 constructor 단계에서 거부한다.
- Python: 잘못된 토큰 조합을 하네스 조립 단계에서 거부한다.

**판단.** 동일하다. 하네스는 조립 계층이므로 오류를 늦게 드러낼 이유가 없다. 실행 도중 실패하게
두면 사용자 메시지와 상태를 소비한 뒤에야 잘못된 구성임을 알게 된다.

**수용 기준**

- 잘못된 옵션 조합으로 하네스를 만들면 예외가 즉시 발생한다.
- 실패 전까지 모델 호출이나 세션 변경이 일어나지 않는다.
- 동일한 잘못된 조합은 완료 실행과 스트리밍 실행 모두에서 같은 예외 범주로 보고된다.

**근거** [12 하네스 조립](../upstream/snapshots/d0a4165f/features/12-harness.md)

---

## HAR-004 자동 반복은 predicate 미들웨어 seam으로 시작한다

**요구사항.** Java 하네스의 자동 반복은 MVP에서 단일 predicate 미들웨어 seam으로 제공하고,
정렬된 evaluator chain은 후속 단계로 남겨야 한다.

**원본 비교**

- .NET: ordered evaluator chain으로 반복 여부를 결정한다.
- Python: `should_continue` predicate와 helper 함수 조합으로 반복을 구현한다.

**판단.** Java는 구현 난이도와 설명 가능성을 위해 Python 모델을 먼저 택한다. 하네스는 코어 실행
커널이 아니므로 초기에 복잡한 evaluator 우선순위 체계를 도입할 필요가 없다. 다만 후속 단계에서
체인 모델로 확장할 수 있게 seam은 열어 둔다.

**수용 기준**

- 반복 여부를 결정하는 공개 seam이 단일 predicate 인터페이스로 존재한다.
- 반복 seam은 다음 입력 메시지를 반환하거나 중단을 선택할 수 있다.
- evaluator chain 전용 공개 타입은 Core+ 초기 단계에 필수로 요구되지 않는다.

**근거** [12 자동 반복 정책](../upstream/snapshots/d0a4165f/features/12-harness.md)

---

## HAR-005 반복은 승인 대기에서 즉시 멈춘다

**요구사항.** 반복 중 승인 대기 요청이 생기면 하네스는 즉시 실행을 멈추고 호출자에게 그 요청을
반환해야 한다.

**원본 비교**

- .NET: loop evaluator가 pending approval을 만나면 반복을 멈춘다.
- Python: loop helper가 pending approval을 만나면 caller에 approval request를 돌려준다.

**판단.** 동일하다. 승인 대기 중에 다음 반복을 계속 돌리면 아직 결정되지 않은 side effect 위에
새 상태를 쌓게 된다. 승인 계층은 하네스의 안전 조정자이므로 반복보다 우선한다.

**수용 기준**

- 승인 요청이 생긴 run은 같은 run 안에서 다음 반복을 시작하지 않는다.
- 호출자는 대기 중인 승인 요청 하나를 즉시 관찰할 수 있다.
- 승인 응답을 주기 전까지 같은 요청에 대한 추가 자동 반복이 발생하지 않는다.

**근거** [12 자동 반복 정책](../upstream/snapshots/d0a4165f/features/12-harness.md), [12 도구 승인](../upstream/snapshots/d0a4165f/features/12-harness.md)

---

## HAR-006 Todo 제공자는 세션 상태 저장만 코어에 포함한다

**요구사항.** Java 코어 하네스는 세션 상태 기반 Todo 제공자만 포함하고, 파일 기반 Todo 저장은
별도 선택 기능으로 남겨야 한다.

**원본 비교**

- .NET: todo provider와 관련 테스트가 안정된 표면을 가진다.
- Python: 세션 상태 저장과 파일 저장을 모두 제공하지만 file-backed store는 더 넓은 운영 표면을 가진다.

**판단.** Java는 세션 상태 경로만 코어에 넣는다. file-backed store는 경로 안전성, rename 실패,
crash safety를 함께 설계해야 하므로 하네스 Core+ 범위를 넘는다. loop와 mode 통합에는 세션 상태
모델만으로 충분하다.

**수용 기준**

- 코어 하네스 모듈은 세션 상태에 Todo 목록을 저장한다.
- 코어 하네스 모듈이 파일 경로를 요구하지 않는다.
- 파일 기반 Todo 저장은 별도 모듈 없이는 사용할 수 없다.

**근거** [12 Todo provider](../upstream/snapshots/d0a4165f/features/12-harness.md)

---

## HAR-007 Todo 조작 결과는 안정된 계약을 가진다

**요구사항.** Todo 추가와 완료 도구는 증가하는 식별자 부여와 존재하지 않는 항목에 대한 0건 완료
반환을 고정 계약으로 가져야 한다.

**원본 비교**

- .NET: `todos_add` 증가 ID와 `todos_complete`의 0 반환을 테스트로 고정한다.
- Python: 같은 동작을 따르며 file-backed 저장의 내구성까지 추가로 검증한다.

**판단.** 동일하다. 하네스 루프와 프롬프트는 Todo 도구를 결정적 상태 기계처럼 다룬다. 존재하지
않는 ID를 예외로 바꾸면 모델이 보정하기 어려운 실패가 된다.

**수용 기준**

- 한 번의 추가 호출에서 생성된 Todo ID는 중복되지 않는다.
- 나중에 추가한 Todo의 ID는 앞선 ID보다 크다.
- 존재하지 않는 ID 집합을 완료하면 반환값은 0이다.

**근거** [12 Todo provider](../upstream/snapshots/d0a4165f/features/12-harness.md)

---

## HAR-008 Mode 제공자는 기본 `plan` 모드와 외부 변경 알림을 제공한다

**요구사항.** Mode 제공자는 기본 모드를 `plan`으로 시작하고, 외부에서 모드가 바뀌면 다음 turn에
사용자 역할 알림으로 그 변경을 주입해야 한다.

**원본 비교**

- .NET: 기본 mode를 `plan`으로 두고 잘못된 mode 설정을 거부한다.
- Python: 외부 helper가 mode를 바꾸면 다음 run 전에 notification을 주입한다.

**판단.** Java는 두 원본의 계약을 합친다. 기본 모드를 고정해야 프롬프트 앵커가 생기고, 외부 변경
알림이 있어야 시스템 지시만으로는 부족한 mode 전환을 모델이 관찰할 수 있다.

**수용 기준**

- 새 세션에서 mode를 읽으면 `plan`이다.
- 허용되지 않은 mode 설정은 실패하고 기존 mode를 보존한다.
- 외부 helper가 mode를 바꾸면 다음 turn 입력에 변경 알림 메시지가 포함된다.

**근거** [12 Mode provider](../upstream/snapshots/d0a4165f/features/12-harness.md)

---

## HAR-009 File memory는 세션 범위를 기본값으로 하고 예약 이름을 막는다

**요구사항.** File memory 제공자는 기본 namespace를 세션 범위로 잡고, 내부 예약 파일 이름은 저장을
거부해야 한다.

**원본 비교**

- .NET: 기본 namespace 초기화와 예약 이름 검증을 제공한다.
- Python: `session_id` 범위 namespace와 `memories.md` 주입, 예약 이름 거부를 테스트로 고정한다.

**판단.** Java는 Python의 세션 범위 기본값을 택한다. timestamp+GUID 기본값보다 재현성과 디버깅이
좋다. 또한 내부 인덱스와 설명 파일 이름을 열어 두면 모델이 메모리 시스템 파일을 오염시킬 수 있다.

**수용 기준**

- 별도 shared scope를 주지 않으면 같은 세션 안에서만 같은 memory namespace를 본다.
- 메모리 저장 뒤 다음 run에서 `memories.md` 인덱스가 컨텍스트로 주입된다.
- `memories.md`와 `*_description.md` 이름으로는 저장할 수 없다.

**근거** [12 File memory](../upstream/snapshots/d0a4165f/features/12-harness.md)

---

## HAR-010 File access는 별도 선택 모듈로만 제공한다

**요구사항.** File access 제공자는 Java 코어 하네스에 포함하지 않고, 전용 선택 모듈에서 명시적
opt-in으로만 제공해야 한다.

**원본 비교**

- .NET: file access 관련 옵션이 experimental이고 기본은 approval-required다.
- Python: file access는 opt-in이며 경로 안전성과 정규식 guard를 함께 구현한다.

**판단.** 둘 다 기능은 있지만 표면이 넓다. shared mutable state, 승인 경계, symlink 차단,
regex timeout까지 묶여 있어 코어 하네스에 넣기에는 위험하다. Java는 별도 모듈로 분리한다.

**수용 기준**

- 코어 하네스 의존성만으로 file access 도구를 사용할 수 없다.
- file access를 쓰려면 별도 모듈과 명시적 설정이 필요하다.
- file access 모듈 기본값은 approval-required다.

**근거** [12 File access](../upstream/snapshots/d0a4165f/features/12-harness.md)

---

## HAR-011 Tool approval은 대기열과 standing rule을 세션에 유지한다

**요구사항.** 기본 하네스 approval 조립은 [04 도구 정의와 실행 루프](04-tools.md)와 [07 인터셉터와 컨텍스트 관리](07-interceptors.md)가 정의한 승인 규칙 상태와 대기 중 요청 상태를 세션 저장 경로에 연결하고, 호출자 표면에는 한 번에 하나의 pending request만 노출해야 한다.

**원본 비교**

- .NET: approval agent가 rules와 queued state를 세션에 저장하고 one-by-one surface를 사용한다.
- Python: approval middleware가 `rules`, `queued_approval_requests`, `collected_approval_responses`를 상태에 저장한다.

**판단.** 여러 승인 요청을 한 번에 노출하지 않는 surface는 유지하되, 상태 기계의 의미 자체는 소유 문서를 재정의하지 않는다. 하네스는 approval 컴포넌트를 세션 backing store와 기본 UI surface에 올리는 조립 책임만 가진다.

**수용 기준**

- 기본 하네스 preset으로 approval을 켜면 core approval 상태가 세션 직렬화 경로에 포함된다.
- 동시에 여러 pending request가 있어도 하네스 호출 표면은 가장 앞선 요청 하나만 반환한다.
- 세션을 저장 후 복원한 다음 같은 pending approval run을 다시 읽으면 같은 요청이 다시 표면화된다.

**근거** [12 도구 승인](../upstream/snapshots/d0a4165f/features/12-harness.md)

---

## HAR-012 Tool approval 규칙은 인자와 호스트 경계를 정확히 구분한다

**요구사항.** 기본 하네스의 자동 승인 wiring은 [04 도구 정의와 실행 루프](04-tools.md)가 정의한 exact-argument·host-boundary 규칙을 그대로 사용해야 하며, 이름만 맞으면 승인되는 더 느슨한 하네스 전용 shortcut을 추가하지 않아야 한다.

**원본 비교**

- .NET: name-based auto-approval 충돌 위험을 경고하고 all-tools 규칙을 신뢰 환경으로 제한한다.
- Python: argument-scoped rule과 hosted `server_label` 범위를 테스트로 고정한다.

**판단.** exact-argument와 host-boundary의 의미는 코어 승인 문서가 이미 정의한다. 하네스는 그 의미를 다시 풀어쓰지 않고, 기본 preset이 같은 규칙 세트를 켜도록 조립만 고정해야 한다.

**수용 기준**

- 기본 approval preset은 exact-argument matching을 끈 name-only standing rule 모드를 추가로 만들지 않는다.
- hosted 도구를 조립할 때 하네스는 core approval 상태에 `server_label` 또는 동등한 호스트 경계 식별자를 함께 전달한다.
- 같은 도구 이름이라도 인자나 호스트 경계가 달라지면 하네스 기본 preset은 새 approval 요청을 다시 표면화한다.

**근거** [12 도구 승인](../upstream/snapshots/d0a4165f/features/12-harness.md)

---

## HAR-013 승인 재진입은 같은 요청 예산 안에서 계산한다

**요구사항.** 기본 하네스의 approval re-entry wiring은 [04 도구 정의와 실행 루프](04-tools.md)와 [07 인터셉터와 컨텍스트 관리](07-interceptors.md)가 정의한 공용 요청 예산을 그대로 공유해야 하며, 하네스 계층이 승인 재진입 전용 별도 budget counter를 만들지 않아야 한다.

**원본 비교**

- .NET: approval loop에 별도 `MaxAutoApprovalIterations` cap을 둔다.
- Python: function invocation budget state를 approval 재진입과 공유한다.

**판단.** 예산 의미는 코어 실행 루프가 소유하고 하네스는 그 위에 별도 상태 기계를 얹지 않는다. 필요하면 운영 안전장치로 외곽 cap을 둘 수 있지만, 기본 조립은 core budget semantics를 그대로 따라야 설명 가능성이 유지된다.

**수용 기준**

- approval-enabled 하네스 preset은 자동 승인 재진입용 독립 budget counter를 추가로 만들지 않는다.
- 자동 승인 뒤 이어진 도구 호출은 같은 core 남은 예산을 소모한다.
- 선택적 outer cap이 있더라도 core usage 집계와 차감 규칙은 바꾸지 않고 추가 중단 조건으로만 동작한다.

**근거** [12 Invocation budget](../upstream/snapshots/d0a4165f/features/12-harness.md), [12 도구 승인](../upstream/snapshots/d0a4165f/features/12-harness.md)

---

## HAR-014 Skills는 progressive disclosure와 세 도구 표면을 유지한다

**요구사항.** Skills 기능은 기본 프롬프트에는 이름과 설명만 광고하고, 실제 본문과 자원과 스크립트는
`load_skill`, `read_skill_resource`, `run_skill_script` 세 도구로만 늦게 노출해야 한다.

**원본 비교**

- .NET: source에서 skills를 읽어 prompt와 세 도구를 만든다.
- Python: `before_run`에서 skills를 읽어 system prompt와 세 도구를 만든다.

**판단.** 동일하다. skills는 단순 프롬프트 조각이 아니라 실행 자산까지 품는다. progressive disclosure가
없으면 모든 세부 문서와 스크립트가 기본 프롬프트를 오염시키고 승인 경계도 흐려진다.

**수용 기준**

- skill이 없으면 skills 관련 prompt와 도구가 주입되지 않는다.
- skill이 있으면 공개 도구 이름은 정확히 세 개다.
- skill 본문은 `load_skill` 호출 전까지 전체 프롬프트에 직접 삽입되지 않는다.

**근거** [13 Skills](../upstream/snapshots/d0a4165f/features/13-skills-background-code.md)

---

## HAR-015 Skill script 실행은 기본 승인 필요다

**요구사항.** `run_skill_script`는 기본적으로 승인 필요 도구여야 하며, 읽기 전용 skills 도구와
독립된 승인 정책을 가져야 한다.

**원본 비교**

- .NET: 세 skills 도구 각각에 개별 approval wrapper를 붙일 수 있다.
- Python: `load_skill`, `read_skill_resource`, `run_skill_script` 각각의 approval disable flag를 둔다.

**판단.** 동일하다. 읽기와 실행을 같은 신뢰 수준으로 취급하면 안 된다. Java는 `run_skill_script`를
기본 승인 필요로 유지하고, read-only 도구만 별도 자동 승인 규칙을 허용한다.

**수용 기준**

- `run_skill_script`는 별도 설정이 없으면 approval-required다.
- `load_skill`과 `read_skill_resource`는 독립적으로 자동 승인 정책을 줄 수 있다.
- 읽기 도구 자동 승인 설정이 script 실행 승인까지 확장되지 않는다.

**근거** [13 Skills](../upstream/snapshots/d0a4165f/features/13-skills-background-code.md)

---

## HAR-016 파일 기반 skills와 상세 오류는 신뢰 경계를 넘지 않게 다룬다

**요구사항.** 파일 기반 skills source는 traversal과 symlink escape를 막아야 하고, 상세 예외를 모델에
그대로 돌려주는 옵션은 기본적으로 꺼야 한다.

**원본 비교**

- .NET: file skills source가 traversal/symlink를 검사하고 `IncludeDetailedErrors`를 trusted source로 제한한다.
- Python: file source가 traversal/symlink를 방어하고 external source를 trust boundary로 문서화한다.

**판단.** 동일하다. skills source 자체가 신뢰 경계다. 경로 탈출과 상세 예외 재주입은 prompt injection과
비밀 노출 통로가 될 수 있다. Java는 상세 오류를 선택 기능으로만 두고 기본값은 안전 쪽으로 둔다.

**수용 기준**

- 파일 기반 skills 검색이 루트 밖 경로나 symlink escape를 따라가지 않는다.
- 기본 설정에서 예외 message 원문이 모델 출력 경로로 자동 재주입되지 않는다.
- 상세 오류 노출을 켜려면 명시적 옵션이 필요하다.

**근거** [13 Skills](../upstream/snapshots/d0a4165f/features/13-skills-background-code.md)

---

## HAR-017 Background agents는 MVP에 넣지 않고 나중에도 polling registry로 시작한다

**요구사항.** Background agents는 Java 코어 하네스 MVP에 포함하지 않으며, 후속 단계에 도입하더라도
실시간 push가 아닌 polling task registry 계약부터 시작해야 한다.

**원본 비교**

- .NET: background provider와 completion evaluator가 experimental이다.
- Python: background agents가 experimental이며 tool-polled registry와 LOST 상태를 사용한다.

**판단.** 둘 다 experimental이고 runtime handle/session handle 유지가 필요하다. restart 뒤 LOST semantics,
child agent trust boundary, clear/continue 규칙을 함께 설계해야 하므로 Core+에 넣기 어렵다.

**수용 기준**

- 코어 하네스 모듈은 background task 도구를 기본 제공하지 않는다.
- 후속 모듈이 추가되더라도 상태 조회 기본 계약은 polling API다.
- runtime reference를 잃은 task는 명시적 `LOST` 상태로 전이한다.

**근거** [13 Background agents](../upstream/snapshots/d0a4165f/features/13-skills-background-code.md)

---

## HAR-018 셸 실행은 별도 tools 모듈에서 수동 조립한다

**요구사항.** 셸 실행과 셸 환경 제공자는 하네스 본체가 아니라 별도 tools 모듈에 두고, 호출자가 이를
수동 조립해야 한다.

**원본 비교**

- .NET: shell은 별도 패키지이며 하네스가 자동 wiring하지 않는다.
- Python: tools 패키지에 shell을 두고, 하네스는 `shell_executor`를 받으면 자동 wiring할 수 있다.

**판단.** Java는 .NET 쪽 모듈 경계를 택한다. 셸은 호스트 의존성과 보안 설명이 크다. 하네스 본체에
넣으면 조립 계층이 실행 계층의 책임까지 가져오게 된다.

**수용 기준**

- 셸 실행 타입은 하네스 코어가 아니라 별도 모듈에 정의된다.
- 하네스 기본 조립은 셸 도구를 자동 추가하지 않는다.
- 셸 환경 제공자는 선택적으로 컨텍스트 제공자로 붙일 수 있다.

**근거** [13 Shell environment / shell executors](../upstream/snapshots/d0a4165f/features/13-skills-background-code.md)

---

## HAR-019 셸과 로컬 실행의 거부 목록은 가드레일로만 문서화한다

**요구사항.** 셸 정책의 denylist와 로컬 실행 제한은 보안 경계로 주장하지 않고, 승인 기반 가드레일과
추가 격리를 전제로 문서화해야 한다.

**원본 비교**

- .NET: local shell approval loop를 보안 경계로 보고 denylist 충돌 위험을 경고한다.
- Python: `ShellPolicy`가 security boundary가 아니라 guardrail임을 테스트로 고정한다.

**판단.** user 지시대로 이 입장을 정확히 반영한다. denylist는 우발적 실수를 줄이는 장치일 뿐,
적대적 입력을 완전히 막지 못한다. Java 문서가 이를 과장하면 호스트가 잘못된 신뢰를 갖는다.

**수용 기준**

- 공개 문서에 denylist나 policy가 보안 경계가 아님을 명시한다.
- local shell에서 승인 비활성화는 explicit unsafe acknowledgement 없이는 허용되지 않는다.
- 보안 설명이 추가 격리나 승인 절차를 대체한다고 주장하지 않는다.

**근거** [13 Shell environment / shell executors](../upstream/snapshots/d0a4165f/features/13-skills-background-code.md)

---

## HAR-020 LocalCodeAct는 샌드박스로 취급하지 않고 코어에서 제외한다

**요구사항.** LocalCodeAct 류의 로컬 subprocess 코드 실행은 샌드박스가 아니라고 명시하고 Java 코어와
기본 하네스에서 제외해야 한다.

**원본 비교**

- .NET: LocalCodeAct는 preview이고 외부 격리가 이미 있는 환경만 대상으로 한다.
- Python: inspected 범위에 LocalCodeAct 대응 구현이 없다.

**판단.** 더 안전한 기본값을 택한다. sandbox처럼 보이는 이름의 로컬 실행 기능은 가장 위험한 오해를
부른다. 외부 VM이나 컨테이너가 이미 있는 환경이 아니면 제공하지 않는 편이 낫다.

**수용 기준**

- 코어 하네스와 기본 배포물에 로컬 subprocess 코드 실행 기능이 포함되지 않는다.
- 문서에 이 기능이 sandbox가 아니라고 명시한다.
- 후속 모듈이 생겨도 외부 격리 선행 조건을 타입이나 설정 문서에 강하게 요구한다.

**근거** [13 LocalCodeAct](../upstream/snapshots/d0a4165f/features/13-skills-background-code.md)

---

## HAR-021 샌드박스형 CodeAct 백엔드는 별도 선택 모듈로 분리한다

**요구사항.** Hyperlight나 Monty 같은 코드 실행 백엔드는 하네스 본체와 분리된 선택 모듈로 두고,
첫 구현부터 승인 번들링과 파일 staging 안전성을 강제해야 한다.

**원본 비교**

- .NET: Hyperlight는 preview sandbox backend이며 approval mode와 provider-owned tool registry를 묶는다.
- Python: Hyperlight와 Monty는 beta backend이고 mount, approval, symlink-safe capture를 각각 고정한다.

**판단.** sandbox backend는 하네스 convenience 기능이 아니라 별도 실행 계층이다. Java는 backend-first
전략으로 모듈을 분리하고, 승인 계산과 safe staging을 초기에 강제해야 한다. Monty는 .NET parity도 없어
더욱 Optional이 적절하다.

**수용 기준**

- 코드 실행 backend는 하네스 코어 의존성에 포함되지 않는다.
- provider-owned tool 중 하나라도 승인 필요면 `execute_code`도 승인 필요가 된다.
- 입력 staging과 출력 capture는 symlink 또는 reparse point escape를 차단한다.

**근거** [13 Hyperlight CodeAct](../upstream/snapshots/d0a4165f/features/13-skills-background-code.md), [13 Monty CodeAct](../upstream/snapshots/d0a4165f/features/13-skills-background-code.md)

---

## 이 문서가 다루지 않는 것

| 주제 | 소유 문서 |
| --- | --- |
| 일반 도구 호출 루프와 기본 승인 모델 | [04 도구 정의와 실행 루프](04-tools.md) |
| 세션 직렬화와 저장소 | [06 세션과 대화 상태](06-sessions.md) |
| 인터셉터와 컨텍스트 컴팩션 | [07 인터셉터와 컨텍스트 관리](07-interceptors.md) |
| 워크플로 그래프와 런타임 | [09 워크플로와 오케스트레이션](09-workflows.md) |
| 호스팅 환경과 프로토콜 어댑터 | [10 호스팅과 프로토콜](10-hosting.md) |
| 운영 정책, 관찰성, 보안 운영 | [11 운영 품질](11-operations.md) |
