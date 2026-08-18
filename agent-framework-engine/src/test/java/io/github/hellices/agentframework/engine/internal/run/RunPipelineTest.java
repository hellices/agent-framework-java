package io.github.hellices.agentframework.engine.internal.run;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.agent.AgentDefinition;
import io.github.hellices.agentframework.api.agent.AgentRunRequest;
import io.github.hellices.agentframework.api.agent.AgentRuntime;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.context.ContextAttributes;
import io.github.hellices.agentframework.api.session.SessionContext;
import io.github.hellices.agentframework.api.tool.ToolApprovalSettings;
import io.github.hellices.agentframework.engine.internal.model.ResponseIdentity;
import io.github.hellices.agentframework.engine.internal.tool.ToolApprovalCoordinator;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelResponseUpdate;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

/**
 * {@link RunPipeline}'s constructor validates the approvals/approvalGate pair in both directions
 * (m-8): approvals configured without a gate, and a gate present without approvals configured, are
 * equally clear wiring mistakes and both must be rejected rather than only one of them.
 */
class RunPipelineTest {

  @Test
  void constructorRejectsAnApprovalGateWhenApprovalsAreNotConfigured() {
    assertThatThrownBy(
            () ->
                newPipeline(
                    /* approvals= */ null,
                    /* approvalGate= */ CompletableFuture.completedFuture(null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("approvalGate must be null when approvals are not configured");
  }

  @Test
  void constructorRejectsApprovalsWithoutAnApprovalGate() {
    ToolApprovalCoordinator approvals =
        new ToolApprovalCoordinator(
            ToolApprovalSettings.builder().build(),
            new SessionContext(
                null, List.of(), ContextAttributes.empty(), new CancellationSignal()),
            AgentRunRequest.builder().build());

    assertThatThrownBy(() -> newPipeline(approvals, /* approvalGate= */ null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("approvalGate must not be null when approvals are enabled");
  }

  private static RunPipeline newPipeline(
      ToolApprovalCoordinator approvals, java.util.concurrent.CompletionStage<Void> approvalGate) {
    return new RunPipeline(
        appended -> neverSubscribed(),
        ModelRequest.builder()::build,
        request -> neverSubscribed(),
        () -> null,
        (tool, call, context) -> null,
        AgentRunRequest.builder().build(),
        new ResponseIdentity("agent-1", "response-1", null, Instant.now()),
        minimalExecution(),
        approvals,
        approvalGate,
        io.github.hellices.agentframework.spi.telemetry.TelemetrySink.noOp());
  }

  private static Flow.Publisher<ModelResponseUpdate> neverSubscribed() {
    return subscriber -> {
      throw new UnsupportedOperationException("not exercised by this constructor test");
    };
  }

  private static RunExecution minimalExecution() {
    AgentDefinition definition = AgentDefinition.builder().id("agent-1").name("assistant").build();
    AgentRuntime runtime = AgentRuntime.builder().modelClient(request -> neverSubscribed()).build();
    return RunExecution.create(AgentRunRequest.builder().build(), definition, runtime);
  }
}
