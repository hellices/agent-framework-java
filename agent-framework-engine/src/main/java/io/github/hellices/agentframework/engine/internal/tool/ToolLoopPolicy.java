package io.github.hellices.agentframework.engine.internal.tool;

import io.github.hellices.agentframework.api.agent.AgentRunRequest;
import io.github.hellices.agentframework.api.context.ContextAttributes;
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
import io.github.hellices.agentframework.api.value.JsonObject;
import io.github.hellices.agentframework.spi.model.ModelRequest;
import io.github.hellices.agentframework.spi.model.ModelResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
   * @param definitions every tool the agent declares, in declaration order; all of them are offered
   *     to the model (TOOL-006) even when no local body is bound to them
   * @param tools the agent's executable function tools, indexed by name; a duplicate name is
   *     rejected here rather than silently shadowing a tool at call time. A declaration without a
   *     matching executable tool is declaration-only: it is offered to the model but cannot be run
   *     locally, so {@link #canExecuteAll(List)} refuses to execute a batch that invokes it.
   * @param maxIterations the agent's iteration budget, counting model calls
   */
  public ToolLoopPolicy(
      List<ToolDefinition> definitions, List<FunctionTool> tools, int maxIterations) {
    this.definitions =
        List.copyOf(Objects.requireNonNull(definitions, "definitions must not be null"));
    Map<String, FunctionTool> indexed = new LinkedHashMap<>();
    for (FunctionTool tool : Objects.requireNonNull(tools, "tools must not be null")) {
      String toolName = tool.definition().name();
      if (indexed.putIfAbsent(toolName, tool) != null) {
        throw new IllegalArgumentException("duplicate tool name: " + toolName);
      }
    }
    this.tools = Map.copyOf(indexed);
    this.maxIterations = maxIterations;
  }

  /**
   * Whether this agent offers any tool to the model at all.
   *
   * <p>Measured over declarations rather than executable bodies, so an agent that declares only
   * declaration-only tools still runs the tool loop: the model is offered those tools, and a call
   * to one of them is detected and ends the run rather than being silently ignored.
   */
  public boolean hasTools() {
    return !definitions.isEmpty();
  }

  /**
   * Whether every call in {@code calls} has a local body this policy can run.
   *
   * <p>True only when each call names an executable tool. A declaration-only call — a call to a
   * tool that is declared and offered to the model but has no bound body — makes this false, which
   * is how both loops decide to end the run with the model's response instead of fabricating a
   * result for a tool the Java core does not implement (TOOL-006).
   */
  public boolean canExecuteAll(List<ToolCallContent> calls) {
    for (ToolCallContent call : calls) {
      if (!tools.containsKey(call.name())) {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether every call in {@code calls} names a declared tool — one this agent offered to the
   * model.
   *
   * <p>This is what separates a declaration-only call, which the model was invited to make and
   * which ends the run without local execution (TOOL-006), from a call to a tool that was never
   * declared and so never offered. The latter is a broken response, so the loops let it reach
   * execution and fail with the existing safe error rather than ending the run on it.
   */
  public boolean declaresAll(List<ToolCallContent> calls) {
    for (ToolCallContent call : calls) {
      if (!isDeclared(call.name())) {
        return false;
      }
    }
    return true;
  }

  private boolean isDeclared(String name) {
    for (ToolDefinition definition : definitions) {
      if (definition.name().equals(name)) {
        return true;
      }
    }
    return false;
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
    return new ToolCallContent(
        accumulated.callId(),
        accumulated.name(),
        mergeObjects(accumulated.arguments(), fragment.arguments()),
        mergeObjects(accumulated.additionalProperties(), fragment.additionalProperties()),
        fragment.rawRepresentation() == null
            ? accumulated.rawRepresentation()
            : fragment.rawRepresentation());
  }

  /**
   * How one bound tool call is actually run. The default runs the tool handler directly; the engine
   * supplies an implementation that routes each executed bound call through the tool interceptor
   * seam, so a chain observes or replaces exactly the calls this policy would have run.
   */
  @FunctionalInterface
  public interface BoundToolInvoker {
    CompletionStage<ToolResult> invoke(
        FunctionTool tool, ToolCallContent call, ToolContext context);
  }

  private static final BoundToolInvoker DIRECT_INVOKER =
      (tool, call, context) -> tool.execute(ToolArguments.of(call.arguments()), context);

  /**
   * Executes {@code calls} one after another and completes with their results in call order,
   * running each bound call directly.
   */
  public CompletionStage<List<Content>> executeToolCalls(
      List<ToolCallContent> calls, AgentRunRequest request) {
    return executeToolCalls(calls, request, DIRECT_INVOKER);
  }

  /**
   * Executes {@code calls} one after another and completes with their results in call order.
   *
   * <p>Sequential execution is what makes the result order a property of the request rather than of
   * how fast each handler happens to complete, and it lets a cancellation observed between two
   * calls stop the remaining ones. Each bound call is run through {@code invoker}, so the engine
   * can route exactly the calls that are actually executed through the tool interceptor seam
   * without this policy owning the seam or its state.
   */
  public CompletionStage<List<Content>> executeToolCalls(
      List<ToolCallContent> calls, AgentRunRequest request, BoundToolInvoker invoker) {
    return executeToolCalls(
        calls, request, Objects.requireNonNull(invoker, "invoker must not be null"), 0, List.of());
  }

  private CompletionStage<List<Content>> executeToolCalls(
      List<ToolCallContent> calls,
      AgentRunRequest request,
      BoundToolInvoker invoker,
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
            invoker.invoke(
                tool,
                call,
                new ToolContext(request.cancellationSignal(), effectiveAttributes(request))),
            "tool handler response stage must not be null");
    return resultStage.thenCompose(
        result -> {
          List<Content> nextResults = new ArrayList<>(accumulatedResults);
          nextResults.add(
              new ToolResultContent(call.callId(), call.name(), result.content(), result.error()));
          return executeToolCalls(calls, request, invoker, index + 1, List.copyOf(nextResults));
        });
  }

  /** The single tool message one round of tool results is reported to the model as. */
  public static Message toolResultMessage(List<Content> results) {
    return new Message(Role.TOOL, results);
  }

  private static ContextAttributes effectiveAttributes(AgentRunRequest request) {
    return request.options().attributes().merge(request.attributes());
  }

  private static JsonObject mergeObjects(JsonObject accumulated, JsonObject fragment) {
    if (accumulated.isEmpty()) {
      return fragment;
    }
    if (fragment.isEmpty()) {
      return accumulated;
    }
    Map<String, io.github.hellices.agentframework.api.value.JsonValue> merged =
        new LinkedHashMap<>(accumulated.values());
    fragment.values().forEach(merged::put);
    JsonObject.Builder builder = JsonObject.builder();
    merged.forEach(builder::put);
    return builder.build();
  }

  /**
   * Builds the request of the iteration after {@code iteration}: the request that was just
   * answered, followed by the model's own messages and by the tool results, with the tools the
   * remaining budget still permits.
   *
   * <p>The model's messages are echoed as the calls that were actually executed rather than as they
   * arrived, because {@code executedCalls} is the merge of what a streamed response may have
   * reported in fragments. Echoing the fragments would send the next model call one tool call per
   * fragment while the tool message answers each call id once, which providers reject as a call
   * without a result. See {@link #echoedMessages(List, List)} for what the rewrite preserves.
   *
   * @param executedCalls the merged calls of {@link #toolCalls(ModelResponse)} that were executed,
   *     which is also what {@code toolResultMessage} reports results for
   */
  public ModelRequest nextRequest(
      ModelRequest current,
      List<Message> responseMessages,
      List<ToolCallContent> executedCalls,
      Message toolResultMessage,
      int iteration) {
    List<Message> messages = new ArrayList<>(current.messages());
    messages.addAll(echoedMessages(responseMessages, executedCalls));
    messages.add(toolResultMessage);
    return current.toBuilder().messages(messages).tools(toolsForIteration(iteration + 1)).build();
  }

  /**
   * The model's own messages as the next request echoes them: each call id appears once, as the
   * merged call that was executed, where its first fragment appeared.
   *
   * <p>Everything else is left alone — non-tool content keeps its place and order, a message no
   * split call touched is the same instance the response carried, and a call id that was not
   * executed is echoed unchanged rather than dropped. A message that consisted only of fragments of
   * a call already echoed earlier has nothing left to say and is dropped, because an empty
   * assistant message is not what the model produced.
   */
  private static List<Message> echoedMessages(
      List<Message> responseMessages, List<ToolCallContent> executedCalls) {
    Map<String, ToolCallContent> merged = new LinkedHashMap<>();
    for (ToolCallContent call : executedCalls) {
      merged.put(call.callId(), call);
    }
    Map<String, Integer> fragmentsPerCall = fragmentsPerCall(responseMessages, merged);
    if (fragmentsPerCall.values().stream().noneMatch(fragments -> fragments > 1)) {
      return List.copyOf(responseMessages);
    }
    Set<String> echoedCallIds = new LinkedHashSet<>();
    List<Message> echoed = new ArrayList<>();
    for (Message message : responseMessages) {
      List<Content> content = new ArrayList<>();
      boolean rewritten = false;
      for (Content item : message.content()) {
        ToolCallContent replacement =
            item instanceof ToolCallContent call ? merged.get(call.callId()) : null;
        if (replacement == null) {
          content.add(item);
          continue;
        }
        rewritten = rewritten || fragmentsPerCall.get(replacement.callId()) > 1;
        if (echoedCallIds.add(replacement.callId())) {
          content.add(replacement);
        }
      }
      if (!rewritten) {
        echoed.add(message);
      } else if (!content.isEmpty()) {
        echoed.add(
            new Message(
                message.role(),
                content,
                message.attribution(),
                message.additionalProperties(),
                message.rawRepresentation()));
      }
    }
    return List.copyOf(echoed);
  }

  /** How many fragments each executed call id was reported in; one when it was not split. */
  private static Map<String, Integer> fragmentsPerCall(
      List<Message> responseMessages, Map<String, ToolCallContent> merged) {
    Map<String, Integer> fragments = new LinkedHashMap<>();
    for (Message message : responseMessages) {
      for (Content item : message.content()) {
        if (item instanceof ToolCallContent call && merged.containsKey(call.callId())) {
          fragments.merge(call.callId(), 1, Integer::sum);
        }
      }
    }
    return fragments;
  }
}
