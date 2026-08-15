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
 *
 * @param sourceId the contributing provider's source id, or {@code null} when the messages were
 *     added through {@link SessionContext#addContextMessages(java.util.List)}, which carries no
 *     contributing provider and is therefore an external, unspecified source
 * @param message the contributed message, exactly as it was appended to the run's context
 */
public record ContextMessageContribution(String sourceId, Message message) {

  /**
   * @throws IllegalArgumentException if {@code sourceId} is non-null and blank, because a blank
   *     source id carries no provenance and would silently match nothing
   * @throws NullPointerException if {@code message} is {@code null}
   */
  public ContextMessageContribution {
    if (sourceId != null && sourceId.isBlank()) {
      throw new IllegalArgumentException("sourceId must not be blank");
    }
    Objects.requireNonNull(message, "message must not be null");
  }

  /**
   * Returns whether this message was contributed by the given provider source id. A contribution
   * without a contributing provider matches no source id.
   */
  public boolean contributedBy(String candidateSourceId) {
    return sourceId != null && sourceId.equals(candidateSourceId);
  }
}
