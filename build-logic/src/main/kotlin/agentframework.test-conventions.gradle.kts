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
    systemProperty("java.awt.headless", "true")
    systemProperty("user.language", "en")
    systemProperty("file.encoding", "UTF-8")
    testLogging {
        events("failed")
        showStackTraces = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.register("testJava17") {
    group = "verification"
    description = "Runs tests with the Eclipse Temurin 17 launcher."
    dependsOn(tasks.named("test"))
}

listOf(21, 25).forEach { javaVersion ->
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
