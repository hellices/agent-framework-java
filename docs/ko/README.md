# Agent Framework for Java 한국어 안내

## 프로젝트 목적과 현재 상태

이 저장소는 [Microsoft Agent Framework](https://github.com/microsoft/agent-framework)의 관찰 가능한 실행 의미론을 Java로 옮기는 작업을 진행합니다. 산출물의 중심은 애플리케이션 서버가 아니라 호스트에 내장하는 `AgentEngine`입니다.

현재 저장소에는 결정적인 단일 에이전트 실행 경로, 기본 함수 도구 루프, 세션 영속화, 그리고 OpenAI Chat Completions provider adapter(Preview)가 구현되어 있습니다. host 통합과 provider 스트리밍은 후속 단계입니다. 큰 흐름은 [루트 README](../../README.md)에서, 문서 전체 목록은 [문서 인덱스](../README.md)에서 확인할 수 있습니다.

## 런타임 소유권과 아키텍처 경계

`AgentEngine`는 모델 호출과 도구 호출의 상태 전이, 세션 상태 변경 규칙, 스트리밍 이벤트 순서를 소유합니다. 이 경계 덕분에 엔진은 프레임워크 중립을 유지하고, 같은 의미론을 서로 다른 호스트 환경에 삽입할 수 있습니다.

호스트 런타임은 의존성 주입, 구성, 객체 생명주기, 실행기, 스케줄러, HTTP 서버, 보안, 트랜잭션을 소유합니다. 저장소의 모듈 배치와 경계 규칙은 [기초 설계](../design/foundation-design.md)와 [모듈 구성 계약](../design/module-composition.md)에서 정리합니다.

## 빠른 시작과 공통 Gradle 명령

개발과 검증의 기준 JDK는 17입니다. 호환성 확인은 Temurin 21과 25에서도 수행하므로, 로컬에 두 버전이 없으면 해당 검증은 CI에 맡길 수 있습니다.

```bash
./gradlew policyCheck
./gradlew quality
./gradlew testJava17
./gradlew check
```

- `./gradlew policyCheck`: 저장소 정책과 문서·구조 규칙을 확인합니다.
- `./gradlew quality`: JDK 17에서 포매팅과 정적 분석을 실행합니다.
- `./gradlew testJava17`: 기본 테스트 실행 경로입니다.
- `./gradlew check`: 위 검증에 더해 추가 호환성 실행까지 묶어 확인합니다.
- 처음 기여를 시작할 때 필요한 흐름은 [시작 가이드](../operations/getting-started.md)에서 확인합니다.

## 저장소와 모듈 구성

이 저장소를 읽을 때 먼저 아래 경로의 역할을 익히면 탐색이 빨라집니다.

- `agent-framework-api`: 공개 타입과 계약의 출발점입니다.
- `agent-framework-engine`: 임베디드 실행 상태 머신을 담습니다.
- `agent-framework-testkit`: 결정적 테스트 지원 도구를 담습니다.
- `agent-framework-bom`: 공개 아티팩트 버전을 정렬합니다.
- `build-logic`: Gradle 관례 플러그인을 담습니다.
- `build-tools/harness-policy`: 저장소 정책을 실행 가능한 테스트로 검증합니다.
- `config`: 정적 분석과 품질 도구 구성을 담습니다.
- `docs`: 요구사항, 설계, 운영, 업스트림 근거 문서를 담습니다.
- `samples/sample-standalone`: 서버나 DI 컨테이너 없이 `agent.run(...)`을 실행하는 예제입니다.
- `.harness`: 에이전트 아티팩트 스키마와 하네스 자산을 담습니다.

## 어떤 문서를 언제 읽어야 하는가

- [요구사항 인덱스](../requirements/README.md): Java 구현이 무엇을 만족해야 하는지 확인할 때 읽습니다.
- [기초 설계](../design/foundation-design.md): 아키텍처 방향과 초기 구현 순서를 이해할 때 읽습니다.
- [모듈 구성 계약](../design/module-composition.md): 코드와 모듈을 어디에 둘지 판단할 때 읽습니다.
- [시작 가이드](../operations/getting-started.md): 로컬 개발, 검증, 기여 절차를 따라갈 때 읽습니다.
- [고정 업스트림 스냅샷 안내](../upstream/snapshots/d0a4165f/README.md): 현재 저장소가 어떤 업스트림 근거를 기준으로 해석되었는지 확인할 때 읽습니다.

요구사항은 구현 대상의 범위를 잡아 주고, 설계 문서는 왜 그런 구조를 택했는지 설명하며, 운영 가이드는 작업 절차를 안내하고, 고정 업스트림 근거는 비교 기준과 출처를 보여 줍니다.

## 현재 상태

저장소는 검증 가능한 기반과 결정적인 `AgentEngine` 실행 및 함수 도구 루프를 갖췄습니다. `samples/sample-standalone`은 `OPENAI_API_KEY`가 필요하며, 실제 OpenAI 호환 엔드포인트를 호출해 `agent.run(...)`을 실행합니다. `OPENAI_BASE_URL`과 `OPENAI_MODEL`은 선택 값이고, 기본 프롬프트가 로컬 `current_utc_time` 도구를 이름으로 요청하므로 기본 실행은 함수 도구 루프 전체를 거칩니다. 다만 실제 모델이 도구를 호출할지는 모델의 판단이며, 실행 결과로 출력되는 것은 마지막 assistant 라운드의 답변과 `toolCalls` 횟수를 담은 요약 한 줄입니다. 도구 호출과 함께 모델이 남긴 사전 설명(preamble)은 CLI가 출력하는 마지막 assistant 라운드에는 나타나지 않지만, 그 사전 설명은 버려지는 것이 아니라 `response.messages()`와 트랜스크립트에 그대로 남아 있습니다. 토큰 수가 `n/a`로 표시되면 엔드포인트가 사용량을 보고하지 않았다는 뜻이고, 측정된 `0`과는 다릅니다. API 키는 자격 증명이므로 저장소나 빌드 파일에 커밋하지 않습니다. 후속 엔진·provider·host 작업은 같은 공개 계약 위에 쌓입니다.

## 기여와 보안 안내

- [저장소 작업 지침](../../AGENTS.md)
- [기여 가이드](../../CONTRIBUTING.md)
- [보안 정책](../../SECURITY.md)
- [라이선스](../../LICENSE)

문서를 읽고 바로 작업을 시작하려면 루트 안내와 시작 가이드를 먼저 확인한 뒤, 변경 범위에 맞는 요구사항과 설계 문서를 따라가면 됩니다.

## 정본 언어

이 문서는 한국어 안내서이지만 계약과 세부 정의의 기준은 영문 문서입니다.
English documents are authoritative.
