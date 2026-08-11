plugins {
    id("java-library")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Werror"))
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

configurations.matching { it.name.endsWith("Classpath") }.configureEach {
    resolutionStrategy.activateDependencyLocking()
}

tasks.register("resolveAndLockAll") {
    group = "build setup"
    description = "Resolves every locked classpath so Gradle can write dependency lock state."
    doLast {
        require(gradle.startParameter.isWriteDependencyLocks) {
            "resolveAndLockAll must be invoked with --write-locks"
        }
        configurations
            .filter { it.isCanBeResolved && it.name.endsWith("Classpath") }
            .forEach { it.resolve() }
    }
}
