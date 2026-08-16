package io.github.hellices.agentframework.api.message;

import java.util.Map;

/**
 * Base for content kinds owned by an adapter rather than by the framework.
 *
 * <p>{@link Content} is sealed because the framework owns its own kinds and code that reasons about
 * them must stay exhaustive. A protocol or provider adapter still has to carry content the
 * framework has no type for, such as an MCP image payload, and reopening the sealed set for every
 * adapter would make adding an adapter a core change. This class is the single extension point that
 * keeps both properties: the framework kinds stay closed, and an adapter contributes its own kind
 * without a core release.
 *
 * <p>A subclass returns a namespaced {@link #type()} discriminator so two adapters cannot claim the
 * same name, keeps {@link #additionalProperties()} to JSON-safe values, and puts the originating
 * SDK object in {@link #rawRepresentation()}, which is a transient diagnostic handle rather than
 * persistable state.
 *
 * <p>Session persistence covers the framework kinds only. A snapshot that contains extension
 * content fails explicitly until a content codec for that kind is registered, which is preferred
 * over restoring a different type than was saved.
 */
public abstract non-sealed class ExtensionContent extends Content {

  /**
   * Creates extension content.
   *
   * @param additionalProperties JSON-safe properties describing the payload, or {@code null}
   * @param rawRepresentation the originating adapter object, or {@code null}
   */
  protected ExtensionContent(Map<String, Object> additionalProperties, Object rawRepresentation) {
    super(additionalProperties, rawRepresentation);
  }
}
