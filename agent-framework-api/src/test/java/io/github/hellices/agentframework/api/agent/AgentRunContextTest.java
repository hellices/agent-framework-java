package io.github.hellices.agentframework.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.context.ContextAttributes;
import io.github.hellices.agentframework.api.context.ContextKey;
import io.github.hellices.agentframework.api.session.SessionContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentRunContextTest {

  private static final Agent AGENT = new NoOpAgent();
  private static final ContextKey<String> ATTRIBUTE_KEY =
      ContextKey.of("agent", "key", String.class);

  @Test
  void acceptsMatchingSession() {
    AgentSession session = new AgentSession("session-1", "service-1", Map.of());
    SessionContext sessionContext =
        new SessionContext(session, List.of(), ContextAttributes.empty(), new CancellationSignal());

    AgentRunContext context =
        new AgentRunContext(AGENT, session, ContextAttributes.empty(), sessionContext);

    assertThat(context.session()).isEqualTo(session);
    assertThat(context.sessionContext().session()).isEqualTo(session);
  }

  @Test
  void acceptsBothSessionsNullForSessionlessRuns() {
    SessionContext sessionContext =
        new SessionContext(null, List.of(), ContextAttributes.empty(), new CancellationSignal());

    AgentRunContext context =
        new AgentRunContext(AGENT, null, ContextAttributes.empty(), sessionContext);

    assertThat(context.session()).isNull();
    assertThat(context.sessionContext().session()).isNull();
  }

  @Test
  void rejectsMismatchedSession() {
    AgentSession session = new AgentSession("session-1", "service-1", Map.of());
    AgentSession otherSession = new AgentSession("session-2", "service-1", Map.of());
    SessionContext sessionContext =
        new SessionContext(
            otherSession, List.of(), ContextAttributes.empty(), new CancellationSignal());

    assertThatThrownBy(
            () -> new AgentRunContext(AGENT, session, ContextAttributes.empty(), sessionContext))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("session must match sessionContext.session()");
  }

  @Test
  void rejectsSessionWhenSessionContextIsSessionless() {
    AgentSession session = new AgentSession("session-1", "service-1", Map.of());
    SessionContext sessionContext =
        new SessionContext(null, List.of(), ContextAttributes.empty(), new CancellationSignal());

    assertThatThrownBy(
            () -> new AgentRunContext(AGENT, session, ContextAttributes.empty(), sessionContext))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("session must match sessionContext.session()");
  }

  @Test
  void rejectsSessionContextWhenSessionIsSessionless() {
    AgentSession sessionContextSession = new AgentSession("session-1", "service-1", Map.of());
    SessionContext sessionContext =
        new SessionContext(
            sessionContextSession, List.of(), ContextAttributes.empty(), new CancellationSignal());

    assertThatThrownBy(
            () -> new AgentRunContext(AGENT, null, ContextAttributes.empty(), sessionContext))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("session must match sessionContext.session()");
  }

  @SuppressWarnings({"deprecation", "removal"})
  @Test
  void deprecatedThreeArgConstructorDelegatesToCanonicalConstructorWithAnEmptySessionContext() {
    AgentSession session = new AgentSession("session-1", "service-1", Map.of());
    ContextAttributes attributes = ContextAttributes.builder().put(ATTRIBUTE_KEY, "value").build();

    AgentRunContext context = new AgentRunContext(AGENT, session, attributes);

    assertThat(context.agent()).isEqualTo(AGENT);
    assertThat(context.session()).isEqualTo(session);
    assertThat(context.attributes()).isEqualTo(attributes);
    assertThat(context.sessionContext().session()).isEqualTo(session);
    assertThat(context.sessionContext().inputMessages()).isEmpty();
    assertThat(context.sessionContext().contextMessages()).isEmpty();
    assertThat(context.sessionContext().metadata()).isEqualTo(attributes);
    assertThat(context.sessionContext().cancellationSignal()).isNotNull();
    assertThat(context.sessionContext().cancellationSignal().isCancelled()).isFalse();
  }

  @SuppressWarnings({"deprecation", "removal"})
  @Test
  void deprecatedThreeArgConstructorNormalizesNullAttributesForBothContextAndSessionContext() {
    AgentSession session = new AgentSession("session-1", "service-1", Map.of());

    AgentRunContext context = new AgentRunContext(AGENT, session, null);

    assertThat(context.attributes()).isEqualTo(ContextAttributes.empty());
    assertThat(context.sessionContext().metadata()).isEqualTo(ContextAttributes.empty());
  }

  @SuppressWarnings({"deprecation", "removal"})
  @Test
  void deprecatedThreeArgConstructorProducesAFreshCancellationSignalPerCall() {
    AgentSession session = new AgentSession("session-1", "service-1", Map.of());

    AgentRunContext first = new AgentRunContext(AGENT, session, ContextAttributes.empty());
    AgentRunContext second = new AgentRunContext(AGENT, session, ContextAttributes.empty());

    assertThat(first.sessionContext().cancellationSignal())
        .isNotSameAs(second.sessionContext().cancellationSignal());
  }

  @SuppressWarnings({"deprecation", "removal"})
  @Test
  void deprecatedThreeArgConstructorStillValidatesAgentNonNull() {
    AgentSession session = new AgentSession("session-1", "service-1", Map.of());

    assertThatThrownBy(() -> new AgentRunContext(null, session, ContextAttributes.empty()))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("agent must not be null");
  }

  private static final class NoOpAgent extends Agent {
    private NoOpAgent() {
      super("no-op-agent", "NoOpAgent", "agent test double");
    }

    @Override
    protected AgentRun runInternal(AgentRunContext context, AgentRunRequest request) {
      throw new UnsupportedOperationException("not used by this test");
    }

    @Override
    protected AgentStreamingRun<AgentResponseUpdate> runStreamingInternal(
        AgentRunContext context, AgentRunRequest request) {
      throw new UnsupportedOperationException("not used by this test");
    }
  }
}
