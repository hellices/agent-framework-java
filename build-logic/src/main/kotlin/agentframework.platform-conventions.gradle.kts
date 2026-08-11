plugins {
    `java-platform`
    id("agentframework.publishing-conventions")
}

publishing {
    publications {
        create<MavenPublication>("platform") {
            from(components["javaPlatform"])
        }
    }
}
