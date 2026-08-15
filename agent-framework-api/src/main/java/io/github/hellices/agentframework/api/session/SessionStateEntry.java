package io.github.hellices.agentframework.api.session;

public record SessionStateEntry(String typeId, int codecVersion, Object payload) {

  public SessionStateEntry {
    if (typeId == null || typeId.isBlank()) {
      throw new IllegalArgumentException("typeId must not be blank");
    }
    if (codecVersion < 1) {
      throw new IllegalArgumentException("codecVersion must be greater than 0");
    }
    payload = SessionStateValues.immutableCopy(payload);
  }

  @Override
  public Object payload() {
    return SessionStateValues.immutableCopy(payload);
  }
}
