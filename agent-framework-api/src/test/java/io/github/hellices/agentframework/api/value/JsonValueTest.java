package io.github.hellices.agentframework.api.value;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonValueTest {

  @Test
  void isAClosedSealedHierarchy() {
    assertThat(JsonValue.class.isSealed()).isTrue();
    assertThat(JsonValue.class.getPermittedSubclasses())
        .containsExactlyInAnyOrder(
            JsonNull.class,
            JsonBoolean.class,
            JsonNumber.class,
            JsonString.class,
            JsonArray.class,
            JsonObject.class);
    assertThat(
            List.of(
                JsonNull.class,
                JsonBoolean.class,
                JsonNumber.class,
                JsonString.class,
                JsonArray.class,
                JsonObject.class))
        .allSatisfy(type -> assertThat(Modifier.isFinal(type.getModifiers())).isTrue());
  }

  @Test
  void convertsJavaMapsToTypedObjectsWithDeterministicKeyOrdering() {
    assertThat(JsonValues.fromJava(Map.of("enabled", true, "count", 2)))
        .isEqualTo(
            JsonObject.builder()
                .put("count", JsonNumber.of(2))
                .put("enabled", JsonBoolean.of(true))
                .build());
  }

  @Test
  void convertsNullToTheSharedSingleton() {
    assertThat(JsonValues.fromJava(null)).isSameAs(JsonNull.instance());
  }

  @Test
  void rejectsNonFiniteDoubleValues() {
    assertThatThrownBy(() -> JsonValues.fromJava(Double.NaN))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void builderRejectsNullValues() {
    assertThatThrownBy(() -> JsonObject.builder().put("bad", null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void makesDefensiveCopiesOfObjectsAndArrays() {
    Map<String, Object> mutable = new LinkedHashMap<>();
    List<Object> items = new ArrayList<>();
    items.add("first");
    mutable.put("items", items);

    JsonObject json = (JsonObject) JsonValues.fromJava(mutable);

    items.add("second");
    mutable.put("added", true);

    assertThat(json.values()).containsOnlyKeys("items");
    assertThat(((JsonArray) json.get("items").orElseThrow()).values())
        .containsExactly(JsonString.of("first"));
    assertThatThrownBy(() -> json.values().put("added", JsonBoolean.of(true)))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () -> ((JsonArray) json.get("items").orElseThrow()).values().add(JsonNull.instance()))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void normalizesFiniteNumbersToSupportedRepresentations() {
    assertThat(JsonValues.fromJava(2L)).isEqualTo(JsonNumber.of(2));
    assertThat(JsonValues.fromJava(new BigInteger("2"))).isEqualTo(JsonNumber.of(2));
    assertThat(JsonValues.fromJava(new BigDecimal("2.0")))
        .isEqualTo(JsonNumber.of(new BigDecimal("2")));
  }

  @Test
  void canonicalizesPowerOfTenNumbersAcrossSourceTypesForEqualityAndHashing() {
    JsonNumber integer = JsonNumber.of(1000L);
    JsonNumber decimal = JsonNumber.of(new BigDecimal("1000.0"));

    assertThat(integer).isEqualTo(decimal);
    assertThat(integer.hashCode()).isEqualTo(decimal.hashCode());
  }

  @Test
  void rejectsIntegersOutsideTheSupportedLongRange() {
    assertThatThrownBy(
            () -> JsonValues.fromJava(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("long range");
  }

  @Test
  void rejectsDecimalsThatExceedThePinnedSessionStateLimits() {
    assertThatThrownBy(() -> JsonValues.fromJava(BigDecimal.ONE.scaleByPowerOfTen(-10_001)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("scale limit");
    assertThatThrownBy(() -> JsonValues.fromJava(new BigDecimal("1" + "0".repeat(1_024) + ".0")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("length limit");
  }

  @Test
  void rejectsValuesThatExceedTheMaximumNestingDepth() {
    Object nested = "leaf";
    for (int i = 0; i < 65; i++) {
      nested = List.of(nested);
    }
    Object overLimit = nested;

    assertThatThrownBy(() -> JsonValues.fromJava(overLimit))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nesting depth limit");
  }

  @Test
  void rejectsTypedValuesThatExceedTheMaximumNestingDepth() {
    JsonValue nested = JsonString.of("leaf");
    for (int i = 0; i < 64; i++) {
      nested = JsonArray.of(List.of(nested));
    }
    JsonValue maximumDepth = nested;

    assertThatThrownBy(() -> JsonArray.of(List.of(maximumDepth)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nesting depth limit");
  }

  @Test
  void preservesBuilderInsertionOrderForIteration() {
    JsonObject json =
        JsonObject.builder()
            .put("zeta", JsonBoolean.of(true))
            .put("alpha", JsonBoolean.of(false))
            .build();

    assertThat(json.values().entrySet())
        .containsExactly(
            Map.entry("zeta", JsonBoolean.of(true)), Map.entry("alpha", JsonBoolean.of(false)));
  }
}
