package io.github.hellices.agentframework.spi.interception;

import io.github.hellices.agentframework.spi.model.ModelResponseUpdate;
import java.util.concurrent.Flow;

/** Continues a model invocation chain with an explicit invocation snapshot. */
public interface ModelInvocationChain {

  Flow.Publisher<ModelResponseUpdate> proceed(ModelInvocation invocation);
}
