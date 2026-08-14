package io.github.hellices.agentframework.api.agent;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.Flow;

public final class AgentStreamingRun {

  private final Flow.Publisher<AgentResponseUpdate> updates;
  private final CompletionStage<AgentResponse> response;
  private final CancellationSignal cancellationSignal;

  public AgentStreamingRun(AgentResponseUpdate update) {
    this(toPublisher(update), CompletableFuture.completedFuture(toResponse(update)), new CancellationSignal());
  }

  public AgentStreamingRun(
      Flow.Publisher<AgentResponseUpdate> updates,
      CompletionStage<AgentResponse> response,
      CancellationSignal cancellationSignal) {
    this.updates = Objects.requireNonNull(updates, "updates must not be null");
    this.response = Objects.requireNonNull(response, "response must not be null");
    this.cancellationSignal = cancellationSignal == null ? new CancellationSignal() : cancellationSignal;
  }

  public Flow.Publisher<AgentResponseUpdate> updates() {
    return updates;
  }

  public CompletionStage<AgentResponse> response() {
    return response;
  }

  public void cancel() {
    cancellationSignal.cancel();
  }

  private static Flow.Publisher<AgentResponseUpdate> toPublisher(AgentResponseUpdate update) {
    SubmissionPublisher<AgentResponseUpdate> publisher = new SubmissionPublisher<>();
    publisher.submit(Objects.requireNonNull(update, "update must not be null"));
    publisher.close();
    return publisher;
  }

  private static AgentResponse toResponse(AgentResponseUpdate update) {
    return new AgentResponse(
        update.agentId(),
        update.responseId(),
        update.messageId(),
        update.authorName(),
        update.createdAt(),
        update.finishReason(),
        update.messages(),
        update.usage(),
        update.additionalProperties(),
        update.rawRepresentation());
  }
}
