package io.github.hellices.agentframework.engine.internal.model;

import io.github.hellices.agentframework.api.agent.AgentResponse;
import io.github.hellices.agentframework.api.agent.AgentResponseUpdate;
import io.github.hellices.agentframework.api.value.JsonObject;
import io.github.hellices.agentframework.api.value.JsonValues;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import io.github.hellices.agentframework.spi.model.ModelResponseUpdate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Rebuilds the {@link ModelResponse} of one streaming model call from the updates that call
 * emitted.
 *
 * <p>The streaming tool loop has to answer the same questions after a model call that the ordinary
 * loop answers from a returned response — which tools were called, with which arguments, what did
 * the call cost, did the model finish or ask to be resumed — but a stream delivers those in pieces:
 * a tool call's arguments may arrive split across updates, and usage typically arrives last. This
 * accumulator collects the updates of a single call and reassembles them with exactly the merge
 * rules {@link AgentResponse#fromUpdates} uses, so a streaming iteration and an ordinary one see
 * the same response, and the assembled outer response of the run is a merge of the same updates the
 * subscriber saw.
 *
 * <p>One instance covers one model call; a new iteration gets a new accumulator, which is what
 * keeps a later call's usage and finish reason from being attributed to an earlier one.
 *
 * <p>Metadata is the one value the forwarded update does not repeat. {@link
 * AgentResponse#fromUpdates} unions the metadata of every update it assembles, so forwarding each
 * call's metadata would make a run's assembled metadata the union of all its model calls, where an
 * ordinary run reports the metadata of its terminal call alone. The accumulator therefore records
 * the model's metadata — {@link #toModelResponse()} still reports it, so the loop can report the
 * terminal call's metadata once — and forwards an update without it.
 *
 * <p>Instances are not thread-safe: they are written from the serialized signals of one model
 * publisher.
 */
public final class StreamingModelResponseAccumulator {

  private final ResponseIdentity identity;
  private final List<AgentResponseUpdate> updates = new ArrayList<>();

  public StreamingModelResponseAccumulator(ResponseIdentity identity) {
    this.identity = Objects.requireNonNull(identity, "identity must not be null");
  }

  /**
   * Maps one model update onto this run's response identity, records it, and returns the update to
   * forward downstream, so the subscriber and the reassembled response are fed from the same
   * values.
   */
  public AgentResponseUpdate record(ModelResponseUpdate update) {
    return record(identity.map(update));
  }

  /**
   * Records an already-mapped update and returns the form of it the loop forwards, which reports
   * everything the model reported except its metadata.
   *
   * <p>An update that belongs to another response is rejected here rather than at the end of the
   * iteration: mixing identities would otherwise surface as a failure of the whole run assembly,
   * long after the update that caused it was forwarded downstream.
   */
  public AgentResponseUpdate record(AgentResponseUpdate update) {
    AgentResponseUpdate value =
        Objects.requireNonNull(update, "agent response update must not be null");
    if (!identity.agentId().equals(value.agentId())
        || !identity.responseId().equals(value.responseId())) {
      throw new IllegalStateException("model update does not belong to this agent response");
    }
    updates.add(value);
    return withoutMetadata(value);
  }

  private static AgentResponseUpdate withoutMetadata(AgentResponseUpdate update) {
    if (update.additionalProperties().isEmpty()) {
      return update;
    }
    return new AgentResponseUpdate(
        update.agentId(),
        update.responseId(),
        update.messageId(),
        update.authorName(),
        update.createdAt(),
        update.finishReason(),
        update.continuationToken(),
        update.messages(),
        update.usage(),
        Map.of(),
        update.rawRepresentation());
  }

  /** Whether this model call has emitted no update yet. */
  public boolean isEmpty() {
    return updates.isEmpty();
  }

  /**
   * The response this model call amounts to.
   *
   * <p>A call that completed without a single update fails: the ordinary loop always has a response
   * to inspect, and continuing with an invented empty one would silently turn a broken client into
   * a run that ends with no answer and no error.
   */
  public ModelResponse toModelResponse() {
    if (updates.isEmpty()) {
      throw new IllegalStateException("model stream completed without any update");
    }
    AgentResponse response = AgentResponse.fromUpdates(updates);
    return ModelResponse.builder()
        .messages(response.messages())
        .usage(response.usage())
        .finishReason(response.finishReason())
        .continuationToken(response.continuationToken())
        .metadata(jsonObject(response.additionalProperties()))
        .rawRepresentation(response.rawRepresentation())
        .build();
  }

  private static JsonObject jsonObject(Map<String, Object> values) {
    return values.isEmpty() ? JsonObject.empty() : (JsonObject) JsonValues.fromJava(values);
  }
}
