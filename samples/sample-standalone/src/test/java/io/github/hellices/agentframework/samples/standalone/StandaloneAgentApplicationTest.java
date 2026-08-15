package io.github.hellices.agentframework.samples.standalone;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hellices.agentframework.api.agent.Agent;
import org.junit.jupiter.api.Test;

class StandaloneAgentApplicationTest {

  @Test
  void createsAnAgentThatRunsWithoutAHostFramework() {
    Agent agent = StandaloneAgentApplication.createAgent();

    var response = agent.run("hello").response().toCompletableFuture().join();

    assertThat(response.text()).isEqualTo("Standalone agent received: hello");
  }
}
