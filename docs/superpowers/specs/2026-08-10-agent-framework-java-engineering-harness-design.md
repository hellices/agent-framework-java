# Agent Framework for Java 엔지니어링 하네스 설계

- 상태: 검토 대기
- 작성일: 2026-08-10
- 적용 기준: Microsoft Agent Framework upstream
  `d0a4165f170193ba1d026a259af40d35bb7eaefe`
- 범위: 저장소 지침, Java 품질 게이트, 호환성 하네스, 에이전트 작업 그래프,
  하네스 회귀, GitHub Actions와 AKS ARC 운영 경계

## 1. 목적

이 저장소는 Microsoft Agent Framework(MAF)의 관찰 가능한 실행 의미론을 Java로
구현한다. 코어는 Spring Boot, Quarkus, Jakarta EE, Micronaut 또는 특정 DI 컨테이너에
종속되지 않아야 하며, 각 프레임워크는 호스트 또는 선택적 통합 모듈로만 참여한다.

엔지니어링 하네스의 목적은 다음 네 가지다.

1. 사람과 코딩 에이전트가 같은 아키텍처·품질·검증 규칙을 사용한다.
2. Java 코어와 각 프레임워크 통합의 경계를 자동으로 검증한다.
3. 고정된 MAF upstream의 동작과 Java 구현의 차이를 재현 가능하게 추적한다.
4. 하네스 자체의 지시문, 작업 그래프와 평가 결과가 퇴행하지 않게 한다.

하네스는 제품 런타임의 일부가 아니다. 제품 artifact에는 코딩 에이전트, CI runner,
평가 서비스 또는 특정 벤더의 agent SDK가 포함되지 않는다.

## 2. 전제와 잠정 결정

현재 저장소에는 승인된 기초 설계와 upstream snapshot 문서만 존재한다. Maven 프로젝트,
Java 소스, GitHub Actions와 Git 메타데이터는 아직 없다.

다음 결정을 이 설계의 기본값으로 사용한다.

- 최소 지원 Java는 17이다.
- CI는 Eclipse Temurin JDK 17, 21, 25에서 검증한다.
- Java 17을 지원하는 동안 테스트 기반은 JUnit 5 계열을 사용한다. Java 21이 필요한
  JUnit 6은 채택하지 않는다.
- Maven 3.9 계열과 Maven Wrapper를 사용한다. Maven 4는 GA와 플러그인 호환성이 확인된
  뒤 별도 결정으로 다룬다.
- `AGENTS.md`를 저장소의 벤더 중립 지침 원본으로 사용한다.
- 일반 Java 빌드는 비특권 ARC runner에서 실행한다. Docker가 필요한 작업만 별도
  privileged runner로 격리한다.
- 정확한 플러그인 버전은 구현 시 Maven Central과 공식 릴리스를 다시 확인하고 하나의
  버전 카탈로그 역할을 하는 root POM 속성에 고정한다.

## 3. 검토한 접근법

### 3.1 저장소 단일형

모든 agent 설정, 평가 실행기, ARC Helm values, Kubernetes 정책과 CI를 이 저장소에서
관리한다.

장점은 한 저장소만으로 전체 구성을 발견할 수 있다는 것이다. 단점은 애플리케이션
변경과 cluster 운영 권한이 결합되고, ARC credential과 runner 보안 변경이 제품 PR의
영향 범위에 들어오며, 다른 저장소에서 재사용하기 어렵다는 것이다.

### 3.2 조직 플랫폼 중앙형

품질과 agent workflow 대부분을 조직 공용 reusable workflow 또는 별도 플랫폼 저장소에
둔다.

장점은 여러 저장소에서 일관성을 유지하기 쉽다는 것이다. 단점은 현재 저장소만 checkout한
개발자가 실제 규칙과 실행 경로를 이해하거나 재현하기 어렵고, 중앙 변경이 이 저장소의
검증을 예고 없이 바꿀 수 있다는 것이다.

### 3.3 계층형 이식 가능 하네스

제품 계약, 검증 설정과 workflow entry point는 저장소에 둔다. 개인 설정은 로컬에,
runner와 cluster 보안은 플랫폼 영역에 둔다. 중앙 reusable workflow를 사용하더라도
호출 버전을 고정하고 로컬에서 같은 Maven 명령을 실행할 수 있게 한다.

**결정:** 3.3을 채택한다. 저장소 재현성과 운영 분리를 함께 만족하며, 향후 조직 공용
workflow로 승격할 때도 제품 규칙을 잃지 않는다.

## 4. 소유권 경계

### 4.1 저장소에서 관리

다음 자산은 제품 변경과 함께 review되어야 하므로 저장소에 포함한다.

