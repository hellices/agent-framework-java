plugins {
    base
}

description = "Agent Framework for Java build harness root project."

val aggregatedVerificationTasks =
    mapOf(
        "policyCheck" to "Runs repository policy regression in every project that owns it.",
        "quality" to "Runs formatting and static analysis once on the Gradle runtime JDK 17.",
        "testJava17" to "Runs tests with the Eclipse Temurin 17 launcher.",
        "testJava21" to "Runs tests with the Eclipse Temurin 21 launcher.",
        "testJava25" to "Runs tests with the Eclipse Temurin 25 launcher."
    )

aggregatedVerificationTasks.forEach { (taskName, taskDescription) ->
    val aggregate =
        tasks.register(taskName) {
            group = "verification"
            description = taskDescription
            dependsOn(
                provider {
                    subprojects.mapNotNull { subproject -> subproject.tasks.findByName(taskName)?.path }
                }
            )
        }
    tasks.named("check") {
        dependsOn(aggregate)
    }
}
