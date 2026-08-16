/**
 * Model Context Protocol client integration for Agent Framework for Java.
 *
 * <p>This package exposes MCP server tools as framework tools under two ownership models.
 *
 * <p>{@link io.github.hellices.agentframework.mcp.ConnectedMcpClientAdapter} borrows an already
 * connected client: it never opens, initializes, reconnects, or closes one, because the application
 * or host that built the client owns its lifecycle. That stays true when several adapters share one
 * client, so borrowing never ends a session another holder is still using.
 *
 * <p>{@link io.github.hellices.agentframework.mcp.McpStdioTools} and {@link
 * io.github.hellices.agentframework.mcp.McpStreamableHttpTools} own their connection: each builds
 * the transport and client, drives the handshake on an explicit {@code connect()}, validates the
 * session before each call, replaces it at most once when the server has forgotten it, and releases
 * it on {@code close()}. They never connect implicitly, so a call before {@code connect()} or after
 * {@code close()} fails instead of quietly starting a process or opening a socket.
 *
 * <p>WebSocket transports, prompts, resources, sampling, MCP tasks, request headers, and trace
 * propagation are deliberately absent and are separate requirement slices.
 *
 * <p>Requirements for this package live in {@code docs/requirements/05-mcp.md}, and the design that
 * governs discovery and invocation lives in {@code
 * docs/design/requirements-design/02-state-extension-mcp.md}.
 */
package io.github.hellices.agentframework.mcp;
