plugins {
    id("java-library")
    id("agentframework.publishing-conventions")
}

// Sources and javadoc jars are required by Maven Central and are what lets a consumer step into
// this code from an IDE, so they are part of the library contract rather than a release-time extra.
java {
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).apply {
        addStringOption("Xdoclint:all,-missing", "-quiet")
        encoding = "UTF-8"
        source = "17"
    }
}

publishing {
    publications {
        create<MavenPublication>("library") {
            from(components["java"])
        }
    }
}
