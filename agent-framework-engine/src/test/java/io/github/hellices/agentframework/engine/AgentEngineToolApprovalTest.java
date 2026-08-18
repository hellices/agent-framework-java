package io.github.hellices.agentframework.engine;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.hellices.agentframework.api.agent.Agent;
import io.github.hellices.agentframework.api.agent.AgentRunOptions;
import io.github.hellices.agentframework.api.agent.AgentRunRequest;
import io.github.hellices.agentframework.api.agent.AgentSession;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.agent.RunContribution;
import io.github.hellices.agentframework.api.context.ContextAttributes;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.message.ToolApprovalRequestContent;
import io.github.hellices.agentframework.api.session.SessionContext;
import io.github.hellices.agentframework.api.session.SessionSnapshot;
import io.github.hellices.agentframework.api.session.SessionStateEntry;
import io.github.hellices.agentframework.api.session.SessionStateKey;
import io.github.hellices.agentframework.api.value.JsonObject;
import io.github.hellices.agentframework.api.value.JsonValues;
import io.github.hellices.agentframework.engine.internal.tool.ToolApprovalQueueState;
import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import io.github.hellices.agentframework.spi.session.ProviderSessionState;
import io.github.hellices.agentframework.spi.session.SessionStore;
import io.github.hellices.agentframework.spi.session.StatefulContextProvider;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

/**
 * Task 4 persisted-state contract: the tool approval queue is a versioned, engine-registered
 * session state type that survives a save/load round trip under its reserved state key, without
 * exercising any approval pipeline/state-machine behavior (that is Task 5's scope).
 */
class AgentEngineToolApprovalTest {

  @Test
  void approvalQueueStateRoundTripsThroughTheEngineDefaultRegistryWithoutACustomRegistry() {
    RecordingSessionStore store = new RecordingSessionStore();
    ToolApprovalRequestContent first =
        new ToolApprovalRequestContent(
            "req-1", "call-1", "weather", jsonObject(Map.of("city", "Seoul")), "mcp-server-a");
    ToolApprovalRequestContent second =
        new ToolApprovalRequestContent(
            "req-2", "call-2", "search", jsonObject(Map.of("query", "news")), null);
    ApprovalQueueProvider writer =
        new ApprovalQueueProvider(
            new ToolApprovalQueueState(List.of(first, second)), /* write= */ true);
    Agent writerAgent = agentWithStore(store, fixedClient("hello"), writer);

    runWithSession(writerAgent, "session-1");

    SessionSnapshot savedSnapshot = store.saved.get("session-1");
    assertThat(savedSnapshot).isNotNull();
    SessionStateEntry entry = savedSnapshot.state().get(ToolApprovalQueueState.STATE_ID);
    assertThat(entry).isNotNull();
    assertThat(entry.typeId()).isEqualTo("engine.tool_approval_queue");
    assertThat(entry.codecVersion()).isEqualTo(1);

    ApprovalQueueProvider reader = new ApprovalQueueProvider(null, /* write= */ false);
    Agent readerAgent = agentWithStore(store, fixedClient("hello"), reader);

    runWithSession(readerAgent, "session-1");

    assertThat(reader.observed).isPresent();
    assertThat(reader.observed.get().pending()).containsExactly(first, second);
  }

  @Test
  void approvalQueueStatePreservesFifoOrderAndExactArgumentsAcrossARoundTrip() {
    RecordingSessionStore store = new RecordingSessionStore();
    ToolApprovalRequestContent nested =
        new ToolApprovalRequestContent(
            "req-1",
            "call-1",
            "weather",
            jsonObject(Map.of("city", "Seoul", "days", 3)),
            "mcp-server-a");
    ApprovalQueueProvider writer =
        new ApprovalQueueProvider(new ToolApprovalQueueState(List.of(nested)), true);
    runWithSession(agentWithStore(store, fixedClient("hello"), writer), "session-2");

    ApprovalQueueProvider reader = new ApprovalQueueProvider(null, false);
    runWithSession(agentWithStore(store, fixedClient("hello"), reader), "session-2");

    assertThat(reader.observed).isPresent();
    ToolApprovalRequestContent restored = reader.observed.get().pending().get(0);
    assertThat(restored.requestId()).isEqualTo("req-1");
    assertThat(restored.toolCallId()).isEqualTo("call-1");
    assertThat(restored.toolName()).isEqualTo("weather");
    assertThat(restored.arguments()).isEqualTo(jsonObject(Map.of("city", "Seoul", "days", 3L)));
    assertThat(restored.hostBoundary()).contains("mcp-server-a");
  }

