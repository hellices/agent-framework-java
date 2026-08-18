package io.github.hellices.agentframework.engine.internal.interception;

import io.github.hellices.agentframework.api.tool.ToolResult;
import io.github.hellices.agentframework.spi.interception.AgentExecution;
import io.github.hellices.agentframework.spi.interception.AgentExecutionInterceptor;
import io.github.hellices.agentframework.spi.interception.AgentInvocation;
import io.github.hellices.agentframework.spi.interception.AgentInvocationChain;
import io.github.hellices.agentframework.spi.interception.ModelInvocation;
import io.github.hellices.agentframework.spi.interception.ModelInvocationChain;
import io.github.hellices.agentframework.spi.interception.ModelInvocationInterceptor;
import io.github.hellices.agentframework.spi.interception.SessionInvocation;
import io.github.hellices.agentframework.spi.interception.SessionInvocationChain;
import io.github.hellices.agentframework.spi.interception.SessionOperationInterceptor;
import io.github.hellices.agentframework.spi.interception.SessionOperationResult;
import io.github.hellices.agentframework.spi.interception.ToolInvocation;
import io.github.hellices.agentframework.spi.interception.ToolInvocationChain;
import io.github.hellices.agentframework.spi.interception.ToolInvocationInterceptor;
import io.github.hellices.agentframework.spi.model.ModelResponseUpdate;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Immutable shared interceptor chains assembled once at engine build time. */
public final class InterceptorRegistry {

  private final AgentInterceptorChain agentChain;
  private final ModelInterceptorChain modelChain;
  private final ToolInterceptorChain toolChain;
  private final SessionInterceptorChain sessionChain;

  public InterceptorRegistry(
      List<? extends AgentExecutionInterceptor> agentExecutionInterceptors,
      List<? extends ModelInvocationInterceptor> modelInvocationInterceptors,
      List<? extends ToolInvocationInterceptor> toolInvocationInterceptors,
      List<? extends SessionOperationInterceptor> sessionOperationInterceptors) {
    this.agentChain = new AgentInterceptorChain(agentExecutionInterceptors);
    this.modelChain = new ModelInterceptorChain(modelInvocationInterceptors);
    this.toolChain = new ToolInterceptorChain(toolInvocationInterceptors);
    this.sessionChain = new SessionInterceptorChain(sessionOperationInterceptors);
  }

  public AgentExecution interceptAgent(AgentInvocation invocation, AgentInvocationChain terminal) {
    return agentChain.intercept(invocation, terminal);
  }

  public Flow.Publisher<ModelResponseUpdate> interceptModel(
      ModelInvocation invocation, ModelInvocationChain terminal) {
    return modelChain.intercept(invocation, terminal);
  }

  public CompletionStage<ToolResult> interceptTool(
      ToolInvocation invocation, ToolInvocationChain terminal) {
    return toolChain.intercept(invocation, terminal);
  }

  public CompletionStage<SessionOperationResult> interceptSession(
      SessionInvocation invocation, SessionInvocationChain terminal) {
    return sessionChain.intercept(invocation, terminal);
  }
}