- `AGENTS.md`와 벤더별 얇은 지침 adapter
- Maven Wrapper, root parent POM과 BOM
- `.editorconfig`, `.gitattributes`, formatter와 lint 설정
- compiler, Enforcer, dependency, static analysis와 architecture 규칙
- unit, property, contract, integration과 conformance tests
- MAF test vector의 provenance와 upstream pin
- agent harness artifact schema와 deterministic eval fixture
- GitHub Actions workflow, composite action과 Dependabot 설정
- CODEOWNERS, dependency review, CodeQL과 OpenSSF Scorecard 설정
- 공개 API 호환성 예외와 보안 suppression
- 릴리스 SBOM과 provenance 생성 규칙

모든 suppression은 규칙 ID, 이유, 범위와 제거 조건을 기록한다. 날짜 없는 전역
suppression은 허용하지 않는다.

### 4.2 로컬에서 관리

다음 자산은 개인 환경, credential 또는 실행 비용과 연관되므로 커밋하지 않는다.

- 개인 agent 권한과 모델 선택
- API key, GitHub token, Azure credential
- IDE별 사용자 설정
- 로컬 Maven repository와 build cache
- agent 실행 trace 원본과 임시 worktree
- 로컬 OpenTelemetry Collector endpoint
- 개인별 비용·turn 제한과 실험 모델 canary

저장소에는 비밀이 없는 예제 파일만 둘 수 있다. 실제 파일 이름은 `.gitignore`에
명시하고, 누락 시 secret scanning이 실패시켜야 한다.

### 4.3 플랫폼 또는 별도 인프라 저장소에서 관리

다음 자산은 애플리케이션 저장소에 포함하지 않는다.

- ARC controller와 runner scale set의 Helm release 및 values
- ARC GitHub App private key와 Kubernetes Secret
- runner image build와 image admission 정책
- AKS namespace, RBAC, Pod Security Admission과 NetworkPolicy
- CI 전용 node pool, cluster autoscaler와 Spot 정책
- Azure Monitor 또는 Prometheus 수집 설정
- GitHub runner group과 organization action policy
- Entra federated identity credential과 Azure role assignment

애플리케이션 workflow는 runner label이라는 안정된 계약만 참조한다. runner의 pod spec,
credential과 cluster 권한을 추론하거나 생성하지 않는다.

## 5. `AGENTS.md` 설계

### 5.1 역할

`AGENTS.md`는 긴 개발 교과서가 아니라 이 저장소에서 작업하기 위한 실행 계약이다. 사람도
검토할 수 있고 서로 다른 agent 제품에서도 같은 의미를 유지해야 한다.

다음 내용을 포함한다.

1. 저장소 목적과 현재 구현 단계
2. authoritative design 및 upstream snapshot 링크
3. 모듈 책임과 금지 의존성
4. Java·Maven 기준과 표준 명령
5. 변경 전 탐색, 테스트 우선과 검증 절차
6. task 위험 등급과 승인 조건
7. MAF conformance 및 provenance 규칙
8. 보안, 민감정보와 telemetry 규칙
9. 문서와 공개 API 변경 규칙
10. 완료 증거 형식

특정 agent 제품의 도구 이름, 모델 이름, 토큰 가격 또는 사용자 홈 경로는 넣지 않는다.

### 5.2 핵심 지침 초안

최종 `AGENTS.md`는 다음 규칙을 명시한다.

#### 저장소 정체성

- 이 프로젝트는 특정 서버 프레임워크가 아니라 임베드 가능한 `AgentEngine`을 제공한다.
- API 이름의 직역보다 고정 upstream의 관찰 가능한 실행 의미론을 우선한다.
- 코어는 DI container, HTTP server, executor, scheduler 또는 shutdown hook을 소유하지
  않는다.

#### 의존성 규칙

- API는 Spring, Quarkus, Jakarta EE, Micronaut와 provider SDK에 의존하지 않는다.
- Engine은 API와 명시적으로 승인된 최소 공통 라이브러리에만 의존한다.
- Adapter는 공개 port만 구현하며 engine internal package를 참조하지 않는다.
- Host integration은 조립만 담당하고 core가 host를 역참조하지 않는다.
- Sample과 testkit은 제품 artifact의 의존 대상이 아니다.

#### 작업 절차

- 변경 전 관련 설계, public contract, 사용처와 인접 테스트를 읽는다.
- 동작 변경은 재현 테스트 또는 실패하는 contract test를 먼저 만든다.
- 기존 helper와 fixture를 검색한 뒤 재사용 또는 공용화한다.
- 변경 범위를 affected module로 제한한다.
- 좁은 검증에서 시작하고 변경 위험에 따라 matrix와 deep 검증으로 확장한다.
- 테스트를 삭제하거나 `@Disabled`로 바꾸어 실패를 숨기지 않는다.

#### 완료 기준

- 요청한 동작이 공개 API 또는 관찰 가능한 event/state로 검증되었다.
- formatter, compiler, unit, architecture와 affected contract test가 통과했다.
- 공개 API, dependency, upstream conformance 또는 instruction 변경이면 해당 전용
  gate도 통과했다.
