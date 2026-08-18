package io.github.hellices.agentframework.api.value;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

public final class JsonNumber implements JsonValue {

  private static final int MAX_DECIMAL_TEXT_LENGTH = 1_024;
  private static final int MAX_DECIMAL_SCALE = 10_000;

  private final BigDecimal value;

  private JsonNumber(BigDecimal value) {
    this.value = value;
  }

  public static JsonNumber of(Number value) {
    Objects.requireNonNull(value, "value must not be null");
    if (value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long) {
      return new JsonNumber(normalizeDecimal(BigDecimal.valueOf(value.longValue())));
    }
    if (value instanceof BigInteger integer) {
      try {
        return new JsonNumber(normalizeDecimal(BigDecimal.valueOf(integer.longValueExact())));
      } catch (ArithmeticException failure) {
        throw new IllegalArgumentException(
            "state integer is outside the supported long range", failure);
      }
    }
    if (value instanceof Double doubleValue) {
      if (!Double.isFinite(doubleValue)) {
        throw new IllegalArgumentException("state payload must contain only finite numbers");
      }
      return new JsonNumber(normalizeDecimal(BigDecimal.valueOf(doubleValue)));
    }
    if (value instanceof Float floatValue) {
      if (!Float.isFinite(floatValue)) {
        throw new IllegalArgumentException("state payload must contain only finite numbers");
      }
      return new JsonNumber(normalizeDecimal(BigDecimal.valueOf(floatValue.doubleValue())));
    }
    if (value instanceof BigDecimal decimal) {
      return new JsonNumber(normalizeDecimal(decimal));
    }
    throw new IllegalArgumentException(
        "state payload contains unsupported number type: " + value.getClass().getName());
  }

  public BigDecimal value() {
    return value;
  }

  private static BigDecimal normalizeDecimal(BigDecimal decimal) {
    if (decimal.toString().length() > MAX_DECIMAL_TEXT_LENGTH) {
      throw new IllegalArgumentException("decimal payload exceeds the length limit");
    }
    if (Math.abs((long) decimal.scale()) > MAX_DECIMAL_SCALE) {
      throw new IllegalArgumentException("decimal payload exceeds the scale limit");
    }
    return decimal.stripTrailingZeros();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof JsonNumber jsonNumber && value.equals(jsonNumber.value);
  }

  @Override
  public int hashCode() {
    return value.hashCode();
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
