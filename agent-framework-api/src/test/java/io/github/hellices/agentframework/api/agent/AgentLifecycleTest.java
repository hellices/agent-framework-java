package io.github.hellices.agentframework.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import java.lang.reflect.Modifier;
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

  private static class TestAgent extends Agent {
    private TestAgent(String id) {
      super(id, "TestAgent", "agent test");
    }

    @Override
    protected AgentRun runInternal(AgentRunContext context, AgentRunRequest request) {
      return new AgentRun(
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
              null));
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
