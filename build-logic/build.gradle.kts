plugins {
    `kotlin-dsl`
}

group = "com.microsoft.agentframework.build"
description = "Convention plugins shared by every Agent Framework for Java project."

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    implementation("com.diffplug.spotless:spotless-plugin-gradle:${libs.versions.spotless.get()}")
    implementation("com.github.spotbugs.snom:spotbugs-gradle-plugin:${libs.versions.spotbugsPlugin.get()}")

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed")
    }
    systemProperty(
        "agentframework.versionCatalog",
        rootDir.resolve("../gradle/libs.versions.toml").canonicalPath
    )
}
