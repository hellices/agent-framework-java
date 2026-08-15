package io.github.hellices.agentframework.spi.model;

@FunctionalInterface
public interface ModelClientFactory {

  ModelClient create(ModelClient defaultClient);
}
