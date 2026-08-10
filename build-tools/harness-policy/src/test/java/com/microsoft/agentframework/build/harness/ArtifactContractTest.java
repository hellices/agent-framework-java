package com.microsoft.agentframework.build.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ArtifactContractTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  static Stream<Arguments> contracts() {
    return Stream.of(
        Arguments.of("task-intent"),
        Arguments.of("change-context"),
        Arguments.of("impact-set"),
        Arguments.of("test-plan"),
        Arguments.of("verification-result"),
        Arguments.of("run-score"));
  }

  @ParameterizedTest(name = "{0} schema has a matching complete example")
  @MethodSource("contracts")
  void schemaHasMatchingCompleteExample(String contractName) throws IOException {
    Path root = RepositoryPaths.root();
    JsonNode schema =
        JSON.readTree(root.resolve(".harness/schemas/" + contractName + ".schema.json").toFile());
    JsonNode example =
        JSON.readTree(root.resolve(".harness/examples/" + contractName + ".json").toFile());

    assertThat(schema.path("$schema").asText())
        .isEqualTo("https://json-schema.org/draft/2020-12/schema");
    assertThat(schema.path("$id").asText()).endsWith("/" + contractName + ".schema.json");
    assertThat(schema.path("type").asText()).isEqualTo("object");
    assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
    assertThat(schema.path("required").isArray()).isTrue();
    assertThat(example.path("schemaVersion").asText()).isEqualTo("1.0");

    for (JsonNode requiredProperty : schema.path("required")) {
      assertThat(example.has(requiredProperty.asText()))
          .as("example contains required property %s", requiredProperty.asText())
          .isTrue();
    }
  }
}
