package io.github.hellices.agentframework.spi.model;

import java.util.concurrent.Flow;

/**
 * The single port every model provider implements: one invocation shape for both ordinary and
 * streaming runs.
 *
 * <p>A call to {@link #execute(ModelRequest)} returns a {@link Flow.Publisher} of {@link
 * ModelResponseUpdate}, and the request itself carries everything a run needs, including its {@link
 * ModelRequest#continuationToken() continuation token}. There is no longer a separate streaming,
 * continuation, or streaming-continuation client: a provider decides how many updates a call emits
 * and whether it honours a continuation token, and the engine no longer probes a client's type to
 * discover a capability. A provider that answers in one shot emits exactly one update and then
 * completes; a streaming provider emits several before it completes.
 *
 * <p>The returned publisher obeys the Reactive Streams rules: it accepts a non-null subscriber,
 * signals {@code onSubscribe} before any other signal, emits an update only after a positive {@link
 * Flow.Subscription#request(long) request}, treats a non-positive request as an {@link
 * IllegalArgumentException} delivered through {@code onError}, and terminates with exactly one of
 * {@code onComplete} or {@code onError}. Cancelling the subscription stops further signals.
 */
public interface ModelClient {

  /**
   * Invokes the model for one request.
   *
   * @param request the neutral request, never {@code null}
   * @return a publisher of the model's response updates, never {@code null}
   */
  Flow.Publisher<ModelResponseUpdate> execute(ModelRequest request);

  /**
   * Returns what this provider can do, so the engine adapts without probing the client's concrete
   * type. The default advertises a provider that keeps no history itself, so the local history path
   * owns the conversation (AGT-016); a provider whose service stores history overrides this to
   * return a descriptor with {@link ModelCapabilities#serviceManagesHistory()} enabled.
   *
   * <p>This is a defaulted method, so {@link ModelClient} stays a functional interface: {@link
   * #execute(ModelRequest)} remains its single abstract method and a provider can still be written
   * as a lambda.
   *
   * @return this provider's capabilities, never {@code null}
   */
  default ModelCapabilities capabilities() {
    return ModelCapabilities.defaults();
  }
}
