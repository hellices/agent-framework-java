# Microsoft Agent Framework Upstream Snapshot

## 식별 정보

| 항목 | 값 |
| --- | --- |
| Repository | `https://github.com/microsoft/agent-framework.git` |
| Branch at capture | `main` |
| Full commit | `d0a4165f170193ba1d026a259af40d35bb7eaefe` |
| Git tree | `0dbd7a60d70ad3b588b5b2ad77131b3a0879c3cf` |
| Author date | `2026-08-09T22:51:59-07:00` |
| Commit date | `2026-08-10T05:51:59Z` |
| Subject | `[BREAKING] Python: Migrate FHA to responses==2.0.0b1 and add Foundry state store (#7533)` |
| Nearest tag description | `dotnet-1.17.0-23-gd0a4165f` |
| Git archive SHA-256 | `73bf466a3df507a7af15355c32c37526fd4aad8a30de569fe34664c11237d45e` |
| Tracked files | 4,876 |

Git archive checksum은 다음 명령의 tar byte stream을 기준으로 한다.

```bash
git archive --format=tar d0a4165f170193ba1d026a259af40d35bb7eaefe \
  | shasum -a 256
```

소스는 다음 명령으로 재현한다.

```bash
git clone --no-checkout --filter=blob:none \
  https://github.com/microsoft/agent-framework.git agent-framework-upstream
git -C agent-framework-upstream checkout --detach \
  d0a4165f170193ba1d026a259af40d35bb7eaefe
```

## 저장소 구성

| 영역 | 추적 파일 수 | 역할 |
| --- | ---: | --- |
| `dotnet/` | 2,941 | .NET core, workflow, harness, hosting, protocol 및 provider 구현 |
| `python/` | 1,778 | Python core, orchestration, hosting 및 provider 구현 |
| `docs/` | 68 | Repository specification, decision 및 feature 문서 |
| `declarative-agents/` | 19 | Declarative agent schema와 관련 자산 |
| `.github/` | 58 | CI, release와 repository automation |
| 기타 root 파일 | 12 | License, contribution, security와 project metadata |

## 상위 버전

### .NET

`dotnet/nuget/nuget-package.props`의 공통 `VersionPrefix`는 `1.17.0`이다. Snapshot은
`dotnet-1.17.0` tag 이후 23개 commit 상태이므로 `1.17.0` 릴리스와 동일하다고 간주하지
않는다.

### Python

| Package | Version | Version 상태 |
| --- | --- | --- |
| `agent-framework-core` | `1.13.0` | stable version |
| `agent-framework-openai` | `1.12.0` | stable version |
| `agent-framework-foundry` | `1.10.4` | stable version |
| `agent-framework-ag-ui` | `1.0.1` | stable version |
| `agent-framework-orchestrations` | `1.0.2` | stable version |
| `agent-framework-declarative` | `1.0.1` | stable version |
| `agent-framework-github-copilot` | `1.0.1` | stable version |
| `agent-framework-hosting` | `1.0.0a260730` | alpha version |
| `agent-framework-hosting-a2a` | `1.0.0a260730` | alpha version |
| `agent-framework-hosting-mcp` | `1.0.0a260730` | alpha version |
| `agent-framework-hosting-responses` | `1.0.0a260730` | alpha version |
| `agent-framework-hosting-telegram` | `1.0.0a260730` | alpha version |
| `agent-framework-azure-cosmos-memory` | `1.0.0a260730` | alpha version |
| `agent-framework-a2a` | `1.0.0b260730` | beta version |
| `agent-framework-anthropic` | `1.0.0b260730` | beta version |
| `agent-framework-azure-ai-search` | `1.0.0b260730` | beta version |
| `agent-framework-azure-contentunderstanding` | `1.0.0b260730` | beta version |
| `agent-framework-azure-cosmos` | `1.0.0b260730` | beta version |
| `agent-framework-bedrock` | `1.0.0b260730` | beta version |
| `agent-framework-chatkit` | `1.0.0b260730` | beta version |
| `agent-framework-claude` | `1.0.0b260730` | beta version |
| `agent-framework-copilotstudio` | `1.0.0b260730` | beta version |
| `agent-framework-devui` | `1.0.0b260730` | beta version |
| `agent-framework-foundry-hosting` | `1.0.0b260730` | beta version |
| `agent-framework-foundry-local` | `1.0.0b260730` | beta version |
| `agent-framework-gemini` | `1.0.0b260730` | beta version |
| `agent-framework-hyperlight` | `1.0.0b260730` | beta version |
| `agent-framework-lab` | `1.0.0b260730` | beta version |
| `agent-framework-mem0` | `1.0.0b260730` | beta version |
| `agent-framework-mistral` | `1.0.0b260730` | beta version |
| `agent-framework-monty` | `1.0.0b260730` | beta version |
| `agent-framework-ollama` | `1.0.0b260730` | beta version |
| `agent-framework-purview` | `1.0.0b260730` | beta version |
| `agent-framework-redis` | `1.0.0b260730` | beta version |
| `agent-framework-tools` | `1.0.0b260730` | beta version |

여기서 상태는 package version 문자열의 PEP 440 prerelease marker를 기준으로 한다. Package
metadata classifier나 개별 기능 안정성은 기능 매트릭스에서 별도로 판단한다.

## 분석 기준

- 이 commit 이후 추가된 기능은 현재 Java 설계 기준에 포함하지 않는다.
- 현재 commit에서 제거되었지만 외부 문서에 남아 있는 기능은 미구현 또는 제거 상태로
  기록한다.
- Public API 존재만으로 기능 완성을 판단하지 않고 실제 실행 경로와 테스트를 확인한다.
- Sample은 기능 발견에 사용하지만 production contract의 근거로 단독 사용하지 않는다.
- Provider 전용 기능을 core 공통 기능으로 일반화하지 않는다.
- Experimental, alpha, beta 및 stable 상태를 하나의 지원 등급으로 합치지 않는다.
- 코드 인용은 이 full commit의 GitHub permalink를 사용한다.

## Snapshot 변경 절차

1. 새 `main` commit과 tree hash를 기록한다.
2. Git archive SHA-256과 파일 수를 계산한다.
3. Package version 및 안정성 변화를 기록한다.
4. 기존 snapshot과 source tree 및 public API 차이를 산출한다.
5. 기능 매트릭스에 추가·변경·제거 상태를 반영한다.
6. Java 지원 목표와 구현 영향도를 별도 delta 문서에서 승인한다.
