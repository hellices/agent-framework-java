package io.github.hellices.agentframework.api.agent;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.context.ContextAttributes;
import io.github.hellices.agentframework.api.context.ContextKey;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.session.SessionContext;
import io.github.hellices.agentframework.api.tool.ToolArguments;
import io.github.hellices.agentframework.api.tool.ToolBinding;
import io.github.hellices.agentframework.api.tool.ToolContext;
import io.github.hellices.agentframework.api.tool.ToolDefinition;
import io.github.hellices.agentframework.api.tool.ToolHandler;
import io.github.hellices.agentframework.api.tool.ToolResult;
import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.session.ContextProvider;
import io.github.hellices.agentframework.spi.session.ProviderSessionState;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class AgentRuntimeTest {

  @Test
  void toolBindingRequiresDeclaredNameAndHandler() {
    assertThatThrownBy(() -> ToolBinding.of(" ", successfulHandler()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("tool binding name must not be blank");
    assertThatThrownBy(() -> ToolBinding.of("lookup", null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("handler must not be null");
  }

  @Test
  void builderRequiresModelClientAndDefaultsToEmptyState() {
    assertThatThrownBy(() -> AgentRuntime.builder().build())
        .isInstanceOf(NullPointerException.class)
        .hasMessage("modelClient must not be null");

    ModelClient modelClient = request -> completedFuture(null);

    AgentRuntime runtime = AgentRuntime.builder().modelClient(modelClient).build();

    assertThat(runtime.modelClient()).isSameAs(modelClient);
    assertThat(runtime.toolBindings()).isEmpty();
    assertThat(runtime.contextProviders()).isEmpty();
    assertThat(runtime.attributes()).isSameAs(ContextAttributes.empty());
  }

  @Test
  void builderRejectsDuplicateBindingNamesAndNullProviders() {
    assertThatThrownBy(
            () ->
                AgentRuntime.builder()
                    .modelClient(request -> completedFuture(null))
                    .toolBinding(ToolBinding.of("lookup", successfulHandler()))
                    .toolBinding(ToolBinding.of("lookup", successfulHandler()))
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("duplicate tool binding name: lookup");

    assertThatThrownBy(
            () ->
                AgentRuntime.builder()
                    .modelClient(request -> completedFuture(null))
                    .contextProvider(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("contextProvider must not be null");
    assertThatThrownBy(
            () ->
                AgentRuntime.builder()
                    .modelClient(request -> completedFuture(null))
                    .contextProviders(Arrays.asList(new NamedContextProvider("history"), null)))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("contextProvider must not be null");
  }

  @Test
  void builderRejectsSameContextProviderAddedTwice() {
    NamedContextProvider provider = new NamedContextProvider("memory");

    assertThatThrownBy(
            () ->
                AgentRuntime.builder()
                    .modelClient(request -> completedFuture(null))
                    .contextProvider(provider)
                    .contextProvider(provider)
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("duplicate context provider sourceId: memory");
  }

  @Test
  void builderRejectsDifferentContextProvidersWithSameSourceId() {
    assertThatThrownBy(
            () ->
                AgentRuntime.builder()
                    .modelClient(request -> completedFuture(null))
                    .contextProviders(
                        List.of(
                            new NamedContextProvider("memory"), new NamedContextProvider("memory")))
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("duplicate context provider sourceId: memory");
  }

  @Test
  void builderRejectsBlankContextProviderSourceId() {
    assertThatThrownBy(
            () ->
                AgentRuntime.builder()
                    .modelClient(request -> completedFuture(null))
                    .contextProvider(new NamedContextProvider(" "))
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("context provider sourceId must not be blank");
  }

  @Test
  void builderDefensivelyCopiesImmutableCollectionsAndPreservesOrder() {
    ModelClient modelClient = request -> completedFuture(null);
    ContextKey<String> tenant = ContextKey.of("agent", "tenant", String.class);
    ContextAttributes attributes = ContextAttributes.builder().put(tenant, "acme").build();
    List<ToolBinding> bindings = new ArrayList<>();
    bindings.add(ToolBinding.of("lookup", successfulHandler()));
    bindings.add(ToolBinding.of("search", successfulHandler()));
    List<ContextProvider> providers = new ArrayList<>();
    NamedContextProvider history = new NamedContextProvider("history");
    NamedContextProvider memory = new NamedContextProvider("memory");
    providers.add(history);
    providers.add(memory);

    AgentRuntime runtime =
        AgentRuntime.builder()
            .modelClient(modelClient)
            .toolBindings(bindings)
            .contextProviders(providers)
            .attributes(attributes)
            .build();

    bindings.add(ToolBinding.of("mutated", successfulHandler()));
    providers.add(new NamedContextProvider("mutated"));

    assertThat(runtime.toolBindings())
        .extracting(ToolBinding::toolName)
        .containsExactly("lookup", "search");
    assertThat(runtime.contextProviders()).containsExactly(history, memory);
    assertThat(runtime.attributes()).isSameAs(attributes);
    assertThatThrownBy(
            () -> runtime.toolBindings().add(ToolBinding.of("extra", successfulHandler())))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> runtime.contextProviders().add(new NamedContextProvider("extra")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void validationAllowsDeclarationOnlyToolsAndMatchingBindings() {
    AgentDefinition definition =
        AgentDefinition.builder().tool(tool("lookup")).tool(tool("remote-search")).build();
    AgentRuntime runtime =
        AgentRuntime.builder()
            .modelClient(request -> completedFuture(null))
            .toolBinding(ToolBinding.of("lookup", successfulHandler()))
            .build();

    assertThatCode(() -> runtime.validate(definition)).doesNotThrowAnyException();
  }

  @Test
  void validationRejectsBindingsWithoutDeclarations() {
    AgentDefinition definition = AgentDefinition.builder().tool(tool("lookup")).build();
    AgentRuntime runtime =
        AgentRuntime.builder()
            .modelClient(request -> completedFuture(null))
            .toolBinding(ToolBinding.of("remote-search", successfulHandler()))
            .build();

    assertThatThrownBy(() -> runtime.validate(definition))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("tool binding has no matching declaration: remote-search");
  }

  @Test
  void runtimeAndBindingExposeOnlyTheirExpectedFields() {
    assertThat(Arrays.stream(AgentRuntime.class.getDeclaredFields()).map(Field::getName).toList())
        .containsExactlyInAnyOrder("modelClient", "toolBindings", "contextProviders", "attributes");
    assertThat(Arrays.stream(ToolBinding.class.getDeclaredFields()).map(Field::getName).toList())
        .containsExactlyInAnyOrder("toolName", "handler");
  }

  private static ToolDefinition tool(String name) {
    return ToolDefinition.builder().name(name).description(name + " tool").build();
  }

  private static ToolHandler successfulHandler() {
    return (ToolArguments arguments, ToolContext context) ->
        completedFuture(ToolResult.success(new TextContent("ok")));
  }

  private static final class NamedContextProvider implements ContextProvider {
    private final String sourceId;

    private NamedContextProvider(String sourceId) {
      this.sourceId = sourceId;
    }

    @Override
    public String sourceId() {
      return sourceId;
    }

    @Override
    public CompletionStage<Void> beforeRun(SessionContext context, ProviderSessionState state) {
      return completedFuture(null);
    }

    @Override
    public CompletionStage<Void> afterRun(SessionContext context, ProviderSessionState state) {
      return completedFuture(null);
    }
  }
}
