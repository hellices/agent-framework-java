package io.github.hellices.agentframework.engine.internal.context;

import io.github.hellices.agentframework.api.agent.AgentDefinition;
import io.github.hellices.agentframework.api.agent.RunContribution;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.tool.ToolDefinition;
import io.github.hellices.agentframework.spi.model.ModelRequestOptions;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Merges an agent's declaration with the ordered {@link RunContribution}s a run's context providers
 * prepared into the single effective request the run's first model call is built from, computed
 * once and immutable thereafter.
 *
 * <p>The merge is deterministic and provider-order aware:
 *
 * <ul>
 *   <li><b>instructions</b> — the definition's instructions (when non-blank) come first, then each
 *       provider's instruction additions in registration order, each rendered as a leading {@link
 *       Role#SYSTEM} {@link TextContent} message. A candidate that exactly duplicates the role and
 *       text of the message it would lead is skipped (MSG-009), so an instruction a provider
 *       repeats is not prepended twice.
 *   <li><b>tools</b> — the definition's tool declarations come first, then each provider's
 *       contributed declarations in registration order; a name that duplicates any earlier
 *       declaration fails here, before the model is called. Contributed tools are declaration-only:
 *       they are offered to the model but carry no executable body, so this merger produces only
 *       the declaration list the effective {@link
 *       io.github.hellices.agentframework.engine.internal.tool.ToolLoopPolicy} offers, never a new
 *       executable binding.
 *   <li><b>options</b> — the definition defaults are merged with each provider's options in
 *       registration order, so a later provider overrides an earlier one by typed option class.
 * </ul>
 *
 * <p>Provider-contributed messages are not merged here: they are folded into the run's {@link
 * io.github.hellices.agentframework.api.session.SessionContext} by {@link ContextProviderPipeline}
 * as each hook runs, which stamps provider attribution. {@link #assembleMessages(List, List)}
 * combines those already-attributed context messages, the caller's input, and the deduplicated
 * leading instruction messages into the final ordered request message list.
 */
public final class RunContributionMerger {

  private final List<Message> instructionMessages;
  private final List<ToolDefinition> toolDeclarations;
  private final ModelRequestOptions options;

  private RunContributionMerger(
      List<Message> instructionMessages,
      List<ToolDefinition> toolDeclarations,
      ModelRequestOptions options) {
    this.instructionMessages = List.copyOf(instructionMessages);
    this.toolDeclarations = List.copyOf(toolDeclarations);
    this.options = options;
  }

  /**
   * Merges {@code definition} with the ordered {@code contributions}.
   *
   * @param definition the agent's declaration, supplying leading instructions, tool declarations,
   *     and the option defaults contributions merge over
   * @param contributions the run's context contributions in provider registration order
   * @throws IllegalStateException if a contributed tool name duplicates any earlier declaration
   */
  public static RunContributionMerger merge(
      AgentDefinition definition, List<RunContribution> contributions) {
    Objects.requireNonNull(definition, "definition must not be null");
    Objects.requireNonNull(contributions, "contributions must not be null");
    return new RunContributionMerger(
        mergeInstructionMessages(definition, contributions),
        mergeToolDeclarations(definition, contributions),
        mergeOptions(contributions));
  }

  /** The effective, definition-plus-contributed tool declarations offered to the model. */
  public List<ToolDefinition> toolDeclarations() {
    return toolDeclarations;
  }

  /** The effective model request options, definition defaults merged with contributed options. */
  public ModelRequestOptions options() {
    return options;
  }

  /**
   * Assembles the effective request message list: the deduplicated leading instruction messages,
   * then the run's provider-contributed context messages, then the caller's input.
   *
   * @param contextMessages the run's accumulated, already-attributed context messages
   * @param inputMessages the caller's input messages
   */
  public List<Message> assembleMessages(
      List<Message> contextMessages, List<Message> inputMessages) {
    Objects.requireNonNull(contextMessages, "contextMessages must not be null");
    Objects.requireNonNull(inputMessages, "inputMessages must not be null");
    List<Message> tail = new ArrayList<>(contextMessages);
    tail.addAll(inputMessages);
    List<Message> assembled = new ArrayList<>(instructionMessages.size() + tail.size());
    for (Message instruction : instructionMessages) {
      Message leading =
          assembled.isEmpty()
              ? (tail.isEmpty() ? null : tail.get(0))
              : assembled.get(assembled.size() - 1);
      if (leading != null && sameRoleAndText(leading, instruction)) {
        // MSG-009: a leading instruction with the same role and text already exists.
        continue;
      }
      assembled.add(instruction);
    }
    assembled.addAll(tail);
    return List.copyOf(assembled);
  }

  private static List<Message> mergeInstructionMessages(
      AgentDefinition definition, List<RunContribution> contributions) {
    List<Message> messages = new ArrayList<>();
    String instructions = definition.instructions();
    if (instructions != null && !instructions.isBlank()) {
      messages.add(systemMessage(instructions));
    }
    for (RunContribution contribution : contributions) {
      for (String addition : contribution.instructionAdditions()) {
        messages.add(systemMessage(addition));
      }
    }
    return messages;
  }

  private static List<ToolDefinition> mergeToolDeclarations(
      AgentDefinition definition, List<RunContribution> contributions) {
    List<ToolDefinition> declarations = new ArrayList<>(definition.tools());
    Set<String> names = new LinkedHashSet<>();
    for (ToolDefinition declared : declarations) {
      names.add(declared.name());
    }
    for (RunContribution contribution : contributions) {
      for (ToolDefinition contributed : contribution.tools()) {
        if (!names.add(contributed.name())) {
          throw new IllegalStateException("duplicate contributed tool name: " + contributed.name());
        }
        declarations.add(contributed);
      }
    }
    return declarations;
  }

  private static ModelRequestOptions mergeOptions(List<RunContribution> contributions) {
    ModelRequestOptions merged = ModelRequestOptions.empty();
    for (RunContribution contribution : contributions) {
      merged = merged.merge(contribution.modelOptions());
    }
    return merged;
  }

  private static Message systemMessage(String text) {
    return new Message(Role.SYSTEM, List.of(new TextContent(text)));
  }

  private static boolean sameRoleAndText(Message left, Message right) {
    return left.role().equals(right.role()) && left.text().equals(right.text());
  }
}
