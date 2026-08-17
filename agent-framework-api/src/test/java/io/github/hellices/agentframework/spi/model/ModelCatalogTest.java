package io.github.hellices.agentframework.spi.model;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.message.FinishReason;
import org.junit.jupiter.api.Test;

class ModelCatalogTest {

  @Test
  void resolvesNamedAndDefaultModels() {
    ModelClient first = request -> completedFuture(response());
    ModelClient second = request -> completedFuture(response());
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
    ModelClient client = request -> completedFuture(response());

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

  private static ModelResponse response() {
    return ModelResponse.builder().finishReason(FinishReason.STOP).build();
  }
}
