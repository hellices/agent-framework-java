import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsTask

plugins {
    java
    checkstyle
    pmd
    jacoco
    id("com.diffplug.spotless")
    id("com.github.spotbugs")
}

val versionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

spotless {
    java {
        target("src/**/*.java")
        googleJavaFormat(versionCatalog.findVersion("googleJavaFormat").get().requiredVersion)
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("gradleScripts") {
        target("*.gradle.kts")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

checkstyle {
    toolVersion = versionCatalog.findVersion("checkstyle").get().requiredVersion
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
    maxWarnings = 0
}

pmd {
    toolVersion = versionCatalog.findVersion("pmd").get().requiredVersion
    isConsoleOutput = true
    ruleSets = emptyList()
    ruleSetFiles = files(rootProject.file("config/pmd/ruleset.xml"))
    isIgnoreFailures = false
}

spotbugs {
    toolVersion.set(versionCatalog.findVersion("spotbugsTool").get().requiredVersion)
    effort.set(Effort.MAX)
    reportLevel.set(Confidence.MEDIUM)
    excludeFilter.set(rootProject.layout.projectDirectory.file("config/spotbugs/exclude.xml"))
    ignoreFailures.set(false)
}

jacoco {
    toolVersion = versionCatalog.findVersion("jacoco").get().requiredVersion
}

tasks.withType<SpotBugsTask>().configureEach {
    reports.create("xml") {
        required.set(true)
    }
    reports.create("html") {
        required.set(false)
    }
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

val quality =
    tasks.register("quality") {
        group = "verification"
        description = "Runs formatting, static analysis, and coverage reporting on the Gradle runtime JDK."
        dependsOn(
            tasks.named("spotlessCheck"),
            tasks.named("checkstyleMain"),
            tasks.named("checkstyleTest"),
            tasks.named("pmdMain"),
            tasks.named("pmdTest"),
            tasks.named("spotbugsMain"),
            tasks.named("spotbugsTest"),
            tasks.named("jacocoTestReport")
        )
    }

tasks.named("check") {
    dependsOn(quality)
}
