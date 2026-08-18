package io.github.hellices.agentframework.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.agent.AgentDefinition;
import io.github.hellices.agentframework.api.agent.AgentRuntime;
import io.github.hellices.agentframework.api.agent.AgentSession;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.session.SessionContext;
import io.github.hellices.agentframework.engine.internal.context.ProviderBinding;
import io.github.hellices.agentframework.engine.session.InMemoryHistoryProvider;
import io.github.hellices.agentframework.spi.model.ModelCapabilities;
import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import io.github.hellices.agentframework.spi.model.ModelResponseUpdate;
import io.github.hellices.agentframework.spi.session.ContextProvider;
import io.github.hellices.agentframework.spi.session.HistoryPolicy;
import java.util.List;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

/**
 * The bind-time default-history resolution AGT-016 and SES-014 pin: who owns the conversation is
 * decided once, when the agent is bound, from the model's advertised capability and the configured
 * providers, and the per-run list a binding resolves is a fixed, immutable value.
 */
class AgentBindingTest {

  @Test
  void anEligibleLocalSessionResolvesAnImmutableListCarryingTheDefaultHistory() {
    AgentBinding binding = binding(localRuntime());

    List<ProviderBinding> resolved = binding.resolveProviders(sessionContext(session("s1", null)));

    assertThat(resolved).anyMatch(AgentBindingTest::isDefaultHistory);
    assertThatThrownBy(() -> resolved.add(new ProviderBinding("late", null)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void aServiceManagingModelResolvesNoDefaultHistoryWithoutAServiceSessionId() {
    AgentBinding binding = binding(serviceManagingRuntime());

    assertThat(binding.resolveProviders(sessionContext(session("s1", null))))
        .noneMatch(AgentBindingTest::isDefaultHistory);
  }

  @Test
  void aServiceManagingModelResolvesNoDefaultHistoryEvenWithAServiceSessionId() {
    AgentBinding binding = binding(serviceManagingRuntime());

    assertThat(binding.resolveProviders(sessionContext(session("s1", "svc-1"))))
        .noneMatch(AgentBindingTest::isDefaultHistory);
  }

  @Test
  void aLocalModelResolvesNoDefaultHistoryForAServiceManagedSession() {
    AgentBinding binding = binding(localRuntime());

    assertThat(binding.resolveProviders(sessionContext(session("s1", "svc-1"))))
        .noneMatch(AgentBindingTest::isDefaultHistory);
  }

  @Test
  void aServiceManagingModelKeepsAnExplicitDefaultNamespaceProviderWithoutACollision() {
    ContextProvider audit =
        new InMemoryHistoryProvider(
            InMemoryHistoryProvider.DEFAULT_SOURCE_ID,
            HistoryPolicy.builder().loadMessages(false).storeOutputs(false).build());
    AgentBinding binding = binding(serviceManagingRuntimeWith(audit));

    List<ProviderBinding> resolved = binding.resolveProviders(sessionContext(session("s1", null)));

    assertThat(resolved).singleElement().satisfies(b -> assertThat(b.provider()).isSameAs(audit));
  }

  @Test
  void aLocalModelRejectsAnExplicitStoreOnlyProviderOnTheDefaultNamespaceForAnEligibleRun() {
    ContextProvider audit =
        new InMemoryHistoryProvider(
            InMemoryHistoryProvider.DEFAULT_SOURCE_ID,
            HistoryPolicy.builder().loadMessages(false).storeOutputs(false).build());
    AgentBinding binding = binding(localRuntimeWith(audit));

    assertThatThrownBy(() -> binding.resolveProviders(sessionContext(session("s1", null))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(InMemoryHistoryProvider.DEFAULT_SOURCE_ID);
  }

  private static boolean isDefaultHistory(ProviderBinding binding) {
    return InMemoryHistoryProvider.DEFAULT_SOURCE_ID.equals(binding.sourceId());
  }

  private static AgentBinding binding(AgentRuntime runtime) {
    return AgentBinding.create(AgentDefinition.builder().id("agent-1").build(), runtime);
  }

  private static AgentRuntime localRuntime() {
    return AgentRuntime.builder().modelClient(localClient()).build();
  }

  private static AgentRuntime localRuntimeWith(ContextProvider provider) {
    return AgentRuntime.builder().modelClient(localClient()).contextProvider(provider).build();
  }

  private static AgentRuntime serviceManagingRuntime() {
    return AgentRuntime.builder().modelClient(serviceManagingClient()).build();
  }

  private static AgentRuntime serviceManagingRuntimeWith(ContextProvider provider) {
    return AgentRuntime.builder()
        .modelClient(serviceManagingClient())
        .contextProvider(provider)
        .build();
  }

  private static ModelClient localClient() {
    return request -> EngineModels.of(response());
  }

  private static ModelClient serviceManagingClient() {
    return new ModelClient() {
      @Override
      public Flow.Publisher<ModelResponseUpdate> execute(ModelRequest request) {
        return EngineModels.of(response());
      }

      @Override
      public ModelCapabilities capabilities() {
        return ModelCapabilities.builder().serviceManagesHistory(true).build();
      }
    };
  }

  private static ModelResponse response() {
    return ModelResponse.builder()
        .messages(List.of(new Message(Role.ASSISTANT, List.of(new TextContent("hi")))))
        .finishReason(FinishReason.STOP)
        .build();
  }

  private static AgentSession session(String sessionId, String serviceSessionId) {
    AgentSession.Builder builder = AgentSession.builder().sessionId(sessionId);
    if (serviceSessionId != null) {
      builder.serviceSessionId(serviceSessionId);
    }
    return builder.build();
  }

  private static SessionContext sessionContext(AgentSession session) {
    return new SessionContext(session, Message.normalize("hi"), null, null);
  }
}