- 실행한 명령, 결과와 실행하지 못한 검증을 최종 응답에 명시한다.

#### 금지 사항

- 비밀, prompt 본문, tool argument 또는 model response를 기본 telemetry에 기록하지 않는다.
- 실패를 성공 형태로 바꾸는 broad catch 또는 silent fallback을 추가하지 않는다.
- upstream `main`의 현재 상태를 pin된 snapshot과 혼합하지 않는다.
- provider 전용 동작을 검증 없이 core 공통 계약으로 승격하지 않는다.
- agent가 release, force push, infrastructure 변경 또는 suppression 확대를 자동 승인하지
  않는다.

### 5.3 벤더별 adapter

벤더별 파일은 `AGENTS.md`를 복제하지 않는다.

- `CLAUDE.md`: `AGENTS.md`를 우선 읽으라는 짧은 연결과 Claude 전용 로컬 파일 이름만 기록
- `GEMINI.md`: 같은 방식의 연결
- `.github/copilot-instructions.md`: 같은 방식의 연결

도구별 지침 차이는 최소화한다. symlink는 Windows checkout과 일부 agent loader에서
동작이 다를 수 있으므로 작은 일반 파일을 사용한다.

## 6. Java 품질 하네스

### 6.1 빌드 기준

- Maven Wrapper와 wrapper distribution checksum을 커밋한다.
- 모든 dependency와 plugin version을 root POM에서 관리한다.
- compiler는 `--release 17`, UTF-8과 합리적인 `-Xlint`를 사용한다.
- warning-as-error는 처음부터 전역 적용하지 않는다. 새 코드에서 신호가 안정된 category부터
  승격한다.
- `project.build.outputTimestamp`와 reproducible archive 설정을 사용한다.
- Enforcer로 Java/Maven 범위, plugin version, release dependency와 bytecode level을
  검증한다.
- dependency convergence는 프레임워크 BOM 조합에서 오탐이 생길 수 있으므로 module별
  의도와 함께 적용한다.

`.mvn/jvm.config`에는 실제로 필요한 옵션만 둔다. 테스트 도구를 위해 근거 없이
`--add-opens`, attach 허용 또는 javac internal export를 전역 추가하지 않는다.

### 6.2 품질 도구의 역할

| 도구 | 역할 | 초기 적용 |
| --- | --- | --- |
| Spotless | 결정적 Java/POM/기타 텍스트 포맷 | PR 필수 |
| Checkstyle | 공개 API Javadoc, naming, import와 금지 패턴 | 좁은 규칙으로 PR 필수 |
| Maven Enforcer | toolchain, dependency와 plugin 정책 | PR 필수 |
| Maven Dependency Plugin | 미선언·미사용 dependency | PR 필수, 명시적 예외 허용 |
| PMD | source-level bug pattern과 복잡도 | baseline 생성 후 PR 필수 |
| SpotBugs | bytecode-level 결함 | baseline 생성 후 PR 필수 |
| ArchUnit | framework 중립성과 module dependency | 첫 코드부터 PR 필수 |
| JaCoCo | coverage 관찰과 ratchet | 보고부터 시작해 diff ratchet |
| Revapi | 공개 API binary/source compatibility | 첫 릴리스 이후 필수 |
| PIT | state machine test의 결함 검출력 | main/scheduled 및 release |
| CycloneDX | aggregate SBOM | package/release |
| CodeQL/dependency review | SAST와 신규 dependency 위험 | GitHub PR/schedule |

Error Prone과 NullAway는 높은 신호를 제공하지만 JDK별 javac internal access와 annotation
processor 조합을 복잡하게 한다. Java 17·21·25 matrix에서 안정성과 false positive를
측정한 뒤 선택적으로 승격한다. Nullness annotation을 공개 API에 도입할 때는 JSpecify와
각 프레임워크의 nullness 해석 호환성을 별도 결정한다.

OWASP Dependency-Check는 NVD API와 CPE matching의 운영 부담이 크므로 모든 로컬
`verify`에 결합하지 않는다. GitHub dependency review와 CodeQL을 PR 기본선으로 사용하고,
Dependency-Check 또는 Trivy SBOM scan은 scheduled/release에서 운영한 뒤 신호 품질에
따라 승격한다.

### 6.3 테스트 계층

#### Unit

- JUnit 5 계열과 AssertJ를 사용한다.
- core 상태 전이는 deterministic fake model/tool/session으로 검증한다.
- Mockito는 외부 adapter 경계에서만 제한적으로 사용한다.
- 시간, ID, scheduler와 random source는 주입한다.

#### Property

jqwik으로 다음 불변식을 검증한다.

- session serialize/deserialize round-trip
- tool call/result pairing 보존
- 취소 이후 추가 state commit 금지
- 동일 scripted provider 입력의 event sequence 결정성
- workflow graph가 추가되면 fan-in, checkpoint와 resume 불변식

