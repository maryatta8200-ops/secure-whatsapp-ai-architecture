plugins {
    alias(libs.plugins.kotlin.jvm)
}

// :core is deliberately dependency-free (Kotlin standard library and the JDK
// only). Everything in this module is deterministic, side-effect free and unit
// testable on the JVM, which is what makes it verifiable both in CI and in the
// offline sandbox (see tools/local-verify).
dependencies {
    testImplementation(libs.junit)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.named<Test>("test") {
    useJUnit()
    maxParallelForks = 1
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
    }
}
