# d0a4165f upstream snapshot 인덱스

이 문서는 Java 구현 계획으로 넘어가기 전에 `d0a4165f170193ba1d026a259af40d35bb7eaefe`
snapshot에서 확보한 근거 문서를 한곳에 묶어 읽는 순서와 판단 게이트를 고정한다.

## Snapshot identity

| 항목 | 값 |
| --- | --- |
| Repository | `https://github.com/microsoft/agent-framework.git` |
| Branch at capture | `main` |
| Full commit | `d0a4165f170193ba1d026a259af40d35bb7eaefe` |
| Git tree | `0dbd7a60d70ad3b588b5b2ad77131b3a0879c3cf` |
| Commit time | `2026-08-10T05:51:59Z` |
| Subject | `[BREAKING] Python: Migrate FHA to responses==2.0.0b1 and add Foundry state store (#7533)` |
| Baseline manifest | [snapshot-manifest.md](./snapshot-manifest.md) |

## 분석 우선순위

동일 기능을 설명하는 자료가 다르면 다음 순서로 판단한다.

1. 고정 commit의 production source
2. 고정 commit의 unit, integration, conformance test
3. 고정 commit의 specification과 feature 문서
4. Microsoft Learn의 공식 문서
5. sample과 README

문서에만 있고 코드와 테스트에서 확인되지 않는 기능은 구현 완료로 간주하지 않으며, .NET과
Python 구현 차이는 임의 표준화 대신 drift로 기록한 뒤 Java 설계 결정으로 분리한다.

## 산출물 요약

- [스냅샷 매니페스트](./snapshot-manifest.md): 기준 commit, tree, package version, 저장소 구성과
  snapshot 갱신 절차를 고정한다.
