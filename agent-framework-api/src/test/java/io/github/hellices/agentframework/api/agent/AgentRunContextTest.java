package io.github.hellices.agentframework.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.context.ContextAttributes;
import io.github.hellices.agentframework.api.session.SessionContext;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentRunContextTest {

  private static final Agent AGENT = new NoOpAgent();

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

  @Test
  void rawThreeArgumentConstructorIsRemoved() {
    assertThat(
            findConstructor(
                AgentRunContext.class, Agent.class, AgentSession.class, ContextAttributes.class))
        .isEmpty();
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

  private static java.util.Optional<Constructor<?>> findConstructor(
      Class<?> type, Class<?>... parameterTypes) {
    try {
      return java.util.Optional.of(type.getDeclaredConstructor(parameterTypes));
    } catch (NoSuchMethodException missing) {
      return java.util.Optional.empty();
    }
  }
}