seed와 축소된 counterexample을 CI artifact에 기록한다.

#### Architecture

ArchUnit과 Maven module graph 검증으로 다음을 막는다.

- core에서 framework/provider package 참조
- adapter에서 engine internal package 참조
- 제품에서 sample/testkit 참조
- core에서 executor, server, DI container 생성
- 공개 API에 framework type 노출

단순 package 이름 휴리스틱만 사용하지 않고 module dependency와 bytecode import를 함께
검증한다.

#### Integration

- Maven Failsafe와 별도 integration-test module을 사용한다.
- Spring Boot, Quarkus, Jakarta EE/MicroProfile와 Micronaut adapter는 실제 container 또는
  framework test harness로 조립과 lifecycle 위임을 검증한다.
- 외부 저장소가 필요한 session adapter만 Testcontainers를 사용한다.
- container가 필요 없는 테스트를 privileged runner로 보내지 않는다.

#### Compatibility

지원 범위를 모든 버전의 cartesian product로 만들지 않는다.

- core: JDK 17, 21, 25
- 각 framework adapter: 문서화한 최소 지원선과 현재 지원선
- provider: 공용 provider contract suite
- session store: 공용 persistence contract suite
- sample: Maven Invoker 또는 독립 sample build

framework BOM 업데이트는 최소선과 현재선 모두 통과해야 한다.

### 6.4 Maven 검증 profile

| Profile/명령 | 목적 | 실행 시점 |
| --- | --- | --- |
| `fast` | format check, compile, unit, ArchUnit | 로컬 반복 |
| 기본 `verify` | static analysis, unit/property/contract, coverage | 모든 trusted PR |
| `compat` | JDK와 framework 지원 matrix | 영향 PR, main, schedule |
| `deep` | Testcontainers, MAF conformance, reproducibility | main, nightly |
| `mutation` | engine 핵심 상태 전이 PIT | engine 변경, nightly |
| `release` | Revapi, full matrix, SBOM, source/javadoc, signing | release |

명령의 실제 형태는 구현 계획에서 확정한다. 모든 CI job은 로컬에서 실행 가능한 Maven
명령을 호출하며 CI에만 존재하는 품질 로직을 만들지 않는다.

## 7. MAF 호환성 하네스

### 7.1 기준

MAF upstream은 commit SHA로 고정한다. 문서보다 production source와 conformance test를
우선한다. 현재 .NET의 `AgentConformance.IntegrationTests`는 provider 구현이 상속하는
abstract conformance suite 구조를 제공한다.

Java testkit도 같은 패턴을 사용한다.

- 공용 abstract contract suite
- adapter가 구현하는 fixture interface
- deterministic fake fixture
- 실제 provider integration fixture
- test vector와 upstream provenance metadata

실제 provider test의 재시도 성공과 deterministic test의 안정성을 혼동하지 않는다.
deterministic suite는 retry 없이 통과해야 한다. live provider suite의 retry 횟수와 첫
실패는 결과에 기록한다.

### 7.2 초기 golden scenario

#### Upstream 동등 시나리오

1. 입력 없는 run이 실패하지 않는다.
2. 문자열 입력 응답과 agent ID가 올바르다.
3. message 입력과 복수 message 입력이 같은 의미를 유지한다.
4. 두 turn 후 session history가 user/assistant 네 message를 순서대로 가진다.
5. 위 시나리오의 streaming 결과를 이어 붙이면 기대 텍스트를 포함한다.
6. agent instruction만으로 실행할 수 있다.
7. 단일 및 연속 tool 호출이 결과와 history에 올바르게 연결된다.
8. streaming tool 호출이 동일 의미를 가진다.

#### Java 엔진 필수 확장

9. session serialization round-trip 후 같은 history와 state version을 가진다.
10. tool 실패, 승인 거절과 반복 제한이 typed failure로 전파된다.
11. streaming cancellation 후 event와 session commit 경계가 일관된다.
12. backpressure 또는 느린 subscriber가 event ordering을 깨지 않는다.
13. 동일 idempotency key 재실행이 중복 tool side effect를 만들지 않는다.
14. telemetry에서 span parentage가 올바르고 민감정보는 기본 비활성화된다.

workflow는 MVP 이후 독립 설계이므로 지금 빈 module이나 통과하는 placeholder test를
만들지 않는다.

### 7.3 정규화와 provenance

호환성 비교는 다음을 분리한다.

- 필수 invariant: role, message 수와 순서, agent ID, tool pairing, finish reason, state change
- 허용 변동: 자연어 문구, provider usage의 선택 필드, chunk boundary
- 금지 변동: event 순서 역전, 중복 tool 실행, 취소 후 commit, 민감정보 span

각 vector는 upstream repository, commit, path, test 이름, derivation method와 확인일을
기록한다. MIT source를 직접 번역할 때는 license notice 의무를 지킨다. 가능하면 test
본문을 복사하지 않고 관찰 가능한 요구사항에서 Java 관용 test를 독립 작성한다.