- [31개 feature docs](#기능-그룹별-문서-맵): core, workflow, hosting, observability,
  packaging, provider inventory까지 기능 단위 근거를 분해해 정리했다.
- [Coverage ledger](./coverage-ledger.md): 31개 기능 문서와 upstream 코드·테스트·ADR·spec
  집합 사이의 매핑 누락이 없음을 기록한다.
- [71-row compatibility matrix](./compatibility-matrix.md): 기능별 .NET/Python 구현 상태,
  Java 목표, release phase, 소유 모듈, acceptance scenario를 한 표로 묶는다.

## 기능 그룹별 문서 맵

### 1. Agent core와 state surface

- [`01-agent-lifecycle.md` — 01-agent-lifecycle](./features/01-agent-lifecycle.md): agent
  abstraction, identity/metadata, session seed, run entrypoint, delegation 경계를 다룬다.
- [`02-message-content.md` — 02-message-content](./features/02-message-content.md): role,
  multimodal content, usage/update/final response, normalization, stream finalization을 정리한다.
- [`03-model-execution.md` — 03-model-execution](./features/03-model-execution.md): model
  client contract, request options, sync/async 실행, streaming continuation, cancellation을 다룬다.
- [`04-structured-output.md` — 04-structured-output](./features/04-structured-output.md):
  structured schema 요청, provider capability, fallback parse, validation, stream 제약을 정리한다.
- [`05-function-tools.md` — 05-function-tools](./features/05-function-tools.md): function tool
  정의, schema 생성, argument validation, tool selection, budget, parallel execution semantics를 다룬다.
- [`06-tool-approval.md` — 06-tool-approval](./features/06-tool-approval.md): approval
  request/response, standing approval rule, approval-aware wrapper와 denial result 규칙을 정리한다.
- [`07-mcp-client-tools.md` — 07-mcp-client-tools](./features/07-mcp-client-tools.md): MCP
  client lifecycle, tool discovery/invocation, transport 차이, approval 경계를 다룬다.
- [`08-sessions.md` — 08-sessions](./features/08-sessions.md): AgentSession identity/state,
  create/serialize/deserialize, store/file-backed session surface를 비교한다.
- [`09-history-context-memory.md` — 09-history-context-memory](./features/09-history-context-memory.md):
  history provider, context provider, memory injection, persistence adapter 경계를 정리한다.

### 2. 실행 파이프라인과 harness

- [`10-middleware.md` — 10. Middleware](./features/10-middleware.md): .NET decorator seam과
  Python typed pipeline을 기준으로 middleware 삽입 지점을 비교한다.
- [`11-compaction.md` — 11. Compaction](./features/11-compaction.md): compaction subsystem의
  trigger, summarization contract, state rewrite 규칙을 정리한다.
- [`12-harness.md` — 12. Harness](./features/12-harness.md): harness composition, loop policy,
  todo/mode provider, file memory, workspace access 범위를 다룬다.
- [`13-skills-background-code.md` — 13. Skills · Background · Code Execution](./features/13-skills-background-code.md):
  skills loading, background task, code execution 관련 계약과 경계만 묶어 정리한다.

### 3. Workflow와 orchestration

- [`14-workflow-graph.md` — 14-workflow-graph](./features/14-workflow-graph.md): workflow graph
  definition, node/edge shape, builder surface와 graph-level invariant를 다룬다.
- [`15-workflow-runtime.md` — 15-workflow-runtime](./features/15-workflow-runtime.md):
  built workflow 실행 루프, runner, streaming run handle, state manager 책임을 정리한다.
- [`16-workflow-checkpoint-hitl.md` — 16-workflow-checkpoint-hitl](./features/16-workflow-checkpoint-hitl.md):
  checkpoint/resume, persistence, request-response, HITL approval flow를 통합 정리한다.
- [`17-workflow-composition.md` — 17-workflow-composition](./features/17-workflow-composition.md):
  subworkflow, workflow-as-agent, hosted composition, session propagation 규칙을 다룬다.
- [`18-orchestrations.md` — 18-orchestrations](./features/18-orchestrations.md): sequential,
  concurrent, handoff, group chat orchestration 계층의 public surface를 비교한다.
- [`19-declarative.md` — 19-declarative](./features/19-declarative.md): declarative agent
  assets, schema, workflow loading, runtime/state binding, validation 경계를 정리한다.

### 4. Hosting과 protocol adapters

- [`20-hosting.md` — 20. Hosting](./features/20-hosting.md): generic hosting lifecycle, host
  composition, scaling responsibility, ASP.NET/Python hosting 보조 레이어를 다룬다.
- [`21-openai-responses-hosting.md` — 21. OpenAI Responses-compatible hosting](./features/21-openai-responses-hosting.md):
  OpenAI Responses 호환 hosting contract, endpoint shape, state ownership 규칙을 정리한다.
- [`22-a2a.md` — 22. A2A](./features/22-a2a.md): A2A integration, hosting adapter, agent exposure
  방식과 transport 경계를 다룬다.
- [`23-ag-ui.md` — 23. AG-UI](./features/23-ag-ui.md): AG-UI adapter와 hosting, event surface,
  UI-facing state 전달 규칙을 정리한다.
- [`24-mcp-hosting.md` — 24. MCP hosting](./features/24-mcp-hosting.md): MCP server hosting,
  client/server boundary, hosted tool exposure, protocol ownership을 다룬다.
- [`25-foundry-devui-channels.md` — 25. Foundry hosting, DevUI, Aspire integration, ChatKit, Telegram, 기타 확인된 channel adapters](./features/25-foundry-devui-channels.md):
  Foundry hosting과 DevUI, Aspire, channel adapter 계열을 한 묶음으로 정리한다.
- [`26-identity-session-routing.md` — 26. Identity / session routing](./features/26-identity-session-routing.md):
  hosted identity, authorization responsibility, per-user isolation, session routing drift를 다룬다.

### 5. 품질, 배포, provider 생태계

- [`27-observability.md` — 27. Observability](./features/27-observability.md): OpenTelemetry,
  feature telemetry, logging, sensitive data 처리 기준을 정리한다.
- [`28-errors-resilience-security.md` — 28. Errors, Resilience, Security](./features/28-errors-resilience-security.md):
  error taxonomy, validation boundary, cancellation, timeout, cleanup, retry/security 경계를 다룬다.
- [`29-evaluation-testing.md` — 29. Evaluation & Testing](./features/29-evaluation-testing.md):
  evaluation API, checks/scoring, agent/workflow evaluation, generated/provider test harness를 정리한다.
- [`30-packaging-compatibility.md` — 30. Packaging & Compatibility](./features/30-packaging-compatibility.md):
  package layout, release metadata, compatibility surface, stability marker와 배포 단위를 다룬다.
- [`31-provider-integrations.md` — 31. Provider / Integration Inventory](./features/31-provider-integrations.md):
  dotnet 33개 project와 python 35개 package의 provider/integration inventory를 집계한다.

## 읽는 순서

1. [snapshot-manifest.md](./snapshot-manifest.md)로 commit, tree, package version, snapshot
   범위를 고정한다.
2. 이 인덱스에서 기능 그룹 지도를 훑고 Java MVP/Core+, Workflow, Hosting, Optional adapters
   범주를 먼저 분리한다.
3. `01`~`09` 문서로 core runtime과 state model을 읽고 Java `AgentEngine`의 기본 계약을 잡는다.
4. `10`~`13` 문서로 middleware, compaction, harness, skills/background/code execution 같은
   실행 seam을 읽는다.
5. `14`~`19` 문서로 workflow와 orchestration을 별도 하위 프로젝트 범위로 나눌지 판단한다.
6. `20`~`26` 문서로 hosting, protocol adapter, identity/session routing 경계를 읽고 host 책임을
   고정한다.
7. `27`~`31` 문서와 [coverage-ledger.md](./coverage-ledger.md),
   [compatibility-matrix.md](./compatibility-matrix.md)를 대조해 구현 phase, acceptance
   scenario, optional adapter 분리를 확정한다.

## Java 구현 planning으로 넘어가기 위한 gate

Java 파일·클래스·메서드 단위 planning은 다음 조건을 모두 만족할 때만 시작한다.

1. 위 31개 기능 문서의 범위를 읽고, core/runtime/workflow/hosting/provider 항목이 어느 Java
   모듈로 귀속되는지 [compatibility-matrix.md](./compatibility-matrix.md) 기준으로 합의한다.
2. [.NET/Python drift, docs-only, provider-specific 판정](./compatibility-matrix.md)이 남긴
   차이를 core contract와 optional adapter로 분리해 임의 표준화 없이 기록한다.
3. [coverage-ledger.md](./coverage-ledger.md)에서 31개 문서와 upstream 코드·테스트·ADR·spec
   집합 사이의 매핑 누락이 없음을 확인한다.
4. [snapshot-manifest.md](./snapshot-manifest.md) 기준 commit 밖의 기능이나 Learn/sample-only
   정보가 planning 입력으로 섞이지 않도록 차단한다.

이 gate를 통과한 뒤에만 Java 구현 planning을 작성하며, 그 이전에는 문서 근거 확정이
우선이다.
