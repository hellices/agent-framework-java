package io.github.hellices.agentframework.spi.interception;

/**
 * Intercepts one agent execution before finalization.
 *
 * <p>The returned {@link AgentExecution} carries only the update stream and cancellation signal.
 * The engine derives the final {@code AgentResponse}, runs post-stream lifecycle work, and persists
 * the session exactly once after the interceptor chain returns.
 *
 * <p>An interceptor either short-circuits or proceeds, and the two are mutually exclusive. To
 * short-circuit, it returns an execution built directly from its own updates (for example {@link
 * AgentExecution#fromUpdate}) <em>without</em> calling {@link AgentInvocationChain#proceed}: the
 * engine then performs no model, tool, context-provider, session-load, or session-save work. To
 * proceed, it calls {@code proceed}, which lazily builds the run's pipeline, and must return
 * updates that <em>consume</em> that proceeded execution — either the returned execution itself or
 * one derived from it through {@link AgentExecution#mapUpdates}. Consuming the proceeded updates is
 * what drives the pipeline to produce the run's response and advance its lifecycle. An interceptor
 * that calls {@code proceed} but then returns updates that never consume the proceeded execution
 * abandons a pipeline the engine cannot finalize; the engine fails such a run explicitly,
 * identically for the ordinary and streaming shapes, and persists nothing. A genuine pre-pipeline
 * replacement must short-circuit — it must not call {@code proceed} at all.
 */
public interface AgentExecutionInterceptor {

  AgentExecution intercept(AgentInvocation invocation, AgentInvocationChain next);
}
