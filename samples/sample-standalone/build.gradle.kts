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

    // Declared directly because this sample uses the SDK directly: it builds and closes the
    // `OpenAIClientAsync` the adapter borrows. The provider exposes the SDK on `api` for its public
    // builder signature, so this compiles either way today, and that is exactly the accident worth
    // removing - a provider that later wrapped the client would break this sample's compilation for
    // a reason that has nothing to do with the sample. The version comes from the same
    // `libs.versions.toml` entry the provider uses, so there is one version and no skew.
    implementation(libs.openai.java)
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
