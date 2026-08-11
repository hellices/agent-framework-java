package com.microsoft.agentframework.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agentframework.api.ApiContract;
import org.junit.jupiter.api.Test;

class EngineDependencyTest {

  @Test
  void apiContractIsReachableFromTheEngineClasspath() {
    assertThat(ApiContract.packageName()).isEqualTo("com.microsoft.agentframework.api");
  }
}