### 7.4 upstream delta

주간 workflow는 현재 pin과 upstream 후보 SHA 사이에서 다음 경로의 변경을 탐지한다.

- .NET abstraction과 conformance tests
- Python core public type와 conformance/evaluation tests
- 공식 specification과 decision 문서

변경 발견은 자동 구현이나 pin 이동이 아니라 issue 또는 review artifact를 만든다.
사람이 동작 차이와 Java 영향을 승인한 뒤 snapshot, matrix와 pin을 원자적으로 갱신한다.

## 8. 에이전트 작업 그래프

### 8.1 원칙

- 무제한 self-loop가 아니라 명시적 DAG를 사용한다.
- node는 하나의 책임과 작은 입출력 계약을 가진다.
- edge는 대화 전체가 아니라 검증 가능한 artifact를 전달한다.
- 읽기, 쓰기, 검증과 release 권한을 분리한다.
- 실패 또는 누락된 evidence는 성공 형태로 대체하지 않는다.
- 작업 위험과 크기에 따라 그래프를 축약하되 필수 gate는 우회하지 않는다.

### 8.2 표준 DAG

```text
intake
  |
  v
risk-and-scope
  |
  +------> context --------+
  |                        |
  +------> impact ---------+--> plan
                                  |
                                  v
                            failing-test
                                  |
                                  v
                              implement
                                  |
             +--------------------+--------------------+
             |                    |                    |
             v                    v                    v
         build-test          static-arch         conformance
             |                    |                    |
             +--------------------+--------------------+
                                  |
                                  v
                         read-only-review
                                  |
                                  v
                         evidence-and-score
                                  |
                      +-----------+-----------+
                      |                       |
                      v                       v
                 human-gate              PR/complete
```

### 8.3 Artifact 계약

저장소에는 JSON Schema 또는 동등한 portable schema를 둔다.

- `TaskIntent`: 요청, 비목표, success criteria
- `ChangeContext`: 관련 설계, module, symbol과 기존 test
- `ImpactSet`: 변경 가능 경로, 영향 module과 risk tier
- `TestPlan`: 먼저 실패해야 하는 test와 이후 검증 명령
- `ChangeSummary`: 변경 파일, 공개 계약과 migration 영향
- `VerificationResult`: 명령, exit code, duration과 artifact link
- `ReviewResult`: correctness/security finding과 disposition
- `RunScore`: 결과, scope 준수, 비용·시간과 flake 정보

run별 artifact 원본은 session/local 또는 CI artifact storage에 두고 커밋하지 않는다.
schema, fixture와 익명화된 regression baseline만 저장소에 둔다.

### 8.4 위험 기반 경로

| 등급 | 예 | 필수 경로 |
| --- | --- | --- |
| 낮음 | 오탈자, 링크, 비동작 문서 | context, targeted check, review |
| 보통 | 내부 구현, test, adapter 변경 | 전체 표준 DAG |
| 높음 | 공개 API, engine state, session format, dependency | 전체 DAG + compatibility + human gate |
| 치명 | release, credential, workflow 권한, ARC/infra | 자동 실행 금지 또는 별도 승인 workflow |

POM 변경 자체를 모두 치명으로 보지 않는다. dependency 범위, build plugin 실행 권한,
release credential 접근 여부로 위험을 분류한다.

### 8.5 권한과 실패 복구

- context/impact/review node는 read-only다.
- implement node는 `ImpactSet` 범위만 수정한다.
- verify node는 source를 수정하지 않는다.
- commit, push, release와 infrastructure 변경은 별도 권한이며 기본 비활성화한다.
- build 실패는 원인 evidence와 함께 implement로 한 번 돌아갈 수 있다.
- 같은 실패를 반복하거나 scope가 커지면 중단하고 사람에게 escalation한다.
- resume은 대화 메모리가 아니라 검증된 artifact와 repository state를 기준으로 한다.

## 9. 하네스 회귀 전략

### 9.1 세 계층

#### 계층 A: 결정적 저장소 fixture

LLM과 외부 network 없이 동작한다.

- 아키텍처 위반 탐지
- 잘못된 module 영향 분석
- 금지 파일 수정 차단
- verification evidence 누락 탐지
- deterministic fake 기반 MAF scenario

모든 PR에서 실행하며 재시도하지 않는다.

#### 계층 B: pin된 upstream conformance

MAF snapshot의 invariant를 Java fixture와 adapter에 적용한다. upstream pin, Java harness
version과 vector provenance를 결과에 포함한다.

engine, API, provider, session 또는 conformance vector 변경 시 실행한다.

#### 계층 C: agent instruction과 tool-use eval

작은 임시 저장소 fixture에서 실제 agent가 다음 작업을 수행하게 한다.

