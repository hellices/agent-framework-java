package io.github.hellices.agentframework.spi.session;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.agent.AgentRuntime;
import io.github.hellices.agentframework.api.agent.AgentSession;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.agent.RunContribution;
import io.github.hellices.agentframework.api.context.ContextAttributes;
import io.github.hellices.agentframework.api.session.SessionContext;
import io.github.hellices.agentframework.api.session.SessionSnapshot;
import io.github.hellices.agentframework.api.session.SessionStateKey;
import io.github.hellices.agentframework.spi.model.StubModelClients;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Executable form of the contribution-based provider contract (SES-012): a stateless {@link
 * ContextProvider} owns no session-state namespace, a {@link StatefulContextProvider} binds exactly
 * one typed key and reaches its state only through the bound view, and the engine drives one
 * ordered provider list because the stateful bridge resolves that view itself.
 */
class ContextProviderContractTest {

  @Test
  void statelessProviderDeclaresNoNamespaceAccessor() {
    assertThat(ContextProvider.class.getMethods())
        .as("stateless ContextProvider must not expose a source id or state key accessor")
        .noneMatch(method -> method.getName().equals("sourceId"))
        .noneMatch(method -> method.getName().equals("stateKey"));
  }

  @Test
  void anyNumberOfStatelessProvidersReserveNoNamespaceAndDoNotConflict() {
    // Two stateless providers own nothing, so configuring both never collides on a namespace.
    AgentRuntime runtime =
        AgentRuntime.builder()
            .modelClient(StubModelClients.stub())
            .contextProvider(new RecordingStatelessProvider())
            .contextProvider(new RecordingStatelessProvider())
            .build();

    assertThat(runtime.contextProviders()).hasSize(2);
  }

  @Test
  void duplicateStatefulStateKeyIdsFailAssembly() {
    assertThatThrownBy(
            () ->
                AgentRuntime.builder()
                    .modelClient(StubModelClients.stub())
                    .contextProvider(new StatefulMarkerProvider("memory"))
                    .contextProvider(new StatefulMarkerProvider("memory"))
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("duplicate context provider stateKey id: memory");
  }

  @Test
  void blankStatefulStateKeyFailsAssembly() {
    assertThatThrownBy(
            () ->
                AgentRuntime.builder()
                    .modelClient(StubModelClients.stub())
                    .contextProvider(new StatefulMarkerProvider(" "))
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("id must not be blank");
  }

  @Test
  void defaultPrepareResolvesTypedViewAndDelegates() {
    StatefulMarkerProvider provider = new StatefulMarkerProvider("memory");
    AgentSession session =
        AgentSession.builder()
            .sessionId("session-1")
            .state(
                io.github.hellices.agentframework.api.session.SessionState.empty()
                    .with(provider.stateKey(), new MarkerValue("stored")))
            .build();
    SessionContext context =
        new SessionContext(session, List.of(), ContextAttributes.empty(), new CancellationSignal());

    RunContribution contribution = provider.prepare(context).toCompletableFuture().join();

    assertThat(contribution).isEqualTo(RunContribution.empty());
    assertThat(provider.observedValue.get()).contains(new MarkerValue("stored"));
    assertThat(provider.observedKey.get()).isEqualTo(provider.stateKey());
  }

  @Test
  void defaultCompleteResolvesTypedViewAndDelegatesAndPersists() {
    StatefulMarkerProvider provider = new StatefulMarkerProvider("memory");
    AgentSession session = AgentSession.builder().sessionId("session-1").build();
    SessionContext context =
        new SessionContext(session, List.of(), ContextAttributes.empty(), new CancellationSignal());

    provider.complete(context).toCompletableFuture().join();

    assertThat(context.updatedSession().orElseThrow().state().get(provider.stateKey()))
        .contains(new MarkerValue("written"));
  }

  @Test
  void typedFirstWriteResolvesTheDeclaredAbstractCodecType() {
    // The declared key type is an interface with a registered codec; the concrete stored value is a
    // subtype. A first write must resolve the codec by the declared (interface) type, not by the
    // concrete value class, so persistence succeeds with no prior entry.
    SessionStateKey<Marker> key = SessionStateKey.of("memory", Marker.class);
    AgentSession session = AgentSession.builder().sessionId("session-1").build();
    SessionContext context =
        new SessionContext(session, List.of(), ContextAttributes.empty(), new CancellationSignal());

    context.providerState(key).set(new MarkerValue("first"));

    AgentSession updated = context.updatedSession().orElseThrow();
    StateCodecRegistry registry =
        StateCodecRegistry.builder().register(new MarkerStateCodec()).build();
    SessionSnapshot snapshot = registry.snapshot(updated, 1L, Instant.EPOCH);
    assertThat(snapshot.state().get("memory").typeId()).isEqualTo("marker");
    assertThat(registry.restore(snapshot).state().get(key)).contains(new MarkerValue("first"));
  }

  @Test
  void providerStateRejectsAKeyThatCollidesOnIdButDiffersOnType() {
    AgentSession session = AgentSession.builder().sessionId("session-1").build();
    SessionContext context =
        new SessionContext(session, List.of(), ContextAttributes.empty(), new CancellationSignal());

    context.providerState(SessionStateKey.of("memory", Marker.class));

    assertThatThrownBy(() -> context.providerState(SessionStateKey.of("memory", String.class)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("session state key collision for memory");
  }

  private static final class RecordingStatelessProvider implements ContextProvider {
    @Override
    public CompletionStage<RunContribution> prepare(SessionContext context) {
      return completedFuture(RunContribution.empty());
    }

    @Override
    public CompletionStage<Void> complete(SessionContext context) {
      return completedFuture(null);
    }
  }

  private static final class StatefulMarkerProvider implements StatefulContextProvider<Marker> {

    private final String id;
    private final AtomicReference<Optional<Marker>> observedValue =
        new AtomicReference<>(Optional.empty());
    private final AtomicReference<SessionStateKey<Marker>> observedKey = new AtomicReference<>();

    private StatefulMarkerProvider(String id) {
      this.id = id;
    }

    @Override
    public SessionStateKey<Marker> stateKey() {
      return SessionStateKey.of(id, Marker.class);
    }

    @Override
    public CompletionStage<RunContribution> prepare(
        SessionContext context, ProviderSessionState<Marker> state) {
      observedKey.set(state.key());
      observedValue.set(state.value());
      return completedFuture(RunContribution.empty());
    }

    @Override
    public CompletionStage<Void> complete(
        SessionContext context, ProviderSessionState<Marker> state) {
      state.set(new MarkerValue("written"));
      return completedFuture(null);
    }
  }

  private interface Marker {}

  private record MarkerValue(String value) implements Marker {}

  private static final class MarkerStateCodec implements StateCodec<Marker> {
    @Override
    public String typeId() {
      return "marker";
    }

    @Override
    public int version() {
      return 1;
    }

    @Override
    public Class<Marker> javaType() {
      return Marker.class;
    }

    @Override
    public Object encode(Marker value) {
      return ((MarkerValue) value).value();
    }

    @Override
    public Marker decode(Object payload) {
      return new MarkerValue((String) payload);
    }
  }
}
