package io.github.hellices.agentframework.engine.session;

import io.github.hellices.agentframework.api.session.SessionSnapshot;
import io.github.hellices.agentframework.spi.session.SessionSnapshotCodec;
import io.github.hellices.agentframework.spi.session.SessionStore;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemorySessionStore implements SessionStore {

  private final SessionSnapshotCodec codec;
  private final ConcurrentMap<String, byte[]> snapshots = new ConcurrentHashMap<>();

  public InMemorySessionStore(SessionSnapshotCodec codec) {
    this.codec = java.util.Objects.requireNonNull(codec, "codec must not be null");
  }

  @Override
  public CompletionStage<Optional<SessionSnapshot>> load(String sessionId) {
    String key = requireSessionId(sessionId);
    byte[] encoded = snapshots.get(key);
    return CompletableFuture.completedFuture(
        encoded == null ? Optional.empty() : Optional.of(codec.decode(encoded.clone())));
  }

  @Override
  public CompletionStage<Void> save(SessionSnapshot snapshot) {
    SessionSnapshot value = java.util.Objects.requireNonNull(snapshot, "snapshot must not be null");
    byte[] encoded = codec.encode(value);
    snapshots.put(value.sessionId(), encoded.clone());
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public CompletionStage<Void> delete(String sessionId) {
    snapshots.remove(requireSessionId(sessionId));
    return CompletableFuture.completedFuture(null);
  }

  private static String requireSessionId(String sessionId) {
    if (sessionId == null || sessionId.isBlank()) {
      throw new IllegalArgumentException("sessionId must not be blank");
    }
    return sessionId;
  }
}
