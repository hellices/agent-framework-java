package io.github.hellices.agentframework.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiContractTest {

  @Test
  void exposesThePublicContractPackage() {
    assertThat(ApiContract.packageName()).isEqualTo("io.github.hellices.agentframework.api");
  }
}
