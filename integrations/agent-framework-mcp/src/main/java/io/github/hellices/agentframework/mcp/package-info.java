/**
 * Model Context Protocol client integration for Agent Framework for Java.
 *
 * <p>This package adapts an already connected MCP client into framework tools. It borrows the
 * client: it never opens, initializes, reconnects, or closes one, because the application or host
 * that built the client owns its lifecycle. Transport construction, reconnection, prompts,
 * resources, sampling, and MCP tasks are deliberately absent and are separate requirement slices.
 *
 * <p>Requirements for this package live in {@code docs/requirements/05-mcp.md}, and the design that
 * governs discovery and invocation lives in {@code
 * docs/design/requirements-design/02-state-extension-mcp.md}.
 */
package io.github.hellices.agentframework.mcp;
