package io.github.hellices.agentframework.engine;

/**
 * Identifies the embedded execution engine surface.
 *
 * <p>Requirement documents drive what lands here one feature at a time. This marker remains the
 * stable module identifier while {@link AgentEngine} provides the embedded execution surface.
 */
public final class EngineContract {

  private static final String PACKAGE_NAME = "io.github.hellices.agentframework.engine";

  private EngineContract() {}

  /**
   * Returns the root package of the execution engine.
   *
   * @return the fully qualified engine package name
   */
  public static String packageName() {
    return PACKAGE_NAME;
  }
}
