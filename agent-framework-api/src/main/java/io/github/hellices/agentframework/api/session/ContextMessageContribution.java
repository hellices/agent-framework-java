package io.github.hellices.agentframework.api.session;

import io.github.hellices.agentframework.api.message.Message;
import java.util.Objects;

/**
 * One context message together with the provider that actually contributed it to this run
 * (SES-012). {@link SessionContext#contextContributions()} exposes these in global contribution
 * order, so a consumer can select context by contributing provider without having to trust the
 * message's own attribution.
 *
 * <p>The distinction matters because the two carry different facts. {@link Message#attribution()}
 * is content provenance and is deliberately preserved across sessions: a memory or retrieval
 * provider may hand over a message that still names the store it came from, and any provider can
 * attach any source id it likes. The contribution's {@link #sourceId()} is the run-time fact the
 * framework observed — which configured provider called {@link
 * SessionContext#addContextMessages(String, java.util.List)} — so a filter keyed on it cannot be
 * spoofed by a sibling provider or by content restored from another session.
 */
public final class ContextMessageContribution {

  private final String sourceId;
  private final Message message;

  /**
   * @throws IllegalArgumentException if {@code sourceId} is non-null and blank, because a blank
   *     source id carries no provenance and would silently match nothing
   * @throws NullPointerException if {@code message} is {@code null}
   */
  public ContextMessageContribution(String sourceId, Message message) {
    if (sourceId != null && sourceId.isBlank()) {
      throw new IllegalArgumentException("sourceId must not be blank");
    }
    this.sourceId = sourceId;
    this.message = Objects.requireNonNull(message, "message must not be null");
  }

  public String sourceId() {
    return sourceId;
  }

  public Message message() {
    return message;
  }

  /**
   * Returns whether this message was contributed by the given provider source id. A contribution
   * without a contributing provider matches no source id.
   */
  public boolean contributedBy(String candidateSourceId) {
    return sourceId != null && sourceId.equals(candidateSourceId);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ContextMessageContribution that
        && Objects.equals(sourceId, that.sourceId)
        && message.equals(that.message);
  }

  @Override
  public int hashCode() {
    return Objects.hash(sourceId, message);
  }

  @Override
  public String toString() {
    return "ContextMessageContribution[sourceId=" + sourceId + ", message=" + message + "]";
  }
}
