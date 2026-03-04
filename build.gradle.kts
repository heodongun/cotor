plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
    alias(libs.plugins.shadow)
    jacoco
    id("com.diffplug.spotless") version "6.25.0"
}

group = "com.cotor"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Kotlin
    implementation(kotlin("stdlib"))
    implementation(libs.kotlinx.coroutines.core)

    // CLI
    implementation(libs.clikt)
    implementation(libs.mordant) // Terminal UI
    implementation(libs.progressbar) // Progress bar

    // Serialization
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kaml)

    // Dependency Injection
    implementation(libs.koin.core)

    // Logging
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
    implementation(libs.kotlin.logging.jvm)

    // Web Server (Ktor)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.sse)

    // Testing
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}

application {
    mainClass.set("com.cotor.MainKt")
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint().editorConfigOverride(
            mapOf(
                "ktlint_standard_max-line-length" to "disabled",
                "ktlint_standard_no-wildcard-imports" to "disabled",
                "ktlint_standard_value-parameter-comment" to "disabled",
                "ktlint_standard_trailing-comma-on-call-site" to "disabled",
                "ktlint_standard_trailing-comma-on-declaration-site" to "disabled",
            ),
        )
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint().editorConfigOverride(
            mapOf(
                "ktlint_standard_max-line-length" to "disabled",
                "ktlint_standard_no-wildcard-imports" to "disabled",
                "ktlint_standard_value-parameter-comment" to "disabled",
                "ktlint_standard_trailing-comma-on-call-site" to "disabled",
                "ktlint_standard_trailing-comma-on-declaration-site" to "disabled",
            ),
        )
    }
}

tasks.register("format") {
    group = "formatting"
    description = "Formats Kotlin and Gradle Kotlin files with Spotless."
    dependsOn("spotlessApply")
}

tasks.register("formatCheck") {
    group = "verification"
    description = "Checks whether Kotlin and Gradle Kotlin files are properly formatted."
    dependsOn("spotlessCheck")
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport, tasks.jacocoTestCoverageVerification)
}

tasks.named("check") {
    dependsOn("spotlessCheck")
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            element = "BUNDLE"
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.30".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

tasks.shadowJar {
    archiveBaseName.set("cotor")
    archiveClassifier.set("all")
    archiveVersion.set(version.toString())
    manifest {
        attributes("Main-Class" to "com.cotor.MainKt")
    }
}

tasks.register("generateCotorProperties") {
    doLast {
        val propertiesFile = file("src/main/resources/cotor.properties")
        propertiesFile.writeText("version=$version\n")
    }
}

tasks.processResources {
    dependsOn("generateCotorProperties")
}

kotlin {
    jvmToolchain(17)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}
