plugins {
    id("agentframework.java-library-conventions")
    id("agentframework.test-conventions")
    id("agentframework.quality-conventions")
    id("agentframework.library-publishing-conventions")
}

description = "Optional OpenTelemetry adapter that maps Agent Framework for Java neutral telemetry " +
        "values to GenAI semantic convention attributes."

dependencies {
    // The OTel API BOM aligns every OTel artifact so a consumer cannot end up with mixed versions.
    api(platform(libs.opentelemetry.bom))
    api(project(":agent-framework-api"))

    // `api`, not `implementation`: the public OpenTelemetrySink constructor takes an
    // OpenTelemetry instance, so a consumer cannot call it without the OTel API on its classpath.
    api(libs.opentelemetry.api)

    // Testing with the OTel SDK's in-memory exporter to assert span attributes without a real
    // backend. This is a test-only dependency and reaches no consumer.
    testImplementation(platform(libs.opentelemetry.bom))
    testImplementation(libs.opentelemetry.sdk.testing)
}
