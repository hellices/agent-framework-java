package io.github.hellices.agentframework.api.agent;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class AgentRun {

  private final CompletionStage<AgentResponse> response;
  private final CancellationSignal cancellationSignal;
  private final Runnable cancellationAction;

  public AgentRun(AgentResponse response) {
    this(
        CompletableFuture.completedFuture(
            Objects.requireNonNull(response, "response must not be null")),
        new CancellationSignal());
  }

  public AgentRun(CompletionStage<AgentResponse> response, CancellationSignal cancellationSignal) {
    CompletionStage<AgentResponse> source =
        Objects.requireNonNull(response, "response must not be null");
    this.cancellationSignal =
        cancellationSignal == null ? new CancellationSignal() : cancellationSignal;
    CompletableFuture<AgentResponse> result = new CompletableFuture<>();
    Runnable removeCancellationListener =
        this.cancellationSignal.onCancel(
            () -> result.completeExceptionally(new CancellationException("run was cancelled")));
    source.whenComplete(
        (value, failure) -> {
          removeCancellationListener.run();
          if (failure == null) {
            result.complete(value);
          } else {
            result.completeExceptionally(failure);
          }
        });
    this.response = result.minimalCompletionStage();
    this.cancellationAction = this.cancellationSignal::cancel;
  }

  public CompletionStage<AgentResponse> response() {
    return response;
  }

  public void cancel() {
    cancellationAction.run();
  }
}
