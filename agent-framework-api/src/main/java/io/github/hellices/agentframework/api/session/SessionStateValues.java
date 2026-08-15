package io.github.hellices.agentframework.api.session;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class SessionStateValues {

  private static final int MAX_DECIMAL_TEXT_LENGTH = 1_024;
  private static final int MAX_DECIMAL_SCALE = 10_000;

  private SessionStateValues() {}

  public static Object immutableCopy(Object value) {
    if (value == null || value instanceof String || value instanceof Boolean) {
      return value;
    }
    if (value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long) {
      return ((Number) value).longValue();
    }
    if (value instanceof BigInteger integer) {
      try {
        return integer.longValueExact();
      } catch (ArithmeticException failure) {
        throw new IllegalArgumentException(
            "state integer is outside the supported long range", failure);
      }
    }
    if (value instanceof Double doubleValue) {
      if (!Double.isFinite(doubleValue)) {
        throw new IllegalArgumentException("state payload must contain only finite numbers");
      }
      return BigDecimal.valueOf(doubleValue).stripTrailingZeros();
    }
    if (value instanceof Float floatValue) {
      if (!Float.isFinite(floatValue)) {
        throw new IllegalArgumentException("state payload must contain only finite numbers");
      }
      return BigDecimal.valueOf(floatValue.doubleValue()).stripTrailingZeros();
    }
    if (value instanceof BigDecimal decimal) {
      if (decimal.toString().length() > MAX_DECIMAL_TEXT_LENGTH) {
        throw new IllegalArgumentException("decimal payload exceeds the length limit");
      }
      if (Math.abs((long) decimal.scale()) > MAX_DECIMAL_SCALE) {
        throw new IllegalArgumentException("decimal payload exceeds the scale limit");
      }
      return decimal.stripTrailingZeros();
    }
    if (value instanceof Number number) {
      throw new IllegalArgumentException(
          "state payload contains unsupported number type: " + number.getClass().getName());
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> copy = new TreeMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (!(entry.getKey() instanceof String key)) {
          throw new IllegalArgumentException("state payload map keys must be strings");
        }
        copy.put(key, immutableCopy(entry.getValue()));
      }
      return Collections.unmodifiableMap(copy);
    }
    if (value instanceof List<?> list) {
      List<Object> copy = new ArrayList<>();
      for (Object item : list) {
        copy.add(immutableCopy(item));
      }
      return Collections.unmodifiableList(copy);
    }
    throw new IllegalArgumentException(
        "state payload contains unsupported type: " + value.getClass().getName());
  }
}
