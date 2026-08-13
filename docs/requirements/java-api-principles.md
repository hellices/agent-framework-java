# Java API and extension principles

이 문서는 `docs/requirements/`의 244개 요구사항 전체에 적용되는 Java 공개 API 원칙이다.
원본의 관찰 가능한 실행 의미는 유지하되 Python의 동적 객체 모델이나 .NET의 전용 런타임 타입을
Java API에 그대로 옮기지 않는다.

## 1. 명시적이고 null-safe한 계약

- 공개 입력은 기본적으로 non-null이다. 값 없음은 빈 컬렉션, 전용 `empty()` 팩토리, 또는 반환
  위치의 `Optional<T>`로 표현한다.
- `null`을 빈 입력이나 성공 결과로 조용히 바꾸지 않는다. `void` 함수나 no-result 도구는 별도
  어댑터가 명시적 빈 결과로 바꾼다.
- `Optional<T>`는 반환 타입에만 사용한다. 필드, 메서드 인자, 컬렉션 원소로 저장하지 않는다.

## 2. 불변 값과 진화 가능한 API

- 메시지, 응답, 옵션, 세션 스냅샷, 워크플로 정의는 불변 값이다. 선택 항목이 많은 타입은
  builder와 `toBuilder()` 또는 copy factory를 제공한다.
- 공개 record는 구성 요소가 장기간 고정되는 닫힌 값에만 사용한다. record 구성 요소 추가는
  소스·바이너리 호환성을 깨므로 확장 가능성이 높은 옵션과 요청 타입에는 사용하지 않는다.
- 외부 구현을 받아야 하는 SPI는 open interface다. sealed hierarchy는 프레임워크가 완전히
  소유하는 닫힌 상태·이벤트 집합에만 사용한다.
- 알려진 종류가 닫혀 있어도 provider 확장은 typed extension envelope로 보존한다. 전역 factory
  registry나 core 수정이 새 provider 도입의 전제여서는 안 된다.

## 3. 타입 안전한 확장 데이터

- 공개 API의 확장 옵션과 컨텍스트는 `Map<String, Object>`를 1차 계약으로 사용하지 않는다.
  공통 옵션은 typed property, provider 옵션은 adapter-owned immutable type으로 표현한다.
- 필요한 extensible attributes는 `ContextKey<T>` 또는 동등한 typed key를 사용한다. 키는
  namespace와 값 타입을 함께 식별하고 충돌 시 실패한다.
- 영속 가능한 추가 속성은 JSON-safe value model 또는 등록된 codec 범위로 제한한다.
  provider SDK 원시 객체는 transient diagnostic handle이며 세션 스냅샷에 자동 직렬화하지 않는다.

## 4. 비동기, 스트리밍, 취소

- 단일 비동기 결과는 `CompletionStage<T>`, backpressure가 필요한 다중 결과는
  `Flow.Publisher<T>`를 framework-neutral public contract로 사용한다. Reactor와 Mutiny,
  Jakarta REST 비동기 타입은 adapter에서 변환한다.
- Reactor `Mono`/`Flux`, Mutiny `Uni`/`Multi`, Jakarta REST 비동기 타입은 adapter에서 변환한다.
- 취소는 명시적 실행 신호와 run handle로 전달하며 `Future.cancel`, thread interruption,
  HTTP client abort를 adapter에서 연결한다. agent execution stream은 계약에 따라
  `Flow.Subscription.cancel`을 run 취소로 연결할 수 있다. durable workflow의 event/watch
  subscription은 관찰만 중단하며 run 취소는 명시적 workflow handle만 수행한다.
- core는 executor, scheduler, virtual-thread factory를 만들지 않는다. 동시 실행이 필요하면
  호스트가 주입한 실행 자원 또는 port가 이미 반환한 비동기 결과를 조합한다.

## 5. 오류와 자원 소유권

- 공개 예외는 기본적으로 unchecked다. 잘못된 인자는 `IllegalArgumentException`, 잘못된 상태는
  `IllegalStateException` 계열로 남기고, 외부 실패만 bounded-context 예외로 분류한다.
- 취소는 일반 실패로 감싸지 않는다. 원인과 machine-readable category를 보존한다.
- 자원을 만든 객체만 닫는다. 주입받은 client·executor·store는 닫지 않는다.
- 동기 종료는 `AutoCloseable`, 비동기 종료는 명시적 completion-returning lifecycle port를
  사용한다. close는 idempotent해야 한다.

## 6. 직렬화와 타입 발견

- Java native serialization, 역직렬화 대상의 임의 class name, `Class.forName` 기반 복원은 사용하지
  않는다.
- 상태와 checkpoint는 안정적인 type id, schema version, 명시적으로 주입된 codec registry로
  복원한다. registry는 instance-scoped이고 조립 뒤 불변이다.
- 함수·도구 schema 추론은 신뢰할 수 있는 Java 타입 및 parameter metadata가 있을 때만 수행한다.
  parameter name이 보존되지 않았거나 generic type이 소거돼 정확한 schema를 만들 수 없으면
  추측하지 않고 명시 schema를 요구한다.
- core는 `ServiceLoader`, component scan, 전역 registry를 자동 실행하지 않는다. plain Java,
  Spring Boot, Quarkus, Jakarta EE adapter가 같은 명시적 builder/constructor 조립 계약을 사용한다.

## 7. 프레임워크 어댑터

- Spring Boot는 auto-configuration과 starter를 분리하고 conditional bean 및 customizer 패턴으로
  조립한다. Spring AI는 선택적 model/tool adapter이며 core tool loop를 대체하지 않는다.
- Quarkus first-class extension은 stable runtime과 deployment artifact를 함께 제공한다.
  runtime artifact는 extension descriptor를, deployment artifact는 필요한
  recorder·generated metadata·native-image build steps를 소유한다.
- Jakarta EE는 CDI producer/portable extension과 container-owned scope를 사용한다. core가 CDI
  container나 request context를 조회하지 않는다.
- 모든 adapter는 공개 port만 구현하며 engine internal package를 참조하지 않는다. adapter 제거 후에도
  core contract test는 그대로 실행돼야 한다.

## 8. 검증

설계와 구현의 모든 공개 타입은 다음 질문에 답해야 한다.

1. plain Java에서 DI container 없이 명시적으로 조립할 수 있는가?
2. Spring Boot, Quarkus, Jakarta EE가 자신의 scope와 lifecycle을 유지한 채 같은 port를 주입할 수
   있는가?
3. provider SDK, JSON mapper, reactive library 타입이 core API에 노출되지 않는가?
4. 새 adapter가 core 수정, 전역 등록, reflection configuration 없이 추가될 수 있는가?
5. 비동기 완료, 취소, 자원 종료, session 저장의 소유자가 하나로 식별되는가?

하나라도 아니면 요구사항 매핑은 완료된 것으로 보지 않는다.