- framework type을 core에 추가하라는 잘못된 요청 거절
- engine bug 재현 test와 수정
- provider adapter 추가 시 contract suite 재사용
- POM dependency 변경의 영향 및 검증 선택
- 실패하는 CI의 원인 분리
- upstream delta를 구현 변경 없이 보고

외부 agent 호출은 trusted trigger, schedule 또는 수동 dispatch에서만 실행한다. fork PR은
credential이 필요한 eval을 실행하지 않는다.

### 9.2 grader

hard gate는 가능한 한 결정적이어야 한다.

- build/test exit code
- 수정 파일이 `ImpactSet` 안에 있는지
- 금지 dependency 또는 tool 사용이 없는지
- 요구된 test와 evidence가 존재하는지
- 공개 동작과 MAF invariant가 맞는지
- secret 또는 민감 prompt가 artifact에 없는지

LLM grader는 설명 품질처럼 결정적으로 검증하기 어려운 항목의 보조 신호로만 사용한다.
LLM grader 단독으로 merge를 차단하지 않는다.

정확한 자연어, 완전 동일한 diff 또는 exact tool-call sequence는 hard gate로 사용하지 않는다.
허용 도구 집합, 위험한 도구의 부재와 검증 결과를 평가한다.

### 9.3 baseline과 차등 평가

baseline key는 다음을 포함한다.

- `AGENTS.md` hash
- harness schema/version
- eval fixture version
- model/provider/version
- tool runtime version
- JDK/Maven version
- upstream MAF SHA

지시문, graph, grader, model 또는 toolchain 변경은 같은 fixture를 old/new 구성으로 실행해
차이를 비교한다. 비교 지표는 pass rate뿐 아니라 scope violation, unsafe action, duration,
turn/tool count와 비용을 포함한다.

### 9.4 flake와 canary

- deterministic suite의 허용 flake는 0이다.
- live agent/provider suite는 동일 조건을 여러 번 실행하고 Wilson interval 또는 최소
  표본 기반 pass-rate를 기록한다.
- 실패를 retry로 숨기지 않고 first-attempt와 eventual-pass를 분리한다.
- 새 model, instruction 또는 graph는 대표 suite canary 후 전체 suite로 확장한다.
- 기존 안정 suite의 pass rate 하락, scope violation 발생 또는 비용의 큰 증가는 승격을
  막는다.
- 개인별 절대 비용 숫자는 커밋하지 않고 CI budget policy와 추세 기준을 분리한다.

### 9.5 scorecard와 보존

각 run은 다음 최소 지표를 남긴다.

- suite와 fixture version
- pass/fail 및 grader별 결과
- first-attempt와 retry 결과
- changed file/module
- forbidden-action count
- duration, turn/tool count와 선택적 비용
- model/toolchain/upstream identity
- redacted trace 위치

trace에는 source diff와 credential이 포함될 수 있으므로 접근 권한, 보존 기간과 redaction을
적용한다. 공개 artifact에는 prompt/model response 원문을 기본 포함하지 않는다.

## 10. GitHub Actions와 AKS ARC

### 10.1 workflow 구성

저장소에는 다음 workflow entry point를 둔다.

| Workflow | Trigger | 역할 |
| --- | --- | --- |
| `ci.yml` | pull request, push | fast/default verify |
| `compatibility.yml` | 영향 PR, main, schedule, dispatch | JDK/framework matrix |
| `security.yml` | PR, main, schedule | dependency review, CodeQL, SBOM scan |
| `harness-regression.yml` | harness/instruction 변경, schedule | 계층 A-C eval |
| `upstream-watch.yml` | schedule, dispatch | pin 대비 MAF delta 보고 |
| `release.yml` | tag 또는 protected dispatch | full gate, publish, attest |

중복 step은 repository composite action 또는 SHA로 pin된 reusable workflow로 추출한다.
reusable workflow를 외부 저장소에서 호출하면 commit SHA를 고정한다.

### 10.2 runner 분리

#### `arc-java-build`

- 일반 Maven build와 non-container test
- ephemeral, non-privileged
- read-only root filesystem과 최소 service account 검토
- internal service 접근 기본 차단
- `minRunners: 0`에서 시작하고 queue latency로 조정

#### `arc-java-dind`

- Testcontainers와 불가피한 image build만 실행
- 별도 namespace, runner group과 node pool
- privileged Pod Security 범위 최소화
- metadata endpoint와 Kubernetes API 접근 차단
- trusted branch/approved PR만 허용
- `minRunners: 0`

#### `arc-java-release`

- protected environment와 repository만 접근
- 일반 PR job을 받지 않음
- OIDC와 short-lived credential만 사용
- artifact publish 이외의 cluster 권한 없음

현재 제품은 Java library이므로 deploy runner를 미리 만들 필요가 없다. 실제 Azure 배포
대상이 생길 때 별도 설계한다.

### 10.3 fork와 신뢰 경계

