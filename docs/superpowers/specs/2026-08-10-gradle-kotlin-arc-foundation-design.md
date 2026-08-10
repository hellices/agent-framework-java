# Gradle Kotlin DSL 및 Java ARC Foundation 설계

- 상태: 승인
- 작성일: 2026-08-10
- 개정: 2026-08-11 — 5.3 registry 결정과 신규 5.4 대상 환경을 실측값으로 확정
- 대체 범위: 기존 엔지니어링 하네스 설계의 Maven build와 범용 `aks-runners` 사용 결정
- 유지 범위: 저장소/로컬/플랫폼 소유권 분리, MAF conformance, agent DAG와 3층 회귀 전략

## 1. 결정

Agent Framework for Java의 build harness는 Gradle Kotlin DSL을 사용한다. GitHub Actions의
trusted Java 작업은 기존 범용 `aks-runners`가 아니라 별도 Java 전용 ARC scale set
`arc-java-build`에서 실행한다.

두 자산은 다음처럼 분리한다.

- 애플리케이션 저장소: Gradle Wrapper, Kotlin DSL build logic, 품질 규칙, test와 workflow
- 별도 로컬 platform 저장소: Java runner image, image 검증, ARC Helm values와 Kubernetes 정책
- Azure/AKS/GitHub 설정: registry credential, GitHub App key, federated identity와 runner group

기존 `harness-foundation` branch의 Maven 구현은 merge하지 않는다. 설계 근거를 보존하되 새
Gradle branch에서 독립적으로 구현한다.

## 2. 검토한 접근법

### 2.1 범용 runner와 CI 설치

기존 `aks-runners`에서 매 job마다 JDK와 Gradle을 설치한다. 초기 변경은 적지만 ephemeral
runner에서 다운로드 비용이 반복되고 Java toolchain이 플랫폼 계약으로 고정되지 않는다.

### 2.2 JDK별 image와 scale set

JDK 17, 21, 25마다 image와 ARC scale set을 둔다. 격리는 명확하지만 image, Helm release,
보안 정책과 autoscaling 운영 대상이 세 배가 된다.

### 2.3 단일 Java image와 Gradle Wrapper

runner image에 JDK 17, 21, 25를 GitHub tool-cache 형식으로 포함하고 저장소의 Gradle
Wrapper로 Gradle 버전을 고정한다. `actions/setup-java`는 download 없이 preinstalled
toolchain을 선택한다.

**결정:** 2.3을 채택한다. JDK matrix 재현성과 runner 운영 단순성을 함께 확보하며 Gradle
버전 소유권은 저장소에 남긴다.

## 3. 애플리케이션 저장소 build

### 3.1 기준

- Gradle Wrapper: 9.7.0
- Gradle runtime: JDK 17 이상
- Java source/bytecode baseline: release 17
- CI toolchain matrix: Eclipse Temurin 17, 21, 25
- Build scripts: Kotlin DSL만 사용
- Dependency version: `gradle/libs.versions.toml`
- 공통 plugin 설정: included build `build-logic`

Gradle distribution과 wrapper JAR checksum을 검증한다. system Gradle은 wrapper 생성과
bootstrap 이외에 사용하지 않는다.

### 3.2 파일 구조

```text
agent-framework-java/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
├── gradlew
├── gradlew.bat
├── build-logic/
│   ├── settings.gradle.kts
│   └── src/main/kotlin/
│       ├── agentframework.java-library-conventions.gradle.kts
│       ├── agentframework.quality-conventions.gradle.kts
│       └── agentframework.test-conventions.gradle.kts
└── build-tools/
    └── harness-policy/
```

root project는 제품 code를 포함하지 않는다. 제품 module은 실제 vertical slice를 구현할 때만
추가한다.

### 3.3 convention plugin 경계

`java-library-conventions`는 Java toolchain 17, `options.release=17`, UTF-8, compiler lint,
reproducible archive와 dependency locking을 소유한다.

`test-conventions`는 JUnit 5, AssertJ, deterministic test defaults와 test report를
소유한다. 각 JDK compatibility task는 Gradle Java Toolchains를 사용해 해당 launcher로
test를 실행한다.

`quality-conventions`는 Spotless, Checkstyle, PMD, SpotBugs와 JaCoCo 설정을 소유한다.
formatter는 javac internal API에 의존하지 않는 engine을 우선한다.

