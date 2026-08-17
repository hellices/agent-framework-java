package io.github.hellices.agentframework.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.context.ContextAttributes;
import io.github.hellices.agentframework.api.context.ContextKey;
import io.github.hellices.agentframework.api.tool.ToolContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpToolAdapterOptionsTest {

  private static final ContextKey<String> RUN_ID = ContextKey.of("mcp", "runId", String.class);

  @Test
  void defaultsCarryNoPrefixNoExtrasAndEmptyMetadata() {
    McpToolAdapterOptions options = McpToolAdapterOptions.defaults();

    assertThat(options.localNamePrefix()).isEmpty();
    assertThat(options.additionalArgumentNames()).isEmpty();
    assertThat(
            options
                .callMetadataProvider()
                .metadata(
                    new ToolContext(null, ContextAttributes.builder().put(RUN_ID, "r").build())))
        .isEmpty();
    assertThat(options.includeResultPayload()).isFalse();
    assertThat(options.maxDiscoveryPages()).isEqualTo(256);
  }

  @Test
  void keepsTheConfiguredValues() {
    McpToolAdapterOptions options =
        McpToolAdapterOptions.builder()
            .localNamePrefix("github_")
            .additionalArgumentNames(List.of("tenant", "region"))
            .callMetadataProvider(context -> Map.of("traceId", "t"))
            .includeResultPayload(true)
            .maxDiscoveryPages(8)
            .build();

    assertThat(options.localNamePrefix()).isEqualTo("github_");
    assertThat(options.additionalArgumentNames()).containsExactlyInAnyOrder("tenant", "region");
    assertThat(
            options
                .callMetadataProvider()
                .metadata(new ToolContext(null, ContextAttributes.empty())))
        .containsEntry("traceId", "t");
    assertThat(options.includeResultPayload()).isTrue();
    assertThat(options.maxDiscoveryPages()).isEqualTo(8);
  }

  @Test
  void copiesTheExtraArgumentNamesOnBothSidesOfTheBoundary() {
    List<String> mutable = new ArrayList<>(List.of("tenant"));
    McpToolAdapterOptions options =
        McpToolAdapterOptions.builder().additionalArgumentNames(mutable).build();

    mutable.add("smuggled");

    assertThat(options.additionalArgumentNames()).containsExactly("tenant");
    assertThatThrownBy(() -> options.additionalArgumentNames().add("smuggled"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void keepsBuilderMutationOutOfAnAlreadyBuiltOptions() {
    McpToolAdapterOptions.Builder builder =
        McpToolAdapterOptions.builder().additionalArgumentNames(List.of("tenant"));
    McpToolAdapterOptions options = builder.build();

    builder
        .localNamePrefix("late_")
        .addAdditionalArgumentName("late")
        .includeResultPayload(true)
        .maxDiscoveryPages(4);

    assertThat(options.localNamePrefix()).isEmpty();
    assertThat(options.additionalArgumentNames()).containsExactly("tenant");
    assertThat(options.includeResultPayload()).isFalse();
    assertThat(options.maxDiscoveryPages()).isEqualTo(256);
  }

  @Test
  void rejectsBlankOrNullConfiguration() {
    McpToolAdapterOptions.Builder builder = McpToolAdapterOptions.builder();

    assertThatThrownBy(() -> builder.localNamePrefix("  "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> builder.localNamePrefix(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> builder.additionalArgumentNames(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> builder.additionalArgumentNames(Arrays.asList("tenant", null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("additionalArgumentNames must not hold a null name");
    assertThatThrownBy(() -> builder.additionalArgumentNames(List.of("tenant", " ")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("additionalArgumentNames must not hold a blank name");
    assertThatThrownBy(() -> builder.addAdditionalArgumentName(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("additionalArgumentNames must not hold a null name");
    assertThatThrownBy(() -> builder.addAdditionalArgumentName(" "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("additionalArgumentNames must not hold a blank name");
    assertThatThrownBy(() -> builder.callMetadataProvider(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> builder.maxDiscoveryPages(0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> builder.maxDiscoveryPages(-1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
