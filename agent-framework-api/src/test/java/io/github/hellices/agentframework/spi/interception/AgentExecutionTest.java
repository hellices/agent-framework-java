package io.github.hellices.agentframework.spi.interception;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hellices.agentframework.api.agent.AgentResponseUpdate;
import io.github.hellices.agentframework.api.agent.CancellationSignal;
import io.github.hellices.agentframework.api.message.FinishReason;
import io.github.hellices.agentframework.api.message.Message;
import io.github.hellices.agentframework.api.message.Role;
import io.github.hellices.agentframework.api.message.TextContent;
import io.github.hellices.agentframework.api.value.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class AgentExecutionTest {

  @Test
  void thirdPartyInterceptorCanShortCircuitWithReplacementUpdates() {
    CancellationSignal signal = new CancellationSignal();
    AgentExecutionInterceptor interceptor =
        (invocation, next) -> AgentExecution.fromUpdate(update("short-circuit"), signal);
    AtomicBoolean downstreamCalled = new AtomicBoolean(false);

    AgentExecution execution =
        interceptor.intercept(
            invocation(),
            invocation -> {
              downstreamCalled.set(true);
              return AgentExecution.fromUpdate(update("downstream"), signal);
            });

    assertThat(consume(execution.updates()))
        .extracting(AgentResponseUpdate::text)
        .containsExactly("short-circuit");
    assertThat(execution.cancellationSignal()).isSameAs(signal);
    assertThat(downstreamCalled).isFalse();
  }

  @Test
  void thirdPartyInterceptorCanReplaceAndMapUpdatesWhileKeepingCancellationIdentity() {
    CancellationSignal signal = new CancellationSignal();
    AgentExecutionInterceptor interceptor =
        (invocation, next) ->
            next.proceed(invocation)
                .mapUpdates(
                    update ->
                        update.toBuilder()
                            .messages(
                                List.of(
                                    new Message(
                                        Role.ASSISTANT,
                                        List.of(new TextContent(update.text().toUpperCase())))))
                            .build());

    AgentExecution execution =
        interceptor.intercept(
            invocation(),
            invocation ->
                AgentExecution.fromUpdates(
                    subscriber -> {
                      subscriber.onSubscribe(subscription());
                      subscriber.onNext(update("first"));
                      subscriber.onNext(update("second"));
                      subscriber.onComplete();
                    },
                    signal));

    assertThat(consume(execution.updates()))
        .extracting(AgentResponseUpdate::text)
        .containsExactly("FIRST", "SECOND");
    assertThat(execution.cancellationSignal()).isSameAs(signal);
  }

  private static AgentInvocation invocation() {
    return AgentInvocation.builder()
        .agentDefinition(
            io.github.hellices.agentframework.api.agent.AgentDefinition.builder()
                .name("agent")
                .build())
        .request(io.github.hellices.agentframework.api.agent.AgentRunRequest.of("hello"))
        .build();
  }

  private static AgentResponseUpdate update(String text) {
    return AgentResponseUpdate.builder()
        .agentId("agent-1")
        .responseId("response-1")
        .messageId("message-1")
        .authorName("assistant")
        .finishReason(FinishReason.STOP)
        .messages(List.of(new Message(Role.ASSISTANT, List.of(new TextContent(text)))))
        .additionalProperties(JsonObject.empty())
        .build();
  }

  private static Flow.Subscription subscription() {
    return new Flow.Subscription() {
      @Override
      public void request(long n) {}

      @Override
      public void cancel() {}
    };
  }

  private static <T> List<T> consume(Flow.Publisher<T> publisher) {
    List<T> values = new ArrayList<>();
    CompletableFuture<Void> completion = new CompletableFuture<>();
    publisher.subscribe(
        new Flow.Subscriber<>() {
          @Override
          public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
          }

          @Override
          public void onNext(T item) {
            values.add(item);
          }

          @Override
          public void onError(Throwable throwable) {
            completion.completeExceptionally(throwable);
          }

          @Override
          public void onComplete() {
            completion.complete(null);
          }
        });
    completion.join();
    return values;
  }
}
