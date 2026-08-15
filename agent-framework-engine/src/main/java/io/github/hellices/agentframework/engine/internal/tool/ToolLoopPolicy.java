package io.github.hellices.agentframework.engine.internal.tool;

import io.github.hellices.agentframework.api.agent.AgentRunRequest;
import io.github.hellices.agentframework.api.message.Content;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.api.message.ToolResultContent;
import io.github.hellices.agentframework.api.tool.FunctionTool;
import io.github.hellices.agentframework.api.tool.ToolArguments;
import io.github.hellices.agentframework.api.tool.ToolContext;
import io.github.hellices.agentframework.api.tool.ToolDefinition;
import io.github.hellices.agentframework.api.tool.ToolResult;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * The single interpretation of an agent's function tool budget, shared by the ordinary run loop and
 * the streaming one (TOOL-015).
 *
 * <p>Both loops ask the same three questions per iteration — which tools may the next request
 * offer, is another tool round still within budget, and what does the request after a tool round
 * look like — so keeping the answers here is what makes an ordinary run and a streaming run of the
 * same agent agree on iteration count, on when tools stop being offered, and on the failure a model
 * that keeps calling tools past that point produces. A second copy of these rules in the streaming
 * path could drift by one iteration without any test of either path failing.
 *
 * <p>The instance is immutable and carries no per-run state, so one policy serves every concurrent
 * run of its agent; the loop state (which iteration a run is on) belongs to the loop.
 */
public final class ToolLoopPolicy {

  private final Map<String, FunctionTool> tools;
  private final List<ToolDefinition> definitions;
  private final int maxIterations;

  /**
   * @param tools the agent's function tools, indexed by name; a duplicate name is rejected here
   *     rather than silently shadowing a tool at call time
   * @param maxIterations the agent's iteration budget, counting model calls
   */
  public ToolLoopPolicy(List<FunctionTool> tools, int maxIterations) {
    Map<String, FunctionTool> indexed = new LinkedHashMap<>();
    for (FunctionTool tool : Objects.requireNonNull(tools, "tools must not be null")) {
      String toolName = tool.definition().name();
      if (indexed.putIfAbsent(toolName, tool) != null) {
        throw new IllegalArgumentException("duplicate tool name: " + toolName);
      }
    }
    this.tools = Map.copyOf(indexed);
    List<ToolDefinition> resolved = new ArrayList<>();
    indexed.values().forEach(tool -> resolved.add(tool.definition()));
    this.definitions = List.copyOf(resolved);
    this.maxIterations = maxIterations;
  }

  /** Whether this agent can execute tools at all. */
  public boolean hasTools() {
    return !tools.isEmpty();
  }

  /**
   * The tool definitions the model request of {@code iteration} may offer.
   *
   * <p>Tools are withheld from the last request the budget permits: answering a tool call issued
   * there would need an iteration that does not exist, so the model is told up front that no tool
   * is available instead of being failed afterwards.
   *
   * @param iteration the zero-based index of the model call the request belongs to
   */
  public List<ToolDefinition> toolsForIteration(int iteration) {
    return iteration + 1 < maxIterations ? definitions : List.of();
  }

  /**
   * Fails when the model of {@code iteration} returned tool calls although {@link
   * #toolsForIteration(int)} withheld the tools from that request.
   */
  public void requireIterationBudget(int iteration) {
    if (iteration + 1 >= maxIterations) {
      throw new IllegalStateException("model returned tool calls after tools were disabled");
    }
  }

  /**
   * Rejects a model response that asks to be resumed while tools are configured: resuming would
   * have to replay the tool loop's state, which no continuation token carries.
   */
  public void validateContinuation(ModelResponse response) {
    if (hasTools() && response.continuationToken() != null) {
      throw new UnsupportedOperationException(
          "model continuation with tool execution is not supported");
    }
  }

