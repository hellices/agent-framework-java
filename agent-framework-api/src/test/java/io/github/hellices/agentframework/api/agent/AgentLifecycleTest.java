package io.github.hellices.agentframework.api.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import java.util.List;
import java.util.Map;
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

  private static final class TestAgent extends Agent {
    private TestAgent(String id) {
      super(id, "TestAgent", "agent test");
    }

    @Override
    public AgentRun run(AgentRunRequest request) {
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
    public AgentStreamingRun runStreaming(AgentRunRequest request) {
      return new AgentStreamingRun(
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
              null));
    }
  }
}
