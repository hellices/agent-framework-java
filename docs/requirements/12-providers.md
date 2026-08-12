# 12 공급자 통합

**접두사** `PRV` · **원본 기능** [31 provider-integrations](../upstream/snapshots/d0a4165f/features/31-provider-integrations.md)

모델 공급자, managed agent runtime, storage, memory, governance, hosting adapter inventory를 Java 모듈 관점에서 정리한다. 개별 protocol wire contract는 [10 호스팅과 프로토콜](10-hosting.md)이, 운영 품질과 패키징 규칙은 [11 운영 품질](11-operations.md)이 소유한다. 이 문서는 어떤 어댑터를 어떤 경계와 우선순위로 옮길지 확정한다.

## 채택 범위

이 문서의 `등급`은 [README](README.md#requirement-grades) 정의대로 기능을 만들기로 했을 때의 강제력이고, 채택 여부는 [호환성 매트릭스](../upstream/snapshots/d0a4165f/compatibility-matrix.md)를 따른다.

- OpenAI-compatible 첫 vertical slice와 공통 provider 경계는 채택 `필수`다.
- 호스팅·프로토콜 분리와 provider-owned continuation 경계는 선택 어댑터 범주에 맞춰 채택 `선택`이다.
- storage·memory·governance와 one-sided long-tail adapter family는 채택 `보류`다.

## 요약

| ID | 요구사항 | 채택 | 등급 | 단계 |
| --- | --- | --- | --- | --- |
| PRV-001 | 코어는 provider SDK에 직접 의존하지 않는다 | 필수 | 필수 | Core+ |
| PRV-002 | 공급자와 통합은 artifact별로 분리한다 | 필수 | 필수 | Core+ |
| PRV-003 | all-providers 번들을 기본 제공하지 않는다 | 선택 | 권장 | Optional |
| PRV-004 | 어댑터 포팅 우선순위를 P0부터 P4까지 고정한다 | 필수 | 필수 | Core+ |
| PRV-005 | hosting과 protocol adapters는 model provider와 분리한다 | 선택 | 필수 | Hosting |
| PRV-006 | storage·memory·governance는 모델 공급자와 분리한다 | 보류 | 필수 | Optional |
| PRV-007 | 공급자 전용 기능은 선택 capability로 노출한다 | 필수 | 필수 | Core+ |
| PRV-008 | 공급자 전용 continuation과 hosted state는 어댑터가 소유한다 | 선택 | 필수 | Hosting |
| PRV-009 | 한쪽 언어에만 있는 통합은 선택 tier로 유지한다 | 보류 | 권장 | Optional |
| PRV-010 | adapter는 maturity·README·테스트 근거를 함께 제공하고 facade를 유지한다 | 필수 | 필수 | Optional |

---

### 공통 경계

## PRV-001 코어는 provider SDK에 직접 의존하지 않는다

**요구사항.** 코어와 엔진 모듈은 provider SDK에 직접 의존하지 않고 중립 port만 알아야 한다.

**원본 비교**

- .NET: OpenAI, Foundry, Anthropic 같은 transport를 개별 project로 분리한다.
- Python: openai, foundry, gemini, bedrock 같은 provider package를 core와 별도 package로 둔다.

**판단.** inventory가 이미 보여 주는 구조다. 코어에 SDK 의존성이 들어오면 long-tail provider 추가와 테스트 대역 작성이 급격히 어려워진다.

**수용 기준**

- core build classpath에 특정 provider SDK가 직접 들어가지 않는다.
- provider adapter는 neutral ChatClient 또는 동등 SPI를 구현해 코어에 연결된다.

**근거** [31 provider-integrations](../upstream/snapshots/d0a4165f/features/31-provider-integrations.md)

---

## PRV-002 공급자와 통합은 artifact별로 분리한다

**요구사항.** 모델 공급자와 통합은 provider 또는 integration family마다 별도 artifact로 분리해야 한다.

**원본 비교**

- .NET: OpenAI, Foundry, GitHub Copilot, Purview, Cosmos, Hosting.OpenAI 등을 project별로 나눈다.
- Python: packages/* 아래에 provider, storage, hosting, channel adapter를 package별로 쪼갠다.

**판단.** 한 artifact에 여러 provider를 섞으면 버전, 의존성, maturity가 함께 묶여 버린다. Java도 provider-per-artifact 원칙을 고정한다.

**수용 기준**

- 새 provider를 추가해도 기존 provider artifact의 transitive dependency set이 늘지 않는다.
- 문서와 빌드 산출물에서 provider artifact가 독립 이름으로 식별된다.

**근거** [31 provider-integrations](../upstream/snapshots/d0a4165f/features/31-provider-integrations.md)

---

## PRV-003 all-providers 번들을 기본 제공하지 않는다

**요구사항.** 기본 배포는 all-providers 번들을 만들지 않고 선택 설치를 우선해야 한다.

**원본 비교**

- .NET: NUGET metadata와 project split이 개별 설치를 전제로 한다.
- Python: meta package가 있어도 packages/* selective install surface를 별도로 유지한다.

**판단.** 선택 설치가 dependency blast radius를 줄인다. aggregator artifact는 있더라도 편의용 선택 항목이어야 한다.

**수용 기준**

- 개별 provider만 설치해도 core 사용이 가능하다.
- aggregator artifact가 있더라도 provider별 artifact를 대체하지 않는다.

**근거** [31 provider-integrations](../upstream/snapshots/d0a4165f/features/31-provider-integrations.md)

---

---

### 우선순위

## PRV-004 어댑터 포팅 우선순위를 P0부터 P4까지 고정한다

**요구사항.** Java 어댑터 포팅 우선순위는 P0 core parity, P1 shared providers와 hosting, P2 persistence와 governance, P3 managed runtime, P4 language-specific extras로 고정해야 한다.

**원본 비교**

- .NET: AzureAI Persistent, LocalCodeAct, AspNetCore, Aspire DevUI 같은 .NET 전용 extras가 별도 tier를 이룬다.
- Python: Bedrock, Claude, Gemini, ChatKit, Telegram 같은 Python 전용 extras가 별도 tier를 이룬다.

**판단.** inventory가 넓어서 우선순위가 없으면 코어 parity가 늦어진다. shared provider와 hosting을 먼저 고정하고 one-sided extras는 뒤로 미룬다.

**수용 기준**

- P0와 P1 범위가 완료되기 전에는 P4 전용 어댑터를 core milestone으로 취급하지 않는다.
- 우선순위 표가 문서와 계획에서 동일하게 재사용된다.

**근거** [31 provider-integrations](../upstream/snapshots/d0a4165f/features/31-provider-integrations.md)

---

---

### 공통 경계

## PRV-005 hosting과 protocol adapters는 model provider와 분리한다

**요구사항.** OpenAI Responses, A2A, AG-UI, MCP 같은 hosting·protocol adapter는 model provider adapter와 별도 artifact로 분리해야 한다.

**원본 비교**

- .NET: Hosting.OpenAI, Hosting.A2A, Hosting.AGUI, Hosting.AspNetCore가 core provider와 별도 package다.
- Python: hosting-responses, hosting-a2a, ag-ui, hosting-mcp가 provider package와 분리되어 있다.

**판단.** host surface와 model transport는 생명주기와 보안 경계가 다르다. 둘을 합치면 wire protocol 변경이 model provider upgrade를 강제한다.

**수용 기준**

- model provider artifact를 빼도 hosting adapter의 테스트 대역이 유지된다.
- hosting adapter는 provider-neutral hosting core 또는 protocol SPI를 통해서만 core와 연결된다.

**근거** [31 provider-integrations](../upstream/snapshots/d0a4165f/features/31-provider-integrations.md),
[20 hosting](../upstream/snapshots/d0a4165f/features/20-hosting.md)

---

## PRV-006 storage·memory·governance는 모델 공급자와 분리한다

**요구사항.** Cosmos, Redis 또는 Valkey, Mem0, Purview 같은 storage·memory·governance integration은 model provider와 별도 artifact로 분리해야 한다.

**원본 비교**

- .NET: CosmosNoSql, Valkey, Purview가 모델 provider와 독립 project로 존재한다.
- Python: azure-cosmos-memory, redis, mem0, purview가 별도 package로 존재한다.

**판단.** 저장소와 거버넌스는 모델보다 더 오래 남는 운영 의존성이다. model adapter와 섞으면 불필요한 transitive surface가 커진다.

**수용 기준**

- storage 또는 governance adapter를 제거해도 model provider artifact의 API가 변하지 않는다.
- history 또는 checkpoint와 semantic memory는 별도 artifact로 식별된다.

**근거** [31 provider-integrations](../upstream/snapshots/d0a4165f/features/31-provider-integrations.md)

---

## PRV-007 공급자 전용 기능은 선택 capability로 노출한다

**요구사항.** 공급자 전용 기능은 core 메서드를 늘리지 말고 adapter-owned capability 또는 option surface로 노출해야 한다.

**원본 비교**

- .NET: Persistent Agents, Foundry hosted notes, provider-specific extensions가 개별 surface로 분리되어 있다.
- Python: ThinkingConfig, BedrockGuardrailConfig, Foundry generated evaluators처럼 전용 기능을 package별 공개 surface로 둔다.

**판단.** 사용자 요구의 “공급자 전용 기능 노출 방식”을 직접 반영한다. 공통 인터페이스를 오염시키지 않아야 portability와 Java 관습을 함께 지킬 수 있다.

**수용 기준**

- 공통 ChatClient 또는 Agent SPI는 특정 provider 전용 옵션 필드를 직접 요구하지 않는다.
- 전용 기능 사용 여부는 capability 조회나 adapter-specific type으로 명시된다.

**근거** [31 provider-integrations](../upstream/snapshots/d0a4165f/features/31-provider-integrations.md)

---

## PRV-008 공급자 전용 continuation과 hosted state는 어댑터가 소유한다

**요구사항.** 공급자 전용 continuation handle이나 managed runtime state는 adapter-owned structured type으로 보관하고 core가 generic하게 파싱하지 않아야 한다.

**원본 비교**

- .NET: A2AAgentSession, Foundry hosting context 같은 provider 또는 runtime 전용 state surface가 별도로 존재한다.
- Python: A2AServiceSessionId와 Foundry state store provider가 provider-owned continuation과 storage shape를 가진다.

**판단.** 호스팅과 공급자 문서의 공통 원칙이다. core가 provider handle을 이해하려 들면 새 adapter가 들어올 때마다 core가 흔들린다.

**수용 기준**

- provider-owned continuation은 adapter 모듈 안에서만 해석된다.
- core telemetry나 session helper가 provider 전용 structured state를 직접 파싱하지 않는다.

**근거** [31 provider-integrations](../upstream/snapshots/d0a4165f/features/31-provider-integrations.md),
[26 identity-session-routing](../upstream/snapshots/d0a4165f/features/26-identity-session-routing.md)

---

---

### 우선순위

## PRV-009 한쪽 언어에만 있는 통합은 선택 tier로 유지한다

**요구사항.** 한쪽 언어나 특정 런타임에만 존재하는 integration은 first-class core dependency가 아니라 선택 adapter tier로 유지해야 한다.

**원본 비교**

- .NET: Azure AI Persistent Agents, LocalCodeAct, Aspire DevUI 같은 .NET-only surface가 있다.
- Python: Bedrock, Claude, ChatKit, Telegram, Foundry Local 같은 Python-only surface가 있다.

**판단.** inventory가 비대칭이다. 이 차이를 core parity의 실패로 취급하지 말고 optional tier로 관리해야 전체 계획이 안정적이다.

**수용 기준**

- one-sided adapter 부재가 core compatibility failure로 분류되지 않는다.
- optional tier adapter는 core BOM에 강제 포함되지 않는다.

**근거** [31 provider-integrations](../upstream/snapshots/d0a4165f/features/31-provider-integrations.md)

---

## PRV-010 adapter는 maturity·README·테스트 근거를 함께 제공하고 facade를 유지한다

**요구사항.** 각 adapter artifact는 maturity, README 또는 동등 문서, 테스트 근거를 함께 제공하고 repo split이나 구현 이동이 있어도 facade surface를 유지해야 한다.

**원본 비교**

- .NET: 중앙 NUGET README fallback과 stage metadata, project별 tests가 존재한다.
- Python: PACKAGE_STATUS, package README, tests, changelog re-export 사례가 함께 존재한다.

**판단.** provider inventory는 존재 확인만으로 충분하지 않다. downstream이 설치·성숙도·신뢰도를 읽을 수 있어야 하고 backend 이동이 있어도 import surface는 버티어야 한다.

**수용 기준**

- 각 adapter는 maturity 표기와 README 또는 중앙 README fallback을 가진다.
- 각 adapter는 최소 하나 이상의 테스트 근거를 문서에서 찾을 수 있다.
- repo split이나 구현 이동 시 facade 또는 re-export 유지 여부가 changelog나 동등 문서에 기록된다.

**근거** [31 provider-integrations](../upstream/snapshots/d0a4165f/features/31-provider-integrations.md)

---

## 이 문서가 다루지 않는 것

| 주제 | 소유 문서 |
| --- | --- |
| 에이전트 실행과 메시지 모델의 코어 계약 | [01 에이전트 실행과 모델 호출](01-agent-execution.md) |
| 호스팅 모델과 식별 경계 | [10 호스팅과 프로토콜](10-hosting.md) |
| 평가, 패키징, 호환성 gate | [11 운영 품질](11-operations.md) |
| 개별 provider의 세부 wire payload와 sample 코드 | ../upstream/snapshots/d0a4165f/features/31-provider-integrations.md |
