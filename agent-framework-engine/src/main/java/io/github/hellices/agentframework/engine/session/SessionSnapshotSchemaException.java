package io.github.hellices.agentframework.engine.session;

public final class SessionSnapshotSchemaException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public SessionSnapshotSchemaException(String message) {
    super(message);
  }

  public SessionSnapshotSchemaException(String message, Throwable cause) {
    super(message, cause);
  }
}
