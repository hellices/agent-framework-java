package io.github.hellices.agentframework.api.message;

import java.util.Map;
import java.util.Objects;

public final class TextContent extends Content {

  private final String text;

  public TextContent(String text) {
    this(text, Map.of(), null);
  }

  public TextContent(
      String text, Map<String, Object> additionalProperties, Object rawRepresentation) {
    super(additionalProperties, rawRepresentation);
    this.text = Objects.requireNonNull(text, "text must not be null");
  }

  public String value() {
    return text;
  }

  @Override
  public String type() {
    return "text";
  }

  @Override
  public String text() {
    return text;
  }
}
