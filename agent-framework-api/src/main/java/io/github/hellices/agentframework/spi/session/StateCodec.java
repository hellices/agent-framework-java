package io.github.hellices.agentframework.spi.session;

public interface StateCodec<T> {

  String typeId();

  int version();

  Class<T> javaType();

  Object encode(T value);

  T decode(Object payload);
}
