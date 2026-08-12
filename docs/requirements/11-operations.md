# 11 운영 품질

**접두사** `OPS` · **원본 기능** [27 observability](../upstream/snapshots/d0a4165f/features/27-observability.md),
[28 errors-resilience-security](../upstream/snapshots/d0a4165f/features/28-errors-resilience-security.md),
[29 evaluation-testing](../upstream/snapshots/d0a4165f/features/29-evaluation-testing.md),
[30 packaging-compatibility](../upstream/snapshots/d0a4165f/features/30-packaging-compatibility.md)

관찰성, 오류 분류, 복원력 경계, 평가, 테스트, 패키징, 호환성 계약을 정의한다. 실행 모델과 프로토콜 자체는 [01 에이전트 실행과 모델 호출](01-agent-execution.md)과 [10 호스팅과 프로토콜](10-hosting.md)이 소유하고, 이 문서는 그 위에 놓이는 운영 품질 규칙만 확정한다.

## 채택 범위

이 문서의 `등급`은 [README](README.md#요구사항-등급) 정의대로 기능을 만들기로 했을 때의 강제력이고, 채택 여부는 [호환성 매트릭스](../upstream/snapshots/d0a4165f/compatibility-matrix.md)를 따른다.

- 기본 운영 품질(`OBS01`, `ERR01`, `SEC01`, `TEST01`, `PKG01`)은 채택 `필수`다.
- 선택 관찰성 확장(`OBS02`)과 평가 기능(`EVAL01`)은 채택 `선택`이다.

## 요약

| ID | 요구사항 | 채택 | 등급 | 단계 |
| --- | --- | --- | --- | --- |
| OPS-001 | OpenTelemetry GenAI 규약을 표준으로 삼는다 | 필수 | 필수 | Core+ |
| OPS-002 | 관찰성은 bootstrap과 wrapper를 분리한다 | 필수 | 필수 | Core+ |
| OPS-003 | 민감 데이터 수집은 기본 끔인 별도 opt-in이다 | 필수 | 필수 | Core+ |
| OPS-004 | logging은 tracing과 별도 계층으로 유지한다 | 필수 | 필수 | Core+ |
| OPS-005 | feature telemetry는 승인된 origin에만 실시간으로 찍는다 | 선택 | 권장 | Core+ |
| OPS-006 | 계측 비활성은 sticky 하게 유지한다 | 필수 | 필수 | Core+ |
| OPS-007 | 같은 작업을 두 계층에서 중복 계측하지 않고 카테고리별 제어를 둔다 | 필수 | 필수 | Core+ |
| OPS-008 | 공통 오류 taxonomy를 노출한다 | 필수 | 필수 | Core+ |
| OPS-009 | validation과 프로그래밍 오류는 built-in 예외로 남긴다 | 필수 | 필수 | Core+ |
| OPS-010 | 취소는 일반 실패로 번역하지 않는다 | 필수 | 필수 | Core+ |
| OPS-011 | timeout은 결과 envelope에 남긴다 | 필수 | 필수 | Core+ |
| OPS-012 | cleanup은 process tree와 remote task 단위로 수행한다 | 필수 | 필수 | Core+ |
| OPS-013 | persistent executor는 session-owned resource다 | 필수 | 필수 | Core+ |
| OPS-014 | shell과 tool 정책을 보안 경계로 포장하지 않는다 | 필수 | 필수 | Core+ |
| OPS-015 | 위험한 우회 경로에는 명시적 안전 장치를 둔다 | 필수 | 필수 | Core+ |
| OPS-016 | 재시도·타임아웃·서킷브레이커 운영 정책은 호스트가 소유한다 | 필수 | 필수 | Core+ |
| OPS-017 | 평가 SPI는 batch-oriented provider-neutral 계약을 쓴다 | 선택 | 필수 | Core+ |
| OPS-018 | evaluation converter를 1급 API로 두고 외부 전송 payload를 최소화한다 | 선택 | 필수 | Core+ |
| OPS-019 | 평가 결과는 실행 실패와 품질 실패를 분리한다 | 선택 | 필수 | Core+ |
| OPS-020 | workflow 평가는 공개 API와 per-agent subresults를 가져야 한다 | 선택 | 필수 | Workflow |
| OPS-021 | generated evaluator와 golden 입력은 결정적으로 재현 가능해야 한다 | 선택 | 필수 | Optional |
| OPS-022 | 공급자 공통 contract test와 golden scenario를 유지한다 | 필수 | 필수 | Core+ |
| OPS-023 | 패키징은 단일 버전 라인과 Gradle BOM을 기준으로 하고 단계 레지스트리를 따로 둔다 | 필수 | 필수 | Core+ |
| OPS-024 | 의존성 관리는 validated bounds와 공급망 pinning을 함께 쓴다 | 필수 | 필수 | Core+ |
| OPS-025 | 호환성·설치 가능성 gate를 maturity와 연결한다 | 필수 | 필수 | Core+ |
| OPS-026 | 교차 언어 호환성은 공개 surface와 행동으로 판정한다 | 필수 | 권장 | Core+ |

---

### 관찰성

## OPS-001 OpenTelemetry GenAI 규약을 표준으로 삼는다

**요구사항.** 관찰성의 표준 semantic convention은 OpenTelemetry GenAI 규약이어야 한다.
이 결정은 core 공개 API가 OpenTelemetry SDK 타입에 직접 의존한다는 뜻이 아니며, telemetry
adapter가 표준 vocabulary와 context를 변환해야 한다.

**원본 비교**

- .NET: OpenTelemetryAgent와 workflow telemetry가 GenAI semantic conventions를 전제로 span과 metric을 만든다.
- Python: observability 모듈이 tracer, meter, metrics view를 한곳에서 관리하며 같은 OTel 의미 체계를 쓴다.

**판단.** 두 원본의 공통 분모다. Java도 독자 규약을 만들지 않고 OTel GenAI를 기준으로 해야 exporter와 backend가 바로 호환된다.

**수용 기준**

- 공개 span과 metric 이름은 OTel GenAI vocabulary에 맞는다.
- provider, agent, workflow 식별 태그가 custom ad-hoc key가 아니라 표준 의미 체계 위에 놓인다.
- `agent-framework-api`와 `agent-framework-engine`의 공개 signature에 OpenTelemetry,
  Micrometer, Spring Observation 타입이 없다.

**근거** [27 observability](../upstream/snapshots/d0a4165f/features/27-observability.md)

---

## OPS-002 관찰성은 bootstrap과 wrapper를 분리한다

**요구사항.** Java 관찰성은 provider bootstrap 계층과 agent·workflow wrapper 계층을 분리해야 한다.

**원본 비교**

- .NET: builder extension이 OpenTelemetryAgent와 workflow wrapper를 붙인다.
- Python: observability.py가 provider wiring과 accessor를 중앙에서 관리한다.

**판단.** 둘의 장점을 합친다. bootstrap과 wrapper가 섞이면 app-wide 초기화와 per-run decoration이 서로를 오염시킨다.

**수용 기준**

- OTel provider 초기화 없이도 no-op wrapper를 구성할 수 있다.
- bootstrap API를 바꾸지 않고 agent 또는 workflow wrapper만 교체할 수 있다.

**근거** [27 observability](../upstream/snapshots/d0a4165f/features/27-observability.md)

---

## OPS-003 민감 데이터 수집은 기본 끔인 별도 opt-in이다

**요구사항.** message content, tool args, tool results 같은 민감 데이터 수집은 기본 비활성이고 별도 opt-in이어야 한다.

**원본 비교**

- .NET: EnableSensitiveData를 켜야 raw inputs, outputs, tool payload가 span에 들어간다.
- Python: enable_sensitive_telemetry()를 명시적으로 호출해야 민감 payload capture가 켜진다.

**판단.** 사용자 지시와 원본이 일치한다. 더 안전한 기본값을 택해 운영 환경에서 우발적 payload 유출을 막는다.

**수용 기준**

- 기본 설정의 span·log에는 원문 메시지와 tool arguments가 없다.
- 민감 데이터 capture를 켜는 설정과 끄는 설정이 테스트로 구분된다.

**근거** [27 observability](../upstream/snapshots/d0a4165f/features/27-observability.md)

---

## OPS-004 logging은 tracing과 별도 계층으로 유지한다

**요구사항.** 사람이 읽는 logging 계층은 tracing 계층과 분리하고 payload-bearing 로그는 기본 비활성으로 둬야 한다.

**원본 비교**

- .NET: LoggingAgent가 lifecycle 로그와 payload-bearing Trace 로그를 구분한다.
- Python: logger exporter는 observability bootstrap의 일부지만 민감 데이터 capture와 별도 스위치로 통제된다.

**판단.** 동일한 목적이다. tracing과 logging을 한 경로로 숨기면 운영자가 payload log를 예기치 않게 켜기 쉽다.

**수용 기준**

- Debug 수준만 켜면 lifecycle 정보만 남고 payload는 남지 않는다.
- redaction-aware serializer나 formatter를 교체할 수 있다.

**근거** [27 observability](../upstream/snapshots/d0a4165f/features/27-observability.md)

---

## OPS-005 feature telemetry는 승인된 origin에만 실시간으로 찍는다

**요구사항.** feature telemetry는 request-time live signal로 계산하되 승인된 first-party origin에만 방출해야 한다.

**원본 비교**

- .NET: 이번 스냅샷의 production code에서는 UA identity와 hosted prefix는 보이지만 Python 수준의 live feature token 전체 구현은 직접 확인되지 않는다.
- Python: process-global bitmask, approved-origin 검사, stale token stripping, opt-out env var를 실제로 구현한다.

**판단.** 확인 가능한 구현이 더 엄격한 Python 모델을 채택한다. 숨겨진 telemetry나 third-party leakage를 막는 쪽이 더 안전하다.

**수용 기준**

- feature token은 승인된 HTTPS origin이 아니면 request header에서 제거된다.
- feature telemetry 전체 opt-out과 bitmask-only opt-out을 별도로 지원한다.

**근거** [27 observability](../upstream/snapshots/d0a4165f/features/27-observability.md)

---

## OPS-006 계측 비활성은 sticky 하게 유지한다

**요구사항.** 운영자가 application-scoped instrumentation control에서 계측을 끄면 force
재활성화 전까지 framework helper가 다시 켜지 못하게 해야 한다. 상태는 host가 소유하며
JVM-global static flag로 모든 application context에 공유하지 않는다.

**원본 비교**

- .NET: builder opt-in 구조라 자동 재활성화 경로가 상대적으로 좁다.
- Python: disable_instrumentation() 뒤에는 sticky disable이 유지되고 force=True 없이는 다시 켜지지 않는다.

**판단.** Python의 운영 보호가 더 강하다. Java도 sticky disable을 채택해 예기치 않은 재계측을 막는다.

**수용 기준**

- disable 뒤에는 민감 telemetry enable이나 provider bootstrap 호출만으로 계측이 다시 켜지지 않는다.
- force 재활성화 경로가 별도 API로 드러난다.
- 같은 JVM의 독립 application context 두 개가 instrumentation 상태를 공유하지 않는다.

**근거** [27 observability](../upstream/snapshots/d0a4165f/features/27-observability.md)

---

## OPS-007 같은 작업을 두 계층에서 중복 계측하지 않고 카테고리별 제어를 둔다

**요구사항.** 같은 작업은 한 계층에서만 계측하고 workflow 계측은 카테고리별로 켜고 끌 수 있어야 한다.

**원본 비교**

- .NET: 이미 계측된 chat client는 다시 장식하지 않고 workflow telemetry에 category-level disable switch를 둔다.
- Python: 중앙 bootstrap이 tracer와 meter 구성을 통제해 중복 wiring을 피하고 broad enable/disable을 관리한다.

**판단.** 사용자 지시의 “동일 작업 중복 계측 금지”를 직접 반영한다. 중복 span은 비용과 해석 혼란만 늘린다.

**수용 기준**

- 이미 계측된 inner client나 transport를 재장식하면 no-op가 된다.
- workflow build, run, executor, message 카테고리를 독립적으로 비활성화할 수 있다.

**근거** [27 observability](../upstream/snapshots/d0a4165f/features/27-observability.md)

---

---

### 오류·복원력·보안

## OPS-008 공통 오류 taxonomy를 노출한다

**요구사항.** Java는 layer별 분기가 가능한 공통 오류 taxonomy를 노출해야 한다.

**원본 비교**

- .NET: 이번 스냅샷에서는 package별 로컬 예외 계층과 built-in 예외 혼용이 중심이다.
- Python: framework-wide exception hierarchy를 제공하고 agent, workflow, integration, tool branches를 둔다.

**판단.** 분류 가능한 taxonomy가 있어야 protocol binder와 host가 machine-readable branching을 할 수 있다. Python 구조를 기본으로 채택한다.

**수용 기준**

- 공개 예외 타입은 agent, workflow, integration, tool, provider 같은 상위 branch를 가진다.
- 호스트는 메시지 문자열 파싱 없이 예외 타입만으로 오류 분류를 할 수 있다.

**근거** [28 errors-resilience-security](../upstream/snapshots/d0a4165f/features/28-errors-resilience-security.md)

---

## OPS-009 validation과 프로그래밍 오류는 built-in 예외로 남긴다

**요구사항.** 잘못된 인자, 잘못된 상태, 구성 실수는 framework-specific domain error로 감싸지 않고 built-in 예외로 남겨야 한다.

**원본 비교**

- .NET: LocalShellExecutor가 ArgumentException과 ArgumentOutOfRangeException을 직접 사용한다.
- Python: coding standard가 ValueError, TypeError, RuntimeError 같은 built-in 경계를 명시한다.

**판단.** 두 원본의 의도는 같다. validation 오류까지 domain 예외로 감싸면 사용자가 수정해야 할 실수를 운영 실패처럼 오해한다.

**수용 기준**

- 잘못된 API 입력은 IllegalArgumentException류로 실패한다.
- domain exception hierarchy는 외부 서비스 실패나 runtime failure에만 사용된다.

**근거** [28 errors-resilience-security](../upstream/snapshots/d0a4165f/features/28-errors-resilience-security.md)

---

## OPS-010 취소는 일반 실패로 번역하지 않는다

**요구사항.** 취소는 ordinary failure와 다른 제어 신호로 유지하고 generic domain exception으로 번역하지 않아야 한다.

**원본 비교**

- .NET: MCP wrapper가 cancelled 상태를 OperationCanceledException으로 드러낸다.
- Python: declarative HTTP executor가 CancelledError를 wrapping하지 않고 그대로 전파한다.

**판단.** 사용자 지시대로 cancellation은 taxonomy에 포함되되 실패로 오염하지 않는다. 그래야 host timeout, client abort, human cancel이 정확히 구분된다.

**수용 기준**

- 취소된 실행은 generic FrameworkException으로 치환되지 않는다.
- 취소 전파 테스트가 tool transport와 workflow transport 양쪽에 존재한다.

**근거** [28 errors-resilience-security](../upstream/snapshots/d0a4165f/features/28-errors-resilience-security.md)

---

## OPS-011 timeout은 결과 envelope에 남긴다

**요구사항.** shell과 코드 실행 같은 장기 작업은 timeout 여부를 예외와 별도로 결과 envelope에 남겨야 한다.

**원본 비교**

- .NET: ShellResult가 TimedOut과 ExitCode=124 규약을 가진다.
- Python: shell executor가 timeout 후 output을 수습해 envelope로 반환하려 한다.

**판단.** 동일하다. timeout을 결과 구조에 남겨야 상위 agent와 harness가 deterministic하게 후속 정책을 적용할 수 있다.

**수용 기준**

- timeout 결과에는 timedOut=true와 표준화된 종료 정보가 포함된다.
- timeout 미발생 경로에서는 같은 필드가 false 또는 empty로 일관된다.

**근거** [28 errors-resilience-security](../upstream/snapshots/d0a4165f/features/28-errors-resilience-security.md)

---

## OPS-012 cleanup은 process tree와 remote task 단위로 수행한다

**요구사항.** cleanup은 parent process 하나가 아니라 process tree 전체와 원격 장기 task까지 포함해야 한다.

**원본 비교**

- .NET: shell이 timeout 후 process tree를 정리하고 MCP wrapper는 local cancellation 시 remote cancel을 best-effort로 시도한다.
- Python: kill_process_tree가 자식 프로세스까지 정리한다.

**판단.** 더 안전한 기본값을 택한다. cleanup이 얕으면 고아 프로세스와 고아 task가 남아 운영 리소스를 잠식한다.

**수용 기준**

- local process timeout 또는 cancel 시 descendants까지 정리된다.
- 원격 task wrapper는 local cancel 후 remote cancel을 best-effort로 시도한다.

**근거** [28 errors-resilience-security](../upstream/snapshots/d0a4165f/features/28-errors-resilience-security.md)

---

## OPS-013 persistent executor는 session-owned resource다

**요구사항.** persistent shell이나 code executor는 단일 conversation 또는 session이 소유하는 resource로 제한해야 한다.

**원본 비교**

- .NET: persistent shell session ownership과 timeout 시 interrupt-then-teardown 규칙을 둔다.
- Python: shell tooling은 stateless 중심이지만 session-level coordination과 cleanup 필요성을 sample과 policy가 드러낸다.

**판단.** 세션 소유를 강제해야 state leakage와 command interleaving을 막을 수 있다. singleton 공유는 Java에서 특히 위험하다.

**수용 기준**

- persistent executor 인스턴스는 동시에 두 session에서 공유되지 않는다.
- 세션 종료나 복구 실패 시 executor teardown 경로가 명시된다.

**근거** [28 errors-resilience-security](../upstream/snapshots/d0a4165f/features/28-errors-resilience-security.md)

---

## OPS-014 shell과 tool 정책을 보안 경계로 포장하지 않는다

**요구사항.** regex denylist, allowlist, command policy 같은 guardrail은 approval·sandbox 요구를 대체하는 보안 경계로 동작하지 않아야 한다.

**원본 비교**

- .NET: ShellPolicy가 variable expansion과 interpreter escape 우회를 직접 경고한다.
- Python: shell policy 문서가 guardrail 한계를 노골적으로 적고 approval와 sandbox를 실제 경계로 둔다.

**판단.** 우회 가능한 정책 객체만으로 신뢰 등급이 올라가면 운영자가 잘못 믿게 된다. Java는 policy-only guardrail과 실제 승인·격리 경계를 구성과 API에서 분리해 드러내야 한다.

**수용 기준**

- unattended unsafe mode 또는 approval 비활성 경로는 `acknowledgeUnsafe`와 동등한 명시 인자 없이는 생성되거나 활성화되지 않는다.
- regex denylist·allowlist·command policy 설정만으로 tool 또는 executor의 approval-required 기본값이 꺼지지 않는다.
- sandbox capability 또는 격리 backend가 없는 executor는 API나 descriptor에서 sandboxed로 보고되지 않는다.

**근거** [28 errors-resilience-security](../upstream/snapshots/d0a4165f/features/28-errors-resilience-security.md)

---

## OPS-015 위험한 우회 경로에는 명시적 안전 장치를 둔다

**요구사항.** unattended unsafe mode와 declarative state path access 같은 위험 경로에는 명시적 acknowledgement 또는 path safety 검사를 강제해야 한다.

**원본 비교**

- .NET: shell approval wrapper와 auto-approval 충돌 경고가 위험한 우회 경로를 제한한다.
- Python: LocalShellTool은 acknowledge_unsafe를 요구하고 declarative state path는 reflective escape를 차단한다.

**판단.** 둘 다 “위험한 우회는 눈에 띄게 켠다”는 방향이다. Java도 silent opt-out을 금지하고 state traversal 공격면을 줄여야 한다.

**수용 기준**

- approval을 끄는 unattended mode는 explicit acknowledgement 없이 생성되지 않는다.
- reflective escape나 path traversal에 해당하는 state path는 기본값 또는 오류로 차단된다.

**근거** [28 errors-resilience-security](../upstream/snapshots/d0a4165f/features/28-errors-resilience-security.md)

---

## OPS-016 재시도·타임아웃·서킷브레이커 운영 정책은 호스트가 소유한다

**요구사항.** 재시도, request timeout, circuit breaker 같은 운영 정책은 코어의 암묵 동작이 아니라 host 또는 adapter가 명시적으로 소유해야 한다.

**원본 비교**

- .NET: 이번 스냅샷 근거로는 framework-wide generic retry layer를 직접 확인할 수 없다.
- Python: runtime 전반 공통 retry보다 test workflow와 개별 layer 설정이 확인된다.

**판단.** 확인 불가한 자동 재시도를 요구사항으로 만들지 않는다. 사용자 지시대로 retry·timeout·circuit breaker 운영 정책은 host 소유로 고정한다.

**수용 기준**

- 코어 기본 동작에 silent retry가 없다.
- 재시도, timeout, circuit-breaker는 host 또는 adapter 설정으로만 활성화된다.

**근거** [28 errors-resilience-security](../upstream/snapshots/d0a4165f/features/28-errors-resilience-security.md)

---

---

### 평가·테스트

## OPS-017 평가 SPI는 batch-oriented provider-neutral 계약을 쓴다

**요구사항.** 평가 SPI는 batch-oriented provider-neutral 계약을 사용해야 한다.

**원본 비교**

- .NET: IAgentEvaluator가 batch 단위 EvaluateAsync(items, evalName, ...)를 사용한다.
- Python: Evaluator 모델도 EvalItem 목록을 받아 EvalResults를 돌려준다.

**판단.** 동일하다. 배치 계약이 있어야 cloud evaluator, 비용 집계, score aggregation을 효율적으로 다룬다.

**수용 기준**

- 단일 evaluator 호출로 여러 EvalItem을 평가할 수 있다.
- provider-specific evaluator도 같은 EvalItem/EvalResults 모델을 사용한다.

**근거** [29 evaluation-testing](../upstream/snapshots/d0a4165f/features/29-evaluation-testing.md)

---

## OPS-018 evaluation converter를 1급 API로 두고 외부 전송 payload를 최소화한다

**요구사항.** runtime message와 tool definition을 evaluator schema로 바꾸는 converter를 1급 API로 두고 외부 evaluator로 나가는 payload를 최소화해야 한다.

**원본 비교**

- .NET: BuildEvalItem 계열 helper가 minimal conversation과 tool definitions를 조립한다.
- Python: AgentEvalConverter가 content 변환과 unparseable tool arguments sanitization을 직접 수행한다.

**판단.** Python 쪽이 더 엄격하다. converter는 단순 포맷터가 아니라 정보 최소화 경계이므로 Java도 별도 API와 sanitization 규칙을 가져야 한다.

**수용 기준**

- converter 없이 evaluator가 runtime Message를 직접 파싱하지 않는다.
- 파싱 불가능한 tool arguments는 raw string 대신 safe placeholder로 치환된다.

**근거** [29 evaluation-testing](../upstream/snapshots/d0a4165f/features/29-evaluation-testing.md)

---

## OPS-019 평가 결과는 실행 실패와 품질 실패를 분리한다

**요구사항.** 평가 결과 모델은 evaluator 실행 실패와 품질 기준 미달을 서로 다른 상태로 표현하고 gate helper를 별도로 제공해야 한다.

**원본 비교**

- .NET: AgentEvaluationResults가 Status, Error, AssertAllPassed, AssertScoreAtLeast를 함께 가진다.
- Python: EvalResults가 status, error, raise_for_status, assert_score_at_least를 제공한다.

**판단.** infra failure와 quality failure를 섞으면 CI와 리포팅이 둘 중 하나를 잘못 삼킨다. 두 실패를 분리하는 공통 결과 모델을 유지한다.

**수용 기준**

- 평가 실행 실패는 gate failure와 다른 상태 코드나 enum으로 구분된다.
- raw 결과를 읽는 API와 gate helper API가 같은 모델 위에서 분리되어 있다.

**근거** [29 evaluation-testing](../upstream/snapshots/d0a4165f/features/29-evaluation-testing.md)

---

## OPS-020 workflow 평가는 공개 API와 per-agent subresults를 가져야 한다

**요구사항.** workflow 평가는 별도 공개 API로 제공하고 overall 결과와 per-agent subresults를 함께 반환해야 한다.

**원본 비교**

- .NET: 결과 모델은 SubResults를 준비하지만 explicit workflow evaluation public API는 이번 스냅샷 근거로 직접 확인되지 않는다.
- Python: evaluate_workflow()가 public API로 존재하고 per-agent breakdown을 채운다.

**판단.** Python이 더 완전하다. Java는 결과 타입만 준비하는 수준에 머물지 않고 explicit API까지 요구해야 한다.

**수용 기준**

- evaluateWorkflow류 공개 API가 존재한다.
- 결과 모델은 overall score와 executor 또는 agent id keyed subresults를 함께 보존한다.

**근거** [29 evaluation-testing](../upstream/snapshots/d0a4165f/features/29-evaluation-testing.md)

---

## OPS-021 generated evaluator와 golden 입력은 결정적으로 재현 가능해야 한다

**요구사항.** generated 또는 provider evaluator와 golden 입력은 버전 고정과 결정적 재현을 전제로 운영해야 한다.

**원본 비교**

- .NET: GeneratedEvaluatorRef가 version pinning을 전제로 하고 conformance traces를 disk corpus로 고정한다.
- Python: FoundryEvals가 generated rubric evaluator reference를 관리하고 tests가 deterministic inputs를 사용한다.

**판단.** 사용자 요구의 “결정적 재현”을 그대로 반영한다. latest-floating evaluator나 가변 입력은 회귀 판단을 흐리게 만든다.

**수용 기준**

- generated evaluator reference는 버전 없는 latest 기본값을 강제하지 않는다.
- golden 또는 trace corpus는 소스 관리되는 고정 입력으로 유지된다.

**근거** [29 evaluation-testing](../upstream/snapshots/d0a4165f/features/29-evaluation-testing.md)

---

## OPS-022 공급자 공통 contract test와 golden scenario를 유지한다

**요구사항.** 공급자 공통 contract test, golden scenario, protocol wire conformance test를 별도 층으로 유지해야 한다.

**원본 비교**

- .NET: agent contract integration tests와 OpenAI trace-driven conformance suite를 분리해 둔다.
- Python: aggregate tests와 provider-sharded integration jobs를 강하게 운용하지만 동급 trace-driven wire harness는 이번 스냅샷 근거로 직접 확인되지 않는다.

**판단.** Java는 둘 다 요구한다. 공통 contract test만으로는 wire regression을 못 잡고 golden만으로는 provider-neutral contract를 못 고정한다.

**수용 기준**

- provider를 바꿔도 같은 suite로 돌 수 있는 공통 contract test가 존재한다.
- 대표 시나리오는 golden 입력과 기대 결과로 고정된다.
- 프로토콜 어댑터는 trace-driven 또는 동등한 wire conformance suite를 가진다.

**근거** [29 evaluation-testing](../upstream/snapshots/d0a4165f/features/29-evaluation-testing.md)

---

---

### 패키징·호환성

## OPS-023 패키징은 단일 버전 라인과 Gradle BOM을 기준으로 하고 단계 레지스트리를 따로 둔다

**요구사항.** Java monorepo는 단일 버전 라인과 Gradle BOM을 기준으로 패키징하되 artifact metadata와 별도 lifecycle registry로 성숙도 단계를 관리해야 한다.

**원본 비교**

- .NET: 중앙 VersionPrefix와 csproj stage metadata로 버전과 단계 계산을 관리한다.
- Python: workspace package와 PACKAGE_STATUS.md로 package lifecycle을 별도 문서에 유지한다.

**판단.** 사용자 요구의 단일 버전, BOM, 성숙도 구분을 한 요구사항으로 고정한다. 버전 계산과 단계 공지는 같은 파일에 묶어 두지 않는 편이 downstream에 명확하다.

**수용 기준**

- 모든 published artifact는 같은 release train 버전을 따른다.
- 소비자는 Gradle BOM 또는 platform을 import해 tested dependency set을 받을 수 있다.
- alpha, beta, preview, released 같은 단계는 별도 lifecycle registry에서 확인할 수 있다.

**근거** [30 packaging-compatibility](../upstream/snapshots/d0a4165f/features/30-packaging-compatibility.md)

---

## OPS-024 의존성 관리는 validated bounds와 공급망 pinning을 함께 쓴다

**요구사항.** 의존성 관리는 tested version BOM과 validated bounds를 함께 두고 보안 목적 pinning을 명시적으로 기록해야 한다.

**원본 비교**

- .NET: 중앙 package management와 transitive pinning, NuGet audit를 사용한다.
- Python: dependency bounds 규칙과 constraint 또는 override dependency를 함께 관리한다.

**판단.** 둘의 장점을 합친다. tested version만 고정하면 소비자 유연성이 줄고 bounds만 두면 공급망 대응이 느려진다.

**수용 기준**

- 권장 tested version은 BOM이나 중앙 dependency file에 고정된다.
- supported bounds와 보안 목적 override 또는 pinning 변경 사유가 문서나 주석에 남는다.

**근거** [30 packaging-compatibility](../upstream/snapshots/d0a4165f/features/30-packaging-compatibility.md)

---

## OPS-025 호환성·설치 가능성 gate를 maturity와 연결한다

**요구사항.** binary 또는 API compatibility gate와 installability smoke test는 artifact maturity와 연결해 release pipeline에서 강제해야 한다.

**원본 비교**

- .NET: package validation baseline, suppression, install check를 release build에 포함한다.
- Python: release workflow가 package directory 해석과 build artifact 생성을 강제한다.

**판단.** 릴리스는 빌드 성공만으로 충분하지 않다. 실제 설치와 호환성이 깨지지 않는지까지 CI가 책임져야 한다.

**수용 기준**

- stable artifact는 binary 또는 API compatibility gate를 통과해야 한다.
- release pipeline은 예제 소비자 프로젝트에서 실제 resolve와 build를 검증한다.

**근거** [30 packaging-compatibility](../upstream/snapshots/d0a4165f/features/30-packaging-compatibility.md)

---

## OPS-026 교차 언어 호환성은 공개 surface와 행동으로 판정한다

**요구사항.** 교차 언어 호환성은 같은 버전 체계가 아니라 공개 surface, behavioral contract, facade 유지, changelog-driven review로 판정해야 한다.

**원본 비교**

- .NET: version metadata와 package validation, conformance testing으로 공개 surface를 유지한다.
- Python: repo split이 있어도 re-export와 install surface를 유지하고 changelog로 promotion과 compatibility adjustment를 기록한다.

**판단.** 사용자 요구의 “Java 관습을 따르되 동작 호환을 우선한다”를 packaging 관점에서 풀어낸다. facade 유지와 change review가 숫자 버전 일치보다 중요하다.

**수용 기준**

- repo split이나 package 이동이 생겨도 facade 또는 re-export 유지 여부가 명시된다.
- stage promotion, compatibility adjustment, central version 변경은 changelog나 동등 문서 신호로 snapshot 재검토를 유발한다.

**근거** [30 packaging-compatibility](../upstream/snapshots/d0a4165f/features/30-packaging-compatibility.md)

---

## 이 문서가 다루지 않는 것

| 주제 | 소유 문서 |
| --- | --- |
| 세션과 체크포인트의 직렬화 포맷 | [06 세션과 대화 상태](06-sessions.md) |
| 호스팅 route와 protocol binding | [10 호스팅과 프로토콜](10-hosting.md) |
| 공급자별 wire contract와 adapter surface | [12 공급자 통합](12-providers.md) |
| 개별 workflow 그래프의 비즈니스 의미 | [09 워크플로와 오케스트레이션](09-workflows.md) |
