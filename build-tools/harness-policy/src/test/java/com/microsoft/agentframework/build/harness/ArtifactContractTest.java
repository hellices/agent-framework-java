package com.microsoft.agentframework.build.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ArtifactContractTest {

  private static final String SCHEMA_ID_PREFIX = "https://agent-framework-java.dev/harness/";

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final SchemaRegistry SCHEMA_REGISTRY =
      SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

  private static final List<String> CONTRACTS =
      List.of(
          "task-intent",
          "change-context",
          "impact-set",
          "test-plan",
          "change-summary",
          "verification-result",
          "review-result",
          "run-score");

  static Stream<String> contracts() {
    return CONTRACTS.stream();
  }

  @ParameterizedTest(name = "{0} declares a closed 2020-12 schema")
  @MethodSource("contracts")
  void schemaIsClosedAndUsesDraft202012(String contractName) throws IOException {
    JsonNode schema = JSON.readTree(schemaText(contractName));

    assertThat(textOf(schema, "$schema")).isEqualTo("https://json-schema.org/draft/2020-12/schema");
    assertThat(textOf(schema, "$id")).isEqualTo(SCHEMA_ID_PREFIX + contractName + ".schema.json");
    assertThat(textOf(schema, "type")).isEqualTo("object");
    assertThat(schema.path("required").isArray()).isTrue();
    assertThat(schema.path("required")).isNotEmpty();
    assertClosedObjectSchemas(contractName, schema);
  }

  @ParameterizedTest(name = "{0} example validates against its schema")
  @MethodSource("contracts")
  void exampleValidatesAgainstSchema(String contractName) throws IOException {
    Schema schema = SCHEMA_REGISTRY.getSchema(schemaText(contractName));

    List<Error> errors = schema.validate(exampleText(contractName), InputFormat.JSON);

    assertThat(errors).isEmpty();
  }

  @ParameterizedTest(name = "{0} rejects an undeclared property")
  @MethodSource("contracts")
  void undeclaredPropertyIsRejected(String contractName) throws IOException {
    Schema schema = SCHEMA_REGISTRY.getSchema(schemaText(contractName));
    ObjectNode mutated = (ObjectNode) JSON.readTree(exampleText(contractName));
    mutated.put("undeclaredPolicyProbe", "rejected");

    List<Error> errors = schema.validate(JSON.writeValueAsString(mutated), InputFormat.JSON);

    assertThat(errors).isNotEmpty();
    assertThat(errors).anyMatch(error -> "additionalProperties".equals(error.getKeyword()));
  }

  @ParameterizedTest(name = "{0} example pins schemaVersion 1.0")
  @MethodSource("contracts")
  void examplePinsSchemaVersion(String contractName) throws IOException {
    JsonNode example = JSON.readTree(exampleText(contractName));

    assertThat(textOf(example, "schemaVersion")).isEqualTo("1.0");
  }

  private static void assertClosedObjectSchemas(String contractName, JsonNode root) {
    Deque<JsonNode> pending = new ArrayDeque<>();
    pending.push(root);
    while (!pending.isEmpty()) {
      JsonNode node = pending.pop();
      if (node.isObject() && "object".equals(textOf(node, "type"))) {
        JsonNode additionalProperties = node.path("additionalProperties");
        assertThat(additionalProperties.isBoolean())
            .as("%s declares additionalProperties on every object schema", contractName)
            .isTrue();
        assertThat(additionalProperties.booleanValue())
            .as("%s sets additionalProperties to false on every object schema", contractName)
            .isFalse();
      }
      node.forEach(pending::push);
    }
  }

  private static String textOf(JsonNode node, String fieldName) {
    return node.path(fieldName).textValue();
  }

  private static String schemaText(String contractName) throws IOException {
    return read(".harness/schemas/" + contractName + ".schema.json");
  }

  private static String exampleText(String contractName) throws IOException {
    return read(".harness/examples/" + contractName + ".json");
  }

  private static String read(String relativePath) throws IOException {
    return Files.readString(RepositoryPaths.root().resolve(relativePath), StandardCharsets.UTF_8);
  }
}
