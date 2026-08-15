package io.github.hellices.agentframework.spi.model;

import java.util.concurrent.CompletionStage;

public interface ContinuationModelClient extends ModelClient {

  CompletionStage<ModelResponse> resume(ModelRequest request, String continuationToken);
}
