package io.github.hellices.agentframework.engine.internal.context;

import io.github.hellices.agentframework.spi.session.ContextProvider;

/**
 * A context provider paired with the fixed source id read once when the agent was bound, so a
 * provider cannot change the session state namespace it owns between runs or hooks. The source id
 * is the state-key id of a {@link
 * io.github.hellices.agentframework.spi.session.StatefulContextProvider}, or {@code null} for a
 * stateless provider that owns no namespace.
 *
 * @param sourceId the provider's fixed source id, or {@code null} for a stateless provider
 * @param provider the bound context provider
 */
public record ProviderBinding(String sourceId, ContextProvider provider) {}
