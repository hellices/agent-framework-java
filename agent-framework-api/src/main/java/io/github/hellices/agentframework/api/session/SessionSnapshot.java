package io.github.hellices.agentframework.api.session;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public final class SessionSnapshot {

  private final String type;
  private final String version;
  private final String sessionId;
  private final String serviceSessionId;
  private final long revision;
  private final Instant createdAt;
  private final Map<String, SessionStateEntry> state;

  public SessionSnapshot(
      String type,
      String version,
      String sessionId,
      String serviceSessionId,
      long revision,
      Instant createdAt,
      Map<String, SessionStateEntry> state) {
    if (type == null || type.isBlank()) {
      throw new IllegalArgumentException("type must not be blank");
    }
    if (version == null || version.isBlank()) {
      throw new IllegalArgumentException("version must not be blank");
    }
    if (sessionId == null || sessionId.isBlank()) {
      throw new IllegalArgumentException("sessionId must not be blank");
    }
    if (serviceSessionId != null && serviceSessionId.isBlank()) {
      throw new IllegalArgumentException("serviceSessionId must not be blank");
    }
    if (revision < 0) {
      throw new IllegalArgumentException("revision must not be negative");
    }
    this.type = type;
    this.version = version;
    this.sessionId = sessionId;
    this.serviceSessionId = serviceSessionId;
    this.revision = revision;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    Map<String, SessionStateEntry> normalized = new TreeMap<>();
    if (state != null) {
      for (Map.Entry<String, SessionStateEntry> entry : state.entrySet()) {
        String key = entry.getKey();
        if (key == null || key.isBlank()) {
          throw new IllegalArgumentException("state keys must not be blank");
        }
        normalized.put(
            key, Objects.requireNonNull(entry.getValue(), "state values must not be null"));
      }
    }
    this.state = Collections.unmodifiableMap(new LinkedHashMap<>(normalized));
  }

  public String type() {
    return type;
  }

  public String version() {
    return version;
  }

  public String sessionId() {
    return sessionId;
  }

  public String serviceSessionId() {
    return serviceSessionId;
  }

  public long revision() {
    return revision;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Map<String, SessionStateEntry> state() {
    return state;
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof SessionSnapshot that)) {
      return false;
    }
    return revision == that.revision
        && type.equals(that.type)
        && version.equals(that.version)
        && sessionId.equals(that.sessionId)
        && Objects.equals(serviceSessionId, that.serviceSessionId)
        && createdAt.equals(that.createdAt)
        && state.equals(that.state);
  }

  @Override
  public int hashCode() {
    return Objects.hash(type, version, sessionId, serviceSessionId, revision, createdAt, state);
  }

  @Override
  public String toString() {
    return "SessionSnapshot[type="
        + type
        + ", version="
        + version
        + ", sessionId="
        + sessionId
        + ", serviceSessionId="
        + serviceSessionId
        + ", revision="
        + revision
        + ", createdAt="
        + createdAt
        + ", state="
        + state
        + "]";
  }
}