- 외부 fork code를 privileged 또는 internal-network ARC runner에서 실행하지 않는다.
- 공개 저장소라면 fork PR의 최소 검증은 GitHub-hosted runner로 실행하거나 maintainer
  승인 후 제한된 quarantine runner를 사용한다.
- `pull_request_target`에서 PR head를 checkout하고 build하지 않는다.
- low-trust job의 artifact와 cache를 trusted job에서 실행 파일로 사용하지 않는다.
- agent eval과 live provider test는 fork PR에서 실행하지 않는다.

### 10.4 workflow 보안

- workflow-level `GITHUB_TOKEN`은 `contents: read`로 시작한다.
- job에 필요한 permission만 승격한다.
- 모든 action은 full commit SHA에 고정하고 Dependabot으로 갱신한다.
- checkout credential persistence를 필요하지 않으면 비활성화한다.
- Azure와 artifact attestation은 GitHub OIDC를 사용한다.
- release는 GitHub Environment reviewer와 branch/tag 제한을 사용한다.
- stale PR run은 concurrency group으로 취소하되 release run은 취소하지 않는다.
- cache에는 credential, Maven settings secret 또는 build output을 저장하지 않는다.

### 10.5 cache와 artifact

- Maven dependency cache는 GitHub cache service를 사용한다.
- cache key는 OS, JDK, root POM과 module POM hash를 포함한다.
- framework compatibility job은 framework line을 key에 포함한다.
- build artifact, test report, SBOM, mutation report와 scorecard는 retention을 구분한다.
- release JAR와 SBOM은 provenance attestation을 생성한다.
- 임시 runner pod 로그는 pod 삭제 전에 Azure Monitor 또는 동등 수집기로 전송한다.

## 11. Graph engineering 운영 규칙

그래프 최적화 목표는 node 수가 아니라 실패를 일찍, 싸고 설명 가능하게 발견하는 것이다.

### 11.1 변경 영향 선택

module dependency graph와 변경 경로로 검증을 선택한다.

- 문서만 변경: link/format/instruction 관련 검사
- API 변경: 모든 downstream adapter compile, Revapi와 public contract
- Engine 변경: 전체 golden scenario, property, mutation 대상
- Provider 변경: provider contract와 공용 golden scenario
- Session 변경: serialization, concurrency와 migration
- Build/harness/instruction 변경: 전체 deterministic harness와 대표 live canary

path filter만으로 안전을 판단하지 않는다. root POM, BOM, public API와 shared testkit 변경은
전체 downstream을 확장한다.

### 11.2 fan-out과 fan-in

독립적인 build, static analysis와 conformance는 병렬 실행한다. fan-in은 모든 필수 결과가
존재하고 성공해야 통과한다. `if: always()`는 report upload에는 사용할 수 있지만 실패한
검증을 성공으로 바꾸는 데 사용하지 않는다.

### 11.3 예산

- PR: 빠른 feedback과 필수 correctness
- main/nightly: broad matrix, mutation과 live conformance
- release: full matrix, reproducibility, API compatibility와 supply chain

시간과 비용 예산을 넘기면 검사 삭제보다 change-impact selection, cache, test split과
deterministic fake 비율을 먼저 개선한다.

## 12. 단계별 도입

### 단계 0: 저장소와 계약

- Git 저장소 초기화 및 기본 branch/protection 준비
- `AGENTS.md`, license, contribution와 security policy
- Maven Wrapper, Java 17 baseline, root parent/BOM
- formatter, Enforcer, reproducible build
- CI의 non-privileged `arc-java-build` smoke test

종료 조건은 로컬과 ARC에서 동일한 wrapper 명령이 재현되는 것이다.

### 단계 1: 첫 vertical slice 품질

- API, engine과 testkit 최소 module
- JUnit/AssertJ, deterministic fake와 upstream golden scenario 일부
- ArchUnit 경계와 Spotless
- PMD/SpotBugs는 report mode로 baseline 수집
- JaCoCo report와 diff ratchet 기반선

종료 조건은 일반 응답과 단일 tool loop가 Java 17·21·25에서 결정적으로 통과하는 것이다.

### 단계 2: contract와 matrix

- provider 및 session store contract suite
- property tests와 streaming cancellation
- Spring Boot/Quarkus/Jakarta EE/Micronaut integration skeleton은 실제 구현되는 순서에만 추가
- 최소·현재 framework version matrix
- PMD/SpotBugs의 신규 위반 차단

종료 조건은 core 변경이 모든 구현된 adapter에 대해 같은 public contract를 검증하는 것이다.

### 단계 3: compatibility와 agent harness

- 전체 초기 MAF conformance vector와 provenance
- agent graph artifact schema와 deterministic eval fixture
- `harness-regression` 및 `upstream-watch`
- instruction baseline과 scorecard

종료 조건은 `AGENTS.md` 또는 graph 변경의 안전성·성공률 차이를 재현할 수 있는 것이다.

### 단계 4: 공급망과 release