  /**
   * The tool calls a model response asks for, in the order the model first mentioned each of them.
   *
   * <p>A streamed model call may report one call in fragments — an id and a name first, then its
   * arguments, or arguments a token at a time — and {@link
   * io.github.hellices.agentframework.api.agent.AgentResponse#fromUpdates} concatenates the content
   * of those updates without merging it. Executing that content as-is would run one call's handler
   * once per fragment and report several results under one call id, so the fragments of a call id
   * are merged here, where both loops read them.
   *
   * <p>Merging is defined so that a later fragment refines an earlier one: arguments and the
   * provider's own properties are merged in arrival order with the later value winning, and the raw
   * handle is the last one a fragment carried. A call id that arrives under two tool names is not a
   * fragmented call but a broken response, and fails the run rather than picking a name.
   */
  public static List<ToolCallContent> toolCalls(ModelResponse response) {
    Map<String, ToolCallContent> calls = new LinkedHashMap<>();
    for (Message message : response.messages()) {
      for (Content content : message.content()) {
        if (content instanceof ToolCallContent call) {
          calls.merge(call.callId(), call, ToolLoopPolicy::mergeToolCall);
        }
      }
    }
    return List.copyOf(calls.values());
  }

  private static ToolCallContent mergeToolCall(
      ToolCallContent accumulated, ToolCallContent fragment) {
    if (!accumulated.name().equals(fragment.name())) {
      throw new IllegalStateException(
          "tool call "
              + accumulated.callId()
              + " was reported as both '"
              + accumulated.name()
              + "' and '"
              + fragment.name()
              + "'");
    }
    Map<String, Object> arguments = new LinkedHashMap<>(accumulated.arguments());
    arguments.putAll(fragment.arguments());
    Map<String, Object> additionalProperties =
        new LinkedHashMap<>(accumulated.additionalProperties());
    additionalProperties.putAll(fragment.additionalProperties());
    return new ToolCallContent(
        accumulated.callId(),
        accumulated.name(),
        arguments,
        additionalProperties,
        fragment.rawRepresentation() == null
            ? accumulated.rawRepresentation()
            : fragment.rawRepresentation());
  }

  /**
   * Executes {@code calls} one after another and completes with their results in call order.
   *
   * <p>Sequential execution is what makes the result order a property of the request rather than of
   * how fast each handler happens to complete, and it lets a cancellation observed between two
   * calls stop the remaining ones.
   */
  public CompletionStage<List<Content>> executeToolCalls(
      List<ToolCallContent> calls, AgentRunRequest request) {
    return executeToolCalls(calls, request, 0, List.of());
  }

  private CompletionStage<List<Content>> executeToolCalls(
      List<ToolCallContent> calls,
      AgentRunRequest request,
      int index,
      List<Content> accumulatedResults) {
    if (index >= calls.size()) {
      return CompletableFuture.completedFuture(accumulatedResults);
    }
    if (request.cancellationSignal().isCancelled()) {
      throw new CancellationException("run was cancelled");
    }
    ToolCallContent call = calls.get(index);
    FunctionTool tool = tools.get(call.name());
    if (tool == null) {
      throw new IllegalStateException("unknown tool call: " + call.name());
    }
    CompletionStage<ToolResult> resultStage =
        Objects.requireNonNull(
            tool.execute(
                new ToolArguments(call.arguments()),
                new ToolContext(request.cancellationSignal(), request.attributes())),
            "tool handler response stage must not be null");
    return resultStage.thenCompose(
        result -> {
          List<Content> nextResults = new ArrayList<>(accumulatedResults);
          nextResults.add(
              new ToolResultContent(call.callId(), call.name(), result.content(), result.error()));
          return executeToolCalls(calls, request, index + 1, List.copyOf(nextResults));
        });
  }

  /** The single tool message one round of tool results is reported to the model as. */
  public static Message toolResultMessage(List<Content> results) {
    return new Message(Role.TOOL, results);
  }

  /**
   * Builds the request of the iteration after {@code iteration}: the request that was just
   * answered, followed by the model's own messages and by the tool results, with the tools the
   * remaining budget still permits.
   */
  public ModelRequest nextRequest(
      ModelRequest current,
      List<Message> responseMessages,
      Message toolResultMessage,
      int iteration) {
    List<Message> messages = new ArrayList<>(current.messages());
    messages.addAll(responseMessages);
    messages.add(toolResultMessage);
    return new ModelRequest(
        messages,
        current.options(),
        current.cancellationSignal(),
        toolsForIteration(iteration + 1),
        current.metadata());
  }
}
