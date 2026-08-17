plugins {
    application
    id("agentframework.java-library-conventions")
    id("agentframework.test-conventions")
    id("agentframework.quality-conventions")
}

description = "Runnable standalone Agent.run sample over a real OpenAI-compatible endpoint."

dependencies {
    implementation(project(":agent-framework-api"))
    implementation(project(":agent-framework-engine"))
    implementation(project(":providers:agent-framework-openai"))
}

application {
    mainClass = "io.github.hellices.agentframework.samples.standalone.StandaloneAgentApplication"
}

// The missing-credential regression runs the sample's real `main` in a child JVM, so it needs the
// sample's own runtime classpath. A Gradle test worker does not expose it through
// `java.class.path`, and reconstructing it from the test classpath would silently drift.
val sampleRuntimeClasspath: FileCollection = sourceSets.main.get().runtimeClasspath

tasks.withType<Test>().configureEach {
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf("-Dsample.runtime.classpath=${sampleRuntimeClasspath.asPath}")
        }
    )
}
