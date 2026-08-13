package io.github.hellices.agentframework.engine;

/**
 * Identifies the embedded execution engine surface.
 *
 * <p>The engine ships no run loop yet. Requirement documents drive what lands here, one feature at
 * a time, so this type exists to give the module a compiled, documented, and publishable surface
 * from the first commit.
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
