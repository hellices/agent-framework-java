package io.github.hellices.agentframework.spi.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.tool.ToolDefinition;
import io.github.hellices.agentframework.api.value.JsonObject;
import io.github.hellices.agentframework.api.value.JsonString;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelValuesTest {

  @Test
  void requestBuilderRoundTripsAndDefensivelyCopiesLists() {
    List<Message> messages = new ArrayList<>(List.of(user("hello")));
    List<ToolDefinition> tools = new ArrayList<>(List.of(tool("lookup")));
    CancellationSignal signal = new CancellationSignal();
    JsonObject metadata = JsonObject.builder().put("traceId", JsonString.of("trace-1")).build();
    ModelRequest request =
        ModelRequest.builder()
            .messages(messages)
            .options(ModelRequestOptions.builder().maxOutputTokens(32).build())
            .continuationToken("service-turn-1")
            .cancellationSignal(signal)
            .tools(tools)
            .metadata(metadata)
            .build();

    messages.add(user("later"));
    tools.clear();

    assertThat(request.toBuilder().build()).isEqualTo(request).hasSameHashCodeAs(request);
    assertThat(request.cancellationSignal()).isSameAs(signal);
    assertThat(request.continuationToken()).isEqualTo("service-turn-1");
    assertThat(request.metadata()).isEqualTo(metadata);
    assertThat(request.messages()).extracting(Message::text).containsExactly("hello");
    assertThat(request.tools()).extracting(ToolDefinition::name).containsExactly("lookup");
    assertThatThrownBy(() -> request.messages().add(user("boom")))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> request.tools().add(tool("boom")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void requestEqualityIncludesContinuationTokenButExcludesCancellationSignal() {
    ModelRequestOptions options = ModelRequestOptions.empty();
    ModelRequest first =
        ModelRequest.builder()
            .options(options)
            .continuationToken("continuation-1")
            .cancellationSignal(new CancellationSignal())
            .build();
    ModelRequest same =
        ModelRequest.builder()
            .options(options)
            .continuationToken("continuation-1")
            .cancellationSignal(new CancellationSignal())
            .build();
    ModelRequest differentContinuation =
        ModelRequest.builder().options(options).continuationToken("continuation-2").build();

    assertThat(first).isEqualTo(same).hasSameHashCodeAs(same);
    assertThat(first).isNotEqualTo(differentContinuation);
  }

  @Test
  void responseBuilderRoundTripsAndKeepsRawRepresentationInEquality() {
    Object raw = new Object();
    ModelResponse response =
        ModelResponse.builder()
            .messages(List.of(user("hello")))
            .finishReason(FinishReason.STOP)
            .metadata(JsonObject.empty())
            .rawRepresentation(raw)
            .build();

    assertThat(response.toBuilder().build()).isEqualTo(response).hasSameHashCodeAs(response);
    assertThat(response)
        .isNotEqualTo(
            ModelResponse.builder()
                .messages(List.of(user("hello")))
                .finishReason(FinishReason.STOP)
                .metadata(JsonObject.empty())
                .rawRepresentation(new Object())
                .build());
  }

  @Test
  void updateBuilderRoundTripsAndKeepsRawRepresentationInEquality() {
    Object raw = new Object();
    ModelResponseUpdate update =
        ModelResponseUpdate.builder()
            .messages(List.of(user("hello")))
            .finishReason(FinishReason.STOP)
            .metadata(JsonObject.empty())
            .rawRepresentation(raw)
            .build();

    assertThat(update.toBuilder().build()).isEqualTo(update).hasSameHashCodeAs(update);
    assertThat(update)
        .isNotEqualTo(
            ModelResponseUpdate.builder()
                .messages(List.of(user("hello")))
                .finishReason(FinishReason.STOP)
                .metadata(JsonObject.empty())
                .rawRepresentation(new Object())
                .build());
  }

  @Test
  void responseAndUpdateStillRequireFinishReason() {
    assertThatThrownBy(() -> ModelResponse.builder().build())
        .isInstanceOf(NullPointerException.class)
        .hasMessage("finishReason must not be null");
    assertThatThrownBy(() -> ModelResponseUpdate.builder().build())
        .isInstanceOf(NullPointerException.class)
        .hasMessage("finishReason must not be null");
  }

  @Test
  void legacyConstructorsAreRemoved() {
    assertThat(
            findConstructor(
                ModelRequest.class, List.class, ModelRequestOptions.class, java.util.Map.class))
        .isEmpty();
    assertThat(
            findConstructor(
                ModelResponse.class,
                List.class,
                io.github.hellices.agentframework.api.message.Usage.class,
                FinishReason.class,
                java.util.Map.class,
                Object.class))
        .isEmpty();
    assertThat(
            findConstructor(
                ModelResponseUpdate.class,
                List.class,
                io.github.hellices.agentframework.api.message.Usage.class,
                FinishReason.class,
                java.util.Map.class,
                Object.class))
        .isEmpty();
  }

  private static Message user(String text) {
    return new Message(Role.USER, List.of(new TextContent(text)));
  }

  private static ToolDefinition tool(String name) {
    return ToolDefinition.builder().name(name).description("test").build();
  }

  private static java.util.Optional<Constructor<?>> findConstructor(
      Class<?> type, Class<?>... parameterTypes) {
    try {
      return java.util.Optional.of(type.getDeclaredConstructor(parameterTypes));
    } catch (NoSuchMethodException missing) {
      return java.util.Optional.empty();
    }
  }
}
