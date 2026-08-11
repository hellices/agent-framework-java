package com.microsoft.agentframework.api;

/**
 * Identifies the released contract surface.
 *
 * <p>The API module ships no behavior yet. Requirement documents drive what lands here, one feature
 * at a time, so this type exists to give the module a compiled, testable, and publishable surface
 * from the first commit.
 */
public final class ApiContract {

  private static final String PACKAGE_NAME = "com.microsoft.agentframework.api";

  private ApiContract() {}

  /**
   * Returns the root package of the public contract surface.
   *
   * @return the fully qualified public API package name
   */
  public static String packageName() {
    return PACKAGE_NAME;
  }
}
