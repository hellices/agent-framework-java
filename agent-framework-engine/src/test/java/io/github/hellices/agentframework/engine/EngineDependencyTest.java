package io.github.hellices.agentframework.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hellices.agentframework.api.ApiContract;
import io.github.hellices.agentframework.api.agent.Agent;
import org.junit.jupiter.api.Test;

class EngineDependencyTest {

  @Test
  void apiContractIsReachableFromTheEngineClasspath() {
    assertThat(ApiContract.packageName()).isEqualTo("io.github.hellices.agentframework.api");
  }

  @Test
  void exposesTheEnginePackage() {
    assertThat(EngineContract.packageName()).isEqualTo("io.github.hellices.agentframework.engine");
  }

  @Test
  void engineIsNotAnAgent() {
    assertThat(Agent.class.isAssignableFrom(AgentEngine.class)).isFalse();
  }
}
