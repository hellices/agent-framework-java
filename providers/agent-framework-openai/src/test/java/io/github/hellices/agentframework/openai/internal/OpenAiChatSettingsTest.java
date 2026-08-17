package io.github.hellices.agentframework.openai.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class OpenAiChatSettingsTest {

  @Test
  void keepsTheModelAndTheOptionalValues() {
    OpenAiChatSettings settings =
        new OpenAiChatSettings("gpt-4.1-mini", 0.2, 256, Duration.ofSeconds(60));

    assertThat(settings.model()).isEqualTo("gpt-4.1-mini");
    assertThat(settings.temperature()).hasValue(0.2);
    assertThat(settings.maxOutputTokens()).hasValue(256);
    assertThat(settings.requestTimeout()).isEqualTo(Duration.ofSeconds(60));
  }

  @Test
  void leavesTheOptionalValuesEmptyWhenTheyAreNotSupplied() {
    OpenAiChatSettings settings =
        new OpenAiChatSettings("gpt-4.1-mini", null, null, Duration.ofSeconds(60));

    assertThat(settings.temperature()).isEmpty();
    assertThat(settings.maxOutputTokens()).isEmpty();
  }

  @Test
  void rejectsABlankModel() {
    assertThatThrownBy(() -> new OpenAiChatSettings("  ", null, null, Duration.ofSeconds(60)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("model");
  }

  @Test
  void rejectsATemperatureOutsideTheNeutralRange() {
    // The same 0.0 to 2.0 range ModelRequestOptions.Builder validates, so an adapter default and a
    // request option cannot disagree about what a legal temperature is.
    assertThatThrownBy(() -> new OpenAiChatSettings("m", 2.5, null, Duration.ofSeconds(60)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("temperature");
  }

  @Test
  void rejectsANonPositiveTokenLimit() {
    assertThatThrownBy(() -> new OpenAiChatSettings("m", null, 0, Duration.ofSeconds(60)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxOutputTokens");
  }

  @Test
  void rejectsANonPositiveRequestTimeout() {
    // A zero or negative timeout would mean the abandoned work after a cancellation is never
    // reclaimed, which is the one thing the timeout exists to bound.
    assertThatThrownBy(() -> new OpenAiChatSettings("m", null, null, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requestTimeout");
  }
}
