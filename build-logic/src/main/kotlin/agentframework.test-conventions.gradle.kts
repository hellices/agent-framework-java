plugins {
    java
}

val versionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val javaToolchains = extensions.getByType<JavaToolchainService>()

dependencies {
    "testImplementation"(platform(versionCatalog.findLibrary("junit-bom").get()))
    "testImplementation"(versionCatalog.findLibrary("junit-jupiter").get())
    "testImplementation"(versionCatalog.findLibrary("assertj-core").get())
    "testRuntimeOnly"(platform(versionCatalog.findLibrary("junit-bom").get()))
    "testRuntimeOnly"(versionCatalog.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    failFast = false

    // A modern JVM sizes its heap from the cgroup limit, not from host memory, so one test JVM is
    // not the problem. Several are: `testJava17`, `testJava21`, and `testJava25` can run
    // concurrently, and each sizes itself against the whole container without accounting for its
    // siblings. Their defaults then add up past the runner's limit and the pod is OOM-killed, which
    // the build reports as a cancelled step with no failure in the log.
    maxHeapSize = "1g"

    systemProperty("java.awt.headless", "true")
    systemProperty("user.language", "en")
    systemProperty("file.encoding", "UTF-8")
    testLogging {
        events("failed")
        showStackTraces = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

listOf(17, 21, 25).forEach { javaVersion ->
    val compatibilityTest =
        tasks.register<Test>("testJava$javaVersion") {
            group = "verification"
            description = "Runs tests with the Eclipse Temurin $javaVersion launcher."
            javaLauncher.set(
                javaToolchains.launcherFor {
                    languageVersion.set(JavaLanguageVersion.of(javaVersion))
                }
            )
            testClassesDirs = sourceSets["test"].output.classesDirs
            classpath = sourceSets["test"].runtimeClasspath
        }
    tasks.named("check") {
        dependsOn(compatibilityTest)
    }
}
