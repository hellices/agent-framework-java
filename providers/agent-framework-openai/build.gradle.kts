plugins {
    id("agentframework.java-library-conventions")
    id("agentframework.test-conventions")
    id("agentframework.quality-conventions")
    id("agentframework.library-publishing-conventions")
}

description = "OpenAI Chat Completions model client for Agent Framework for Java."

dependencies {
    api(project(":agent-framework-api"))

    // `api`, not `implementation`: the public builder takes a borrowed `OpenAIClientAsync`, so a
    // consumer cannot call it without the SDK on its own compile classpath. Same reasoning as
    // `integrations/agent-framework-mcp`, which takes an `McpAsyncClient`.
    api(libs.openai.java)

    // The Jackson platform is `api` for the same reason the MCP module uses `api(platform(...))`:
    // a constraint declared on `implementation` lands only in `runtimeElements`, so a consumer -
    // including `:samples:sample-standalone` - would compile against the 2.18.9 jackson-databind
    // the SDK POM declares at compile scope while running on 2.22.1. That split is the skew this
    // line exists to remove. Jackson itself stays `implementation`: it is used to parse and
    // re-serialise tool arguments inside the adapter and appears nowhere on the public surface.
    api(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)

    // Test only, never shipped: the end-to-end proof that this adapter and the real tool loop agree
    // runs `AgentEngine`. `ModuleCompositionPolicyTest` allowlists production and test project
    // dependencies separately, so this cannot become a production dependency by accident.
    testImplementation(project(":agent-framework-engine"))
}
