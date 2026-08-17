package io.github.hellices.agentframework.spi.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hellices.agentframework.api.agent.AgentSession;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.context.ContextAttributes;
import io.github.hellices.agentframework.api.session.SessionContext;
import io.github.hellices.agentframework.api.session.SessionState;
import io.github.hellices.agentframework.api.session.SessionStateKey;
import io.github.hellices.agentframework.api.session.SessionStateValues;
import io.github.hellices.agentframework.api.value.JsonValue;
import io.github.hellices.agentframework.api.value.JsonValues;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Executable form of the guarantee {@link ProviderSessionState} documents: the bound view a
 * provider is handed exposes no operation that names another provider's namespace, and a provider
 * written against only that view needs no cross-key API.
 */
class ProviderSessionStateContractTest {

  @Test
  void theBoundViewDeclaresNoSiblingKeyOperation() {
    assertThat(ProviderSessionState.class.getMethods())
        .allSatisfy(
            method ->
                assertThat(method.getParameterTypes())
                    .as("%s must not accept a source id", method.getName())
                    .doesNotContain(String.class));
  }

  @Test
  void theBoundViewNeverReturnsTheParentSessionStateMap() {
    assertThat(ProviderSessionState.class.getMethods())
        .extracting(Method::getReturnType)
        .allSatisfy(returnType -> assertThat(Map.class.isAssignableFrom(returnType)).isFalse());
  }

  @Test
  void aProviderBoundViewReadsAndWritesOnlyItsOwnNamespace() {
    AgentSession session = session("session-1", null, Map.of("memory", 1, "untouched", "keep"));
    SessionContext sessionContext =
        new SessionContext(session, List.of(), ContextAttributes.empty(), new CancellationSignal());

    ProviderSessionState bound = sessionContext.providerState("memory");
    bound.set(2);

    assertThat(bound.sourceId()).isEqualTo("memory");
    assertThat(sessionContext.updatedSession().orElseThrow().state())
        .isEqualTo(sessionState(Map.of("memory", 2, "untouched", "keep")));
  }

  @Test
  void aProviderWritePreservesTheExistingDeclaredStateKeyType() {
    SessionStateKey<Marker> key = SessionStateKey.of("memory", Marker.class);
    AgentSession session =
        AgentSession.builder()
            .sessionId("session-1")
            .state(SessionState.empty().with(key, new MarkerValue("before")))
            .build();
    SessionContext sessionContext =
        new SessionContext(session, List.of(), ContextAttributes.empty(), new CancellationSignal());

    sessionContext.providerState("memory").set(new MarkerValue("after"));

    assertThat(sessionContext.updatedSession().orElseThrow().state().get(key))
        .contains(new MarkerValue("after"));
  }

  private static AgentSession session(
      String sessionId, String serviceSessionId, Map<String, ?> state) {
    AgentSession.Builder builder =
        AgentSession.builder().sessionId(sessionId).state(sessionState(state));
    if (serviceSessionId != null) {
      builder.serviceSessionId(serviceSessionId);
    }
    return builder.build();
  }

  private static SessionState sessionState(Map<String, ?> state) {
    SessionState sessionState = SessionState.empty();
    for (Map.Entry<String, ?> entry : state.entrySet()) {
      sessionState = put(sessionState, entry.getKey(), entry.getValue());
    }
    return sessionState;
  }

  @SuppressWarnings("unchecked")
  private static SessionState put(SessionState state, String key, Object value) {
    if (SessionStateValues.isJsonValueShape(value)) {
      return state.with(SessionStateKey.of(key, JsonValue.class), JsonValues.fromJava(value));
    }
    return state.with((SessionStateKey<Object>) SessionStateKey.of(key, value.getClass()), value);
  }

  private interface Marker {}

  private record MarkerValue(String value) implements Marker {}
}
