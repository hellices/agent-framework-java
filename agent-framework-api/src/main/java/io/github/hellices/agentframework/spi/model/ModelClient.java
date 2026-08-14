package io.github.hellices.agentframework.spi.model;

import java.util.concurrent.CompletionStage;

public interface ModelClient {

  CompletionStage<ModelResponse> run(ModelRequest request);
}
