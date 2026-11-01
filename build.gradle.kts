// Root build file intentionally keeps task wiring minimal.
// Android/Gradle plugin tasks (including `build` and `lint`) are provided by included modules.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

// MockK needs instrumentation. Preload its existing Byte Buddy agent rather than
// relying on dynamic self-attach, which is unavailable on some CI/container JVMs.
subprojects {
    tasks.withType<Test>().configureEach {
        doFirst {
            val agent = classpath.files.singleOrNull {
                it.name.startsWith("byte-buddy-agent-") && it.extension == "jar"
            }
            if (agent != null) jvmArgs("-javaagent:${agent.absolutePath}")
        }
    }
}
