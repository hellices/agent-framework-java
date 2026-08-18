package io.github.hellices.agentframework.api.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.message.ToolApprovalRequestContent;
import io.github.hellices.agentframework.api.message.ToolApprovalResponseContent;
import io.github.hellices.agentframework.api.message.ToolCallContent;
import io.github.hellices.agentframework.api.value.JsonObject;
import io.github.hellices.agentframework.api.value.JsonValues;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * TOOL-016/TOOL-017/TOOL-018/TOOL-019 content contracts: approval requests and responses are
 * core-owned {@code Content} values with stable, exact-preserving equality, a response binds only
 * to the request it names, and a denial normalizes to a stable {@code approved=false} value.
 */
class ToolApprovalContractTest {

  @Test
  void requestContentPreservesExactArgumentsAndHostBoundary() {
    ToolApprovalRequestContent request =
        new ToolApprovalRequestContent(
            "req-1",
            "call-1",
            "weather",
            jsonObject(Map.of("city", "Seoul", "unit", "celsius")),
            "mcp-server-a",
            jsonObject(Map.of("provider", "fake")),
            "raw-request");

    assertThat(request.type()).isEqualTo("tool_approval_request");
    assertThat(request.requestId()).isEqualTo("req-1");
    assertThat(request.toolCallId()).isEqualTo("call-1");
    assertThat(request.toolName()).isEqualTo("weather");
    assertThat(request.arguments())
        .isEqualTo(jsonObject(Map.of("city", "Seoul", "unit", "celsius")));
    assertThat(request.hostBoundary()).contains("mcp-server-a");
    assertThat(request.additionalProperties()).isEqualTo(jsonObject(Map.of("provider", "fake")));
    assertThat(request.rawRepresentation()).isEqualTo("raw-request");
  }

  @Test
  void requestContentTreatsAnAbsentHostBoundaryAsEmpty() {
    ToolApprovalRequestContent request =
        new ToolApprovalRequestContent("req-1", "call-1", "weather", JsonObject.empty(), null);

    assertThat(request.hostBoundary()).isEmpty();
    assertThat(request.arguments()).isEqualTo(JsonObject.empty());
  }

  @Test
  void requestContentRoundTripsStablyThroughEqualsAndHashCode() {
    ToolApprovalRequestContent first =
        new ToolApprovalRequestContent(
            "req-1", "call-1", "weather", jsonObject(Map.of("city", "Seoul")), "mcp-server-a");
    ToolApprovalRequestContent second =
        new ToolApprovalRequestContent(
            "req-1", "call-1", "weather", jsonObject(Map.of("city", "Seoul")), "mcp-server-a");
    ToolApprovalRequestContent differentHostBoundary =
        new ToolApprovalRequestContent(
            "req-1", "call-1", "weather", jsonObject(Map.of("city", "Seoul")), "mcp-server-b");

    assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
    assertThat(first).isNotEqualTo(differentHostBoundary);
  }

