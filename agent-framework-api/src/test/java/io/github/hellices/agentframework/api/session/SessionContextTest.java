package io.github.hellices.agentframework.api.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.agent.AgentResponse;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionContextTest {

  @Test
  void responseIsEmptyUntilCompleted() {
    SessionContext sessionContext =
        new SessionContext(null, List.of(), Map.of(), new CancellationSignal());

    assertThat(sessionContext.response()).isEmpty();
  }

  @Test
  void completeFillsTheResponseSlotExactlyOnce() {
    SessionContext sessionContext =
        new SessionContext(null, List.of(), Map.of(), new CancellationSignal());
    AgentResponse response = sampleResponse();

    sessionContext.complete(response);

    assertThat(sessionContext.response()).contains(response);
  }

  @Test
  void completeRejectsASecondCallEvenWithTheSameValue() {
    SessionContext sessionContext =
        new SessionContext(null, List.of(), Map.of(), new CancellationSignal());
    AgentResponse response = sampleResponse();
    sessionContext.complete(response);

    assertThatThrownBy(() -> sessionContext.complete(response))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("session context response is already complete");

    // The set-once slot keeps holding the first value; the rejected second call must not
    // overwrite it or clear it.
    assertThat(sessionContext.response()).contains(response);
  }

  @Test
  void completeRejectsASecondCallWithADifferentValue() {
    SessionContext sessionContext =
        new SessionContext(null, List.of(), Map.of(), new CancellationSignal());
    AgentResponse first = sampleResponse();
    AgentResponse second = sampleResponse();
    sessionContext.complete(first);

    assertThatThrownBy(() -> sessionContext.complete(second))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("session context response is already complete");
    assertThat(sessionContext.response()).contains(first);
  }

  @Test
  void completeRejectsANullResponse() {
    SessionContext sessionContext =
        new SessionContext(null, List.of(), Map.of(), new CancellationSignal());

    assertThatThrownBy(() -> sessionContext.complete(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("response must not be null");
    assertThat(sessionContext.response()).isEmpty();
  }

  @Test
  void normalizesANullCancellationSignalToAFreshOne() {
    SessionContext sessionContext = new SessionContext(null, List.of(), Map.of(), null);

    assertThat(sessionContext.cancellationSignal()).isNotNull();
    assertThat(sessionContext.cancellationSignal().isCancelled()).isFalse();
  }

  @Test
  void normalizedCancellationSignalIsFreshPerInstance() {
    SessionContext first = new SessionContext(null, List.of(), Map.of(), null);
    SessionContext second = new SessionContext(null, List.of(), Map.of(), null);

    assertThat(first.cancellationSignal()).isNotSameAs(second.cancellationSignal());
  }

  private static AgentResponse sampleResponse() {
    return new AgentResponse(
        "agent-1",
        "response-1",
        "message-1",
        "agent",
        null,
        FinishReason.STOP,
        List.of(new Message(Role.ASSISTANT, List.of(new TextContent("ok")))),
        null,
        Map.of(),
        null);
  }
}