## 4. 검증 그래프

formatter와 static analysis는 한 번만 실행하고 JDK compatibility는 compile/test에
집중한다.

```text
policy
  |
  +--> quality (Gradle runtime JDK 17)
  |
  +--> testJava17
  +--> testJava21
  +--> testJava25
  |
  +--> fan-in --> required CI
```

`quality`는 format, Checkstyle, PMD, SpotBugs, dependency policy, architecture와 coverage
report를 실행한다. `testJava17/21/25`는 품질 plugin을 다시 실행하지 않는다. 이 분리는
google-java-format처럼 JDK compiler internals에 결합된 도구가 matrix 전체를 깨는 문제를
방지한다.

CI와 local command는 동일한 Gradle task를 사용한다.

- `./gradlew policyCheck`
- `./gradlew quality`
- `./gradlew testJava17 testJava21 testJava25`
- `./gradlew check`

## 5. Java 전용 runner image

### 5.1 별도 platform 저장소

로컬 sibling 저장소 기본 경로는 다음과 같다.

```text
/Users/hwang-inhwan/workspace/agent-framework-java-platform/
```

이 저장소는 향후 private infrastructure repository로 이동할 수 있게 독립 Git 저장소로
관리한다.

```text
agent-framework-java-platform/
├── runner-images/java/
│   ├── Dockerfile
│   ├── toolcache-manifest.json
│   ├── verify-image.sh
│   └── README.md
├── arc/arc-java-build/
│   ├── values.yaml
│   ├── namespace.yaml
│   ├── network-policy.yaml
│   └── README.md
└── scripts/
    ├── build-java-runner.sh
    ├── publish-java-runner.sh
    ├── deploy-arc-java-build.sh
    └── verify-arc-java-build.sh
```

실제 secret과 credential은 이 저장소에도 커밋하지 않는다.

### 5.2 image 내용

image는 공식 GitHub Actions runner image를 pinned digest로 기반 삼고 다음만 추가한다.

- Eclipse Temurin JDK 17, 21, 25
- `JAVA_HOME_17_X64`, `JAVA_HOME_21_X64`, `JAVA_HOME_25_X64`
- GitHub tool-cache metadata와 `.complete` marker
- Git, curl, unzip, zip, jq, bash, CA certificates
- non-root `runner` 사용자와 `/home/runner/run.sh`

Gradle distribution, dependency cache, Azure credential, kubectl, Helm, Docker daemon은 image에
넣지 않는다. Gradle은 저장소 wrapper가 소유하고 dependency는 GitHub cache service를
사용한다.

image build는 JDK archive checksum과 base image digest를 검증한다. `verify-image.sh`는
세 JDK version, `javac`, tool-cache layout, non-root 사용자와 runner entrypoint를 확인한다.

### 5.3 registry

**결정(2026-08-11 확정):** 기존 Azure Container Registry `acrpensionguard`를 재사용하고
dedicated repository `gha-runners/agent-framework-java`만 새로 만든다. **새 registry를
만들지 않는다.**

`acrpensionguard`는 `rg-pension-guard`의 공용 자산이므로 이 작업은 registry를 생성하지도,
SKU·admin user·network 등 어떤 property도 변경하지 않는다. AKS kubelet managed identity에
대한 `AcrPull` role assignment는 registry scope로 이미 존재하므로 platform 작업은 이를
가정하지 않고 idempotent하게 재확인만 한다.

tag는 편의용이고 Helm values는 immutable digest를 사용한다.

```text
acrpensionguard.azurecr.io/gha-runners/agent-framework-java:<date>-<source-sha>
acrpensionguard.azurecr.io/gha-runners/agent-framework-java@sha256:<digest>
```

### 5.4 대상 환경

다음 값은 2026-08-11에 실제 cluster와 subscription에서 확인한 authoritative 값이다.
이전 설계 논의에서 등장한 `rg-korvid-contract-test` / `aks-korvid-contract-test` /
신규 registry 안은 폐기한다.

