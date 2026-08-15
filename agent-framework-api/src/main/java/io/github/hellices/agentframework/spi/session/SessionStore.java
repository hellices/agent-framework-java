package io.github.hellices.agentframework.spi.session;

import io.github.hellices.agentframework.api.session.SessionSnapshot;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

public interface SessionStore {

  CompletionStage<Optional<SessionSnapshot>> load(String sessionId);

  CompletionStage<Void> save(SessionSnapshot snapshot);

  CompletionStage<Void> delete(String sessionId);
}