  @Test
  void approvalQueueStateRoundTripsAnAbsentHostBoundaryAsEmpty() {
    RecordingSessionStore store = new RecordingSessionStore();
    ToolApprovalRequestContent withoutHostBoundary =
        new ToolApprovalRequestContent("req-1", "call-1", "search", JsonObject.empty(), null);
    ApprovalQueueProvider writer =
        new ApprovalQueueProvider(new ToolApprovalQueueState(List.of(withoutHostBoundary)), true);
    runWithSession(agentWithStore(store, fixedClient("hello"), writer), "session-3");

    ApprovalQueueProvider reader = new ApprovalQueueProvider(null, false);
    runWithSession(agentWithStore(store, fixedClient("hello"), reader), "session-3");

    assertThat(reader.observed).isPresent();
    assertThat(reader.observed.get().pending().get(0).hostBoundary()).isEmpty();
  }

  @Test
  void emptyApprovalQueueStateRoundTripsToAnEmptyQueue() {
    RecordingSessionStore store = new RecordingSessionStore();
    ApprovalQueueProvider writer = new ApprovalQueueProvider(ToolApprovalQueueState.empty(), true);
    runWithSession(agentWithStore(store, fixedClient("hello"), writer), "session-4");

    ApprovalQueueProvider reader = new ApprovalQueueProvider(null, false);
    runWithSession(agentWithStore(store, fixedClient("hello"), reader), "session-4");

    assertThat(reader.observed).isPresent();
    assertThat(reader.observed.get().pending()).isEmpty();
  }

  private static Agent agentWithStore(
      SessionStore store, ModelClient client, StatefulContextProvider<?> provider) {
    return AgentEngine.builder()
        .sessionStore(store)
        .build()
        .factory()
        .builderWithClient(client)
        .contextProviders(provider)
        .build();
  }

  private static void runWithSession(Agent agent, String sessionId) {
    agent
        .run(
            AgentRunRequest.builder()
                .messages(Message.normalize("hi"))
                .session(AgentSession.builder().sessionId(sessionId).build())
                .options(new AgentRunOptions())
                .cancellationSignal(new CancellationSignal())
                .attributes(ContextAttributes.empty())
                .build())
        .response()
        .toCompletableFuture()
        .join();
  }

  private static ModelClient fixedClient(String text) {
    return request ->
        EngineModels.of(
            ModelResponse.builder()
                .messages(List.of(new Message(Role.ASSISTANT, List.of(new TextContent(text)))))
                .finishReason(FinishReason.STOP)
                .build());
  }

  private static JsonObject jsonObject(Map<String, ?> values) {
    return values.isEmpty() ? JsonObject.empty() : (JsonObject) JsonValues.fromJava(values);
  }

  /**
   * A stateful provider bound to the reserved approval-queue key so the test can drive a save (when
   * {@code write} is true, writing {@code initial} during {@code complete}) or a load (when {@code
   * write} is false, capturing whatever the coordinator restored into {@code observed} during
   * {@code prepare}), without depending on any tool-loop wiring.
   */
  private static final class ApprovalQueueProvider
      implements StatefulContextProvider<ToolApprovalQueueState> {
    private final ToolApprovalQueueState initial;
    private final boolean write;
    private volatile Optional<ToolApprovalQueueState> observed = Optional.empty();

    private ApprovalQueueProvider(ToolApprovalQueueState initial, boolean write) {
      this.initial = initial;
      this.write = write;
    }

    @Override
    public SessionStateKey<ToolApprovalQueueState> stateKey() {
      return ToolApprovalQueueState.STATE_KEY;
    }

    @Override
    public CompletionStage<RunContribution> prepare(
        SessionContext context, ProviderSessionState<ToolApprovalQueueState> state) {
      observed = state.value();
      return completedFuture(RunContribution.empty());
    }

    @Override
    public CompletionStage<Void> complete(
        SessionContext context, ProviderSessionState<ToolApprovalQueueState> state) {
      if (write) {
        state.set(initial);
      }
      return completedFuture(null);
    }
  }

  private static final class RecordingSessionStore implements SessionStore {
    private final Map<String, SessionSnapshot> saved = new HashMap<>();

    @Override
    public CompletionStage<Optional<SessionSnapshot>> load(String sessionId) {
      return completedFuture(Optional.ofNullable(saved.get(sessionId)));
    }

    @Override
    public CompletionStage<Void> save(SessionSnapshot snapshot) {
      saved.put(snapshot.sessionId(), snapshot);
      return completedFuture(null);
    }

    @Override
    public CompletionStage<Void> delete(String sessionId) {
      saved.remove(sessionId);
      return completedFuture(null);
    }
  }
}
