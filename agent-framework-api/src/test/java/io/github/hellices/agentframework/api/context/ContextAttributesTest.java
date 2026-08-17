package io.github.hellices.agentframework.api.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ContextAttributesTest {

  @Test
  void storesAndReadsTypedValuesByKeyIdentity() {
    ContextKey<String> tenant = ContextKey.of("agent", "tenant", String.class);

    ContextAttributes attributes = ContextAttributes.builder().put(tenant, "acme").build();

    assertThat(attributes.get(tenant)).contains("acme");
  }

  @Test
  void keyEqualityIncludesTheDeclaredType() {
    ContextKey<String> stringTenant = ContextKey.of("agent", "tenant", String.class);
    ContextKey<Integer> integerTenant = ContextKey.of("agent", "tenant", Integer.class);

    assertThat(stringTenant).isNotEqualTo(integerTenant);
  }

  @Test
  void rejectsConflictingTypesForTheSameNamespaceAndName() {
    ContextKey<String> stringTenant = ContextKey.of("agent", "tenant", String.class);
    ContextKey<Integer> integerTenant = ContextKey.of("agent", "tenant", Integer.class);

    assertThatThrownBy(
            () ->
                ContextAttributes.builder().put(stringTenant, "acme").put(integerTenant, 7).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "context attribute key collision for agent/tenant: java.lang.String vs java.lang.Integer");
  }
}
