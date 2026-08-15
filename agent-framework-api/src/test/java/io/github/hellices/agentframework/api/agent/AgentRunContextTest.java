package io.github.hellices.agentframework.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.session.SessionContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentRunContextTest {

  private static final Agent AGENT = new NoOpAgent();

  @Test
  void acceptsMatchingSession() {
    AgentSession session = new AgentSession("session-1", "service-1", Map.of());
    SessionContext sessionContext =
        new SessionContext(session, List.of(), Map.of(), new CancellationSignal());

    AgentRunContext context = new AgentRunContext(AGENT, session, Map.of(), sessionContext);

    assertThat(context.session()).isEqualTo(session);
    assertThat(context.sessionContext().session()).isEqualTo(session);
  }

  @Test
  void acceptsBothSessionsNullForSessionlessRuns() {
    SessionContext sessionContext =
        new SessionContext(null, List.of(), Map.of(), new CancellationSignal());

    AgentRunContext context = new AgentRunContext(AGENT, null, Map.of(), sessionContext);

    assertThat(context.session()).isNull();
    assertThat(context.sessionContext().session()).isNull();
  }

  @Test
  void rejectsMismatchedSession() {
    AgentSession session = new AgentSession("session-1", "service-1", Map.of());
    AgentSession otherSession = new AgentSession("session-2", "service-1", Map.of());
    SessionContext sessionContext =
        new SessionContext(otherSession, List.of(), Map.of(), new CancellationSignal());

    assertThatThrownBy(() -> new AgentRunContext(AGENT, session, Map.of(), sessionContext))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("session must match sessionContext.session()");
  }

  @Test
  void rejectsSessionWhenSessionContextIsSessionless() {
    AgentSession session = new AgentSession("session-1", "service-1", Map.of());
    SessionContext sessionContext =
        new SessionContext(null, List.of(), Map.of(), new CancellationSignal());

    assertThatThrownBy(() -> new AgentRunContext(AGENT, session, Map.of(), sessionContext))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("session must match sessionContext.session()");
  }

  @Test
  void rejectsSessionContextWhenSessionIsSessionless() {
    AgentSession sessionContextSession = new AgentSession("session-1", "service-1", Map.of());
    SessionContext sessionContext =
        new SessionContext(sessionContextSession, List.of(), Map.of(), new CancellationSignal());

    assertThatThrownBy(() -> new AgentRunContext(AGENT, null, Map.of(), sessionContext))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("session must match sessionContext.session()");
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
