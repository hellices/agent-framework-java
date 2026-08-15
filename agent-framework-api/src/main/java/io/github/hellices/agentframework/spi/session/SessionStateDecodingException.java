package io.github.hellices.agentframework.spi.session;

public final class SessionStateDecodingException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public SessionStateDecodingException(String message, Throwable cause) {
    super(message, cause);
  }
}
