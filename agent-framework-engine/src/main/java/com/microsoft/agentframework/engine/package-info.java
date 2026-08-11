/**
 * Embedded agent execution engine.
 *
 * <p>The engine owns run and turn state transitions, the tool invocation loop, and session state
 * change rules. It never owns dependency injection, thread pools, servers, configuration loading,
 * transactions, or retry policy; a host runtime supplies those.
 *
 * <p>Requirements for this package live in {@code docs/requirements/01-agent-execution.md}, {@code
 * docs/requirements/04-tools.md}, and {@code docs/requirements/07-interceptors.md}.
 */
package com.microsoft.agentframework.engine;
