package io.github.hellices.agentframework.spi.session;

import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.session.SessionContext;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * The open history contract of SES-013: a {@link StatefulContextProvider} whose load and store
 * behaviour is decided by an immutable {@link HistoryPolicy} rather than by a family of subtypes.
 *
 * <p>This is a plain interface, not an engine base class. An implementor keeps history in one typed
 * session-state key and answers three questions: which {@link HistoryPolicy} governs the run, how
 * stored history is read, and how a selected batch is appended. The engine only sees the {@link
 * StatefulContextProvider} hooks {@link #prepare} and {@link #complete}; an implementor is free to
 * satisfy them by the policy-driven convenience base {@code PolicyDrivenHistoryProvider} in the
 * engine module, or to implement this interface directly without inheriting from any engine class.
 *
 * <p><strong>Roles.</strong> The same implementation is a primary history when it loads and stores,
 * an audit sink when it only stores inputs, an evaluation sink when it only stores outputs, and a
 * context recorder when it stores what other providers contributed. Which role a run plays is a
 * function of its {@link #policy()} alone.
 *
 * <p><strong>Loading.</strong> {@link #load} is the supported read path for stored history, oldest
 * message first. It is storage-only: it returns exactly what was stored and applies no policy. A
 * policy-driven {@link #prepare} contributes those messages into the run only when {@link
 * HistoryPolicy#loadMessages()} is enabled, stamped as {@value #HISTORY_SOURCE_TYPE} attribution so
 * history stays distinguishable from the context a memory or retrieval provider contributed.
 *
 * <p><strong>Storing.</strong> {@link #append} is the supported write path and the counterpart of
 * {@link #load}: it appends exactly the ordered batch it is handed, after what is already stored. A
 * policy-driven {@link #complete} composes that batch — the selected context messages first, then
 * the caller's input, then the run response's messages — and hands it to {@link #append} once, or
 * not at all when the policy selects nothing.
 *
 * <p><strong>Security.</strong> Loaded history is not validated or sanitized by the framework, and
 * neither is context contributed by another provider. A storage backend that can be tampered with
 * can therefore change roles or inject adversarial content into a run.
 *
 * @param <S> the declared type of this provider's session state
 */
public interface HistoryProvider<S> extends StatefulContextProvider<S> {

  /**
   * The attribution source type stamped onto loaded history, matching the pinned upstream chat
   * history source type so a message's provenance reads the same in Java as it does upstream.
   */
  String HISTORY_SOURCE_TYPE = "ChatHistory";

  /** Returns the immutable policy this provider was configured with; must not be {@code null}. */
  HistoryPolicy policy();

  /**
   * Reads this session's stored history, oldest message first.
   *
   * <p>This is the supported read path for stored history: an application, a middleware, or a
   * coordinating component reads history through this operation instead of casting an untyped
   * session state slot. Implementations are storage-only and must not apply the policy.
   *
   * @param context the per-run context, for a storage backend that keys history by session id
   * @param state this provider's key-bound session state view
   * @return a stage carrying the stored messages; neither the stage, the list, nor an entry may be
   *     {@code null}
   */
  CompletionStage<List<Message>> load(SessionContext context, ProviderSessionState<S> state);

  /**
   * Persists one ordered batch for this session, appending it after what is already stored.
   *
   * <p>This is the supported write path for stored history, and the counterpart of {@link #load}.
   * Implementations are storage-only: the batch has already been selected, and calling this
   * directly stores exactly what is passed in.
   *
   * @param context the per-run context, for a storage backend that keys history by session id
   * @param state this provider's key-bound session state view
   * @param messages the non-empty ordered batch to append
   * @return a stage completing when the batch is persisted; must not be {@code null}
   */
  CompletionStage<Void> append(
      SessionContext context, ProviderSessionState<S> state, List<Message> messages);
}
