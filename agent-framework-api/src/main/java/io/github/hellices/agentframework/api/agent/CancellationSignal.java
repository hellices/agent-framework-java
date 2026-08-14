package io.github.hellices.agentframework.api.agent;

import java.util.concurrent.atomic.AtomicBoolean;

public final class CancellationSignal {

  private final AtomicBoolean cancelled = new AtomicBoolean(false);

  public boolean isCancelled() {
    return cancelled.get();
  }

  public void cancel() {
    cancelled.set(true);
  }
}
