plugins {
    kotlin("jvm") version "1.6.21"
    kotlin("plugin.serialization") version "1.6.21"
    id("com.github.johnrengelman.shadow") version "7.1.2"
    application
}

group = "de.skyslycer"
version = "1.0.1"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))

    implementation("dev.kord:kord-core:0.8.0-M14")

    implementation("io.github.microutils", "kotlin-logging", "2.1.23")
    implementation("org.slf4j", "slf4j-api", "2.0.0-alpha7")
    implementation("ch.qos.logback", "logback-classic", "1.3.0-alpha16")
    implementation("io.sentry", "sentry", "6.0.0")
    implementation("io.sentry", "sentry-logback", "6.0.0")

    implementation("io.github.cdimascio:dotenv-kotlin:6.3.1")
}


application {
    mainClass.set("de.skyslycer.paste.PasteBotKt")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>() {
    kotlinOptions.jvmTarget = "17"
}

tasks {
    named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
        val classifier: String? = null
        archiveClassifier.set(classifier)
    }

    build {
        dependsOn("shadowJar")
    }
}