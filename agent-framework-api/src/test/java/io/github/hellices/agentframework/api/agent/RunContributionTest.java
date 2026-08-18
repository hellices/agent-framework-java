package io.github.hellices.agentframework.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.tool.ToolDefinition;
import io.github.hellices.agentframework.api.value.JsonObject;
import io.github.hellices.agentframework.spi.model.ModelProviderOption;
import io.github.hellices.agentframework.spi.model.ModelRequestOptions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunContributionTest {

  @Test
  void emptyContributionDefaultsToEmptyCollectionsAndModelOptions() {
    RunContribution contribution = RunContribution.empty();

    assertThat(contribution.messages()).isEmpty();
    assertThat(contribution.instructionAdditions()).isEmpty();
    assertThat(contribution.tools()).isEmpty();
    assertThat(contribution.modelOptions().temperature()).isEmpty();
    assertThat(contribution.modelOptions().maxOutputTokens()).isEmpty();
    assertThat(contribution.modelOptions().providerOptions()).isEmpty();
  }

  @Test
  void contributionDefensivelyCopiesOrderedCollections() {
    List<Message> messages = new ArrayList<>(List.of(userMessage("first"), userMessage("second")));
    List<String> instructions = new ArrayList<>(List.of("alpha", "beta"));
    List<ToolDefinition> tools = new ArrayList<>(List.of(tool("lookup"), tool("calculate")));

    RunContribution contribution =
        RunContribution.builder()
            .messages(messages)
            .instructionAdditions(instructions)
            .tools(tools)
            .build();

    messages.add(userMessage("third"));
    instructions.add("gamma");
    tools.add(tool("summarize"));

    assertThat(contribution.messages())
        .extracting(Message::text)
        .containsExactly("first", "second");
    assertThat(contribution.instructionAdditions()).containsExactly("alpha", "beta");
    assertThat(contribution.tools())
        .extracting(ToolDefinition::name)
        .containsExactly("lookup", "calculate");
    assertThatThrownBy(() -> contribution.messages().add(userMessage("nope")))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> contribution.instructionAdditions().add("nope"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> contribution.tools().add(tool("nope")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void duplicateToolNamesAreRejected() {
    assertThatThrownBy(
            () -> RunContribution.builder().tools(List.of(tool("lookup"), tool("lookup"))).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("tools must not contain duplicate names: lookup");
    assertThatThrownBy(
            () -> RunContribution.builder().addTool(tool("lookup")).addTool(tool("lookup")).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("tools must not contain duplicate names: lookup");
  }

  @Test
  void explicitNullSettersAndElementsFail() {
    assertThatThrownBy(() -> RunContribution.builder().messages(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("messages must not be null");
    assertThatThrownBy(() -> RunContribution.builder().instructionAdditions(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("instructionAdditions must not be null");
    assertThatThrownBy(() -> RunContribution.builder().tools(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("tools must not be null");
    assertThatThrownBy(() -> RunContribution.builder().modelOptions(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("modelOptions must not be null");
    assertThatThrownBy(() -> RunContribution.builder().addMessage(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("message must not be null");
    assertThatThrownBy(() -> RunContribution.builder().addInstructionAddition(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("instructionAddition must not be null");
    assertThatThrownBy(() -> RunContribution.builder().addTool(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("tool must not be null");
    assertThatThrownBy(
            () -> RunContribution.builder().messages(Arrays.asList(userMessage("ok"), null)))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("messages must not contain null entries");
    assertThatThrownBy(
            () -> RunContribution.builder().instructionAdditions(Arrays.asList("ok", null)))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("instructionAdditions must not contain null entries");
    assertThatThrownBy(() -> RunContribution.builder().tools(Arrays.asList(tool("lookup"), null)))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("tools must not contain null entries");
  }

  @Test
  void blankInstructionAdditionsAreRejected() {
    assertThatThrownBy(() -> RunContribution.builder().addInstructionAddition(" "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("instructionAddition must not be blank");
    assertThatThrownBy(
            () -> RunContribution.builder().instructionAdditions(List.of("first", " ")).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("instructionAddition must not be blank");
  }

  @Test
  void contributionCarriesTypedModelOptions() {
    TestProviderOption providerOption = new TestProviderOption("concise");
    ModelRequestOptions modelOptions =
        ModelRequestOptions.builder().temperature(0.4).providerOption(providerOption).build();

    RunContribution contribution = RunContribution.builder().modelOptions(modelOptions).build();

    assertThat(contribution.modelOptions().temperature()).hasValue(0.4);
    assertThat(contribution.modelOptions().providerOption(TestProviderOption.class))
        .containsSame(providerOption);
  }

  @Test
  void contributionsUseStructuralEqualityAndToBuilder() {
    RunContribution first =
        RunContribution.builder()
            .messages(List.of(userMessage("hello")))
            .instructionAdditions(List.of("be concise"))
            .tools(List.of(tool("lookup")))
            .modelOptions(
                ModelRequestOptions.builder()
                    .maxOutputTokens(128)
                    .providerOption(new TestProviderOption("precise"))
                    .build())
            .build();
    RunContribution second =
        RunContribution.builder()
            .messages(List.of(userMessage("hello")))
            .instructionAdditions(List.of("be concise"))
            .tools(List.of(tool("lookup")))
            .modelOptions(
                ModelRequestOptions.builder()
                    .maxOutputTokens(128)
                    .providerOption(new TestProviderOption("precise"))
                    .build())
            .build();

    assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
    assertThat(first.toBuilder().build()).isEqualTo(first).hasSameHashCodeAs(first);
  }

  private static Message userMessage(String text) {
    return new Message(Role.USER, List.of(new TextContent(text)));
  }

  private static ToolDefinition tool(String name) {
    return ToolDefinition.builder()
        .name(name)
        .description(name + " description")
        .inputSchema(JsonObject.empty())
        .build();
  }

  private record TestProviderOption(String detail) implements ModelProviderOption {
    @Override
    public String providerId() {
      return "test";
    }
  }
}
