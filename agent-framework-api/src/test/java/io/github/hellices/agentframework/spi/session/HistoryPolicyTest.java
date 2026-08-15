package io.github.hellices.agentframework.spi.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HistoryPolicyTest {

  @Test
  void defaultsLoadAndStoreTheTurnButNotOtherProvidersContext() {
    HistoryPolicy policy = HistoryPolicy.defaults();

    assertThat(policy.loadMessages()).isTrue();
    assertThat(policy.storeInputs()).isTrue();
    assertThat(policy.storeContextMessages()).isFalse();
    assertThat(policy.storeContextFrom()).isEmpty();
    assertThat(policy.storeOutputs()).isTrue();
  }

  @Test
  void theBuilderConfiguresEveryFlag() {
    HistoryPolicy policy =
        HistoryPolicy.builder()
            .loadMessages(false)
            .storeInputs(false)
            .storeContextMessages(true)
            .storeContextFrom("rag", "notes")
            .storeOutputs(false)
            .build();

    assertThat(policy.loadMessages()).isFalse();
    assertThat(policy.storeInputs()).isFalse();
    assertThat(policy.storeContextMessages()).isTrue();
    assertThat(policy.storeContextFrom()).containsExactlyInAnyOrder("rag", "notes");
    assertThat(policy.storeOutputs()).isFalse();
  }

  @Test
  void twoPoliciesConfiguredTheSameWayAreEqual() {
    HistoryPolicy first =
        HistoryPolicy.builder().storeContextMessages(true).storeContextFrom("rag").build();
    HistoryPolicy second =
        HistoryPolicy.builder().storeContextMessages(true).storeContextFrom(List.of("rag")).build();

    assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
  }

  @Test
  void theStoredSourceSetIsCopiedAndImmutable() {
    List<String> sources = new ArrayList<>(List.of("rag"));
    HistoryPolicy policy = HistoryPolicy.builder().storeContextFrom(sources).build();
    sources.add("notes");

    assertThat(policy.storeContextFrom()).containsExactly("rag");
    assertThatThrownBy(() -> policy.storeContextFrom().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void repeatedStoreContextFromSourcesAreDeduplicated() {
    HistoryPolicy policy = HistoryPolicy.builder().storeContextFrom("rag", "rag").build();

    assertThat(policy.storeContextFrom()).containsExactly("rag");
  }

  @Test
  void aLaterStoreContextFromCallReplacesTheEarlierSelection() {
    HistoryPolicy policy =
        HistoryPolicy.builder().storeContextFrom("rag").storeContextFrom("notes").build();

    assertThat(policy.storeContextFrom()).containsExactly("notes");
  }

  @Test
  void anAbsentSourceSetMeansEveryOtherSource() {
    HistoryPolicy policy = new HistoryPolicy(true, true, true, null, true);

    assertThat(policy.storeContextFrom()).isEmpty();
  }

  @Test
  void aNullSourceIsRejected() {
    List<String> sources = new ArrayList<>();
    sources.add(null);

    assertThatThrownBy(() -> HistoryPolicy.builder().storeContextFrom(sources).build())
        .isInstanceOf(NullPointerException.class)
        .hasMessage("storeContextFrom must not contain null entries");
  }

  @Test
  void aBlankSourceIsRejected() {
    assertThatThrownBy(() -> HistoryPolicy.builder().storeContextFrom("rag", "  ").build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("storeContextFrom must not contain blank entries");
  }

  @Test
  void aNullSourceCollectionIsRejectedByTheBuilder() {
    assertThatThrownBy(() -> HistoryPolicy.builder().storeContextFrom((Set<String>) null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("storeContextFrom must not be null");
  }
}
