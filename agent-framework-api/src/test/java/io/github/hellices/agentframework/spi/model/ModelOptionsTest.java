package io.github.hellices.agentframework.spi.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.value.JsonObject;
import io.github.hellices.agentframework.api.value.JsonString;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelOptionsTest {

  @Test
  void runTimeModelOptionsOverrideAgentDefaults() {
    OpenAiOptions agentDefaultsOption = new OpenAiOptions("reasoning-low");
    OpenAiOptions runTimeOpenAiOption = new OpenAiOptions("reasoning-high");
    AzureOpenAiOptions azureOpenAiOption = new AzureOpenAiOptions("preview");
    ModelRequestOptions agentDefaults =
        ModelRequestOptions.builder()
            .temperature(0.2)
            .maxOutputTokens(256)
            .providerOption(agentDefaultsOption)
            .build();
    ModelRequestOptions runTimeOverride =
        ModelRequestOptions.builder()
            .temperature(0.9)
            .providerOption(runTimeOpenAiOption)
            .providerOption(azureOpenAiOption)
            .build();

    ModelRequestOptions merged = agentDefaults.merge(runTimeOverride);

    assertThat(merged.temperature()).hasValue(0.9);
    assertThat(merged.maxOutputTokens()).hasValue(256);
    assertThat(merged.providerOption(OpenAiOptions.class)).containsSame(runTimeOpenAiOption);
    assertThat(merged.providerOption(AzureOpenAiOptions.class)).containsSame(azureOpenAiOption);
  }

  @Test
  void providerOptionsMergePerConcreteOptionClass() {
    OpenAiOptions defaultsOption = new OpenAiOptions("reasoning-low");
    OpenAiOptions overridesOption = new OpenAiOptions("reasoning-high");
    ModelRequestOptions defaults =
        ModelRequestOptions.builder().providerOption(defaultsOption).build();
    ModelRequestOptions overrides =
        ModelRequestOptions.builder().providerOption(overridesOption).build();

    ModelRequestOptions merged = defaults.merge(overrides);

    assertThat(merged.providerOption(OpenAiOptions.class)).containsSame(overridesOption);
  }

  @Test
  void requestRejectsDuplicateConcreteProviderOptionClasses() {
    assertThatThrownBy(
            () ->
                ModelRequestOptions.builder()
                    .providerOption(new OpenAiOptions("reasoning-low"))
                    .providerOption(new OpenAiOptions("reasoning-high"))
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("provider option type already configured: " + OpenAiOptions.class.getName());
  }

  @Test
  void requestRejectsBlankProviderIds() {
    assertThatThrownBy(
            () -> ModelRequestOptions.builder().providerOption(new BlankProviderOption()).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("providerId must not be blank");
  }

  @Test
  void rawMapProviderOptionBridgesAreRemoved() {
    assertThat(findMethod(ModelRequestOptions.class, "fromLegacyOptions", Map.class)).isEmpty();
    assertThat(findMethod(ModelProviderOption.class, "of", String.class, Map.class)).isEmpty();
  }

  @Test
  void optionsRejectOutOfRangeTemperature() {
    assertThatThrownBy(() -> ModelRequestOptions.builder().temperature(3.0).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("temperature must be between 0.0 and 2.0");
  }

  @Test
  void optionsRejectNaNTemperature() {
    assertThatThrownBy(() -> ModelRequestOptions.builder().temperature(Double.NaN).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("temperature must be between 0.0 and 2.0");
  }

  @Test
  void requestCarriesTypedOptions() {
    ModelRequest request =
        ModelRequest.builder()
            .messages(List.of(new Message(Role.USER, List.of(new TextContent("hello")))))
            .options(ModelRequestOptions.builder().maxOutputTokens(400).build())
            .metadata(JsonObject.builder().put("traceId", JsonString.of("trace-1")).build())
            .build();

    assertThat(request.options().maxOutputTokens()).hasValue(400);
    assertThat(request.metadata().values()).containsEntry("traceId", JsonString.of("trace-1"));
  }

  @Test
  void requestCarriesTheRunCancellationSignal() {
    CancellationSignal signal = new CancellationSignal();

    ModelRequest request =
        ModelRequest.builder()
            .options(ModelRequestOptions.empty())
            .cancellationSignal(signal)
            .build();

    assertThat(request.cancellationSignal()).isSameAs(signal);
  }

  @Test
  void cancellationSignalDoesNotChangeModelRequestValueEquality() {
    ModelRequestOptions options = ModelRequestOptions.empty();
    ModelRequest first =
        ModelRequest.builder()
            .options(options)
            .cancellationSignal(new CancellationSignal())
            .build();
    ModelRequest second =
        ModelRequest.builder()
            .options(options)
            .cancellationSignal(new CancellationSignal())
            .build();

    assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
  }

  private static java.util.Optional<Method> findMethod(
      Class<?> type, String name, Class<?>... parameterTypes) {
    try {
      return java.util.Optional.of(type.getDeclaredMethod(name, parameterTypes));
    } catch (NoSuchMethodException missing) {
      return java.util.Optional.empty();
    }
  }

  private record OpenAiOptions(String reasoningEffort) implements ModelProviderOption {
    @Override
    public String providerId() {
      return "openai";
    }
  }

  private record AzureOpenAiOptions(String apiVersion) implements ModelProviderOption {
    @Override
    public String providerId() {
      return "azure-openai";
    }
  }

  private record BlankProviderOption() implements ModelProviderOption {
    @Override
    public String providerId() {
      return " ";
    }
  }
}
