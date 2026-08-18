package io.github.hellices.agentframework.api.agent;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.context.ContextAttributes;
import io.github.hellices.agentframework.api.context.ContextKey;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.session.SessionContext;
import io.github.hellices.agentframework.api.session.SessionStateKey;
import io.github.hellices.agentframework.api.tool.ToolArguments;
import io.github.hellices.agentframework.api.tool.ToolBinding;
import io.github.hellices.agentframework.api.tool.ToolContext;
import io.github.hellices.agentframework.api.tool.ToolDefinition;
import io.github.hellices.agentframework.api.tool.ToolHandler;
import io.github.hellices.agentframework.api.tool.ToolResult;
import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.model.StubModelClients;
import io.github.hellices.agentframework.spi.session.ContextProvider;
import io.github.hellices.agentframework.spi.session.ProviderSessionState;
import io.github.hellices.agentframework.spi.session.StatefulContextProvider;
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

    ModelClient modelClient = StubModelClients.stub();

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
                    .modelClient(StubModelClients.stub())
                    .toolBinding(ToolBinding.of("lookup", successfulHandler()))
                    .toolBinding(ToolBinding.of("lookup", successfulHandler()))
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("duplicate tool binding name: lookup");

    assertThatThrownBy(
            () -> AgentRuntime.builder().modelClient(StubModelClients.stub()).contextProvider(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("contextProvider must not be null");
    assertThatThrownBy(
            () ->
                AgentRuntime.builder()
                    .modelClient(StubModelClients.stub())
                    .contextProviders(Arrays.asList(new StatefulNamedProvider("history"), null)))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("contextProvider must not be null");
  }

  @Test
  void builderRejectsSameContextProviderAddedTwice() {
    StatefulNamedProvider provider = new StatefulNamedProvider("memory");

    assertThatThrownBy(
            () ->
                AgentRuntime.builder()
                    .modelClient(StubModelClients.stub())
                    .contextProvider(provider)
                    .contextProvider(provider)
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("duplicate context provider stateKey id: memory");
  }

  @Test
  void builderRejectsDifferentContextProvidersWithSameStateKeyId() {
    assertThatThrownBy(
            () ->
                AgentRuntime.builder()
                    .modelClient(StubModelClients.stub())
                    .contextProviders(
                        List.of(
                            new StatefulNamedProvider("memory"),
                            new StatefulNamedProvider("memory")))
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("duplicate context provider stateKey id: memory");
  }

  @Test
  void builderRejectsBlankContextProviderStateKeyId() {
    assertThatThrownBy(
            () ->
                AgentRuntime.builder()
                    .modelClient(StubModelClients.stub())
                    .contextProvider(new StatefulNamedProvider(" "))
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("id must not be blank");
  }

  @Test
  void builderAllowsAnyNumberOfStatelessProvidersThatReserveNoNamespace() {
    AgentRuntime runtime =
        AgentRuntime.builder()
            .modelClient(StubModelClients.stub())
            .contextProvider(new StatelessProvider())
            .contextProvider(new StatelessProvider())
            .build();

    assertThat(runtime.contextProviders()).hasSize(2);
  }

  @Test
  void builderDefensivelyCopiesImmutableCollectionsAndPreservesOrder() {
    ModelClient modelClient = StubModelClients.stub();
    ContextKey<String> tenant = ContextKey.of("agent", "tenant", String.class);
    ContextAttributes attributes = ContextAttributes.builder().put(tenant, "acme").build();
    List<ToolBinding> bindings = new ArrayList<>();
    bindings.add(ToolBinding.of("lookup", successfulHandler()));
    bindings.add(ToolBinding.of("search", successfulHandler()));
    List<ContextProvider> providers = new ArrayList<>();
    StatefulNamedProvider history = new StatefulNamedProvider("history");
    StatefulNamedProvider memory = new StatefulNamedProvider("memory");
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
    providers.add(new StatefulNamedProvider("mutated"));

    assertThat(runtime.toolBindings())
        .extracting(ToolBinding::toolName)
        .containsExactly("lookup", "search");
    assertThat(runtime.contextProviders()).containsExactly(history, memory);
    assertThat(runtime.attributes()).isSameAs(attributes);
    assertThatThrownBy(
            () -> runtime.toolBindings().add(ToolBinding.of("extra", successfulHandler())))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> runtime.contextProviders().add(new StatefulNamedProvider("extra")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void validationAllowsDeclarationOnlyToolsAndMatchingBindings() {
    AgentDefinition definition =
        AgentDefinition.builder().tool(tool("lookup")).tool(tool("remote-search")).build();
    AgentRuntime runtime =
        AgentRuntime.builder()
            .modelClient(StubModelClients.stub())
            .toolBinding(ToolBinding.of("lookup", successfulHandler()))
            .build();

    assertThatCode(() -> runtime.validate(definition)).doesNotThrowAnyException();
  }

  @Test
  void validationRejectsBindingsWithoutDeclarations() {
    AgentDefinition definition = AgentDefinition.builder().tool(tool("lookup")).build();
    AgentRuntime runtime =
        AgentRuntime.builder()
            .modelClient(StubModelClients.stub())
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

  private static final class StatelessProvider implements ContextProvider {
    @Override
    public CompletionStage<RunContribution> prepare(SessionContext context) {
      return completedFuture(RunContribution.empty());
    }

    @Override
    public CompletionStage<Void> complete(SessionContext context) {
      return completedFuture(null);
    }
  }

  private static final class StatefulNamedProvider implements StatefulContextProvider<Marker> {
    private final String id;

    private StatefulNamedProvider(String id) {
      this.id = id;
    }

    @Override
    public SessionStateKey<Marker> stateKey() {
      return SessionStateKey.of(id, Marker.class);
    }

    @Override
    public CompletionStage<RunContribution> prepare(
        SessionContext context, ProviderSessionState<Marker> state) {
      return completedFuture(RunContribution.empty());
    }

    @Override
    public CompletionStage<Void> complete(
        SessionContext context, ProviderSessionState<Marker> state) {
      return completedFuture(null);
    }
  }

  private interface Marker {}
}
