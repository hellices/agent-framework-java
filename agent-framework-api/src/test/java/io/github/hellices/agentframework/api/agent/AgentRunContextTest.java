package io.github.hellices.agentframework.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.context.ContextAttributes;
import io.github.hellices.agentframework.api.context.ContextKey;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.session.SessionContext;
import io.github.hellices.agentframework.api.session.SessionState;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRunContextTest {

  private static final Agent AGENT = new NoOpAgent();
  private static final ContextKey<String> TENANT = ContextKey.of("agent", "tenant", String.class);

  @Test
  void acceptsMatchingSession() {
    AgentSession session = session("session-1", "service-1");
    SessionContext sessionContext =
        new SessionContext(session, List.of(), ContextAttributes.empty(), new CancellationSignal());

    AgentRunContext context =
        AgentRunContext.builder()
            .agent(AGENT)
            .session(session)
            .attributes(ContextAttributes.empty())
            .sessionContext(sessionContext)
            .build();

    assertThat(context.session()).isEqualTo(session);
    assertThat(context.sessionContext().session()).isEqualTo(session);
  }

  @Test
  void acceptsBothSessionsNullForSessionlessRuns() {
    SessionContext sessionContext =
        new SessionContext(null, List.of(), ContextAttributes.empty(), new CancellationSignal());

    AgentRunContext context =
        AgentRunContext.builder()
            .agent(AGENT)
            .attributes(ContextAttributes.empty())
            .sessionContext(sessionContext)
            .build();

    assertThat(context.session()).isNull();
    assertThat(context.sessionContext().session()).isNull();
  }

  @Test
  void rejectsMismatchedSession() {
    AgentSession session = session("session-1", "service-1");
    AgentSession otherSession = session("session-2", "service-1");
    SessionContext sessionContext =
        new SessionContext(
            otherSession, List.of(), ContextAttributes.empty(), new CancellationSignal());

    assertThatThrownBy(
            () ->
                AgentRunContext.builder()
                    .agent(AGENT)
                    .session(session)
                    .attributes(ContextAttributes.empty())
                    .sessionContext(sessionContext)
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("session must match sessionContext.session()");
  }

  @Test
  void rejectsSessionWhenSessionContextIsSessionless() {
    AgentSession session = session("session-1", "service-1");
    SessionContext sessionContext =
        new SessionContext(null, List.of(), ContextAttributes.empty(), new CancellationSignal());

    assertThatThrownBy(
            () ->
                AgentRunContext.builder()
                    .agent(AGENT)
                    .session(session)
                    .attributes(ContextAttributes.empty())
                    .sessionContext(sessionContext)
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("session must match sessionContext.session()");
  }

  @Test
  void rejectsSessionContextWhenSessionIsSessionless() {
    AgentSession sessionContextSession = session("session-1", "service-1");
    SessionContext sessionContext =
        new SessionContext(
            sessionContextSession, List.of(), ContextAttributes.empty(), new CancellationSignal());

    assertThatThrownBy(
            () ->
                AgentRunContext.builder()
                    .agent(AGENT)
                    .attributes(ContextAttributes.empty())
                    .sessionContext(sessionContext)
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("session must match sessionContext.session()");
  }

  @Test
  void requestBuilderRoundTripsAndDefensivelyCopiesMessages() {
    List<Message> messages = new ArrayList<>(List.of(message("hello")));
    AgentSession session = session("session-1", "service-1");
    CancellationSignal signal = new CancellationSignal();
    ContextAttributes attributes = ContextAttributes.builder().put(TENANT, "acme").build();
    AgentRunRequest request =
        AgentRunRequest.builder()
            .messages(messages)
            .session(session)
            .options(AgentRunOptions.builder().build())
            .cancellationSignal(signal)
            .attributes(attributes)
            .build();

    messages.add(message("later"));

    assertThat(request.toBuilder().build()).isEqualTo(request).hasSameHashCodeAs(request);
    assertThat(request.cancellationSignal()).isSameAs(signal);
    assertThat(request.messages()).extracting(Message::text).containsExactly("hello");
    assertThat(request.session()).isEqualTo(session);
    assertThat(request.attributes().get(TENANT)).contains("acme");
    assertThatThrownBy(() -> request.messages().add(message("boom")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void requestEqualityIncludesContinuationStateButExcludesCancellationSignal() {
    AgentSession session = session("session-1", "service-1");
    AgentRunOptions options = AgentRunOptions.builder().continuationToken("continuation-1").build();
    ContextAttributes attributes = ContextAttributes.builder().put(TENANT, "acme").build();
    AgentRunRequest first =
        AgentRunRequest.builder()
            .session(session)
            .options(options)
            .cancellationSignal(new CancellationSignal())
            .attributes(attributes)
            .build();
    AgentRunRequest same =
        AgentRunRequest.builder()
            .session(session)
            .options(options)
            .cancellationSignal(new CancellationSignal())
            .attributes(attributes)
            .build();
    AgentRunRequest differentContinuation =
        AgentRunRequest.builder()
            .session(session)
            .options(AgentRunOptions.builder().continuationToken("continuation-2").build())
            .attributes(attributes)
            .build();

    assertThat(first).isEqualTo(same).hasSameHashCodeAs(same);
    assertThat(first).isNotEqualTo(differentContinuation);
  }

  @Test
  void contextBuilderRoundTrips() {
    AgentSession session = session("session-1", "service-1");
    ContextAttributes attributes = ContextAttributes.builder().put(TENANT, "acme").build();
    SessionContext sessionContext =
        new SessionContext(session, List.of(), attributes, new CancellationSignal());
    AgentRunContext context =
        AgentRunContext.builder()
            .agent(AGENT)
            .session(session)
            .attributes(attributes)
            .sessionContext(sessionContext)
            .build();

    assertThat(context.toBuilder().build()).isEqualTo(context).hasSameHashCodeAs(context);
  }

  @Test
  void legacyConstructorsAreRemoved() {
    assertThat(
            findConstructor(
                AgentRunRequest.class,
                List.class,
                AgentSession.class,
                AgentRunOptions.class,
                CancellationSignal.class,
                ContextAttributes.class))
        .isEmpty();
    assertThat(
            findConstructor(
                AgentRunContext.class,
                Agent.class,
                AgentSession.class,
                ContextAttributes.class,
                SessionContext.class))
        .isEmpty();
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

  private static AgentSession session(String sessionId, String serviceSessionId) {
    return AgentSession.builder()
        .sessionId(sessionId)
        .serviceSessionId(serviceSessionId)
        .state(SessionState.empty())
        .build();
  }

  private static Message message(String text) {
    return new Message(Role.USER, List.of(new TextContent(text)));
  }
}
