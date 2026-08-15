package io.github.hellices.agentframework.spi.session;

import io.github.hellices.agentframework.api.session.SessionSnapshot;

public interface SessionSnapshotCodec {

  byte[] encode(SessionSnapshot snapshot);

  SessionSnapshot decode(byte[] encoded);
}
