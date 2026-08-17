package io.github.hellices.agentframework.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.context.ContextAttributes;
import io.github.hellices.agentframework.api.context.ContextKey;
import io.github.hellices.agentframework.api.tool.ToolDefinition;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentDefinitionTest {

  @Test
  void defaultsGenerateDeclarativeIdentityAndEmptyState() {
    AgentDefinition first = AgentDefinition.builder().build();
    AgentDefinition second = AgentDefinition.builder().build();

    assertThat(UUID.fromString(first.id())).isNotNull();
    assertThat(first.name()).isEqualTo("agent");
    assertThat(first.description()).isEmpty();
    assertThat(first.instructions()).isEmpty();
    assertThat(first.tools()).isEmpty();
    assertThat(first.defaultRunOptions()).isNotNull();
    assertThat(first.defaultRunOptions()).isNotSameAs(second.defaultRunOptions());
    assertThat(first.defaultRunOptions().attributes()).isSameAs(ContextAttributes.empty());
    assertThat(first.defaultRunOptions().modelClientFactory()).isEmpty();
    assertThat(first.defaultRunOptions().continuationToken()).isEmpty();
    assertThat(first.attributes()).isSameAs(ContextAttributes.empty());
  }

  @Test
  void blankIdentityFieldsAreRejected() {
    assertThatThrownBy(() -> AgentDefinition.builder().id(" ").build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("id must not be blank");
    assertThatThrownBy(() -> AgentDefinition.builder().name(" ").build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("name must not be blank");
  }

  @Test
  void nullBuilderSettersAreRejected() {
    assertThatThrownBy(() -> AgentDefinition.builder().description(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("description must not be null");
    assertThatThrownBy(() -> AgentDefinition.builder().instructions(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("instructions must not be null");
    assertThatThrownBy(() -> AgentDefinition.builder().defaultRunOptions(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("defaultRunOptions must not be null");
    assertThatThrownBy(() -> AgentDefinition.builder().attributes(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("attributes must not be null");
  }

  @Test
  void toolsAreDefensivelyCopiedAndImmutable() {
    List<ToolDefinition> tools = new ArrayList<>();
    tools.add(tool("lookup"));

    AgentDefinition definition = AgentDefinition.builder().tools(tools).build();
    tools.add(tool("mutated"));

    assertThat(definition.tools()).containsExactly(tool("lookup"));
    assertThatThrownBy(() -> definition.tools().add(tool("extra")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void duplicateToolNamesFailAtBuild() {
    assertThatThrownBy(
            () -> AgentDefinition.builder().tool(tool("lookup")).tool(tool("lookup")).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("duplicate tool name: lookup");
  }

  @Test
  void runtimeCollaboratorsCannotAppearInDefaultRunOptions() {
    AgentRunOptions options =
        AgentRunOptions.builder().modelClientFactory(modelClient -> modelClient).build();

    assertThatThrownBy(() -> AgentDefinition.builder().defaultRunOptions(options).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("defaultRunOptions must not include modelClientFactory");
  }

  @Test
  void toBuilderProducesEqualValueObjects() {
    ContextKey<String> tenant = ContextKey.of("agent", "tenant", String.class);
    ContextAttributes attributes = ContextAttributes.builder().put(tenant, "acme").build();
    AgentRunOptions options = AgentRunOptions.builder().attributes(attributes).build();
    AgentDefinition definition =
        AgentDefinition.builder()
            .id("agent-id")
            .name("assistant")
            .description("describes the agent")
            .instructions("be helpful")
            .tool(tool("lookup"))
            .defaultRunOptions(options)
            .attributes(attributes)
            .build();

    AgentDefinition rebuilt = definition.toBuilder().build();
    AgentDefinition equivalent =
        AgentDefinition.builder()
            .id("agent-id")
            .name("assistant")
            .description("describes the agent")
            .instructions("be helpful")
            .tool(tool("lookup"))
            .defaultRunOptions(AgentRunOptions.builder().attributes(attributes).build())
            .attributes(attributes)
            .build();

    assertThat(rebuilt).isEqualTo(definition).hasSameHashCodeAs(definition);
    assertThat(equivalent).isEqualTo(definition).hasSameHashCodeAs(definition);
    assertThat(rebuilt).isNotSameAs(definition);
  }

  @Test
  void runtimeCollaboratorFieldsAreAbsent() {
    assertThat(
            Arrays.stream(AgentDefinition.class.getDeclaredFields()).map(Field::getName).toList())
        .containsExactlyInAnyOrder(
            "id",
            "name",
            "description",
            "instructions",
            "tools",
            "defaultRunOptions",
            "attributes");
  }

  private static ToolDefinition tool(String name) {
    return ToolDefinition.builder().name(name).description(name + " tool").build();
  }
}
