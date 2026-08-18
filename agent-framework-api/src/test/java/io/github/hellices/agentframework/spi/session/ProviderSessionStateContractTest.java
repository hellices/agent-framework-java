package io.github.hellices.agentframework.spi.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hellices.agentframework.api.agent.AgentSession;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.context.ContextAttributes;
import io.github.hellices.agentframework.api.session.SessionContext;
import io.github.hellices.agentframework.api.session.SessionState;
import io.github.hellices.agentframework.api.session.SessionStateKey;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Executable form of the guarantee {@link ProviderSessionState} documents: the bound view a
 * provider is handed exposes no operation that names another provider's namespace, is typed to its
 * declared state type, and a provider written against only that view reads and writes just its own
 * key.
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
    SessionStateKey<Marker> memory = SessionStateKey.of("memory", Marker.class);
    SessionStateKey<Marker> untouched = SessionStateKey.of("untouched", Marker.class);
    AgentSession session =
        AgentSession.builder()
            .sessionId("session-1")
            .state(
                SessionState.empty()
                    .with(memory, new MarkerValue("one"))
                    .with(untouched, new MarkerValue("keep")))
            .build();
    SessionContext sessionContext =
        new SessionContext(session, List.of(), ContextAttributes.empty(), new CancellationSignal());

    ProviderSessionState<Marker> bound = sessionContext.providerState(memory);
    bound.set(new MarkerValue("two"));

    assertThat(bound.key()).isEqualTo(memory);
    SessionState updated = sessionContext.updatedSession().orElseThrow().state();
    assertThat(updated.get(memory)).contains(new MarkerValue("two"));
    assertThat(updated.get(untouched)).contains(new MarkerValue("keep"));
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

    sessionContext.providerState(key).set(new MarkerValue("after"));

    assertThat(sessionContext.updatedSession().orElseThrow().state().get(key))
        .contains(new MarkerValue("after"));
  }

  private interface Marker {}

  private record MarkerValue(String value) implements Marker {}
}
