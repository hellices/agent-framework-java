package io.github.hellices.agentframework.api.message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class Message {

  private final Role role;
  private final List<Content> content;
  private final MessageAttribution attribution;
  private final Map<String, Object> additionalProperties;
  private final Object rawRepresentation;

  public Message(Role role, List<? extends Content> content) {
    this(role, content, null, Map.of(), null);
  }

  public Message(
      Role role,
      List<? extends Content> content,
      MessageAttribution attribution,
      Map<String, Object> additionalProperties,
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
        additionalProperties == null ? Map.of() : Map.copyOf(additionalProperties);
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

  public Map<String, Object> additionalProperties() {
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
}
