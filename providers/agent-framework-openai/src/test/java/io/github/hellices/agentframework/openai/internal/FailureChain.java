package io.github.hellices.agentframework.openai.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Flattens a failure into every throwable a printed stack trace would reach.
 *
 * <p>Both mappers refuse tool arguments they cannot represent, and both refuse them without quoting
 * the arguments. That rule only holds if the Jackson exception is attached nowhere: a cause and a
 * suppressed throwable are both printed by every logger that prints a stack trace, so an assertion
 * on the message alone would pass while the payload still reached the log.
 */
final class FailureChain {

  private FailureChain() {}

  /** Every throwable reachable from {@code root} through causes and suppressed throwables. */
  static List<Throwable> of(Throwable root) {
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    List<Throwable> chain = new ArrayList<>();
    Deque<Throwable> pending = new ArrayDeque<>();
    pending.add(root);
    while (!pending.isEmpty()) {
      Throwable next = pending.poll();
      if (!seen.add(next)) {
        continue;
      }
      chain.add(next);
      if (next.getCause() != null) {
        pending.add(next.getCause());
      }
      pending.addAll(List.of(next.getSuppressed()));
    }
    return chain;
  }
}
