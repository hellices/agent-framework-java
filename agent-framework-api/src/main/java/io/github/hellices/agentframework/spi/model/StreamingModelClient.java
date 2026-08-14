package io.github.hellices.agentframework.spi.model;

import java.util.concurrent.Flow;

public interface StreamingModelClient extends ModelClient {

  Flow.Publisher<ModelResponseUpdate> runStreaming(ModelRequest request);
}
