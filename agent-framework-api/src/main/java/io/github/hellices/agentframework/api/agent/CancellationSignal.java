package io.github.hellices.agentframework.api.agent;

import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CancellationSignal {

  private final AtomicBoolean cancelled = new AtomicBoolean(false);
  private final ConcurrentLinkedQueue<Runnable> listeners = new ConcurrentLinkedQueue<>();

  public boolean isCancelled() {
    return cancelled.get();
  }

  public void cancel() {
    if (!cancelled.compareAndSet(false, true)) {
      return;
    }
    RuntimeException firstFailure = null;
    Runnable listener;
    while ((listener = listeners.poll()) != null) {
      try {
        listener.run();
      } catch (RuntimeException failure) {
        if (firstFailure == null) {
          firstFailure = failure;
        } else {
          firstFailure.addSuppressed(failure);
        }
      }
    }
    if (firstFailure != null) {
      throw firstFailure;
    }
  }

  public Runnable onCancel(Runnable listener) {
    Runnable value = Objects.requireNonNull(listener, "listener must not be null");
    if (cancelled.get()) {
      value.run();
      return () -> {};
    }
    listeners.add(value);
    if (cancelled.get() && listeners.remove(value)) {
      value.run();
    }
    return () -> listeners.remove(value);
  }
}
