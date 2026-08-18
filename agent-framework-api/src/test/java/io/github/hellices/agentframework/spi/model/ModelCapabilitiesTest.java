package io.github.hellices.agentframework.spi.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The evolvable, immutable capability descriptor a {@link ModelClient} advertises (AGT-016). The
 * only capability today is whether the model service keeps conversation history itself, and the
 * descriptor is a value: two descriptors with the same flags are equal, and every mutation goes
 * through the builder so a new capability can be added without breaking callers.
 */
class ModelCapabilitiesTest {

  @Test
  void defaultsDoNotManageHistoryOnTheService() {
    assertThat(ModelCapabilities.defaults().serviceManagesHistory()).isFalse();
  }

  @Test
  void theBuilderMarksServiceManagedHistory() {
    ModelCapabilities capabilities =
        ModelCapabilities.builder().serviceManagesHistory(true).build();

    assertThat(capabilities.serviceManagesHistory()).isTrue();
  }

  @Test
  void toBuilderRoundTripsEveryFlag() {
    ModelCapabilities original = ModelCapabilities.builder().serviceManagesHistory(true).build();

    ModelCapabilities copy = original.toBuilder().build();

    assertThat(copy).isEqualTo(original);
    assertThat(copy.serviceManagesHistory()).isTrue();
  }

  @Test
  void toBuilderDoesNotMutateTheSource() {
    ModelCapabilities original = ModelCapabilities.builder().serviceManagesHistory(true).build();

    ModelCapabilities relaxed = original.toBuilder().serviceManagesHistory(false).build();

    assertThat(original.serviceManagesHistory()).isTrue();
    assertThat(relaxed.serviceManagesHistory()).isFalse();
  }

  @Test
  void equalDescriptorsShareAHashCode() {
    ModelCapabilities first = ModelCapabilities.builder().serviceManagesHistory(true).build();
    ModelCapabilities second = ModelCapabilities.builder().serviceManagesHistory(true).build();

    assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
    assertThat(first).isNotEqualTo(ModelCapabilities.defaults());
  }

  @Test
  void toStringNamesTheFlag() {
    assertThat(ModelCapabilities.builder().serviceManagesHistory(true).build().toString())
        .contains("serviceManagesHistory=true");
  }
}
