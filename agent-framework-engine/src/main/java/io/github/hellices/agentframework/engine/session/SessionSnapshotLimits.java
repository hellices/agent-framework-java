package io.github.hellices.agentframework.engine.session;

final class SessionSnapshotLimits {

  static final int MAX_BYTES = 1_048_576;

  private SessionSnapshotLimits() {}

  static String exceededMessage() {
    return "session snapshot exceeds the " + MAX_BYTES + "-byte limit";
  }
}
