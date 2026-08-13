# Requirements traceability matrix

이 표는 `docs/requirements/`의 244개 ID를 canonical design, 목표 code family, 현재 구현 상태,
검증 계층에 연결한다.

## 상태 해석

- `absent`: 요구 행동 production code 없음
- `bootstrap`: module/marker는 있지만 요구 행동은 없음
- `partial`: 수용 기준 일부에 해당하는 build/policy code와 test가 있음
- `implemented`: 수용 기준 전체가 production code와 executable test에 연결됨

`ApiContract`와 `EngineContract` marker는 module bootstrap이지 기능 구현이 아니므로 각 기능 ID는
`absent`다. 목표 code family는 package/module 계획이며 현재 파일 경로가 아니다.

| ID | 요구사항 | Canonical design | 목표 code family | 현재 | 검증 |
| --- | --- | --- | --- | --- | --- |
| AGT-001 | 공개 진입점은 `Agent` 하나로 통일한다 | [01-core-execution.md](01-core-execution.md) | `api.agent`, `spi.model`, `engine.internal.run` | `absent` | unit + agent contract + golden |
| AGT-002 | 모든 에이전트는 안정적인 식별자를 가진다 | [01-core-execution.md](01-core-execution.md) | `api.agent`, `spi.model`, `engine.internal.run` | `absent` | unit + agent contract + golden |
| AGT-003 | 실행과 스트리밍 진입점을 분리한다 | [01-core-execution.md](01-core-execution.md) | `api.agent`, `spi.model`, `engine.internal.run` | `absent` | unit + agent contract + golden |
| AGT-004 | 스트리밍만 소비해도 실행이 완결된다 | [01-core-execution.md](01-core-execution.md) | `api.agent`, `spi.model`, `engine.internal.run` | `absent` | unit + agent contract + golden |
| AGT-005 | 취소는 명시적 인자로 전달한다 | [01-core-execution.md](01-core-execution.md) | `api.agent`, `spi.model`, `engine.internal.run` | `absent` | unit + agent contract + golden |
| AGT-006 | 에이전트는 세션 호환성만 검증한다 | [01-core-execution.md](01-core-execution.md) | `api.agent`, `spi.model`, `engine.internal.run` | `absent` | unit + agent contract + golden |
| AGT-007 | 데코레이터로 에이전트를 감쌀 수 있다 | [01-core-execution.md](01-core-execution.md) | `api.agent`, `spi.model`, `engine.internal.run` | `absent` | unit + agent contract + golden |
| AGT-008 | 실행 컨텍스트를 명시적으로 전달한다 | [01-core-execution.md](01-core-execution.md) | `api.agent`, `spi.model`, `engine.internal.run` | `absent` | unit + agent contract + golden |
| AGT-009 | 모델 호출은 `ModelClient` 포트로 분리한다 | [01-core-execution.md](01-core-execution.md) | `api.agent`, `spi.model`, `engine.internal.run` | `absent` | unit + agent contract + golden |
| AGT-010 | 선택적 모델 기능은 별도 인터페이스로 노출한다 | [01-core-execution.md](01-core-execution.md) | `api.agent`, `spi.model`, `engine.internal.run` | `absent` | unit + agent contract + golden |
| AGT-011 | 공통 요청 옵션은 타입 안전한 공급자 중립 계약으로 둔다 | [01-core-execution.md](01-core-execution.md) | `api.agent`, `spi.model`, `engine.internal.run` | `absent` | unit + agent contract + golden |
| AGT-012 | 옵션 병합 우선순위를 고정한다 | [01-core-execution.md](01-core-execution.md) | `api.agent`, `spi.model`, `engine.internal.run` | `absent` | unit + agent contract + golden |
| AGT-013 | 실행 단위로 모델 클라이언트를 교체할 수 있다 | [01-core-execution.md](01-core-execution.md) | `api.agent`, `spi.model`, `engine.internal.run` | `absent` | unit + agent contract + golden |
| AGT-014 | 이어받기 실행에 새 입력을 함께 줄 수 없다 | [01-core-execution.md](01-core-execution.md) | `api.agent`, `spi.model`, `engine.internal.run` | `absent` | unit + agent contract + golden |
| AGT-015 | 스트리밍 조각만으로 최종 응답을 복원한다 | [01-core-execution.md](01-core-execution.md) | `api.agent`, `spi.model`, `engine.internal.run` | `absent` | unit + agent contract + golden |
| AGT-016 | 서비스가 이력을 저장하면 로컬 이력을 중복 저장하지 않는다 | [01-core-execution.md](01-core-execution.md) | `api.agent`, `spi.model`, `engine.internal.run` | `absent` | unit + agent contract + golden |
| MSG-001 | 코어가 대화 타입을 직접 소유한다 | [01-core-execution.md](01-core-execution.md) | `api.message`, `engine.internal.model` | `absent` | value unit + serialization + golden |
| MSG-002 | 역할은 알려진 값과 사용자 정의 값을 함께 허용한다 | [01-core-execution.md](01-core-execution.md) | `api.message`, `engine.internal.model` | `absent` | value unit + serialization + golden |
| MSG-003 | 입력을 메시지 목록으로 정규화한다 | [01-core-execution.md](01-core-execution.md) | `api.message`, `engine.internal.model` | `absent` | value unit + serialization + golden |
| MSG-004 | 텍스트 투영은 텍스트 콘텐츠만 본다 | [01-core-execution.md](01-core-execution.md) | `api.message`, `engine.internal.model` | `absent` | value unit + serialization + golden |
| MSG-005 | 코어가 기본 멀티모달 콘텐츠 종류를 제공한다 | [01-core-execution.md](01-core-execution.md) | `api.message`, `engine.internal.model` | `absent` | value unit + serialization + golden |
| MSG-006 | URI와 바이너리 콘텐츠를 생성 시 검증한다 | [01-core-execution.md](01-core-execution.md) | `api.message`, `engine.internal.model` | `absent` | value unit + serialization + golden |
| MSG-007 | 추가 속성과 원시 표현을 보존한다 | [01-core-execution.md](01-core-execution.md) | `api.message`, `engine.internal.model` | `absent` | value unit + serialization + golden |
| MSG-008 | 메시지 출처는 콘텐츠와 분리해 태깅한다 | [01-core-execution.md](01-core-execution.md) | `api.message`, `engine.internal.model` | `absent` | value unit + serialization + golden |
| MSG-009 | 선두 지시문은 중복 삽입하지 않는다 | [01-core-execution.md](01-core-execution.md) | `api.message`, `engine.internal.model` | `absent` | value unit + serialization + golden |
| MSG-010 | 사용량은 표준 필드와 확장 필드를 함께 가진다 | [01-core-execution.md](01-core-execution.md) | `api.message`, `engine.internal.model` | `absent` | value unit + serialization + golden |
| MSG-011 | 응답과 업데이트는 같은 메타데이터 축을 공유한다 | [01-core-execution.md](01-core-execution.md) | `api.message`, `engine.internal.model` | `absent` | value unit + serialization + golden |
| MSG-012 | 업데이트 시퀀스에서 최종 응답을 복원한다 | [01-core-execution.md](01-core-execution.md) | `api.message`, `engine.internal.model` | `absent` | value unit + serialization + golden |
| MSG-013 | 스트림 래퍼는 최종 응답과 변환 훅을 제공한다 | [01-core-execution.md](01-core-execution.md) | `api.message`, `engine.internal.model` | `absent` | value unit + serialization + golden |
| OUT-001 | 구조화 출력은 명시적으로 요청한다 | [01-core-execution.md](01-core-execution.md) | `api.structured`, `engine.internal.structured` | `absent` | parser/schema contract + golden |
| OUT-002 | 타입 검증 경로와 JSON 전용 경로를 함께 지원한다 | [01-core-execution.md](01-core-execution.md) | `api.structured`, `engine.internal.structured` | `absent` | parser/schema contract + golden |
| OUT-003 | 비객체 목표 타입은 래퍼 스키마로 요청한다 | [01-core-execution.md](01-core-execution.md) | `api.structured`, `engine.internal.structured` | `absent` | parser/schema contract + golden |
| OUT-004 | response format 우선순위를 고정한다 | [01-core-execution.md](01-core-execution.md) | `api.structured`, `engine.internal.structured` | `absent` | parser/schema contract + golden |
| OUT-005 | 네이티브 지원 여부는 최선 노력 계약으로 본다 | [01-core-execution.md](01-core-execution.md) | `api.structured`, `engine.internal.structured` | `absent` | parser/schema contract + golden |
| OUT-006 | 타입 값 파싱은 응답 접근 시점에 수행한다 | [01-core-execution.md](01-core-execution.md) | `api.structured`, `engine.internal.structured` | `absent` | parser/schema contract + golden |
| OUT-007 | 구조화 파싱 대상은 마지막 assistant 텍스트 본문이다 | [01-core-execution.md](01-core-execution.md) | `api.structured`, `engine.internal.structured` | `absent` | parser/schema contract + golden |
| OUT-008 | 검증 오류와 JSON 파싱 오류를 구분한다 | [01-core-execution.md](01-core-execution.md) | `api.structured`, `engine.internal.structured` | `absent` | parser/schema contract + golden |
| OUT-009 | 비어 있거나 null인 structured payload는 명시적으로 실패한다 | [01-core-execution.md](01-core-execution.md) | `api.structured`, `engine.internal.structured` | `absent` | parser/schema contract + golden |
| OUT-010 | 유효한 JSON 텍스트면 네이티브 미지원이어도 폴백 파싱한다 | [01-core-execution.md](01-core-execution.md) | `api.structured`, `engine.internal.structured` | `absent` | parser/schema contract + golden |
| OUT-011 | 래퍼가 기대됐는데 bare JSON이 오면 원본 JSON으로 재시도한다 | [01-core-execution.md](01-core-execution.md) | `api.structured`, `engine.internal.structured` | `absent` | parser/schema contract + golden |
| OUT-012 | 구조화 스트리밍 값은 최종 응답에서만 읽는다 | [01-core-execution.md](01-core-execution.md) | `api.structured`, `engine.internal.structured` | `absent` | parser/schema contract + golden |
| TOOL-001 | 도구는 코어 정의 타입으로 표현한다 | [01-core-execution.md](01-core-execution.md) | `api.tool`, `spi.tool`, `engine.internal.tool` | `absent` | tool contract + loop golden |
| TOOL-002 | 도구 실행 루프는 Java 코어가 단독 소유한다 | [01-core-execution.md](01-core-execution.md) | `api.tool`, `spi.tool`, `engine.internal.tool` | `absent` | tool contract + loop golden |
| TOOL-003 | 입력 스키마는 추론하되 명시 스키마가 이긴다 | [01-core-execution.md](01-core-execution.md) | `api.tool`, `spi.tool`, `engine.internal.tool` | `absent` | tool contract + loop golden |
| TOOL-004 | 컨텍스트 인자는 스키마 밖에서 주입한다 | [01-core-execution.md](01-core-execution.md) | `api.tool`, `spi.tool`, `engine.internal.tool` | `absent` | tool contract + loop golden |
| TOOL-005 | 모든 호출 경로에서 인자를 검증한다 | [01-core-execution.md](01-core-execution.md) | `api.tool`, `spi.tool`, `engine.internal.tool` | `absent` | tool contract + loop golden |
| TOOL-006 | 선언 전용 도구와 추가 도구는 로컬 실행하지 않는다 | [01-core-execution.md](01-core-execution.md) | `api.tool`, `spi.tool`, `engine.internal.tool` | `absent` | tool contract + loop golden |
| TOOL-007 | 도구 선택은 `ToolMode`로 고정한다 | [01-core-execution.md](01-core-execution.md) | `api.tool`, `spi.tool`, `engine.internal.tool` | `absent` | tool contract + loop golden |
| TOOL-008 | 실행 옵션이 기본 옵션을 이긴다 | [01-core-execution.md](01-core-execution.md) | `api.tool`, `spi.tool`, `engine.internal.tool` | `absent` | tool contract + loop golden |
| TOOL-009 | 호출 설정은 정규화된 타입과 안전 기본값을 가진다 | [01-core-execution.md](01-core-execution.md) | `api.tool`, `spi.tool`, `engine.internal.tool` | `absent` | tool contract + loop golden |
| TOOL-010 | 반복 제한과 호출 예산의 의미를 고정한다 | [01-core-execution.md](01-core-execution.md) | `api.tool`, `spi.tool`, `engine.internal.tool` | `absent` | tool contract + loop golden |
| TOOL-011 | 호출 예산은 배치 뒤 best-effort로 차단한다 | [01-core-execution.md](01-core-execution.md) | `api.tool`, `spi.tool`, `engine.internal.tool` | `absent` | tool contract + loop golden |
| TOOL-012 | 병렬 실행은 실행 가능 배치만 다루고 순서를 보존한다 | [01-core-execution.md](01-core-execution.md) | `api.tool`, `spi.tool`, `engine.internal.tool` | `absent` | tool contract + loop golden |
| TOOL-013 | 도구 결과는 `Content` 목록으로 정규화한다 | [01-core-execution.md](01-core-execution.md) | `api.tool`, `spi.tool`, `engine.internal.tool` | `absent` | tool contract + loop golden |
| TOOL-014 | 도구 정의와 런타임 카운터를 분리한다 | [01-core-execution.md](01-core-execution.md) | `api.tool`, `spi.tool`, `engine.internal.tool` | `absent` | tool contract + loop golden |
| TOOL-015 | 스트리밍도 같은 도구 루프를 탄다 | [01-core-execution.md](01-core-execution.md) | `api.tool`, `spi.tool`, `engine.internal.tool` | `absent` | tool contract + loop golden |
| TOOL-016 | 승인 요청과 응답은 코어 콘텐츠다 | [01-core-execution.md](01-core-execution.md) | `api.tool`, `spi.tool`, `engine.internal.tool` | `absent` | tool contract + loop golden |
| TOOL-017 | 승인 응답은 원래 요청에만 결합한다 | [01-core-execution.md](01-core-execution.md) | `api.tool`, `spi.tool`, `engine.internal.tool` | `absent` | tool contract + loop golden |
| TOOL-018 | 승인 거부는 합성 종료 결과를 남긴다 | [01-core-execution.md](01-core-execution.md) | `api.tool`, `spi.tool`, `engine.internal.tool` | `absent` | tool contract + loop golden |
| TOOL-019 | 상시 승인은 정확한 인자와 호스트 경계로 매칭한다 | [01-core-execution.md](01-core-execution.md) | `api.tool`, `spi.tool`, `engine.internal.tool` | `absent` | tool contract + loop golden |
| TOOL-020 | 승인 큐는 세션 위에서 하나씩 진행한다 | [01-core-execution.md](01-core-execution.md) | `api.tool`, `spi.tool`, `engine.internal.tool` | `absent` | tool contract + loop golden |
| TOOL-021 | 자동 승인 연쇄는 상한으로 끊는다 | [01-core-execution.md](01-core-execution.md) | `api.tool`, `spi.tool`, `engine.internal.tool` | `absent` | tool contract + loop golden |
| MCP-001 | transport 도구와 연결 어댑터를 함께 둔다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `integrations/agent-framework-mcp` | `absent` | MCP client adapter contract |
| MCP-002 | 연결 소유권에 따라 수명주기를 나눈다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `integrations/agent-framework-mcp` | `absent` | owned/borrowed lifecycle contract |
| MCP-003 | 연결 검증과 재연결을 표준화한다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `integrations/agent-framework-mcp` | `absent` | reconnect/cache contract |
| MCP-004 | 도구 발견은 prefix와 충돌 검사를 포함한다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `integrations/agent-framework-mcp` | `absent` | discovery pagination/collision test |
| MCP-005 | 호출 인자와 메타데이터를 분리한다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `integrations/agent-framework-mcp` | `absent` | invocation mapping contract |
| MCP-006 | prompts는 함수처럼 노출하고 resources는 payload로 둔다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `integrations/agent-framework-mcp` | `absent` | prompt/resource mapping test |
| MCP-007 | sampling은 기본 거부다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `integrations/agent-framework-mcp` | `absent` | sampling default-deny test |
| MCP-008 | sampling 요청 수와 토큰 수를 제한한다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `integrations/agent-framework-mcp` | `absent` | sampling budget test |
| MCP-009 | 연결 소유권에 따라 추적 전파를 구분한다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `integrations/agent-framework-mcp` | `absent` | trace ownership contract |
| MCP-010 | HTTP 헤더는 같은 origin에만 전파한다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `integrations/agent-framework-mcp` | `absent` | same-origin/concurrency test |
| MCP-011 | task-required 도구는 작업 수명주기로 우회한다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `integrations/agent-framework-mcp` | `absent` | task switch contract |
| MCP-012 | 작업 생성 뒤에는 원 호출을 재발행하지 않는다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `integrations/agent-framework-mcp` | `absent` | no-duplicate-call test |
| MCP-013 | 취소와 시간 제한은 원격 취소로 연결한다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `integrations/agent-framework-mcp` | `absent` | remote cancellation test |
| MCP-014 | 작업 종료 상태를 명시적 결과로 바꾼다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `integrations/agent-framework-mcp` | `absent` | task outcome mapping |
| MCP-015 | 범용 hosting helper는 별도 아티팩트로 분리한다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `protocols/agent-framework-mcp-hosting` | `absent` | module/API boundary test |
| MCP-016 | hosting helper는 서버 호스트 책임을 가져오지 않는다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `protocols/agent-framework-mcp-hosting` | `absent` | public API architecture test |
| MCP-017 | hosted agent/workflow adapter는 스키마와 세션 규칙을 고정한다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `protocols/agent-framework-mcp-hosting` | `absent` | adapter schema/session contract |
| MCP-018 | hosting 결과 매핑은 최종 결과 한 번만 만든다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `protocols/agent-framework-mcp-hosting` | `absent` | final result mapping test |
| MCP-019 | prompts/resources/sampling/tasks 호스팅은 helper 범위가 아니다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `protocols/agent-framework-mcp-hosting` | `absent` | API absence/boundary test |
| SES-001 | 공개 세션은 로컬 ID와 서비스 핸들을 분리한다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `api.session`, `spi.session`, `engine.internal.session` | `absent` | store/codec contract + restore golden |
| SES-002 | 서비스 대화 식별자는 권한 경계가 아니다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `api.session`, `spi.session`, `engine.internal.session` | `absent` | store/codec contract + restore golden |
| SES-003 | 세션이 영속 대화 상태의 단일 기준이다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `api.session`, `spi.session`, `engine.internal.session` | `absent` | store/codec contract + restore golden |
| SES-004 | 내구성 스냅샷은 타입·버전 봉투를 강제한다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `api.session`, `spi.session`, `engine.internal.session` | `absent` | store/codec contract + restore golden |
| SES-005 | 세션 상태는 안정적인 타입 레지스트리로 직렬화한다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `api.session`, `spi.session`, `engine.internal.session` | `absent` | store/codec contract + restore golden |
| SES-006 | 내구성 저장 전 상태 값을 엄격히 검증한다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `api.session`, `spi.session`, `engine.internal.session` | `absent` | store/codec contract + restore golden |
| SES-007 | 인메모리 저장소는 분기 안전한 독립 스냅샷을 돌려준다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `api.session`, `spi.session`, `engine.internal.session` | `absent` | store/codec contract + restore golden |
| SES-008 | 파일 저장소는 원자적으로 교체하고 마지막 기록이 이긴다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `api.session`, `spi.session`, `engine.internal.session` | `absent` | store/codec contract + restore golden |
| SES-009 | 파일 기반 세션 저장소는 경로 이탈을 막는다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `api.session`, `spi.session`, `engine.internal.session` | `absent` | store/codec contract + restore golden |
| SES-010 | 파싱 손상과 스키마 불일치를 구분해 복구한다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `api.session`, `spi.session`, `engine.internal.session` | `absent` | store/codec contract + restore golden |
| SES-011 | 실행마다 독립적인 `SessionContext`를 만든다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `api.session`, `spi.session`, `engine.internal.session` | `absent` | store/codec contract + restore golden |
| SES-012 | 컨텍스트 제공자는 세션 네임스페이스 안에서 상태를 관리한다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `api.session`, `spi.session`, `engine.internal.session` | `absent` | store/codec contract + restore golden |
| SES-013 | 이력 제공자는 선택적 적재·저장 플래그를 노출한다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `api.session`, `spi.session`, `engine.internal.session` | `absent` | store/codec contract + restore golden |
| SES-014 | 기본 인메모리 이력 주입 조건을 엄격히 제한한다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `api.session`, `spi.session`, `engine.internal.session` | `absent` | store/codec contract + restore golden |
| SES-015 | 서비스 호출 단위 이력 저장은 명시적 옵션이다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `api.session`, `spi.session`, `engine.internal.session` | `absent` | store/codec contract + restore golden |
| SES-016 | 로컬 이력 모드는 기존 서비스 대화와 혼용하지 않는다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `api.session`, `spi.session`, `engine.internal.session` | `absent` | store/codec contract + restore golden |
| SES-017 | 메시지 주입 큐는 세션에 붙고 대화 연속성을 유지한다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `api.session`, `spi.session`, `engine.internal.session` | `absent` | store/codec contract + restore golden |
| SES-018 | 파일 이력은 세션 스냅샷과 분리된 채널에 저장한다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `api.session`, `spi.session`, `engine.internal.session` | `absent` | store/codec contract + restore golden |
| SES-019 | 외부 메모리는 명시적 범위와 비신뢰 문맥으로 연동한다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `api.session`, `spi.session`, `engine.internal.session` | `absent` | store/codec contract + restore golden |
| SES-020 | 호스팅 격리는 별도 사용자 컨텍스트가 맡는다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `api.session`, `spi.session`, `engine.internal.session` | `absent` | store/codec contract + restore golden |
| INT-001 | 공개 확장점은 책임별 타입 인터셉터로 고정한다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `api.interceptor`, `engine.internal.interceptor/compaction` | `absent` | pipeline/compaction contract |
| INT-002 | 인터셉터는 DI·트랜잭션·보안·AOP를 재구현하지 않는다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `api.interceptor`, `engine.internal.interceptor/compaction` | `absent` | pipeline/compaction contract |
| INT-003 | 등록 순서가 전처리 순서와 감싸기 방향을 결정한다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `api.interceptor`, `engine.internal.interceptor/compaction` | `absent` | pipeline/compaction contract |
| INT-004 | 인터셉터 컨텍스트는 명시적이며 변경 지점이 제한된다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `api.interceptor`, `engine.internal.interceptor/compaction` | `absent` | pipeline/compaction contract |
| INT-005 | 지원하지 않는 seam 설치는 즉시 실패한다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `api.interceptor`, `engine.internal.interceptor/compaction` | `absent` | pipeline/compaction contract |
| INT-006 | `next`를 호출하지 않으면 단축 종료한다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `api.interceptor`, `engine.internal.interceptor/compaction` | `absent` | pipeline/compaction contract |
| INT-007 | 후처리에서 결과를 대체할 수 있다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `api.interceptor`, `engine.internal.interceptor/compaction` | `absent` | pipeline/compaction contract |
| INT-008 | 도구 예외 처리 규칙은 실행 전후를 구분한다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `api.interceptor`, `engine.internal.interceptor/compaction` | `absent` | pipeline/compaction contract |
| INT-009 | 세션 상태 기계는 코어가 소유하고 인터셉터는 관여 지점만 가진다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `api.interceptor`, `engine.internal.interceptor/compaction` | `absent` | pipeline/compaction contract |
| INT-010 | 다중 seam 기능은 opaque bundle로만 배포한다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `api.interceptor`, `engine.internal.interceptor/compaction` | `absent` | pipeline/compaction contract |
| INT-011 | 도구 집합 변경은 다음 반복에만 반영한다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `api.interceptor`, `engine.internal.interceptor/compaction` | `absent` | pipeline/compaction contract |
| INT-012 | 스트리밍 재작성은 finalization 경계 안에서만 허용한다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `api.interceptor`, `engine.internal.interceptor/compaction` | `absent` | pipeline/compaction contract |
| INT-013 | 취소는 모든 인터셉터와 컴팩션으로 전파한다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `api.interceptor`, `engine.internal.interceptor/compaction` | `absent` | pipeline/compaction contract |
| INT-014 | 컴팩션은 도구 호출과 도구 결과의 원자성을 보존한다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `api.interceptor`, `engine.internal.interceptor/compaction` | `absent` | pipeline/compaction contract |
| INT-015 | 컴팩션은 증분 업데이트에서도 식별자와 계수를 안정적으로 유지한다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `api.interceptor`, `engine.internal.interceptor/compaction` | `absent` | pipeline/compaction contract |
| INT-016 | 컴팩션 내부 상태는 명시적 index와 trace metadata로 표현한다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `api.interceptor`, `engine.internal.interceptor/compaction` | `absent` | pipeline/compaction contract |
| INT-017 | 컴팩션 시작 조건과 종료 조건을 분리한다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `api.interceptor`, `engine.internal.interceptor/compaction` | `absent` | pipeline/compaction contract |
| INT-018 | group 기반과 turn 기반 sliding 정책을 분리한다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `api.interceptor`, `engine.internal.interceptor/compaction` | `absent` | pipeline/compaction contract |
| INT-019 | tool-result compaction은 요약으로 대체하되 원문을 다시 노출하지 않는다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `api.interceptor`, `engine.internal.interceptor/compaction` | `absent` | pipeline/compaction contract |
| INT-020 | summarization compaction은 실패 시 원본을 복구한다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `api.interceptor`, `engine.internal.interceptor/compaction` | `absent` | pipeline/compaction contract |
| INT-021 | compaction provider는 실행 전 projection과 선택적 저장 후 훅을 제공한다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `api.interceptor`, `engine.internal.interceptor/compaction` | `absent` | pipeline/compaction contract |
| INT-022 | 로깅과 텔레메트리 인터셉터는 민감 데이터 노출을 opt-in으로 둔다 | [02-state-extension-mcp.md](02-state-extension-mcp.md) | `api.interceptor`, `engine.internal.interceptor/compaction` | `absent` | pipeline/compaction contract |
| HAR-001 | 하네스는 조립 계층으로만 둔다 | [03-workflow-harness.md](03-workflow-harness.md) | `integrations/agent-framework-harness/*` | `absent` | harness contract + safety tests |
| HAR-002 | 기본 하네스 조립은 보수적 opt-in 정책을 따른다 | [03-workflow-harness.md](03-workflow-harness.md) | `integrations/agent-framework-harness/*` | `absent` | harness contract + safety tests |
| HAR-003 | 하네스는 잘못된 조합을 생성 시점에 거부한다 | [03-workflow-harness.md](03-workflow-harness.md) | `integrations/agent-framework-harness/*` | `absent` | harness contract + safety tests |
| HAR-004 | 자동 반복은 predicate 미들웨어 seam으로 시작한다 | [03-workflow-harness.md](03-workflow-harness.md) | `integrations/agent-framework-harness/*` | `absent` | harness contract + safety tests |
| HAR-005 | 반복은 승인 대기에서 즉시 멈춘다 | [03-workflow-harness.md](03-workflow-harness.md) | `integrations/agent-framework-harness/*` | `absent` | harness contract + safety tests |
| HAR-006 | Todo 제공자는 세션 상태 저장만 코어에 포함한다 | [03-workflow-harness.md](03-workflow-harness.md) | `integrations/agent-framework-harness/*` | `absent` | harness contract + safety tests |
| HAR-007 | Todo 조작 결과는 안정된 계약을 가진다 | [03-workflow-harness.md](03-workflow-harness.md) | `integrations/agent-framework-harness/*` | `absent` | harness contract + safety tests |
| HAR-008 | Mode 제공자는 기본 `plan` 모드와 외부 변경 알림을 제공한다 | [03-workflow-harness.md](03-workflow-harness.md) | `integrations/agent-framework-harness/*` | `absent` | harness contract + safety tests |
| HAR-009 | File memory는 세션 범위를 기본값으로 하고 예약 이름을 막는다 | [03-workflow-harness.md](03-workflow-harness.md) | `integrations/agent-framework-harness/*` | `absent` | harness contract + safety tests |
| HAR-010 | File access는 별도 선택 모듈로만 제공한다 | [03-workflow-harness.md](03-workflow-harness.md) | `integrations/agent-framework-file-access` | `absent` | file boundary/traversal tests |
| HAR-011 | Tool approval은 대기열과 standing rule을 세션에 유지한다 | [03-workflow-harness.md](03-workflow-harness.md) | `api.tool`, `engine.internal.tool`, harness adapter | `absent` | approval/session contract |
| HAR-012 | Tool approval 규칙은 인자와 호스트 경계를 정확히 구분한다 | [03-workflow-harness.md](03-workflow-harness.md) | `api.tool`, `engine.internal.tool`, harness adapter | `absent` | approval boundary tests |
| HAR-013 | 승인 재진입은 같은 요청 예산 안에서 계산한다 | [03-workflow-harness.md](03-workflow-harness.md) | `engine.internal.tool`, harness loop | `absent` | approval budget test |
| HAR-014 | Skills는 progressive disclosure와 세 도구 표면을 유지한다 | [03-workflow-harness.md](03-workflow-harness.md) | `integrations/agent-framework-skills` | `absent` | skills contract test |
| HAR-015 | Skill script 실행은 기본 승인 필요다 | [03-workflow-harness.md](03-workflow-harness.md) | `integrations/agent-framework-skills` | `absent` | skill approval test |
| HAR-016 | 파일 기반 skills와 상세 오류는 신뢰 경계를 넘지 않게 다룬다 | [03-workflow-harness.md](03-workflow-harness.md) | `integrations/agent-framework-skills` | `absent` | trust/path/error tests |
| HAR-017 | Background agents는 MVP에 넣지 않고 instance-scoped polling store로 시작한다 | [03-workflow-harness.md](03-workflow-harness.md) | `integrations/agent-framework-background-agents` | `absent` | polling/LOST/lifecycle tests |
| HAR-018 | 셸 실행은 별도 tools 모듈에서 수동 조립한다 | [03-workflow-harness.md](03-workflow-harness.md) | `integrations/agent-framework-shell-tools` | `absent` | manual assembly contract |
| HAR-019 | 셸과 로컬 실행의 거부 목록은 가드레일로만 문서화한다 | [03-workflow-harness.md](03-workflow-harness.md) | `integrations/agent-framework-shell-tools` | `absent` | guardrail/boundary tests |
| HAR-020 | LocalCodeAct는 샌드박스로 취급하지 않고 코어에서 제외한다 | [03-workflow-harness.md](03-workflow-harness.md) | `integrations/agent-framework-local-codeact` | `absent` | capability descriptor tests |
| HAR-021 | 샌드박스형 CodeAct 백엔드는 별도 선택 모듈로 분리한다 | [03-workflow-harness.md](03-workflow-harness.md) | `integrations/agent-framework-codeact-sandbox` | `absent` | sandbox backend contract |
| WF-001 | 그래프 정의와 실행 런타임을 분리한다 | [03-workflow-harness.md](03-workflow-harness.md) | `workflow/agent-framework-workflow-*` | `absent` | graph/runtime/checkpoint contract + golden |
| WF-002 | 실행자 라우트 등록은 annotation processor 경로를 우선한다 | [03-workflow-harness.md](03-workflow-harness.md) | `workflow/agent-framework-workflow-*` | `absent` | graph/runtime/checkpoint contract + golden |
| WF-003 | 미바인딩 실행자가 남으면 빌드를 거부한다 | [03-workflow-harness.md](03-workflow-harness.md) | `workflow/agent-framework-workflow-*` | `absent` | graph/runtime/checkpoint contract + golden |
| WF-004 | 시작점에서 도달할 수 없는 실행자가 있으면 빌드를 거부한다 | [03-workflow-harness.md](03-workflow-harness.md) | `workflow/agent-framework-workflow-*` | `absent` | graph/runtime/checkpoint contract + golden |
| WF-005 | 출력 지정은 비어 있음, 중복, 겹침을 빌드 시점에 거부한다 | [03-workflow-harness.md](03-workflow-harness.md) | `workflow/agent-framework-workflow-*` | `absent` | graph/runtime/checkpoint contract + golden |
| WF-006 | 런타임 edge kind는 `DIRECT`, `FAN_OUT`, `FAN_IN`으로 고정한다 | [03-workflow-harness.md](03-workflow-harness.md) | `workflow/agent-framework-workflow-*` | `absent` | graph/runtime/checkpoint contract + golden |
| WF-007 | 엣지 실행 의미를 조건 drop과 fan-in barrier로 고정한다 | [03-workflow-harness.md](03-workflow-harness.md) | `workflow/agent-framework-workflow-*` | `absent` | graph/runtime/checkpoint contract + golden |
| WF-008 | 직렬화된 callables는 명시적 재바인딩 없이는 복원하지 않는다 | [03-workflow-harness.md](03-workflow-harness.md) | `workflow/agent-framework-workflow-*` | `absent` | graph/runtime/checkpoint contract + golden |
| WF-009 | 워크플로 실행은 1급 run handle로 제어한다 | [03-workflow-harness.md](03-workflow-harness.md) | `workflow/agent-framework-workflow-*` | `absent` | graph/runtime/checkpoint contract + golden |
| WF-010 | 상태는 polling과 status event 둘 다로 노출한다 | [03-workflow-harness.md](03-workflow-harness.md) | `workflow/agent-framework-workflow-*` | `absent` | graph/runtime/checkpoint contract + golden |
| WF-011 | superstep 수명주기와 체크포인트 순서를 고정한다 | [03-workflow-harness.md](03-workflow-harness.md) | `workflow/agent-framework-workflow-*` | `absent` | graph/runtime/checkpoint contract + golden |
| WF-012 | 공유 상태는 scoped API와 pending/committed 버퍼를 함께 가진다 | [03-workflow-harness.md](03-workflow-harness.md) | `workflow/agent-framework-workflow-*` | `absent` | graph/runtime/checkpoint contract + golden |
| WF-013 | 같은 상태 키의 다중 쓰기는 명시적 실패로 처리한다 | [03-workflow-harness.md](03-workflow-harness.md) | `workflow/agent-framework-workflow-*` | `absent` | graph/runtime/checkpoint contract + golden |
| WF-014 | 메시지 송신은 trace context를 전파하고 예약 이벤트 스푸핑을 막는다 | [03-workflow-harness.md](03-workflow-harness.md) | `workflow/agent-framework-workflow-*` | `absent` | graph/runtime/checkpoint contract + golden |
| WF-015 | 공개 cancel API와 stream 소비자 취소를 분리한다 | [03-workflow-harness.md](03-workflow-harness.md) | `workflow/agent-framework-workflow-*` | `absent` | graph/runtime/checkpoint contract + golden |
| WF-016 | pending request가 남아 있으면 새 메시지를 기본 거부한다 | [03-workflow-harness.md](03-workflow-harness.md) | `workflow/agent-framework-workflow-*` | `absent` | graph/runtime/checkpoint contract + golden |
| WF-017 | 실패 이벤트 순서는 실행자 실패 후 워크플로 실패다 | [03-workflow-harness.md](03-workflow-harness.md) | `workflow/agent-framework-workflow-*` | `absent` | graph/runtime/checkpoint contract + golden |
| WF-018 | 체크포인트는 continuation에 필요한 전체 상태를 담는다 | [03-workflow-harness.md](03-workflow-harness.md) | `workflow/agent-framework-workflow-*` | `absent` | graph/runtime/checkpoint contract + golden |
| WF-019 | restore는 시그니처를 검증하고 stale event를 먼저 비운다 | [03-workflow-harness.md](03-workflow-harness.md) | `workflow/agent-framework-workflow-*` | `absent` | graph/runtime/checkpoint contract + golden |
| WF-020 | latest checkpoint 판정은 정렬 계약에 의존한다 | [03-workflow-harness.md](03-workflow-harness.md) | `workflow/agent-framework-workflow-*` | `absent` | graph/runtime/checkpoint contract + golden |
| WF-021 | pending request는 restore 뒤 다시 발행하되 중복되지 않아야 한다 | [03-workflow-harness.md](03-workflow-harness.md) | `workflow/agent-framework-workflow-*` | `absent` | graph/runtime/checkpoint contract + golden |
| WF-022 | 외부 응답은 request id와 port id를 함께 검증한다 | [03-workflow-harness.md](03-workflow-harness.md) | `workflow/agent-framework-workflow-*` | `absent` | graph/runtime/checkpoint contract + golden |
| WF-023 | 승인 재개는 `original_request` payload를 진실 원천으로 삼는다 | [03-workflow-harness.md](03-workflow-harness.md) | `workflow/agent-framework-workflow-*` | `absent` | graph/runtime/checkpoint contract + golden |
| WF-024 | 파일 체크포인트 저장은 경로와 역직렬화 안전장치를 기본 제공한다 | [03-workflow-harness.md](03-workflow-harness.md) | `workflow/agent-framework-workflow-*` | `absent` | graph/runtime/checkpoint contract + golden |
| WF-025 | run handle은 재개와 pending request 조회 API를 제공한다 | [03-workflow-harness.md](03-workflow-harness.md) | `workflow/agent-framework-workflow-*` | `absent` | graph/runtime/checkpoint contract + golden |
| WF-026 | 하위 워크플로 조합은 host executor 소유권 모델을 따른다 | [03-workflow-harness.md](03-workflow-harness.md) | `workflow/agent-framework-workflow-*` | `absent` | graph/runtime/checkpoint contract + golden |
| WF-027 | 자식 출력, intermediate, request 전파 정책은 명시적으로 설정한다 | [03-workflow-harness.md](03-workflow-harness.md) | `workflow/agent-framework-workflow-*` | `absent` | graph/runtime/checkpoint contract + golden |
| WF-028 | workflow-as-agent 세션은 continuation 상태를 함께 직렬화한다 | [03-workflow-harness.md](03-workflow-harness.md) | `workflow/agent-framework-workflow-*` | `absent` | graph/runtime/checkpoint contract + golden |
| WF-029 | functional workflow는 별도 실험 API로 분리한다 | [03-workflow-harness.md](03-workflow-harness.md) | `workflow/agent-framework-workflow-functional-experimental` | `absent` | functional step/cache/interruption contract |
| WF-030 | 오케스트레이션은 공통 output designation helper와 명시적 기본 정책을 가진다 | [03-workflow-harness.md](03-workflow-harness.md) | `workflow/agent-framework-workflow-*` | `absent` | graph/runtime/checkpoint contract + golden |
| WF-031 | sequential·concurrent는 패턴별 계약과 request-info wrapper를 제공한다 | [03-workflow-harness.md](03-workflow-harness.md) | `workflow/agent-framework-workflow-*` | `absent` | graph/runtime/checkpoint contract + golden |
| WF-032 | handoff는 mesh 기본값, 능력 검증, 필터링, pending-request 차단을 갖는다 | [03-workflow-harness.md](03-workflow-harness.md) | `workflow/agent-framework-workflow-*` | `absent` | graph/runtime/checkpoint contract + golden |
| WF-033 | group-chat은 단일 orchestrator 계약과 no-self-echo 규칙을 갖는다 | [03-workflow-harness.md](03-workflow-harness.md) | `workflow/agent-framework-workflow-*` | `absent` | graph/runtime/checkpoint contract + golden |
| WF-034 | magentic은 단일 manager 계약과 plan review·replan 흐름을 갖는다 | [03-workflow-harness.md](03-workflow-harness.md) | `workflow/agent-framework-workflow-*` | `absent` | graph/runtime/checkpoint contract + golden |
| WF-035 | 선언형 워크플로는 분리된 모듈, 안전한 상태 경로, typed handler SPI를 가진다 | [03-workflow-harness.md](03-workflow-harness.md) | `workflow/agent-framework-declarative-spi`, `workflow-declarative`, `agent-assets-declarative` | `absent` | handler/build/path/redaction contract |
| HOST-001 | 호스팅 모델과 wire 프로토콜을 분리한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `agent-framework-hosting-core`, `protocols/*`, `starters/*` | `absent` | hosting contract + protocol wire test |
| HOST-002 | 세션 계약은 API와 엔진이 소유한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `api.session`, `spi.session`, `engine.internal.session`; hosting consumes only | `absent` | session ownership architecture + hosting contract |
| HOST-003 | 호스팅 코어는 바인딩과 상태 조율만 담당한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `agent-framework-hosting-core`, `protocols/*`, `starters/*` | `absent` | hosting contract + protocol wire test |
| HOST-004 | 타깃 해결은 하나의 비동기 resolver 계약으로 통일한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `agent-framework-hosting-core`, `protocols/*`, `starters/*` | `absent` | hosting contract + protocol wire test |
| HOST-005 | 세션 연속성은 작업 복사본과 단일 생성 규칙을 보장한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `agent-framework-hosting-core`, `protocols/*`, `starters/*` | `absent` | hosting contract + protocol wire test |
| HOST-006 | 워크플로 이어받기는 restore-then-run과 durable 폴백을 따른다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `agent-framework-hosting-core`, `protocols/*`, `starters/*` | `absent` | hosting contract + protocol wire test |
| HOST-007 | 인메모리 연속성은 개발 편의로만 둔다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `agent-framework-hosting-core`, `protocols/*`, `starters/*` | `absent` | hosting contract + protocol wire test |
| HOST-008 | 인증·인가·단일 writer·확장은 호스트가 책임진다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `agent-framework-hosting-core`, `protocols/*`, `starters/*` | `absent` | hosting contract + protocol wire test |
| HOST-009 | 원시 서비스 세션 식별자는 권한 경계가 아니다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `agent-framework-hosting-core`, `protocols/*`, `starters/*` | `absent` | hosting contract + protocol wire test |
| HOST-010 | host framework binder는 프레임워크별 선택 모듈로 분리한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | executable `integrations/*`, converters `protocols/*`, dependency-only `starters/*` | `absent` | cross-family architecture + framework binder contract |
| HOST-011 | Responses 어댑터는 요청 파싱과 세션 키 추출을 분리한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `agent-framework-hosting-core`, `protocols/*`, `starters/*` | `absent` | hosting contract + protocol wire test |
| HOST-012 | Responses 기본 매핑은 호출자 override를 엄격 거부한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `agent-framework-hosting-core`, `protocols/*`, `starters/*` | `absent` | hosting contract + protocol wire test |
| HOST-013 | Responses continuation은 branch pointer와 mutable head를 구분한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `agent-framework-hosting-core`, `protocols/*`, `starters/*` | `absent` | hosting contract + protocol wire test |
| HOST-014 | Responses SSE는 최소 프로필과 확장 프로필을 함께 지원한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `agent-framework-hosting-core`, `protocols/*`, `starters/*` | `absent` | hosting contract + protocol wire test |
| HOST-015 | Responses 최종 payload는 tool·reasoning·media 항목군을 보존한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `agent-framework-hosting-core`, `protocols/*`, `starters/*` | `absent` | hosting contract + protocol wire test |
| HOST-016 | 원격 A2A 에이전트 래퍼를 1급 어댑터로 제공한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `agent-framework-hosting-core`, `protocols/*`, `starters/*` | `absent` | hosting contract + protocol wire test |
| HOST-017 | 로컬 A2A 노출은 helper와 Spring binder를 분리한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `agent-framework-hosting-core`, `protocols/*`, `starters/*` | `absent` | hosting contract + protocol wire test |
| HOST-018 | A2A continuation은 structured serviceSessionId로 저장한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `agent-framework-hosting-core`, `protocols/*`, `starters/*` | `absent` | hosting contract + protocol wire test |
| HOST-019 | A2A continuation token과 새 사용자 메시지를 함께 받지 않는다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `agent-framework-hosting-core`, `protocols/*`, `starters/*` | `absent` | hosting contract + protocol wire test |
| HOST-020 | A2A 호스트는 message·task·artifact 수명주기를 구분한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `agent-framework-hosting-core`, `protocols/*`, `starters/*` | `absent` | lifecycle + canceled-terminal wire contract |
| HOST-021 | AG-UI 어댑터는 요청 별칭과 resume 정규화를 소유한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `agent-framework-hosting-core`, `protocols/*`, `starters/*` | `absent` | hosting contract + protocol wire test |
| HOST-022 | AG-UI 상태는 predictive delta와 deterministic snapshot을 분리한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `agent-framework-hosting-core`, `protocols/*`, `starters/*` | `absent` | hosting contract + protocol wire test |
| HOST-023 | AG-UI tool result는 UI payload와 LLM 텍스트를 분리한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `agent-framework-hosting-core`, `protocols/*`, `starters/*` | `absent` | hosting contract + protocol wire test |
| HOST-024 | AG-UI 지속성은 threadId와 snapshot scope를 분리한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `agent-framework-hosting-core`, `protocols/*`, `starters/*` | `absent` | hosting contract + protocol wire test |
| HOST-025 | AG-UI host binder는 SSE transport와 stream-complete save를 제공한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `agent-framework-hosting-core`, `protocols/*`, `starters/*` | `absent` | hosting contract + protocol wire test |
| HOST-026 | Foundry hosting은 선택 어댑터이며 Responses와 Invocations를 분리한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `agent-framework-hosting-core`, `protocols/*`, `starters/*` | `absent` | hosting contract + protocol wire test |
| HOST-027 | Hosted Foundry 경로는 플랫폼 컨텍스트를 검증하고 로컬 경로를 명시적으로 구분한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `agent-framework-hosting-core`, `protocols/*`, `starters/*` | `absent` | hosting contract + protocol wire test |
| HOST-028 | DevUI는 개발 전용 아티팩트로 유지한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `agent-framework-hosting-core`, `protocols/*`, `starters/*` | `absent` | hosting contract + protocol wire test |
| HOST-029 | 채널·프로토콜 어댑터는 프로토콜별 선택 아티팩트로 분리한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `agent-framework-hosting-core`, `protocols/*`, `starters/*` | `absent` | hosting contract + protocol wire test |
| OPS-001 | OpenTelemetry GenAI 규약을 표준으로 삼는다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `spi.telemetry`, `integrations/agent-framework-*`, build policy | `absent` | telemetry/error/evaluation/policy test |
| OPS-002 | 관찰성은 bootstrap과 wrapper를 분리한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `spi.telemetry`, `integrations/agent-framework-*`, build policy | `absent` | telemetry/error/evaluation/policy test |
| OPS-003 | 민감 데이터 수집은 기본 끔인 별도 opt-in이다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `spi.telemetry`, `integrations/agent-framework-*`, build policy | `absent` | telemetry/error/evaluation/policy test |
| OPS-004 | logging은 tracing과 별도 계층으로 유지한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `spi.telemetry`, `integrations/agent-framework-*`, build policy | `absent` | telemetry/error/evaluation/policy test |
| OPS-005 | feature telemetry는 승인된 origin에만 실시간으로 찍는다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `spi.telemetry`, `integrations/agent-framework-*`, build policy | `absent` | telemetry/error/evaluation/policy test |
| OPS-006 | 계측 비활성은 sticky 하게 유지한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `spi.telemetry`, `integrations/agent-framework-*`, build policy | `absent` | telemetry/error/evaluation/policy test |
| OPS-007 | 같은 작업을 두 계층에서 중복 계측하지 않고 카테고리별 제어를 둔다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `spi.telemetry`, `integrations/agent-framework-*`, build policy | `absent` | telemetry/error/evaluation/policy test |
| OPS-008 | 공통 오류 taxonomy를 노출한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `api.error`, protocol/host error mappers | `absent` | error taxonomy + binder contract |
| OPS-009 | validation과 프로그래밍 오류는 built-in 예외로 남긴다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `api.error`, API builders/factories | `absent` | exception contract test |
| OPS-010 | 취소는 일반 실패로 번역하지 않는다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `api.agent`, run/workflow handles, adapters | `absent` | cancellation propagation contract |
| OPS-011 | timeout은 결과 envelope에 남긴다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | shell/code executor result envelopes | `absent` | timeout/exit-result contract |
| OPS-012 | cleanup은 process tree와 remote task 단위로 수행한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | lifecycle ports, shell/MCP adapters | `absent` | cleanup integration test |
| OPS-013 | persistent executor는 session-owned resource다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `spi.lifecycle`, session resource registry | `absent` | session lifecycle contract |
| OPS-014 | shell과 tool 정책을 보안 경계로 포장하지 않는다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | shell/tool integration docs and guards | `absent` | safety-boundary tests |
| OPS-015 | 위험한 우회 경로에는 명시적 안전 장치를 둔다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | capability/acknowledgement APIs, declarative state-path validator | `absent` | unsafe opt-in + traversal rejection tests |
| OPS-016 | 재시도·타임아웃·서킷브레이커 운영 정책은 호스트가 소유한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | host adapters and injected resilience policy | `absent` | architecture + host integration test |
| OPS-017 | 평가 SPI는 batch-oriented provider-neutral 계약을 쓴다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `integrations/agent-framework-evaluation` | `absent` | evaluator provider contract |
| OPS-018 | evaluation converter를 1급 API로 두고 외부 전송 payload를 최소화한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | evaluation converter API | `absent` | converter/privacy contract |
| OPS-019 | 평가 결과는 실행 실패와 품질 실패를 분리한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | evaluation result model | `absent` | result classification test |
| OPS-020 | workflow 평가는 공개 API와 per-agent subresults를 가져야 한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | workflow evaluation adapter | `absent` | workflow evaluation contract |
| OPS-021 | generated evaluator와 golden 입력은 결정적으로 재현 가능해야 한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | evaluation testkit, deterministic fixtures | `absent` | reproducibility test |
| OPS-022 | 공급자 공통 contract test와 golden scenario를 유지한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `agent-framework-testkit`, `compatibility-tests/*` | `absent` | provider contract + golden suite |
| OPS-023 | 패키징은 단일 버전 라인과 Gradle BOM을 기준으로 하고 단계 레지스트리를 따로 둔다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | BOM, publishing conventions, maturity registry | `partial` — `agent-framework-bom/build.gradle.kts`, `build-logic/src/main/kotlin/agentframework.publishing-conventions.gradle.kts`; maturity registry absent | `PublishedBomContractTest`, `ModuleCompositionPolicyTest` |
| OPS-024 | 의존성 관리는 validated bounds와 공급망 pinning을 함께 쓴다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | version catalog, locks, pinned workflow actions | `partial` — `gradle/libs.versions.toml`, project lockfiles, wrapper checksum, pinned actions; validated bounds incomplete | `BuildContractPolicyTest`, `RepositoryGovernancePolicyTest`, `WorkflowPolicyTest` |
| OPS-025 | 호환성·설치 가능성 gate를 maturity와 연결한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | compatibility and consumer installability gates | `absent` | API compatibility + consumer build |
| OPS-026 | 교차 언어 호환성은 공개 surface와 행동으로 판정한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | requirements, upstream ledger, compatibility tests | `absent` | cross-language surface/behavior review |
| PRV-001 | 코어는 provider SDK에 직접 의존하지 않는다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `providers/agent-framework-*`, provider contract test | `absent` | provider contract + integration test |
| PRV-002 | 공급자와 통합은 artifact별로 분리한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `providers/agent-framework-*`, provider contract test | `absent` | provider contract + integration test |
| PRV-003 | all-providers 번들을 기본 제공하지 않는다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `providers/agent-framework-*`, provider contract test | `absent` | provider contract + integration test |
| PRV-004 | 어댑터 포팅 우선순위를 P0부터 P4까지 고정한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `providers/agent-framework-*`, provider contract test | `absent` | provider contract + integration test |
| PRV-005 | hosting과 protocol adapters는 model provider와 분리한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `providers/*`, `hosting/*`, `protocols/*`, dependency policy | `absent` | cross-family architecture + provider contract |
| PRV-006 | storage·memory·governance는 모델 공급자와 분리한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `providers/*`, storage/memory/governance `integrations/*`, dependency policy | `absent` | cross-family architecture + integration contract |
| PRV-007 | 공급자 전용 기능은 선택 capability로 노출한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `providers/agent-framework-*`, provider contract test | `absent` | provider contract + integration test |
| PRV-008 | 공급자 전용 continuation과 hosted state는 어댑터가 소유한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `providers/agent-framework-*`, provider contract test | `absent` | provider contract + integration test |
| PRV-009 | 한쪽 언어에만 있는 통합은 선택 tier로 유지한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `providers/agent-framework-*`, provider contract test | `absent` | provider contract + integration test |
| PRV-010 | adapter는 maturity·README·테스트 근거를 함께 제공하고 facade를 유지한다 | [04-hosting-operations-providers.md](04-hosting-operations-providers.md) | `providers/agent-framework-*`, provider contract test | `absent` | provider contract + integration test |

## Matrix invariants

- requirement corpus의 ID 수와 이 표의 ID 수는 모두 244여야 한다.
- 같은 ID가 두 번 나오거나 빠지면 설계 검증은 실패한다.
- canonical design link는 prefix owner와 일치해야 한다.
- `implemented` 또는 `partial`로 올리려면 production code와 executable test 경로를 같이 기록한다.
- requirement가 바뀌면 제목과 mapping row를 같은 변경에서 갱신한다.
