package io.github.hellices.agentframework.openai.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.hellices.agentframework.api.value.JsonArray;
import io.github.hellices.agentframework.api.value.JsonBoolean;
import io.github.hellices.agentframework.api.value.JsonNull;
import io.github.hellices.agentframework.api.value.JsonNumber;
import io.github.hellices.agentframework.api.value.JsonObject;
import io.github.hellices.agentframework.api.value.JsonString;
import io.github.hellices.agentframework.api.value.JsonValue;
import io.github.hellices.agentframework.api.value.JsonValues;
import java.util.ArrayList;
import java.util.Optional;

/**
 * The one place this adapter reads and writes a tool call's {@code arguments} string.
 *
 * <p>Both directions are used on both sides of the wire: {@link ChatCompletionResponseMapper} reads
 * the arguments a completion carried, {@link ChatCompletionRequestMapper} writes the arguments of a
 * turn it has to rebuild, and it reads a completion's arguments again to decide whether the turn it
 * was handed still says what that completion said. Sharing this class is what keeps the two sides
 * from disagreeing about what an arguments string means - a second, laxer reader would let the
 * request mapper accept a string the response mapper refuses.
 *
 * <p>Neither method lets a Jackson exception escape. Jackson names the token it choked on and the
 * key it could not write in its own message text, and both are model output or caller data, so a
 * failure is reported as an absent result and the caller raises its own exception naming the tool
 * and the call id instead. That is why the return type is {@code Optional} rather than a thrown
 * parser or serialiser exception: an exception that never exists cannot be attached as a cause by
 * accident.
 */
final class ToolArguments {

  // Tool arguments are model output parsed into a Map a tool executor acts on, so the parser is
  // configured rather than defaulted.
  //
  // FAIL_ON_TRAILING_TOKENS: readTree reads one value and stops, so `{"a":1}{"b":2}` would arrive
  // as {"a":1} and the rest would vanish. Half of a response is not the call the model made.
  //
  // STRICT_DUPLICATE_DETECTION: Jackson's default is last-wins, which resolves `{"city":"Seoul",
  // "city":"Busan"}` to Busan and discards Seoul silently. Which value the model meant is not
  // knowable here, and choosing one changes the model's intent, so the duplicate fails instead.
  //
  // INCLUDE_SOURCE_IN_LOCATION disabled: Jackson's own message would otherwise quote the source it
  // failed on, and that source is the arguments string. No parse failure escapes this class - the
  // exceptions its callers raise carry no cause and no suppressed throwable, because Jackson names
  // the token it choked on in the message text itself, which no source-location setting can redact.
  // This line is the second layer rather than the rule: it keeps a parser exception that ever
  // escapes by a route this class does not catch from carrying the payload with it. It is also the
  // pinned Jackson's default, so stating it keeps a future upgrade from re-enabling it silently.
  private final ObjectMapper json =
      JsonMapper.builder()
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
          .build();

  /**
   * Reads an {@code arguments} string as exactly one JSON object with unique keys.
   *
   * <p>An empty string is the shape a no-argument call arrives in and reads as no arguments. Every
   * other failure - invalid JSON, a value that is not an object, input after the object, a repeated
   * key - is reported as an empty result, because the caller is the one that knows which tool and
   * which call id to name and which exception type its side of the wire uses.
   *
   * @param arguments the arguments string, never {@code null}
   * @return the arguments in wire order, or empty when the string is not exactly one JSON object
   *     with unique keys
   */
  Optional<JsonObject> read(String arguments) {
    if (arguments.isEmpty()) {
      return Optional.of(JsonObject.empty());
    }
    JsonNode parsed;
    try {
      parsed = json.readTree(arguments);
    } catch (JsonProcessingException failure) {
      // Deliberately dropped rather than rethrown or attached: see this class's Javadoc.
      return Optional.empty();
    }
    if (!parsed.isObject()) {
      return Optional.empty();
    }
    return Optional.of(toJsonObject(parsed));
  }

  /**
   * Writes an arguments map back as a JSON object, preserving its iteration order.
   *
   * @param arguments the arguments to write, never {@code null}
   * @return the arguments string, or empty when a value has no serialiser
   */
  Optional<String> write(JsonObject arguments) {
    try {
      return Optional.of(json.writeValueAsString(JsonValues.toJava(arguments)));
    } catch (JsonProcessingException failure) {
      // Deliberately dropped rather than rethrown or attached: Jackson appends the failing key to
      // its own message ("through reference chain: ...[\"<key>\"]") and an argument key is part of
      // the arguments. See this class's Javadoc.
      return Optional.empty();
    }
  }

  private static JsonObject toJsonObject(JsonNode node) {
    JsonObject.Builder builder = JsonObject.builder();
    node.properties().forEach(entry -> builder.put(entry.getKey(), toJsonValue(entry.getValue())));
    return builder.build();
  }

  private static JsonValue toJsonValue(JsonNode node) {
    if (node.isObject()) {
      return toJsonObject(node);
    }
    if (node.isArray()) {
      ArrayList<JsonValue> values = new ArrayList<>();
      node.elements().forEachRemaining(element -> values.add(toJsonValue(element)));
      return JsonArray.of(values);
    }
    if (node.isNull()) {
      return JsonNull.instance();
    }
    if (node.isBoolean()) {
      return JsonBoolean.of(node.booleanValue());
    }
    if (node.isTextual()) {
      return JsonString.of(node.textValue());
    }
    if (node.isNumber()) {
      return JsonNumber.of(node.numberValue());
    }
    throw new IllegalArgumentException("unsupported json node type: " + node.getNodeType());
  }
}
