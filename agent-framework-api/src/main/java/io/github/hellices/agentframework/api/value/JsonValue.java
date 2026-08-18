package io.github.hellices.agentframework.api.value;

public sealed interface JsonValue
    permits JsonNull, JsonBoolean, JsonNumber, JsonString, JsonArray, JsonObject {}
