package io.github.hellices.agentframework.api.message;

public enum FinishReason {
  STOP,
  LENGTH,
  CONTENT_FILTER,
  TOOL_CALLS,
  ERROR,
  UNKNOWN
}
