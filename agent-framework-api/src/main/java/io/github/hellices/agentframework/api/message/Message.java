package io.github.hellices.agentframework.api.message;

import io.github.hellices.agentframework.api.value.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class Message {

  private final Role role;
  private final List<Content> content;
  private final MessageAttribution attribution;
  private final JsonObject additionalProperties;
  private final transient Object rawRepresentation;

  public Message(Role role, List<? extends Content> content) {
    this(role, content, null, JsonObject.empty(), null);
  }

  public Message(
      Role role,
      List<? extends Content> content,
      MessageAttribution attribution,
      JsonObject additionalProperties,
      Object rawRepresentation) {
    this.role = Objects.requireNonNull(role, "role must not be null");
    List<Content> normalizedContent = new ArrayList<>();
    if (content != null) {
      for (Content item : content) {
        normalizedContent.add(Objects.requireNonNull(item, "content items must not be null"));
      }
    }
    this.content = List.copyOf(normalizedContent);
    this.attribution = attribution;
    this.additionalProperties =
        additionalProperties == null ? JsonObject.empty() : additionalProperties;
    this.rawRepresentation = rawRepresentation;
  }

  public static List<Message> normalize(Object input) {
    if (input == null) {
      throw new NullPointerException("input must not be null");
    }
    if (input instanceof String text) {
      return List.of(new Message(Role.USER, List.of(new TextContent(text))));
    }
    if (input instanceof Content content) {
      return List.of(new Message(Role.USER, List.of(content)));
    }
    if (input instanceof Message message) {
      return List.of(message);
    }
    if (input instanceof Iterable<?> iterable) {
      List<Message> messages = new ArrayList<>();
      for (Object item : iterable) {
        if (item instanceof Message message) {
          messages.add(message);
        } else if (item instanceof Content content) {
          messages.add(new Message(Role.USER, List.of(content)));
        } else if (item instanceof String text) {
          messages.add(new Message(Role.USER, List.of(new TextContent(text))));
        } else {
          throw new IllegalArgumentException(
              "Unsupported message input type: " + item.getClass().getName());
        }
      }
      return List.copyOf(messages);
    }
    throw new IllegalArgumentException(
        "Unsupported message input type: " + input.getClass().getName());
  }

  public Role role() {
    return role;
  }

  public List<Content> content() {
    return content;
  }

  public MessageAttribution attribution() {
    return attribution;
  }

  /**
   * Returns a copy of this message carrying {@code attribution}, leaving this message untouched.
   *
   * <p>Role, content, additional properties, and raw representation are carried over unchanged, so
   * a caller that has to re-attribute a message it did not create (a history provider stamping
   * loaded history, for example) never has to rebuild the message field by field and never mutates
   * the message another holder observes.
   *
   * @param attribution the attribution the copy carries; must not be {@code null}
   * @throws NullPointerException if {@code attribution} is {@code null}
   */
  public Message withAttribution(MessageAttribution attribution) {
    Objects.requireNonNull(attribution, "attribution must not be null");
    return new Message(role, content, attribution, additionalProperties, rawRepresentation);
  }

  public JsonObject additionalProperties() {
    return additionalProperties;
  }

  public Object rawRepresentation() {
    return rawRepresentation;
  }

  public String text() {
    StringBuilder builder = new StringBuilder();
    for (Content item : content) {
      builder.append(item.text());
    }
    return builder.toString();
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof Message that)) {
      return false;
    }
    return role.equals(that.role)
        && content.equals(that.content)
        && Objects.equals(attribution, that.attribution)
        && additionalProperties.equals(that.additionalProperties)
        && Objects.equals(rawRepresentation, that.rawRepresentation);
  }

  @Override
  public int hashCode() {
    return Objects.hash(role, content, attribution, additionalProperties, rawRepresentation);
  }

  @Override
  public String toString() {
    return "Message[role="
        + role
        + ", content="
        + content
        + ", attribution="
        + attribution
        + ", additionalProperties="
        + additionalProperties
        + ", rawRepresentation="
        + rawRepresentation
        + "]";
  }
}
