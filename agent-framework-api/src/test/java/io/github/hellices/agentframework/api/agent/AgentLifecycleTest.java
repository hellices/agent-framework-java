package io.github.hellices.agentframework.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.session.SessionContext;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class AgentLifecycleTest {

  @Test
  void cancellationSignalTracksState() {
    CancellationSignal signal = new CancellationSignal();
    assertThat(signal.isCancelled()).isFalse();
    signal.cancel();
    assertThat(signal.isCancelled()).isTrue();
  }

  @Test
  void requestNormalizesInputAndKeepsDefaults() {
    AgentRunRequest request = AgentRunRequest.of("hello");

    assertThat(request.messages()).hasSize(1);
    assertThat(request.messages().get(0).role()).isEqualTo(Role.USER);
    assertThat(request.cancellationSignal()).isNotNull();
    assertThat(request.options()).isNotNull();
  }

  @Test
  void agentExposesStableIdAndRunEntryPoints() {
    TestAgent agent = new TestAgent("test-agent");

    assertThat(agent.id()).isEqualTo("test-agent");
    assertThat(agent.run("hi").response()).isNotNull();
    assertThat(agent.runStreaming("hi").updates()).isNotNull();
  }

  @Test
  void runFailsBeforeExecutionWhenSessionIsIncompatible() {
    CompatibilityCheckingAgent agent = new CompatibilityCheckingAgent("test-agent");

    AgentRunRequest request =
        new AgentRunRequest(
            Message.normalize("hi"),
            new AgentSession("session-1", "service-1", Map.of()),
            new AgentRunOptions(),
            new CancellationSignal(),
            Map.of());

    assertThatThrownBy(() -> agent.run(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("session session-1 is not compatible with agent test-agent");
    assertThat(agent.executedRun).isFalse();
    assertThat(agent.executedStreamingRun).isFalse();
  }

  @Test
  void streamingRunFailsBeforeExecutionWhenSessionIsIncompatible() {
    CompatibilityCheckingAgent agent = new CompatibilityCheckingAgent("test-agent");

    AgentRunRequest request =
        new AgentRunRequest(
            Message.normalize("hi"),
            new AgentSession("session-1", "service-1", Map.of()),
            new AgentRunOptions(),
            new CancellationSignal(),
            Map.of());

    assertThatThrownBy(() -> agent.runStreaming(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("session session-1 is not compatible with agent test-agent");
    assertThat(agent.executedRun).isFalse();
    assertThat(agent.executedStreamingRun).isFalse();
  }

  @Test
  void delegatingAgentPreservesIdentityAndDelegatesCalls() {
    TestAgent delegate = new TestAgent("delegate-id");
    Agent agent = new DelegatingAgent(delegate) {};

    AgentRun run = agent.run("hello");
    AgentStreamingRun<AgentResponseUpdate> streamingRun = agent.runStreaming("hello");

    assertThat(agent.id()).isEqualTo("delegate-id");
    assertThat(agent.name()).isEqualTo("TestAgent");
    assertThat(run.response()).isNotNull();
    assertThat(streamingRun.updates()).isNotNull();
  }

  @Test
  void stackingWrappersKeepsRunResultStable() throws Exception {
    TestAgent baseAgent = new TestAgent("base-agent");
    Agent wrapped = new DelegatingAgent(new DelegatingAgent(baseAgent) {}) {};

    assertThat(wrapped.run("hello").response().toCompletableFuture().get().text()).isEqualTo("ok");
    AgentStreamingRun<AgentResponseUpdate> streamingRun = wrapped.runStreaming("hello");
    consume(streamingRun.updates());
    assertThat(streamingRun.response().toCompletableFuture().get().text()).isEqualTo("ok");
  }

  @Test
  void runBuildsExplicitRunContextWithoutGlobalState() {
    ContextCapturingAgent agent = new ContextCapturingAgent("ctx-agent");
    AgentSession session = new AgentSession("session-42", "service-42", Map.of());
    AgentRunRequest request =
        new AgentRunRequest(
            Message.normalize("hello"),
            session,
            new AgentRunOptions(),
            new CancellationSignal(),
            Map.of("traceId", "trace-42"));

    agent.run(request);

    assertThat(agent.lastContext).isNotNull();
    assertThat(agent.lastContext.agent()).isEqualTo(agent);
    assertThat(agent.lastContext.session()).isEqualTo(session);
    assertThat(agent.lastContext.attributes()).containsEntry("traceId", "trace-42");
  }

  @Test
  void convenienceRunMethodsAreFinalToKeepInvariantChecks() throws Exception {
    assertThat(Modifier.isFinal(Agent.class.getMethod("run", String.class).getModifiers()))
        .isTrue();
    assertThat(Modifier.isFinal(Agent.class.getMethod("runStreaming", String.class).getModifiers()))
        .isTrue();
  }

  @Test
  void runContextIsPreservedWhenRunExecutesOnAnotherThread() {
    ContextCapturingAgent agent = new ContextCapturingAgent("ctx-agent");
    AgentSession session = new AgentSession("session-42", "service-42", Map.of());
    AgentRunRequest request =
        new AgentRunRequest(
            Message.normalize("hello"),
            session,
            new AgentRunOptions(),
            new CancellationSignal(),
            Map.of("traceId", "trace-42"));

    CompletableFuture.runAsync(() -> agent.run(request)).join();

    assertThat(agent.lastContext).isNotNull();
    assertThat(agent.lastContext.agent()).isEqualTo(agent);
    assertThat(agent.lastContext.session()).isEqualTo(session);
    assertThat(agent.lastContext.attributes()).containsEntry("traceId", "trace-42");
  }

  @Test
  void streamingRunHandleCancelsTheRequestSignal() {
    TestAgent agent = new TestAgent("test-agent");
    CancellationSignal signal = new CancellationSignal();
    AgentRunRequest request =
        new AgentRunRequest(
            Message.normalize("hello"), null, new AgentRunOptions(), signal, Map.of());

    agent.runStreaming(request).cancel();

    assertThat(signal.isCancelled()).isTrue();
  }

  @Test
  void runHandleCancelsTheRequestSignalAfterCompletion() {
    TestAgent agent = new TestAgent("test-agent");
    CancellationSignal signal = new CancellationSignal();
    AgentRunRequest request =
        new AgentRunRequest(
            Message.normalize("hello"), null, new AgentRunOptions(), signal, Map.of());

    agent.run(request).cancel();

    assertThat(signal.isCancelled()).isTrue();
  }

  @Test
  void eachRunReceivesADistinctSessionContextAndFillsItOnSuccess() {
    SessionContextCapturingAgent agent = new SessionContextCapturingAgent("ctx-agent");

    AgentRun first = agent.run(AgentRunRequest.of("one"));
    AgentRun second = agent.run(AgentRunRequest.of("two"));

    assertThat(agent.contexts).hasSize(2);
    assertThat(agent.contexts.get(0)).isNotSameAs(agent.contexts.get(1));
    assertThat(first.response().toCompletableFuture().join())
        .isSameAs(agent.contexts.get(0).response().orElseThrow());
    assertThat(second.response().toCompletableFuture().join())
        .isSameAs(agent.contexts.get(1).response().orElseThrow());
  }

  @Test
  void sessionContextCopiesInputMessagesSessionAndMetadata() {
    SessionContextCapturingAgent agent = new SessionContextCapturingAgent("ctx-agent");
    AgentSession session = new AgentSession("session-42", "service-42", Map.of());
    AgentRunRequest request =
        new AgentRunRequest(
            Message.normalize("hello"),
            session,
            new AgentRunOptions(),
            new CancellationSignal(),
            Map.of("traceId", "trace-42"));

    agent.run(request);

    SessionContext sessionContext = agent.contexts.get(0);
    assertThat(sessionContext.session()).isEqualTo(session);
    assertThat(sessionContext.inputMessages()).isEqualTo(request.messages());
    assertThat(sessionContext.metadata()).containsEntry("traceId", "trace-42");
    assertThat(sessionContext.contextMessages()).isEmpty();
    assertThat(sessionContext.cancellationSignal()).isEqualTo(request.cancellationSignal());
  }

  @Test
  void streamingRunFillsSessionContextResponseSlotOnlyAfterTerminalCompletion() {
    SessionContextCapturingAgent agent = new SessionContextCapturingAgent("ctx-agent");

    AgentStreamingRun<AgentResponseUpdate> streamingRun = agent.runStreaming("hello");
    SessionContext sessionContext = agent.contexts.get(0);
    assertThat(sessionContext.response()).isEmpty();

    consume(streamingRun.updates());

    assertThat(sessionContext.response()).isPresent();
    assertThat(streamingRun.response().toCompletableFuture().join())
        .isSameAs(sessionContext.response().orElseThrow());
  }

  @Test
  void runDoesNotFillSessionContextResponseSlotOnFailure() {
    FailingAgent agent = new FailingAgent("failing-agent");

    AgentRun run = agent.run("hello");

    assertThatThrownBy(() -> run.response().toCompletableFuture().join())
        .hasCauseInstanceOf(IllegalStateException.class);
    assertThat(agent.contexts).hasSize(1);
    assertThat(agent.contexts.get(0).response()).isEmpty();
  }

  @Test
  void cancellationListenerCanBeRemoved() {
    CancellationSignal signal = new CancellationSignal();
    boolean[] called = {false};
    Runnable remove = signal.onCancel(() -> called[0] = true);

    remove.run();
    signal.cancel();

    assertThat(called[0]).isFalse();
  }

  @Test
  void cancellationDrainsRemainingListenersAfterAListenerFails() {
    CancellationSignal signal = new CancellationSignal();
    boolean[] secondCalled = {false};
    signal.onCancel(
        () -> {
          throw new IllegalStateException("first failed");
        });
    signal.onCancel(() -> secondCalled[0] = true);

    assertThatThrownBy(signal::cancel)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("first failed");
    assertThat(secondCalled[0]).isTrue();
  }

  private static class TestAgent extends Agent {
    private TestAgent(String id) {
      super(id, "TestAgent", "agent test");
    }

    @Override
    protected AgentRun runInternal(AgentRunContext context, AgentRunRequest request) {
      return new AgentRun(
          CompletableFuture.completedFuture(
              new AgentResponse(
                  id(),
                  "response-1",
                  "message-1",
                  name(),
                  null,
                  io.github.hellices.agentframework.api.message.FinishReason.STOP,
                  List.of(new Message(Role.ASSISTANT, List.of(new TextContent("ok")))),
                  null,
                  Map.of(),
                  null)),
          request.cancellationSignal());
    }

    @Override
    protected AgentStreamingRun<AgentResponseUpdate> runStreamingInternal(
        AgentRunContext context, AgentRunRequest request) {
      return AgentStreamingRun.fromUpdate(
          new AgentResponseUpdate(
              id(),
              "response-1",
              "message-1",
              name(),
              null,
              io.github.hellices.agentframework.api.message.FinishReason.STOP,
              List.of(new Message(Role.ASSISTANT, List.of(new TextContent("ok")))),
              null,
              Map.of(),
              null),
          request.cancellationSignal());
    }
  }

  private static final class CompatibilityCheckingAgent extends TestAgent {
    private boolean executedRun;
    private boolean executedStreamingRun;

    private CompatibilityCheckingAgent(String id) {
      super(id);
    }

    @Override
    protected void validateSessionCompatibility(AgentSession session) {
      throw new IllegalArgumentException(
          "session " + session.sessionId() + " is not compatible with agent " + id());
    }

    @Override
    protected AgentRun runInternal(AgentRunContext context, AgentRunRequest request) {
      executedRun = true;
      return super.runInternal(context, request);
    }

    @Override
    protected AgentStreamingRun<AgentResponseUpdate> runStreamingInternal(
        AgentRunContext context, AgentRunRequest request) {
      executedStreamingRun = true;
      return super.runStreamingInternal(context, request);
    }
  }

  private static final class SessionContextCapturingAgent extends TestAgent {
    private final List<SessionContext> contexts = new ArrayList<>();

    private SessionContextCapturingAgent(String id) {
      super(id);
    }

    @Override
    protected AgentRun runInternal(AgentRunContext context, AgentRunRequest request) {
      contexts.add(context.sessionContext());
      return super.runInternal(context, request);
    }

    @Override
    protected AgentStreamingRun<AgentResponseUpdate> runStreamingInternal(
        AgentRunContext context, AgentRunRequest request) {
      contexts.add(context.sessionContext());
      return super.runStreamingInternal(context, request);
    }
  }

  private static final class FailingAgent extends TestAgent {
    private final List<SessionContext> contexts = new ArrayList<>();

    private FailingAgent(String id) {
      super(id);
    }

    @Override
    protected AgentRun runInternal(AgentRunContext context, AgentRunRequest request) {
      contexts.add(context.sessionContext());
      CompletableFuture<AgentResponse> failed = new CompletableFuture<>();
      failed.completeExceptionally(new IllegalStateException("model failure"));
      return new AgentRun(failed, request.cancellationSignal());
    }
  }

  private static final class ContextCapturingAgent extends TestAgent {
    private AgentRunContext lastContext;

    private ContextCapturingAgent(String id) {
      super(id);
    }

    @Override
    protected AgentRun runInternal(AgentRunContext context, AgentRunRequest request) {
      lastContext = context;
      return super.runInternal(context, request);
    }
  }

  private static <T> void consume(Flow.Publisher<T> publisher) {
    CompletableFuture<Void> completion = new CompletableFuture<>();
    publisher.subscribe(
        new Flow.Subscriber<>() {
          @Override
          public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
          }

          @Override
          public void onNext(T item) {}

          @Override
          public void onError(Throwable throwable) {
            completion.completeExceptionally(throwable);
          }

          @Override
          public void onComplete() {
            completion.complete(null);
          }
        });
    completion.join();
  }
}
