package io.github.hellices.agentframework.samples.standalone;

import io.github.hellices.agentframework.api.agent.Agent;
import io.github.hellices.agentframework.api.agent.AgentFactory;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.engine.AgentEngine;
import io.github.hellices.agentframework.spi.model.ModelCatalog;
import io.github.hellices.agentframework.spi.model.ModelClient;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class StandaloneAgentApplication {

  private StandaloneAgentApplication() {}

  public static Agent createAgent() {
    ModelClient modelClient =
        request -> {
          String input = request.messages().stream().map(Message::text).reduce("", String::concat);
          Message output =
              new Message(
                  Role.ASSISTANT, List.of(new TextContent("Standalone agent received: " + input)));
          return CompletableFuture.completedFuture(
              new ModelResponse(
                  List.of(output),
                  null,
                  FinishReason.STOP,
                  Map.of("provider", "deterministic"),
                  null));
        };

    ModelCatalog catalog =
        ModelCatalog.builder()
            .add("deterministic", modelClient)
            .defaultModel("deterministic")
            .build();
    AgentFactory factory = AgentEngine.factory(catalog);

    return factory
        .builder()
        .id("standalone-agent")
        .name("Standalone Agent")
        .description("Runs without a host framework or external service.")
        .build();
  }

  public static void main(String[] args) {
    String input = args.length == 0 ? "hello" : String.join(" ", args);
    Agent agent = createAgent();
    String output = agent.run(input).response().toCompletableFuture().join().text();
    System.out.println(output);
  }
}