| 항목 | 값 | 소유 |
| --- | --- | --- |
| kubectl context | `evalollama` | 기존 |
| Azure subscription | `95933ae5-0201-4a21-a1fc-8051a7437982` (`ME-MngEnvMCAP310512-inhwanhwang-3`) | 기존 |
| Resource group / location | `rg-pension-guard` / `koreacentral` | 기존 |
| AKS cluster | `aks-shared-runners` (Kubernetes 1.35, Cilium dataplane) | 기존 |
| Container registry | `acrpensionguard` / `acrpensionguard.azurecr.io` (Basic, admin disabled) | 기존, 재사용 |
| Image repository | `gha-runners/agent-framework-java` | 신규 |
| ARC chart | `gha-runner-scale-set` `0.14.2` | 기존 |
| 기존 scale set | `arc-runners`의 `aks-runners`, `aks-runners-flutter`, `korvid-runners` | 기존, 변경 금지 |
| 신규 scale set | `arc-runners-java`의 `arc-java-build` | 신규 |
| GitHub credential | secret `gha-token` | 기존, 복사 |

기존 `aks-runners`와 `aks-runners-flutter`는 `https://github.com/open-play-ground/grown-up`,
`korvid-runners`는 `https://github.com/hellices/korvid`를 대상으로 하며 셋 다 `arc-runners`
namespace의 `gha-token`을 참조한다. 신규 scale set은
`https://github.com/open-play-ground/agent-framework-java`를 대상으로 하고 같은 이름의
secret을 자기 namespace에서 참조한다.

## 6. ARC scale set

`arc-java-build`는 기존 scale set을 수정하지 않고 새 Helm release로 추가한다.

- runner scale set name: `arc-java-build`
- namespace: `arc-runners-java`
- container name: `runner`
- image: Java runner image digest
- `minRunners: 0`
- 초기 `maxRunners: 5`
- non-privileged
- `allowPrivilegeEscalation: false`
- 모든 Linux capability drop
- dedicated service account
- GitHub credential은 secret reference만 사용
- Docker-in-Docker와 Kubernetes container mode 사용 안 함

Kubernetes Secret은 namespace-scoped이므로 `arc-runners`의 `gha-token`을
`arc-runners-java`에서 그대로 참조할 수 없다. cluster operator가
`scripts/copy-github-config-secret.sh`로 복사하며, 이 script는 secret을 JSON으로 읽되
`kubectl apply --namespace "$ARC_NAMESPACE" -f -`로 끝나는 단일 pipeline 안에서만 다룬다.
문서 전체는 terminal, log, file 어디에도 출력되지 않고 key 이름만 표시된다. 값을 만들거나
decode하거나 commit하지 않는다.

NetworkPolicy는 기본 deny 후 DNS, GitHub Actions endpoints, GitHub API, Maven Central,
Gradle Plugin Portal, Gradle distribution service와 승인된 artifact registry HTTPS만
허용한다. AKS IMDS와 Kubernetes API는 runner pod에서 차단한다.

ARC controller, listener와 ephemeral runner log는 pod 삭제 전에 Azure Monitor 또는 기존
cluster telemetry로 수집한다.

## 7. GitHub Actions

### 7.1 trusted path

same-repository PR, `main` push와 manual dispatch는 `arc-java-build`에서 실행한다.

- `quality`: JDK 17
- `testJava17`: JDK 17
- `testJava21`: JDK 21
- `testJava25`: JDK 25

`actions/setup-java`는 image의 tool-cache를 선택하며 network download가 발생하면 runner
image regression으로 기록한다.

PR concurrency만 cancel한다. `main` push는 취소하지 않는다.

### 7.2 fork path

fork PR은 `ubuntu-latest`에서 read-only token과 secret 없는 최소 `policyCheck`,
`quality`, `testJava17`을 실행한다. trusted ARC job은 명시적으로 skip한다.

branch protection은 fork와 trusted path를 하나의 required result job으로 fan-in해 실제
검증 없이 skipped-green이 되지 않게 한다.

### 7.3 workflow policy

정규식 한두 개로 단일 workflow만 검사하지 않는다. repository policy test는 모든
`.github/workflows/*.yml`과 `*.yaml`을 parse해 다음을 검증한다.

- 외부 action과 reusable workflow는 full SHA
- local composite action은 `./` 경로만 허용
- `pull_request_target` 금지
- runner label allow-list
- workflow/job permission allow-list
- 모든 checkout에 `persist-credentials: false`
- fork와 trusted runner의 상호 배타적 조건
- `main` run cancellation 금지

## 8. Repository policy

shell policy script는 독립 실행만 가능한 dead gate로 남기지 않는다. `build-tools`의 Gradle
test 또는 custom task로 다음을 검증하고 `check`에 연결한다.