- Revapi와 SemVer policy
- CycloneDX SBOM, dependency review, CodeQL과 Scorecard
- reproducible build 비교
- protected release, OIDC, signing과 artifact attestation
- engine 대상 PIT threshold

종료 조건은 release artifact의 source, dependency, API와 build provenance를 확인할 수 있는
것이다.

### 단계 5: 최적화

- 실제 duration과 queue latency 기반 job split
- ARC node pool과 cache tuning
- Error Prone/NullAway pilot 결과에 따른 승격 결정
- live agent eval canary와 flake budget 조정
- 중앙 reusable workflow로 옮길 공통 부분 선별

## 13. 성공 기준

이 하네스는 다음을 증명할 수 있어야 한다.

1. core에 framework dependency를 추가한 test change가 PR에서 실패한다.
2. 같은 agent scenario가 standalone과 구현된 host adapter에서 같은 invariant를 만족한다.
3. pin된 MAF conformance 변경은 자동 구현되지 않고 review 가능한 delta로 나타난다.
4. instruction 변경 전후 agent eval의 성공, 범위 위반, 비용과 시간을 비교할 수 있다.
5. 일반 PR은 privileged runner나 Azure credential 없이 검증된다.
6. release artifact는 API compatibility, SBOM, provenance와 full matrix 증거를 가진다.
7. 모든 필수 CI 명령을 개발자가 로컬 Maven Wrapper로 재현할 수 있다.

## 14. 명시적 비목표

- 모든 framework adapter를 첫 단계에 빈 module로 생성
- 모든 정적 분석 도구를 첫 PR부터 zero-warning hard gate로 적용
- live LLM 결과를 unit test의 기준으로 사용
- 특정 coding-agent 벤더의 SDK를 제품 build에 추가
- agent가 release 또는 cluster 운영을 무승인 수행
- application repo에서 ARC controller 또는 AKS 보안 정책을 관리
- exact prompt text 또는 exact tool sequence를 장기 호환 계약으로 사용

## 15. 참고 자료

### 프로젝트와 MAF

- [기초 설계](./2026-08-10-agent-framework-java-foundation-design.md)
- [저장소 upstream 기준](../../upstream/README.md)
- [MAF pinned RunTests.cs](https://raw.githubusercontent.com/microsoft/agent-framework/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/AgentConformance.IntegrationTests/RunTests.cs)
- [Microsoft Agent Framework workflows](https://learn.microsoft.com/en-us/agent-framework/workflows/)
- [Microsoft Agent Framework repository](https://github.com/microsoft/agent-framework)

### Java와 Maven

- [Java SE support roadmap](https://www.oracle.com/java/technologies/java-se-support-roadmap.html)
- [Maven version history](https://maven.apache.org/docs/history.html)
- [Maven reproducible builds](https://maven.apache.org/guides/mini/guide-reproducible-builds.html)
- [JUnit documentation](https://docs.junit.org/)
- [Spotless](https://github.com/diffplug/spotless)
- [Checkstyle](https://checkstyle.org/)
- [PMD](https://pmd.github.io/)
- [SpotBugs](https://spotbugs.github.io/)
- [Error Prone](https://errorprone.info/)
- [NullAway](https://github.com/uber/NullAway)
- [JSpecify](https://jspecify.dev/)
- [ArchUnit](https://www.archunit.org/)
- [jqwik](https://jqwik.net/)
- [Testcontainers for Java](https://java.testcontainers.org/)
- [JaCoCo](https://www.jacoco.org/jacoco/)
- [PIT](https://pitest.org/)
- [Revapi](https://revapi.org/)
- [CycloneDX Maven plugin](https://github.com/CycloneDX/cyclonedx-maven-plugin)

### GitHub Actions와 ARC

- [About Actions Runner Controller](https://docs.github.com/en/actions/concepts/runners/actions-runner-controller)
- [Deploying runner scale sets](https://docs.github.com/en/actions/how-tos/manage-runners/use-actions-runner-controller/deploy-runner-scale-sets)
- [GitHub Actions secure use](https://docs.github.com/en/actions/reference/security/secure-use)
- [Artifact attestations](https://docs.github.com/en/actions/how-tos/secure-your-work/use-artifact-attestations/use-artifact-attestations)
- [AKS Workload Identity](https://learn.microsoft.com/en-us/azure/aks/workload-identity-overview)
- [Kubernetes Pod Security Admission](https://kubernetes.io/docs/concepts/security/pod-security-admission/)

### Agent graph와 평가

- [Anthropic agent loop](https://platform.claude.com/docs/en/agent-sdk/agent-loop)
- [Anthropic subagents](https://platform.claude.com/docs/en/agent-sdk/subagents)
- [Anthropic hooks](https://platform.claude.com/docs/en/agent-sdk/hooks)
- [Anthropic evaluation guidance](https://docs.anthropic.com/en/docs/test-and-evaluate/develop-tests)
- [OpenAI Evals](https://github.com/openai/evals)