  @Test
  void requestIdentifiersMustNotBeBlank() {
    assertThatThrownBy(
            () ->
                new ToolApprovalRequestContent(" ", "call-1", "weather", JsonObject.empty(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("requestId must not be blank");
    assertThatThrownBy(
            () -> new ToolApprovalRequestContent("req-1", " ", "weather", JsonObject.empty(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("toolCallId must not be blank");
    assertThatThrownBy(
            () -> new ToolApprovalRequestContent("req-1", "call-1", " ", JsonObject.empty(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("tool name must not be blank");
  }

  @Test
  void responseMatchesOnlyItsOriginalRequest() {
    ToolApprovalRequestContent request =
        new ToolApprovalRequestContent("req-1", "call-1", "weather", JsonObject.empty(), null);
    ToolApprovalResponseContent matchingResponse = ToolApprovalResponseContent.approve("req-1");
    ToolApprovalResponseContent wrongRequestResponse = ToolApprovalResponseContent.approve("req-2");

    assertThat(request.isResponseTo(matchingResponse)).isTrue();
    assertThat(request.isResponseTo(wrongRequestResponse)).isFalse();
  }

  @Test
  void denialNormalizesToAStableFalseApprovedValue() {
    ToolApprovalResponseContent denied = ToolApprovalResponseContent.deny("req-1");
    ToolApprovalResponseContent approved = ToolApprovalResponseContent.approve("req-1");

    assertThat(denied.type()).isEqualTo("tool_approval_response");
    assertThat(denied.requestId()).isEqualTo("req-1");
    assertThat(denied.approved()).isFalse();
    assertThat(denied).isEqualTo(new ToolApprovalResponseContent("req-1", false));
    assertThat(approved.approved()).isTrue();
    assertThat(denied).isNotEqualTo(approved);
  }

  @Test
  void responseIdentifierMustNotBeBlank() {
    assertThatThrownBy(() -> new ToolApprovalResponseContent(" ", true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("requestId must not be blank");
  }

  @Test
  void approvalContextCarriesToolNameArgumentsAndHostBoundaryForPolicyMatching() {
    ToolApprovalContext context =
        new ToolApprovalContext("weather", jsonObject(Map.of("city", "Seoul")), "mcp-server-a");

    assertThat(context.toolName()).isEqualTo("weather");
    assertThat(context.arguments()).isEqualTo(jsonObject(Map.of("city", "Seoul")));
    assertThat(context.hostBoundary()).contains("mcp-server-a");
  }

  @Test
  void approvalPolicyProducesTheThreeDefinedDecisions() {
    ToolApprovalPolicy alwaysRequireApproval = context -> ToolApprovalDecision.REQUIRE_APPROVAL;
    ToolApprovalPolicy alwaysApprove = context -> ToolApprovalDecision.APPROVE;
    ToolApprovalPolicy alwaysDeny = context -> ToolApprovalDecision.DENY;
    ToolApprovalContext context = new ToolApprovalContext("weather", JsonObject.empty(), null);

    assertThat(alwaysRequireApproval.evaluate(context))
        .isEqualTo(ToolApprovalDecision.REQUIRE_APPROVAL);
    assertThat(alwaysApprove.evaluate(context)).isEqualTo(ToolApprovalDecision.APPROVE);
    assertThat(alwaysDeny.evaluate(context)).isEqualTo(ToolApprovalDecision.DENY);
  }

  @Test
  void aToolWideRuleMatchesAnyArgumentsWhileAnExactRuleMatchesOnlyItsOwn() {
    ToolApprovalRule toolWide = ToolApprovalRule.forTool("weather");
    ToolApprovalRule exact =
        ToolApprovalRule.forArguments("weather", jsonObject(Map.of("city", "Seoul")));
    ToolApprovalRule noArguments = ToolApprovalRule.forArguments("weather", JsonObject.empty());

    ToolApprovalContext seoul =
        new ToolApprovalContext("weather", jsonObject(Map.of("city", "Seoul")), null);
    ToolApprovalContext withoutArguments =
        new ToolApprovalContext("weather", JsonObject.empty(), null);

    assertThat(toolWide.matches(seoul)).isTrue();
    assertThat(toolWide.matches(withoutArguments)).isTrue();
    assertThat(exact.matches(seoul)).isTrue();
    assertThat(exact.matches(withoutArguments)).isFalse();
    assertThat(noArguments.matches(seoul)).isFalse();
    assertThat(noArguments.matches(withoutArguments)).isTrue();
    assertThat(toolWide.arguments()).isEmpty();
    assertThat(noArguments.arguments()).contains(JsonObject.empty());
  }

  @Test
  void aRuleNeverCrossesToolNamesOrHostBoundaries() {
    ToolApprovalRule onHostA = ToolApprovalRule.forTool("weather", "mcp-server-a");

    assertThat(
            onHostA.matches(new ToolApprovalContext("weather", JsonObject.empty(), "mcp-server-a")))
        .isTrue();
    assertThat(
            onHostA.matches(new ToolApprovalContext("weather", JsonObject.empty(), "mcp-server-b")))
        .isFalse();
    assertThat(onHostA.matches(new ToolApprovalContext("weather", JsonObject.empty(), null)))
        .isFalse();
    assertThat(
            onHostA.matches(new ToolApprovalContext("search", JsonObject.empty(), "mcp-server-a")))
        .isFalse();
    assertThat(
            ToolApprovalRule.forTool("weather")
                .matches(new ToolApprovalContext("weather", JsonObject.empty(), "mcp-server-a")))
        .isFalse();
  }

  @Test
  void settingsRequireApprovalByDefaultAndConsultThePolicyOnlyWithoutAStandingRule() {
    ToolApprovalContext context = new ToolApprovalContext("weather", JsonObject.empty(), null);
    assertThat(ToolApprovalSettings.builder().build().evaluate(context))
        .isEqualTo(ToolApprovalDecision.REQUIRE_APPROVAL);

    int[] evaluations = new int[1];
    ToolApprovalSettings settings =
        ToolApprovalSettings.builder()
            .standingApproval(ToolApprovalRule.forTool("weather"))
            .policy(
                ignored -> {
                  evaluations[0]++;
                  return ToolApprovalDecision.DENY;
                })
            .build();

    assertThat(settings.evaluate(context)).isEqualTo(ToolApprovalDecision.APPROVE);
    assertThat(evaluations[0]).isZero();
    assertThat(settings.evaluate(new ToolApprovalContext("search", JsonObject.empty(), null)))
        .isEqualTo(ToolApprovalDecision.DENY);
    assertThat(evaluations[0]).isEqualTo(1);
  }

  @Test
  void settingsCarryABoundedAutomaticApprovalAllowanceAndAHostBoundarySeam() {
    assertThat(ToolApprovalSettings.builder().build().maxAutomaticApprovals())
        .isEqualTo(ToolApprovalSettings.DEFAULT_MAX_AUTOMATIC_APPROVALS);
    assertThat(
            ToolApprovalSettings.builder().maxAutomaticApprovals(0).build().maxAutomaticApprovals())
        .isZero();
    assertThatThrownBy(() -> ToolApprovalSettings.builder().maxAutomaticApprovals(-1))
        .isInstanceOf(IllegalArgumentException.class);

    ToolCallContent call = new ToolCallContent("call-1", "weather", JsonObject.empty());
    assertThat(ToolApprovalSettings.builder().build().hostBoundary(call)).isNull();
    assertThat(
            ToolApprovalSettings.builder()
                .hostBoundaryResolver(ignored -> "mcp-server-a")
                .build()
                .hostBoundary(call))
        .isEqualTo("mcp-server-a");
  }

  private static JsonObject jsonObject(Map<String, ?> values) {
    return values.isEmpty() ? JsonObject.empty() : (JsonObject) JsonValues.fromJava(values);
  }
}
