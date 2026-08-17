/**
 * OpenAI Chat Completions model client for Agent Framework for Java.
 *
 * <p>{@link io.github.hellices.agentframework.openai.OpenAiChatModelClient} implements the neutral
 * {@code ModelClient} port over the official {@code com.openai:openai-java} Chat Completions async
 * API. It supports ordinary text responses and the function-tool loop. Streaming, the Responses
 * API, structured output, embeddings, and multimodal content are not supported here.
 *
 * <p>The SDK client is borrowed, never owned. The adapter never builds, configures, reconnects, or
 * closes it - {@code OpenAIClientAsync} declares a {@code close()} of its own that this package
 * never calls - because the client owns an HTTP dispatcher, a connection pool, and an executor that
 * the host created and the host must be free to shut down. Consequently this package allocates no
 * thread, no socket, and no shutdown hook, and discarding an adapter releases nothing.
 *
 * <p>Cancellation stops the framework from waiting; it does not abort an in-flight HTTP request.
 * The future the SDK returns is derived from its transport future, and a JDK {@code
 * CompletableFuture} never cancels its antecedent. A timeout is what bounds work the framework has
 * stopped waiting for, and it bounds one attempt: this package adds no retry, while the SDK client
 * the host injected retries inside a single call according to the host's own {@code maxRetries}.
 *
 * <p>Requirements for this package live in {@code docs/requirements/12-providers.md} and {@code
 * docs/requirements/01-agent-execution.md}.
 */
package io.github.hellices.agentframework.openai;
