plugins {
    id("agentframework.java-library-conventions")
    id("agentframework.test-conventions")
    id("agentframework.quality-conventions")
    id("agentframework.library-publishing-conventions")
}

description = "Model Context Protocol client integration for Agent Framework for Java."

dependencies {
    // The MCP SDK BOM aligns every SDK artifact this module and its consumers resolve, so a
    // consumer that adds a transport or JSON module cannot end up with a mixed SDK version.
    api(platform(libs.mcp.bom))
    api(project(":agent-framework-api"))

    // `api`, not `implementation`: the public adapter constructor takes an SDK `McpAsyncClient`,
    // so a consumer cannot call it without the SDK on its own compile classpath.
    api(libs.mcp.core)
}
