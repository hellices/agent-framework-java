/**
 * Model Context Protocol client integration for Agent Framework for Java.
 *
 * <p>This package offers two ownership models. {@link
 * io.github.hellices.agentframework.mcp.ConnectedMcpClientAdapter} adapts an already connected MCP
 * client and borrows it: it never opens, initializes, reconnects, or closes one, because the
 * application or host that built the client owns its lifecycle. {@link
 * io.github.hellices.agentframework.mcp.McpStdioTools} and {@link
 * io.github.hellices.agentframework.mcp.McpStreamableHttpTools} own the connection instead: each
 * builds the transport, drives the handshake, replaces a connection it has lost at most once, and
 * closes what it created. Even then nothing happens implicitly, because opening the connection is
 * always an explicit call. Prompts, resources, sampling, MCP tasks, and streamable HTTP request
 * headers are deliberately absent and are separate requirement slices.
 *
 * <p>Requirements for this package live in {@code docs/requirements/05-mcp.md}, and the design that
 * governs discovery and invocation lives in {@code
 * docs/design/requirements-design/02-state-extension-mcp.md}.
 */
package io.github.hellices.agentframework.mcp;
