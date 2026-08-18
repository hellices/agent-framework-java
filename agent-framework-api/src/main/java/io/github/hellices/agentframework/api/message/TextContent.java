package io.github.hellices.agentframework.api.message;

import io.github.hellices.agentframework.api.value.JsonObject;
import java.util.Objects;

public final class TextContent extends Content {

  private final String text;

  public TextContent(String text) {
    this(text, JsonObject.empty(), null);
  }

  public TextContent(String text, JsonObject additionalProperties, Object rawRepresentation) {
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

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof TextContent that)) {
      return false;
    }
    return text.equals(that.text) && baseEquals(that);
  }

  @Override
  public int hashCode() {
    return Objects.hash(text, baseHashCode());
  }
}
