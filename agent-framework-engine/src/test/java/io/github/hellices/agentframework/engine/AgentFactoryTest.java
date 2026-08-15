package io.github.hellices.agentframework.engine;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.hellices.agentframework.api.agent.Agent;
import io.github.hellices.agentframework.api.agent.AgentFactory;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.spi.model.ModelCatalog;
import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentFactoryTest {

  @Test
  void standaloneFactoryBuildsAnAgentFromTheDefaultCatalogModel() {
    ModelClient client = request -> completedFuture(response("default"));
    AgentFactory factory =
        AgentEngine.factory(
            ModelCatalog.builder()
                .add("deterministic", client)
                .defaultModel("deterministic")
                .build());

    Agent agent = factory.builder().id("standalone").name("Standalone").build();

    assertThat(agent.id()).isEqualTo("standalone");
    assertThat(agent.run("hello").response().toCompletableFuture().join().text())
        .isEqualTo("default");
  }

  @Test
  void factoryCanSelectNamedAndExplicitModels() {
    ModelClient named = request -> completedFuture(response("named"));
    ModelClient explicit = request -> completedFuture(response("explicit"));
    AgentFactory factory = AgentEngine.factory(ModelCatalog.builder().add("named", named).build());

    assertThat(
            factory
                .builder("named")
                .build()
                .run("hi")
                .response()
                .toCompletableFuture()
                .join()
                .text())
        .isEqualTo("named");
    assertThat(
            factory
                .builder(explicit)
                .build()
                .run("hi")
                .response()
                .toCompletableFuture()
                .join()
                .text())
        .isEqualTo("explicit");
  }

  private static ModelResponse response(String text) {
    return new ModelResponse(
        List.of(new Message(Role.ASSISTANT, List.of(new TextContent(text)))),
        null,
        FinishReason.STOP,
        Map.of(),
        null);
  }
}
