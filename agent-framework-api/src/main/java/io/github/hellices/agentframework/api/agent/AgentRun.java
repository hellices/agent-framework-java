package io.github.hellices.agentframework.api.agent;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class AgentRun {

  private final CompletionStage<AgentResponse> response;
  private final CancellationSignal cancellationSignal;

  public AgentRun(AgentResponse response) {
    this(
        CompletableFuture.completedFuture(
            Objects.requireNonNull(response, "response must not be null")),
        new CancellationSignal());
  }

  public AgentRun(CompletionStage<AgentResponse> response, CancellationSignal cancellationSignal) {
    this.response = Objects.requireNonNull(response, "response must not be null");
    this.cancellationSignal =
        cancellationSignal == null ? new CancellationSignal() : cancellationSignal;
  }

  public CompletionStage<AgentResponse> response() {
    return response;
  }

  public void cancel() {
    cancellationSignal.cancel();
  }
}
