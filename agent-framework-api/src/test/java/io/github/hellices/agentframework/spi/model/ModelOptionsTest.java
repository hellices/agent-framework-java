package io.github.hellices.agentframework.spi.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class ModelOptionsTest {

  @Test
  void runTimeModelOptionsOverrideAgentDefaults() {
    ModelRequestOptions agentDefaults =
        ModelRequestOptions.builder()
            .temperature(0.2)
            .maxOutputTokens(256)
            .providerOption(ModelProviderOption.of("openai", Map.of("parallelToolCalls", false)))
            .build();
    ModelRequestOptions runTimeOverride =
        ModelRequestOptions.builder()
            .temperature(0.9)
            .providerOption(ModelProviderOption.of("openai", Map.of("parallelToolCalls", true)))
            .providerOption(ModelProviderOption.of("azure-openai", Map.of("apiVersion", "preview")))
            .build();

    ModelRequestOptions merged = agentDefaults.merge(runTimeOverride);

    assertThat(merged.temperature()).hasValue(0.9);
    assertThat(merged.maxOutputTokens()).hasValue(256);
    assertThat(merged.providerOption("openai"))
        .contains(Map.of("parallelToolCalls", true));
    assertThat(merged.providerOption("azure-openai"))
        .contains(Map.of("apiVersion", "preview"));
  }

  @Test
  void providerOptionsMergePerKeyForTheSameProvider() {
    ModelRequestOptions defaults =
        ModelRequestOptions.builder()
            .providerOption(
                ModelProviderOption.of(
                    "openai", Map.of("parallelToolCalls", false, "seed", 7)))
            .build();
    ModelRequestOptions overrides =
        ModelRequestOptions.builder()
            .providerOption(ModelProviderOption.of("openai", Map.of("parallelToolCalls", true)))
            .build();

    ModelRequestOptions merged = defaults.merge(overrides);

    assertThat(merged.providerOption("openai"))
        .contains(Map.of("parallelToolCalls", true, "seed", 7));
  }

  @Test
  void optionsRejectOutOfRangeTemperature() {
    assertThatThrownBy(() -> ModelRequestOptions.builder().temperature(3.0).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("temperature must be between 0.0 and 2.0");
  }

  @Test
  void requestCarriesTypedOptions() {
    ModelRequest request =
        new ModelRequest(
            List.of(new Message(Role.USER, List.of(new TextContent("hello")))),
            ModelRequestOptions.builder().maxOutputTokens(400).build(),
            Map.of("traceId", "trace-1"));

    assertThat(request.options().maxOutputTokens()).hasValue(400);
    assertThat(request.metadata()).containsEntry("traceId", "trace-1");
  }

  @Test
  void legacyOptionMapConstructorRemainsAvailable() {
    ModelRequest request =
        new ModelRequest(
            List.of(new Message(Role.USER, List.of(new TextContent("hello")))),
            Map.of("temperature", 0.6, "maxOutputTokens", 128, "toolChoice", "none"),
            Map.of());

    assertThat(request.options().temperature()).hasValue(0.6);
    assertThat(request.options().maxOutputTokens()).hasValue(128);
    assertThat(request.options().providerOption("legacy"))
        .contains(Map.of("toolChoice", "none"));
  }

  @Test
  void streamingCapabilityIsOptInInterface() {
    ModelClient basicClient = request -> null;
    StreamingModelClient streamingClient =
        new StreamingModelClient() {
          @Override
          public java.util.concurrent.CompletionStage<ModelResponse> run(ModelRequest request) {
            return null;
          }

          @Override
          public Flow.Publisher<ModelResponseUpdate> runStreaming(ModelRequest request) {
            return new SingleChunkPublisher(
                new ModelResponseUpdate(
                    List.of(), null, FinishReason.STOP, Map.of(), null));
          }
        };

    assertThat(basicClient).isNotInstanceOf(StreamingModelClient.class);
    assertThat(streamingClient).isInstanceOf(StreamingModelClient.class);
  }

  private static final class SingleChunkPublisher implements Flow.Publisher<ModelResponseUpdate> {
    private final ModelResponseUpdate update;

    private SingleChunkPublisher(ModelResponseUpdate update) {
      this.update = update;
    }

    @Override
    public void subscribe(Flow.Subscriber<? super ModelResponseUpdate> subscriber) {
      subscriber.onSubscribe(
          new Flow.Subscription() {
            private boolean done;

            @Override
            public void request(long n) {
              if (done || n <= 0) {
                return;
              }
              done = true;
              subscriber.onNext(update);
              subscriber.onComplete();
            }

            @Override
            public void cancel() {
              done = true;
            }
          });
    }
  }
}
