plugins {
    kotlin("jvm") version "2.1.21"
    id("application")
    id("com.gradleup.shadow") version "9.0.0"
}

group = "kofeychi"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

application {
    mainClass = "MainKt"
}

dependencies {
    implementation("it.unimi.dsi:fastutil:8.5.12")
    implementation("com.google.code.gson:gson:2.8.9")

    implementation("org.joml:joml:1.10.8")
    implementation("com.github.kwhat:jnativehook:+")
}

kotlin {
    jvmToolchain(21)
}