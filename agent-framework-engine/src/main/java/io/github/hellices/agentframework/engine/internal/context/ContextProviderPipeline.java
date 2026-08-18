package io.github.hellices.agentframework.engine.internal.context;

import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.agent.RunContribution;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.session.SessionContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Composes a run's ordered context providers into the two hooks each run observes (SES-012), and
 * accumulates the {@link RunContribution} each {@code prepare} returns for the {@link
 * RunContributionMerger} to fold into the run's model request.
 *
 * <p>The pipeline is immutable: it captures the resolved provider list once and never exposes a
 * mutable request collection to a provider. {@link #prepare(SessionContext, CancellationSignal)}
 * runs the {@code prepare} hooks in registration order, checking cancellation before each so a run
 * cancelled between hooks stops without calling a later hook or the model; a hook that fails,
 * returns a {@code null} stage, or yields a {@code null} contribution fails the composed stage and
 * runs no later hook. Each contribution's messages are folded into the run's {@link SessionContext}
 * as it arrives — attributed to the contributing provider's source id when it owns one, appended
 * unattributed otherwise — so a history provider still observes who contributed each message, while
 * the ordered contributions are returned for merging. {@link #complete(SessionContext)} composes
 * the {@code complete} hooks in reverse registration order, so a provider always closes over the
 * context its neighbours opened.
 */
public final class ContextProviderPipeline {

  private final List<ProviderBinding> providers;

  public ContextProviderPipeline(List<ProviderBinding> providers) {
    this.providers = List.copyOf(Objects.requireNonNull(providers, "providers must not be null"));
  }

  /** Whether this pipeline resolved no provider, so a run has nothing to prepare or complete. */
  public boolean isEmpty() {
    return providers.isEmpty();
  }

  /**
   * Runs every provider's {@code prepare} hook in registration order, folding each contribution's
   * messages into {@code context} and accumulating the contributions in order.
   *
   * @return a stage yielding the ordered contributions, or failing with the first hook failure,
   *     null stage, null contribution, or a cancellation observed before a hook
   */
  public CompletionStage<List<RunContribution>> prepare(
      SessionContext context, CancellationSignal cancellationSignal) {
    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(cancellationSignal, "cancellationSignal must not be null");
    List<RunContribution> accumulated = Collections.synchronizedList(new ArrayList<>());
    CompletionStage<Void> stage = CompletableFuture.completedFuture(null);
    for (ProviderBinding binding : providers) {
      stage =
          stage.thenCompose(
              ignored -> {
                if (cancellationSignal.isCancelled()) {
                  throw new CancellationException("run was cancelled");
                }
                return Objects.requireNonNull(
                        binding.provider().prepare(context),
                        "context provider prepare stage must not be null")
                    .thenAccept(
                        contribution -> {
                          RunContribution resolved =
                              Objects.requireNonNull(
                                  contribution, "context provider contribution must not be null");
                          foldMessages(context, binding, resolved);
                          accumulated.add(resolved);
                        });
              });
    }
    return stage.thenApply(ignored -> List.copyOf(accumulated));
  }

  /**
   * Runs every provider's {@code complete} hook in reverse registration order. A hook failure fails
   * the composed stage and stops the remaining (earlier-declared) hooks.
   */
  public CompletionStage<Void> complete(SessionContext context) {
    Objects.requireNonNull(context, "context must not be null");
    CompletionStage<Void> stage = CompletableFuture.completedFuture(null);
    for (int index = providers.size() - 1; index >= 0; index--) {
      ProviderBinding binding = providers.get(index);
      stage =
          stage.thenCompose(
              ignored ->
                  Objects.requireNonNull(
                      binding.provider().complete(context),
                      "context provider complete stage must not be null"));
    }
    return stage;
  }

  private static void foldMessages(
      SessionContext context, ProviderBinding binding, RunContribution contribution) {
    List<Message> messages = contribution.messages();
    if (messages.isEmpty()) {
      return;
    }
    String sourceId = binding.sourceId();
    if (sourceId == null) {
      context.addContextMessages(messages);
    } else {
      context.addContextMessages(sourceId, messages);
    }
  }
}
