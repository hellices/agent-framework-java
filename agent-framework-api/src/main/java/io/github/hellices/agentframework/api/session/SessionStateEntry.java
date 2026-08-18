package io.github.hellices.agentframework.api.session;

import java.util.Objects;

public final class SessionStateEntry {

  private final String typeId;
  private final int codecVersion;
  private final Object payload;

  public SessionStateEntry(String typeId, int codecVersion, Object payload) {
    if (typeId == null || typeId.isBlank()) {
      throw new IllegalArgumentException("typeId must not be blank");
    }
    if (codecVersion < 1) {
      throw new IllegalArgumentException("codecVersion must be greater than 0");
    }
    this.typeId = typeId;
    this.codecVersion = codecVersion;
    this.payload = SessionStateValues.immutableCopy(payload);
  }

  public String typeId() {
    return typeId;
  }

  public int codecVersion() {
    return codecVersion;
  }

  public Object payload() {
    return payload;
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof SessionStateEntry that)) {
      return false;
    }
    return codecVersion == that.codecVersion
        && typeId.equals(that.typeId)
        && Objects.equals(payload, that.payload);
  }

  @Override
  public int hashCode() {
    return Objects.hash(typeId, codecVersion, payload);
  }

  @Override
  public String toString() {
    return "SessionStateEntry[typeId="
        + typeId
        + ", codecVersion="
        + codecVersion
        + ", payload="
        + payload
        + "]";
  }
}
