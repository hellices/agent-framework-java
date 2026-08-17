package io.github.hellices.agentframework.spi.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ModelCatalogTest {

  @Test
  void resolvesNamedAndDefaultModels() {
    ModelClient first = StubModelClients.stub();
    ModelClient second = StubModelClients.stub();
    ModelCatalog catalog =
        ModelCatalog.builder()
            .add("first", first)
            .add("second", second)
            .defaultModel("second")
            .build();

    assertThat(catalog.resolve("first")).isSameAs(first);
    assertThat(catalog.defaultClient()).isSameAs(second);
  }

  @Test
  void rejectsDuplicateNamesAndUnknownDefaults() {
    ModelClient client = StubModelClients.stub();

    assertThatThrownBy(() -> ModelCatalog.builder().add("model", client).add("model", client))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("duplicate model name: model");
    assertThatThrownBy(
            () -> ModelCatalog.builder().add("model", client).defaultModel("missing").build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("default model is not registered: missing");
  }

  @Test
  void defaultResolutionHasActionableFailure() {
    ModelCatalog catalog = ModelCatalog.builder().build();

    assertThatThrownBy(catalog::defaultClient)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(
            "no default model is configured; configure defaultModel(name) or resolve a named model");
  }
}
