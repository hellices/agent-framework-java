package io.github.hellices.agentframework.engine.session;

public final class FileSessionStoreException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public FileSessionStoreException(String message, Throwable cause) {
    super(message, cause);
  }

  public FileSessionStoreException(String message) {
    super(message);
  }
}
