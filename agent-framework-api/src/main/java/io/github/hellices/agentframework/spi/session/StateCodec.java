package io.github.hellices.agentframework.spi.session;

/**
 * Converts a registered Java state type to and from the durable logical JSON value model.
 *
 * <p>Encoded numeric values are canonicalized before persistence: integral values restore as {@link
 * Long}, while finite decimal values restore as {@link java.math.BigDecimal}. Codecs must decode
 * that logical representation rather than relying on the original numeric wrapper class.
 */
public interface StateCodec<T> {

  String typeId();

  int version();

  Class<T> javaType();

  Object encode(T value);

  T decode(Object payload);
}
