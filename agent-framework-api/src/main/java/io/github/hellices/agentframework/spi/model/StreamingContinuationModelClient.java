package io.github.hellices.agentframework.spi.model;

import java.util.concurrent.Flow;

public interface StreamingContinuationModelClient
    extends ContinuationModelClient, StreamingModelClient {

  Flow.Publisher<ModelResponseUpdate> resumeStreaming(
      ModelRequest request, String continuationToken);
}
