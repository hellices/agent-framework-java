package io.github.hellices.agentframework.spi.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hellices.agentframework.api.agent.AgentSession;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.context.ContextAttributes;
import io.github.hellices.agentframework.api.session.SessionContext;
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
    AgentSession session =
        new AgentSession("session-1", null, Map.of("memory", 1, "untouched", "keep"));
    SessionContext sessionContext =
        new SessionContext(session, List.of(), ContextAttributes.empty(), new CancellationSignal());

    ProviderSessionState bound = sessionContext.providerState("memory");
    bound.set(bound.value(Integer.class).orElseThrow() + 1);

    assertThat(bound.sourceId()).isEqualTo("memory");
    assertThat(sessionContext.updatedSession().orElseThrow().state())
        .containsExactlyInAnyOrderEntriesOf(Map.of("memory", 2, "untouched", "keep"));
  }
}
