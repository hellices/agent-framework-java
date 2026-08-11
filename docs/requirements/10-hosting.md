# 10 호스팅과 프로토콜

**접두사** `HOST` · **원본 기능** [20 hosting](../upstream/snapshots/d0a4165f/features/20-hosting.md),
[21 openai-responses-hosting](../upstream/snapshots/d0a4165f/features/21-openai-responses-hosting.md),
[22 a2a](../upstream/snapshots/d0a4165f/features/22-a2a.md),
[23 ag-ui](../upstream/snapshots/d0a4165f/features/23-ag-ui.md),
[25 foundry-devui-channels](../upstream/snapshots/d0a4165f/features/25-foundry-devui-channels.md),
[26 identity-session-routing](../upstream/snapshots/d0a4165f/features/26-identity-session-routing.md)

호스팅 코어, OpenAI Responses, A2A, AG-UI, Foundry·DevUI·채널, 식별 경계를 정의한다. 세션 계약은 [06 세션과 대화 상태](06-sessions.md)와 워크플로 코어는 [09 워크플로와 오케스트레이션](09-workflows.md)가 소유한다. 이 문서는 호스팅이 주입받은 계약을 어떻게 바인딩하고 저장을 조율하는지에만 집중한다.

## 채택 범위

이 문서의 `등급`은 [README](README.md#요구사항-등급) 정의대로 기능을 만들기로 했을 때의 강제력이고, 채택 여부는 [호환성 매트릭스](../upstream/snapshots/d0a4165f/compatibility-matrix.md)를 따른다.

- 호스팅 코어와 식별 경계(`HOST01`, `ID01`)는 채택 `필수`다.
- Responses(`RESP01`), A2A(`A2A01`), AG-UI(`AGUI01`), Foundry(`FND01`)와 DevUI·채널 어댑터는 호스팅 코어와 분리된 채택 `선택` 범위다.

## 요약

| ID | 요구사항 | 채택 | 등급 | 단계 |
| --- | --- | --- | --- | --- |
| HOST-001 | 호스팅 모델과 wire 프로토콜을 분리한다 | 필수 | 필수 | Core+ |
| HOST-002 | 세션 계약은 API와 엔진이 소유한다 | 필수 | 필수 | Core+ |
| HOST-003 | 호스팅 코어는 바인딩과 상태 조율만 담당한다 | 필수 | 필수 | Core+ |
| HOST-004 | 타깃 해결은 인스턴스·팩토리·비동기·빌더를 지원한다 | 필수 | 필수 | Core+ |
| HOST-005 | 세션 연속성은 작업 복사본과 단일 생성 규칙을 보장한다 | 필수 | 필수 | Core+ |
| HOST-006 | 워크플로 이어받기는 restore-then-run과 durable 폴백을 따른다 | 필수 | 필수 | Workflow |
| HOST-007 | 인메모리 연속성은 개발 편의로만 둔다 | 필수 | 필수 | Hosting |
| HOST-008 | 인증·인가·단일 writer·확장은 호스트가 책임진다 | 필수 | 필수 | Hosting |
| HOST-009 | 원시 서비스 세션 식별자는 권한 경계가 아니다 | 필수 | 필수 | Hosting |
| HOST-010 | Spring host binder는 선택 모듈로 분리한다 | 필수 | 필수 | Hosting |
| HOST-011 | Responses 어댑터는 요청 파싱과 세션 키 추출을 분리한다 | 선택 | 필수 | Hosting |
| HOST-012 | Responses 기본 매핑은 호출자 override를 엄격 거부한다 | 선택 | 필수 | Hosting |
| HOST-013 | Responses continuation은 branch pointer와 mutable head를 구분한다 | 선택 | 필수 | Hosting |
| HOST-014 | Responses SSE는 최소 프로필과 확장 프로필을 함께 지원한다 | 선택 | 권장 | Hosting |
| HOST-015 | Responses 최종 payload는 tool·reasoning·media 항목군을 보존한다 | 선택 | 필수 | Hosting |
| HOST-016 | 원격 A2A 에이전트 래퍼를 1급 어댑터로 제공한다 | 선택 | 권장 | Hosting |
| HOST-017 | 로컬 A2A 노출은 helper와 Spring binder를 분리한다 | 선택 | 필수 | Hosting |
| HOST-018 | A2A continuation은 structured serviceSessionId로 저장한다 | 선택 | 필수 | Hosting |
| HOST-019 | A2A continuation token과 새 사용자 메시지를 함께 받지 않는다 | 선택 | 필수 | Hosting |
| HOST-020 | A2A 호스트는 message·task·artifact 수명주기를 구분한다 | 선택 | 필수 | Hosting |
| HOST-021 | AG-UI 어댑터는 요청 별칭과 resume 정규화를 소유한다 | 선택 | 필수 | Hosting |
| HOST-022 | AG-UI 상태는 predictive delta와 deterministic snapshot을 분리한다 | 선택 | 필수 | Hosting |
| HOST-023 | AG-UI tool result는 UI payload와 LLM 텍스트를 분리한다 | 선택 | 권장 | Hosting |
| HOST-024 | AG-UI 지속성은 threadId와 snapshot scope를 분리한다 | 선택 | 필수 | Hosting |
| HOST-025 | AG-UI host binder는 SSE transport와 stream-complete save를 제공한다 | 선택 | 필수 | Hosting |
| HOST-026 | Foundry hosting은 선택 어댑터이며 Responses와 Invocations를 분리한다 | 선택 | 필수 | Optional |
| HOST-027 | Hosted Foundry 경로는 플랫폼 컨텍스트를 검증하고 로컬 경로를 명시적으로 구분한다 | 선택 | 필수 | Hosting |
| HOST-028 | DevUI는 개발 전용 아티팩트로 유지한다 | 선택 | 권장 | Optional |
| HOST-029 | 채널·프로토콜 어댑터는 프로토콜별 선택 아티팩트로 분리한다 | 선택 | 필수 | Optional |

---

### 호스팅 코어

## HOST-001 호스팅 모델과 wire 프로토콜을 분리한다

**요구사항.** Java는 호스팅 모델과 wire 프로토콜을 서로 다른 아티팩트와 API로 분리해야 한다.

**원본 비교**

- .NET: generic hosting, ASP.NET seam, protocol-specific helper를 나누지만 일부 프로토콜 패키지에는 route-owning 구현도 함께 둔다.
- Python: helper-first 설계를 택해 protocol helper와 generic hosting state를 더 엄격히 분리한다.

**판단.** 두 원본의 공통 의도는 분리다. Java는 Python의 더 강한 경계를 기본값으로 채택하고, batteries-included route는 선택 모듈로만 허용한다.

**수용 기준**

- hosting core 아티팩트는 OpenAI Responses, A2A, AG-UI 같은 wire DTO에 직접 의존하지 않는다.
- 프로토콜 어댑터는 hosting core에 의존할 수 있지만 hosting core가 프로토콜 어댑터를 역참조하지 않는다.

**근거** [20 hosting](../upstream/snapshots/d0a4165f/features/20-hosting.md),
[21 openai-responses-hosting](../upstream/snapshots/d0a4165f/features/21-openai-responses-hosting.md),
[22 a2a](../upstream/snapshots/d0a4165f/features/22-a2a.md),
[23 ag-ui](../upstream/snapshots/d0a4165f/features/23-ag-ui.md)

---

## HOST-002 세션 계약은 API와 엔진이 소유한다

**요구사항.** AgentSession, SessionStore, checkpoint 계약은 API와 엔진이 소유하고 호스팅은 이를 다시 정의하지 않아야 한다.

**원본 비교**

- .NET: hosting 쪽에 AgentSessionStore와 HostedWorkflowState가 있지만 계약은 protocol-neutral helper로 쓰인다.
- Python: SessionStore는 core에 남고 hosting package는 execution-state helper만 공개한다.

**판단.** 사용자 요구대로 경계를 더 좁힌다. 세션 계약을 hosting이 다시 소유하면 프로토콜과 저장소가 host 쪽으로 새어 나오므로 Java는 API·엔진 소유를 고정한다.

**수용 기준**

- hosting 관련 모듈은 SessionStore나 AgentSession의 별도 공개 사본을 만들지 않는다.
- hosting public API는 주입받은 세션·체크포인트 계약만 사용한다.

**근거** [20 hosting](../upstream/snapshots/d0a4165f/features/20-hosting.md),
[26 identity-session-routing](../upstream/snapshots/d0a4165f/features/26-identity-session-routing.md)

---

## HOST-003 호스팅 코어는 바인딩과 상태 조율만 담당한다

**요구사항.** 호스팅 코어는 target resolution, session load-save, workflow resume 조율만 담당해야 한다.

**원본 비교**

- .NET: DI registration, AgentSessionStore, HostedWorkflowState로 target과 continuation을 묶는다.
- Python: AgentState와 WorkflowState가 target resolve와 session/workflow continuation seam만 제공한다.

**판단.** 두 원본이 만나는 최소 공약수다. 상태 조율을 넘어서 auth, status code, background executor까지 가져오면 AgentEngine이 host 책임을 먹게 된다.

**수용 기준**

- hosting core public type에는 route declaration, HTTP status mapping, 인증 정책 API가 없다.
- hosting core는 run values, session snapshot, checkpoint cursor를 연결하는 API만 노출한다.

**근거** [20 hosting](../upstream/snapshots/d0a4165f/features/20-hosting.md)

---

## HOST-004 타깃 해결은 인스턴스·팩토리·비동기·빌더를 지원한다

**요구사항.** 호스팅 코어는 direct instance, sync factory, async factory, builder 기반 target resolution과 명시적 cache 정책을 지원해야 한다.

**원본 비교**

- .NET: AddAIAgent/AddWorkflow가 custom factory와 lifetime 선택을 제공한다.
- Python: AgentState/WorkflowState가 instance, callable, awaitable, SupportsBuild와 cache_target on/off를 지원한다.

**판단.** 둘 다 같은 문제를 푼다. Java는 Python의 입력 형태 폭과 .NET의 lifecycle 선택성을 함께 채택해 host 조립 비용을 낮춘다.

**수용 기준**

- 동일한 agent 또는 workflow를 인스턴스, 팩토리, 비동기 팩토리, 빌더로 등록할 수 있다.
- cache on/off 설정 차이가 반복 실행에서 관찰 가능하다.

**근거** [20 hosting](../upstream/snapshots/d0a4165f/features/20-hosting.md)

---

## HOST-005 세션 연속성은 작업 복사본과 단일 생성 규칙을 보장한다

**요구사항.** 세션 load는 저장 스냅샷과 분리된 작업 복사본을 돌려주고 create-on-miss 경쟁에서는 세션을 키당 한 번만 생성해야 한다.

**원본 비교**

- .NET: AgentSessionStore contract는 호출마다 독립 인스턴스를 반환하고 create-on-miss를 허용한다.
- Python: SessionStore는 deepcopy semantics를 쓰고 AgentState는 session_id별 lock으로 create-once를 보장한다.

**판단.** 동일하다. 작업 복사본이 없으면 조용한 공유 변경이 생기고 create race를 막지 않으면 첫 턴에 세션이 두 번 태어난다.

**수용 기준**

- 저장 후 재조회하기 전에는 반환된 세션 수정이 저장본에 반영되지 않는다.
- 같은 신규 세션 키로 동시에 들어온 첫 요청들은 세션 생성이 한 번만 일어난다.

**근거** [20 hosting](../upstream/snapshots/d0a4165f/features/20-hosting.md)

---

## HOST-006 워크플로 이어받기는 restore-then-run과 durable 폴백을 따른다

**요구사항.** 워크플로 continuation은 restore-then-run-with-new-input 규칙을 따르고 커서가 없으면 durable 최신 체크포인트로 폴백해야 한다.

**원본 비교**

- .NET: HostedWorkflowState가 restore-then-run과 latest checkpoint fallback을 직접 구현한다.
- Python: WorkflowState는 체크포인트를 직접 저장하지 않지만 README와 spec이 app-owned restore-then-run을 전제로 한다.

**판단.** .NET이 더 구체적이고 Python도 같은 의미를 전제한다. Java는 이 동작을 hosting workflow helper의 계약으로 고정한다.

**수용 기준**

- resume 호출은 이전 체크포인트 복원 뒤 새 입력으로 실행된다.
- 메모리 커서가 비어도 durable checkpoint가 있으면 최신 head에서 다시 이어간다.
- 체크포인트가 없으면 새 실행으로 시작한다.

**근거** [20 hosting](../upstream/snapshots/d0a4165f/features/20-hosting.md)

---

## HOST-007 인메모리 연속성은 개발 편의로만 둔다

**요구사항.** 인메모리 세션과 커서는 단일 프로세스 개발 편의로만 제공하고 transient 또는 multi-replica 배포의 기본값으로 쓰지 않아야 한다.

**원본 비교**

- .NET: HostedWorkflowState 문서는 in-memory cursor를 common case 최적화로만 설명한다.
- Python: hosting ADR와 README가 persistent single-process와 transient host를 명확히 구분한다.

**판단.** 동일하다. 더 안전한 기본값은 “인메모리로는 배포 연속성을 약속하지 않는다”를 문서와 테스트에 함께 남기는 것이다.

**수용 기준**

- 문서와 테스트가 인메모리 저장으로 multi-replica continuity를 보장하지 않는다고 명시한다.
- durable session/checkpoint backend를 교체 가능하게 주입할 수 있다.

**근거** [20 hosting](../upstream/snapshots/d0a4165f/features/20-hosting.md)

---

## HOST-008 인증·인가·단일 writer·확장은 호스트가 책임진다

**요구사항.** 인증, 인가, same-session single writer, background work, scaling topology 선택은 호스트 애플리케이션이 책임져야 한다.

**원본 비교**

- .NET: samples와 ADR이 route, auth, store selection, single-writer policy를 host 책임으로 둔다.
- Python: hosting README가 routing, authentication, background work, durable topology를 app 책임으로 못박는다.

**판단.** 사용자 지시를 그대로 반영한다. AgentEngine은 실행 계약만 가져야 하고 운영 정책을 기본 구현으로 숨기면 위험한 자동화가 된다.

**수용 기준**

- generic hosting core에는 authorization decision이나 lock policy의 기본 구현이 없다.
- host binder는 background executor, single-writer coordinator, durable store를 주입점으로만 노출한다.

**근거** [20 hosting](../upstream/snapshots/d0a4165f/features/20-hosting.md)

---

## HOST-009 원시 서비스 세션 식별자는 권한 경계가 아니다

**요구사항.** protocol helper나 service가 내놓은 raw session, thread, conversation, context, task 식별자는 권한 경계로 쓰지 않고 host가 principal-scoped key로 다시 바인딩해야 한다.

**원본 비교**

- .NET: sessionStoreId, ThreadId, task/context id를 authorization token으로 취급하지 말라고 반복해 경고한다.
- Python: helper가 candidate key를 추출할 뿐 trusted key가 되기 전까지는 opaque value로 남긴다.

**판단.** 가장 중요한 보안 원칙이다. 사용자 요구대로 raw service session identifier를 직접 권한 경계로 쓰지 않는 규칙을 별도 요구사항으로 고정한다.

**수용 기준**

- run parsing API와 trusted store key binding API가 분리되어 있다.
- multi-user mode에서는 principal 또는 tenant 차원이 합성되지 않은 key로 persisted state를 열 수 없다.

**근거** [20 hosting](../upstream/snapshots/d0a4165f/features/20-hosting.md),
[21 openai-responses-hosting](../upstream/snapshots/d0a4165f/features/21-openai-responses-hosting.md),
[22 a2a](../upstream/snapshots/d0a4165f/features/22-a2a.md),
[23 ag-ui](../upstream/snapshots/d0a4165f/features/23-ag-ui.md),
[26 identity-session-routing](../upstream/snapshots/d0a4165f/features/26-identity-session-routing.md)

---

## HOST-010 Spring host binder는 선택 모듈로 분리한다

**요구사항.** Spring MVC/WebFlux route binding, SSE writer, HTTP error mapping, principal-derived isolation helper는 hosting core와 분리된 선택 모듈에 있어야 한다.

**원본 비교**

- .NET: Hosting.AspNetCore, Hosting.A2A.AspNetCore, Hosting.AGUI.AspNetCore처럼 host seam을 별도 패키지로 둔다.
- Python: framework package를 최소화하거나 app-owned route를 직접 작성하는 helper-first seam을 선호한다.

**판단.** Java 생태계에서는 Spring 의존성이 크다. 그래서 .NET의 별도 host seam 구조를 취하되 generic core는 Python처럼 프레임워크 비의존으로 유지한다.

**수용 기준**

- hosting core 모듈은 Spring dependency를 직접 갖지 않는다.
- Spring binder 모듈을 제거해도 protocol adapter와 core tests가 성립한다.

**근거** [20 hosting](../upstream/snapshots/d0a4165f/features/20-hosting.md),
[22 a2a](../upstream/snapshots/d0a4165f/features/22-a2a.md),
[23 ag-ui](../upstream/snapshots/d0a4165f/features/23-ag-ui.md)

---

---

### OpenAI Responses

## HOST-011 Responses 어댑터는 요청 파싱과 세션 키 추출을 분리한다

**요구사항.** OpenAI Responses 어댑터는 run request 파싱 API와 continuation candidate key 추출 API를 분리해야 한다.

**원본 비교**

- .NET: ToAgentRunRequest와 GetSessionStoreId를 별도 helper로 둔다.
- Python: responses_to_run과 responses_session_id를 별도 helper로 둔다.

**판단.** 동일하다. 이 분리가 있어야 “요청을 읽을 수 있다”와 “그 키로 상태를 열 수 있다”를 혼동하지 않는다.

**수용 기준**

- JSON body 파싱만으로 store lookup이 자동 수행되지 않는다.
- 세션 키 추출 결과는 host authorization을 거치기 전까지 raw candidate value로 남는다.

**근거** [21 openai-responses-hosting](../upstream/snapshots/d0a4165f/features/21-openai-responses-hosting.md),
[26 identity-session-routing](../upstream/snapshots/d0a4165f/features/26-identity-session-routing.md)

---

## HOST-012 Responses 기본 매핑은 호출자 override를 엄격 거부한다

**요구사항.** OpenAI Responses 기본 request mapper는 caller-supplied tools, tool_choice, instructions, sampling override를 기본 거부해야 한다.

**원본 비교**

- .NET: RunOptionsFactory 기본 구현이 tools, tool_choice, instructions, sampling fields를 NotSupported로 거부한다.
- Python: transport field를 제외한 다수 옵션을 pass-through하므로 host allowlist가 필요하다.

**판단.** 더 안전한 기본값을 택한다. self-hosted endpoint에서 agent 설정을 조용히 덮어쓰지 않게 .NET의 strict default를 Java 기본값으로 채택한다.

**수용 기준**

- 기본 설정에서 caller가 tools나 sampling 값을 보내면 명시적 validation error가 난다.
- host는 별도 allowlist mapper를 등록해 필요한 필드만 opt-in 할 수 있다.

**근거** [21 openai-responses-hosting](../upstream/snapshots/d0a4165f/features/21-openai-responses-hosting.md)

---

## HOST-013 Responses continuation은 branch pointer와 mutable head를 구분한다

**요구사항.** OpenAI Responses 호스팅은 previous_response_id를 immutable branch pointer로, conversation 계열을 stable mutable head로 구분해야 한다.

**원본 비교**

- .NET: PreviousResponseId는 stable partition key가 아니고 ConversationId가 stable key라고 설명한다.
- Python: README가 previous_response_id branch와 mutable conversation head를 직접 구분한다.

**판단.** 동일하다. branch와 head를 섞으면 병렬 분기와 single-writer 직렬화 규칙을 동시에 깨뜨린다.

**수용 기준**

- 같은 previous_response_id에서 두 분기를 시작해도 상태가 서로 섞이지 않는다.
- mutable conversation head 경로는 host가 single-writer coordination을 적용할 수 있어야 한다.

**근거** [21 openai-responses-hosting](../upstream/snapshots/d0a4165f/features/21-openai-responses-hosting.md)

---

## HOST-014 Responses SSE는 최소 프로필과 확장 프로필을 함께 지원한다

**요구사항.** OpenAI Responses 스트리밍은 created-delta-terminal 최소 프로필을 보장하고 필요할 때 itemized rich event 프로필을 추가로 제공해야 한다.

**원본 비교**

- .NET: response lifecycle, output item, content part, function args, reasoning delta까지 포함한 full typed event union을 가진다.
- Python: created, text delta, completed 또는 failed 중심의 단순 SSE helper를 제공한다.

**판단.** 호환성과 구현 비용을 함께 보려면 두 층이 필요하다. 최소 프로필은 Python 수준으로 맞추고, rich profile은 .NET 수준 기능을 별도 renderer option으로 노출한다.

**수용 기준**

- simple profile은 response.created와 terminal completed 또는 failed를 항상 보낸다.
- text streaming이 있으면 delta 순서가 보존된다.
- full profile을 켜면 function, reasoning, media 계열 event를 loss 없이 추가할 수 있다.

**근거** [21 openai-responses-hosting](../upstream/snapshots/d0a4165f/features/21-openai-responses-hosting.md)

---

## HOST-015 Responses 최종 payload는 tool·reasoning·media 항목군을 보존한다

**요구사항.** OpenAI Responses 최종 payload는 텍스트만이 아니라 function_call, function_call_output, reasoning, media·file 항목군을 표현해야 한다.

**원본 비교**

- .NET: streaming path에서 function call, reasoning, media, approval event를 typed event union으로 표면화한다.
- Python: final JSON renderer가 reasoning, function_call, function_call_output, MCP, shell, approval, media/file item family를 직접 만든다.

**판단.** 둘 다 plain text 이상의 surface를 전제한다. Java는 final payload 표현력을 Python 수준으로 고정해 provider-neutral 콘텐츠 손실을 막는다.

**수용 기준**

- 최종 JSON은 assistant text 외에 function_call과 function_call_output을 구분해 담을 수 있다.
- reasoning과 file 또는 URI 계열 결과가 text fallback 없이 보존된다.

**근거** [21 openai-responses-hosting](../upstream/snapshots/d0a4165f/features/21-openai-responses-hosting.md)

---

---

### A2A

## HOST-016 원격 A2A 에이전트 래퍼를 1급 어댑터로 제공한다

**요구사항.** Java는 remote A2A client 또는 AgentCard를 local Agent처럼 쓰는 래퍼를 1급 어댑터로 제공해야 한다.

**원본 비교**

- .NET: IA2AClient.AsAIAgent와 AgentCard.AsAIAgent로 두 bootstrap 경로를 제공한다.
- Python: A2AAgent가 URL, agent_card, existing client 세 경로를 지원한다.

**판단.** 두 원본이 모두 가진 핵심 기능이다. A2A를 host-only 기능으로 밀어 버리면 원격 agent 상호운용성이 불필요하게 약해진다.

**수용 기준**

- 직접 구성한 client와 discovery된 card 양쪽에서 remote A2A agent를 생성할 수 있다.
- immediate message와 task 응답이 모두 local agent response surface로 변환된다.

**근거** [22 a2a](../upstream/snapshots/d0a4165f/features/22-a2a.md)

---

## HOST-017 로컬 A2A 노출은 helper와 Spring binder를 분리한다

**요구사항.** 로컬 Agent나 Workflow를 A2A로 노출할 때는 conversion helper와 Spring route binder를 분리해야 한다.

**원본 비교**

- .NET: A2A hosting package와 AspNetCore endpoint package를 나눠 둔다.
- Python: native A2A server 조합을 위한 helper-only package와 full executor path를 분리한다.

**판단.** helper-first가 더 이식성이 높다. Java는 host가 native A2A server를 조립할 수 있는 seam을 기본으로 두고 Spring binder는 선택으로 둔다.

**수용 기준**

- A2A conversion/card helper는 Spring 없이 사용할 수 있다.
- Spring binder는 helper 계층에만 의존하고 protocol conversion을 재구현하지 않는다.

**근거** [22 a2a](../upstream/snapshots/d0a4165f/features/22-a2a.md)

---

## HOST-018 A2A continuation은 structured serviceSessionId로 저장한다

**요구사항.** A2A session continuation은 contextId, taskId, taskState를 담는 structured serviceSessionId로 저장해야 한다.

**원본 비교**

- .NET: A2AAgentSession이라는 전용 세션 타입으로 contextId, taskId, taskState를 보존한다.
- Python: A2AServiceSessionId를 선호하고 A2AAgentSession은 deprecated compatibility layer로 민다.

**판단.** Python 쪽 결정이 더 미래지향적이다. core가 전용 세션 subclass를 알지 않아도 되게 adapter-owned structured value로 저장한다.

**수용 기준**

- serviceSessionId는 단순 문자열과 structured continuation 둘 다 저장·복원할 수 있다.
- A2A continuation 필드를 generic telemetry나 unrelated core code가 직접 파싱하지 않는다.

**근거** [22 a2a](../upstream/snapshots/d0a4165f/features/22-a2a.md),
[26 identity-session-routing](../upstream/snapshots/d0a4165f/features/26-identity-session-routing.md)

---

## HOST-019 A2A continuation token과 새 사용자 메시지를 함께 받지 않는다

**요구사항.** A2A continuation token을 사용하는 요청은 새 사용자 메시지를 함께 받지 않아야 한다.

**원본 비교**

- .NET: ContinuationToken과 user messages 동시 사용을 금지한다.
- Python: continuation_token path와 새 task 시작 path를 run API에서 분리한다.

**판단.** 동일하다. 이어받기와 새 입력을 한 요청에 섞으면 task resume와 refinement 의미가 모호해진다.

**수용 기준**

- continuation token과 새 입력을 동시에 주면 validation error가 난다.
- poll 또는 subscribe resume 경로가 fresh run 경로와 다른 API나 모드로 드러난다.

**근거** [22 a2a](../upstream/snapshots/d0a4165f/features/22-a2a.md)

---

## HOST-020 A2A 호스트는 message·task·artifact 수명주기를 구분한다

**요구사항.** A2A 호스트는 immediate message, in-progress task, artifact updates를 서로 다른 수명주기로 표면화해야 한다.

**원본 비교**

- .NET: handler가 continuation 유무에 따라 lightweight message와 task lifecycle을 구분한다.
- Python: A2AExecutor가 stable artifact id와 append semantics로 task artifact updates를 만든다.

**판단.** A2A의 핵심은 task-aware surface다. text chat처럼 평탄화하면 long-running work와 partial artifacts가 사라진다.

**수용 기준**

- 즉시 완료 응답은 message surface로 끝낼 수 있고 진행 중 작업은 task state와 continuation 정보를 노출한다.
- 같은 논리 응답의 streaming chunk는 stable artifact id 아래 append 의미를 유지한다.

**근거** [22 a2a](../upstream/snapshots/d0a4165f/features/22-a2a.md)

---

---

### AG-UI

## HOST-021 AG-UI 어댑터는 요청 별칭과 resume 정규화를 소유한다

**요구사항.** AG-UI protocol adapter는 snake_case와 camelCase 별칭을 모두 수용하고 legacy resume payload를 canonical interrupt-resume 모델로 정규화해야 한다.

**원본 비교**

- .NET: repo-local 구현은 AG-UI SDK RunAgentInput을 받아 host concern만 덧붙인다.
- Python: AGUIRequest가 alias와 legacy resume coercion을 직접 구현한다.

**판단.** Java는 SDK 유무와 무관하게 protocol adapter 자체에 이 정규화 규칙을 두는 편이 낫다. 그래야 host binder가 request shape를 다시 해석하지 않는다.

**수용 기준**

- runId/run_id, threadId/thread_id 같은 별칭이 모두 허용된다.
- legacy resume shape가 canonical ResumeEntry 배열로 정규화된다.

**근거** [23 ag-ui](../upstream/snapshots/d0a4165f/features/23-ag-ui.md)

---

## HOST-022 AG-UI 상태는 predictive delta와 deterministic snapshot을 분리한다

**요구사항.** AG-UI state surface는 predictive delta와 deterministic snapshot을 서로 다른 API로 제공해야 한다.

**원본 비교**

- .NET: repo-local layer는 raw StateSnapshotEvent 보존과 session persistence 위주다.
- Python: PredictiveStateHandler와 state_update()가 optimistic delta와 authoritative snapshot을 분리해 구현한다.

**판단.** Python 구현이 더 구체적이고 UI 품질에 직접 도움이 된다. Java도 예측 경로와 확정 경로를 분리해 상태 튐과 권한 있는 결과 반영을 구별한다.

**수용 기준**

- tool args streaming만으로 StateDeltaEvent를 만들 수 있다.
- 실제 tool result 후에는 별도 deterministic snapshot API가 authoritative state를 내보낸다.

**근거** [23 ag-ui](../upstream/snapshots/d0a4165f/features/23-ag-ui.md)

---

## HOST-023 AG-UI tool result는 UI payload와 LLM 텍스트를 분리한다

**요구사항.** AG-UI tool result는 UI에 보여 줄 payload와 LLM이 다시 읽을 텍스트 결과를 서로 다른 채널로 보존해야 한다.

**원본 비교**

- .NET: repo-local source는 tool event 생성보다 transport와 continuity에 집중하고 세부 payload split은 직접 구현하지 않는다.
- Python: ToolCallResultEvent.content와 function result text를 분리하는 경로를 직접 구현한다.

**판단.** UI 친화성과 모델 안정성을 동시에 얻으려면 Python 쪽 split을 채택하는 편이 낫다. 하나의 텍스트로 합치면 UI 구조와 model context가 서로 오염된다.

**수용 기준**

- tool result event는 display payload를 별도 필드로 실을 수 있다.
- 동시에 LLM에 다시 주입할 function result text가 손실되지 않는다.

**근거** [23 ag-ui](../upstream/snapshots/d0a4165f/features/23-ag-ui.md)

---

## HOST-024 AG-UI 지속성은 threadId와 snapshot scope를 분리한다

**요구사항.** AG-UI persistence는 transport threadId와 authorization boundary인 snapshot scope를 분리해야 한다.

**원본 비교**

- .NET: ThreadId를 bare persistent key로 쓰는 것은 single-user prototype에만 안전하다고 경고한다.
- Python: snapshot persistence가 켜지면 snapshot_scope_resolver를 강제하고 workflow cache도 scope와 thread를 함께 keying한다.

**판단.** 사용자 요구와 정확히 맞는다. threadId만 믿으면 다른 사용자의 스냅샷을 열 수 있으므로 scope resolver를 필수 경계로 둔다.

**수용 기준**

- 지속성을 켤 때 multi-user mode에서는 scope resolver 없이 부팅되지 않는다.
- 같은 threadId라도 다른 scope에서는 state와 workflow cache를 공유하지 않는다.

**근거** [23 ag-ui](../upstream/snapshots/d0a4165f/features/23-ag-ui.md),
[26 identity-session-routing](../upstream/snapshots/d0a4165f/features/26-identity-session-routing.md)

---

## HOST-025 AG-UI host binder는 SSE transport와 stream-complete save를 제공한다

**요구사항.** AG-UI host binder는 SSE와 keepalive를 transport concern으로 처리하고 세션 저장은 스트림 완료 시점에 수행해야 한다.

**원본 비교**

- .NET: SSE polyfill과 stream completion 이후 SaveSessionAsync를 제공한다.
- Python: endpoint가 keepalive와 SSE headers를 소유하고 event stream 종료 후 outer transport가 끝난다.

**판단.** 동일한 책임 분리다. keepalive는 transport 옵션이고 세션 저장은 스트림 소비 완료에 묶여야 partial state 확정을 피할 수 있다.

**수용 기준**

- SSE headers와 keepalive 주기는 binder 설정으로 제어된다.
- 세션 저장은 첫 delta 직후가 아니라 stream finalization 후에 일어난다.

**근거** [23 ag-ui](../upstream/snapshots/d0a4165f/features/23-ag-ui.md)

---

---

### Foundry·DevUI·채널

## HOST-026 Foundry hosting은 선택 어댑터이며 Responses와 Invocations를 분리한다

**요구사항.** Foundry hosting은 core가 아닌 선택 어댑터로 두고 Responses contract와 Invocations contract를 분리해야 한다.

**원본 비교**

- .NET: Foundry.Hosting이 Responses route와 toolbox bridge를 제공한다.
- Python: ResponsesHostServer와 InvocationsHostServer를 별도 surface로 제공한다.

**판단.** Foundry는 관리형 런타임 전용 계약이다. core에 섞지 말고 runtime contract별 entrypoint를 나눠야 테스트와 배포 영향이 줄어든다.

**수용 기준**

- core 모듈은 Foundry SDK나 hosted runtime header에 직접 의존하지 않는다.
- Responses와 Invocations는 별도 server surface 또는 모듈로 노출된다.

**근거** [25 foundry-devui-channels](../upstream/snapshots/d0a4165f/features/25-foundry-devui-channels.md)

---

## HOST-027 Hosted Foundry 경로는 플랫폼 컨텍스트를 검증하고 로컬 경로를 명시적으로 구분한다

**요구사항.** Hosted Foundry 경로는 platform context를 검증해 fail-fast 해야 하고 local 개발 경로는 spoofable hosted header와 구분된 명시적 fallback만 허용해야 한다.

**원본 비교**

- .NET: hosted env에서는 call id와 user identity를 요구하고 local에는 null-tolerant path를 둔다.
- Python: validate_foundry_request_context가 hosted 모드에서 call_id와 user_id를 강제한다.

**판단.** 동일하다. hosted isolation을 local header 흉내로 우회하게 두면 권한 경계가 무너진다.

**수용 기준**

- hosted mode에서 필수 platform context가 없으면 명시적 protocol error가 난다.
- local mode는 별도 설정이나 코드 경로로만 활성화되고 raw hosted header만으로 승격되지 않는다.

**근거** [25 foundry-devui-channels](../upstream/snapshots/d0a4165f/features/25-foundry-devui-channels.md),
[26 identity-session-routing](../upstream/snapshots/d0a4165f/features/26-identity-session-routing.md)

---

## HOST-028 DevUI는 개발 전용 아티팩트로 유지한다

**요구사항.** DevUI와 유사 진단 UI는 개발 전용 아티팩트로 유지하고 production runtime의 기본 의존성으로 넣지 않아야 한다.

**원본 비교**

- .NET: DevUI와 Aspire DevUI integration을 preview dev surface로 제공한다.
- Python: DevServer가 developer-facing UI와 API를 제공하지만 auth, host binding, CORS를 명시적 운영 경계로 둔다.

**판단.** 두 원본 모두 개발 도구로 취급한다. Java도 같은 분리를 택해 production classpath와 attack surface를 불필요하게 넓히지 않는다.

**수용 기준**

- DevUI 모듈을 제외해도 production hosting과 provider 모듈이 정상 동작한다.
- DevUI 기본 접근 정책은 loopback 또는 명시적 remote enable 같은 제한적 기본값을 가진다.

**근거** [25 foundry-devui-channels](../upstream/snapshots/d0a4165f/features/25-foundry-devui-channels.md)

---

## HOST-029 채널·프로토콜 어댑터는 프로토콜별 선택 아티팩트로 분리한다

**요구사항.** ChatKit, Telegram, DevUI 같은 채널·프로토콜 어댑터는 protocol별 선택 아티팩트로 분리하고 하나의 all-channels 번들로 묶지 않아야 한다.

**원본 비교**

- .NET: Aspire DevUI, Hosting.AspNetCore, A2A.AspNetCore처럼 host·protocol 패키지를 세분화한다.
- Python: ChatKit, hosting-telegram, hosting-mcp, hosting-a2a, ag-ui를 각기 독립 package로 둔다.

**판단.** 원본 inventory가 이미 보여 주는 방향이다. protocol별 버전, 위험, maturity가 다르므로 선택 아티팩트가 Java에도 맞다.

**수용 기준**

- 채널 어댑터 제거가 core 또는 다른 프로토콜 어댑터의 binary dependency를 깨지 않는다.
- 문서와 빌드 설정에서 각 채널 어댑터가 별도 artifact로 식별된다.

**근거** [25 foundry-devui-channels](../upstream/snapshots/d0a4165f/features/25-foundry-devui-channels.md),
[31 provider-integrations](../upstream/snapshots/d0a4165f/features/31-provider-integrations.md)

---

## 이 문서가 다루지 않는 것

| 주제 | 소유 문서 |
| --- | --- |
| Agent와 ChatClient의 실행 계약 | [01 에이전트 실행과 모델 호출](01-agent-execution.md) |
| 세션 직렬화 형식과 저장소 내부 구조 | [06 세션과 대화 상태](06-sessions.md) |
| 워크플로 그래프와 체크포인트 의미 | [09 워크플로와 오케스트레이션](09-workflows.md) |
| 관찰성, 오류 taxonomy, 패키징 정책 | [11 운영 품질](11-operations.md) |
| 공급자 inventory와 어댑터 우선순위 일반론 | [12 공급자 통합](12-providers.md) |