- `AGENTS.md`와 vendor adapter 크기·연결
- Gradle wrapper URL과 checksum
- quality tool version
- artifact schema/example
- workflow policy

shell script가 필요하면 Gradle task가 실행하거나 Java/Kotlin policy test의 thin wrapper로
만든다.

## 9. Artifact 계약

foundation은 기존 여섯 계약에 두 계약을 추가해 설계 DAG와 일치시킨다.

- `TaskIntent`
- `ChangeContext`
- `ImpactSet`
- `TestPlan`
- `ChangeSummary`
- `VerificationResult`
- `ReviewResult`
- `RunScore`

JSON Schema 2020-12 validator로 example 전체를 실제 schema에 대해 검증한다. top-level key
존재만 확인하지 않는다. `additionalProperties`가 명시적으로 `false`인지도 검증한다.

## 10. 오류와 복구

- image checksum 또는 image test 실패: publish와 Helm upgrade를 중단한다.
- 새 scale set listener/runner 실패: 기존 `aks-runners`는 변경하지 않고 새 release만 rollback한다.
- tool-cache miss: CI를 실패시키기보다 metric으로 먼저 기록하고 image test에서 차단한다.
- fork path 실패: trusted runner로 자동 재실행하지 않는다.
- quality 실패: compatibility test 성공으로 덮지 않는다.
- ARC credential/registry 정보 부재: app build를 완료하고 platform deploy만 명시적으로 blocked 처리한다.
- registry 부재·이름 변경·admin user 활성화: `verify-acr.sh`가 BLOCKED로 중단하고 아무것도
  바꾸지 않는다. 공용 registry이므로 수정은 out-of-band operator 작업이다.
- `arc-runners-java`에 `gha-token` 부재: preflight가 차단하고 operator가 복사 script를 실행한
  뒤 재실행한다. credential을 생성하는 경로는 없다.

## 11. 회귀

### 앱 저장소

- Gradle TestKit으로 convention plugin 적용과 task graph 검증
- JDK 17·21·25 toolchain test
- workflow YAML parser 기반 security policy
- JSON Schema example validation
- wrapper distribution/checksum regression

### platform 저장소

- Dockerfile lint와 base digest 확인
- image 내부 JDK/tool-cache/non-root 검증
- Helm template snapshot과 schema validation
- `kubectl --dry-run=server` 또는 별도 test cluster 검증
- 배포 후 ephemeral runner smoke workflow

## 12. 단계

1. Maven branch를 merge 대상에서 제외하고 Gradle 설계·계획을 확정한다.
2. 앱 저장소에 Gradle Wrapper, Kotlin DSL, build-logic과 policy test를 만든다.
3. sibling platform 저장소에 Java runner image와 검증을 만든다.
4. 기존 ACR `acrpensionguard`와 현재 ARC authentication/namespace 정책을 확인한다. 새 registry는 만들지 않는다.
5. image를 publish하고 `arc-java-build`를 별도 release로 배포한다.
6. smoke workflow 후 앱 CI label을 `arc-java-build`로 전환한다.
7. fork/trusted result fan-in과 branch protection을 검증한다.

## 13. 성공 기준

1. `./gradlew check`가 JDK 17에서 통과한다.
2. Java 17·21·25 compatibility test가 동일 source baseline을 검증한다.
3. `arc-java-build` runner가 image download 없이 세 JDK를 선택한다.
4. runner pod는 non-privileged이며 기존 세 scale set(`aks-runners`, `aks-runners-flutter`, `korvid-runners`)을 변경하지 않는다.
5. fork PR은 GitHub-hosted 검증 없이 green이 되지 않는다.
6. 모든 repository policy가 Gradle `check`에 포함된다.
7. runner image와 ARC configuration은 앱 저장소 밖에서 재현 가능하다.

## 14. 참고

- [Gradle 9.7.0 release metadata](https://services.gradle.org/versions/current)
- [Gradle Java compatibility](https://docs.gradle.org/9.7.0/userguide/compatibility.html)
- [Gradle Java Toolchains](https://docs.gradle.org/9.7.0/userguide/toolchains.html)
- [ARC custom runner image](https://docs.github.com/en/actions/how-tos/manage-runners/use-actions-runner-controller/deploy-runner-scale-sets#using-a-custom-runner-image)
- [setup-java](https://github.com/actions/setup-java)

