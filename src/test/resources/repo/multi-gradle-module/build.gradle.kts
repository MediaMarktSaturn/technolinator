plugins {
    // ** WARNING: kotlin jvm version must align with kotlin-ktor-koin-commons library
    kotlin("jvm") version "1.8.21" apply false
    kotlin("plugin.serialization") version "1.8.21" apply false
    id("com.jfrog.artifactory") version "4.31.9" apply false
    id("com.github.johnrengelman.shadow") version "8.1.1" apply false
    id("io.gitlab.arturbosch.detekt") version "1.22.0"
    id("com.palantir.docker") version "0.35.0" apply false
    id("org.openapi.generator") version "6.6.0" apply false
    id("com.google.protobuf") version "0.9.3" apply false
    id("com.github.node-gradle.node") version "5.0.0" apply false
}

repositories {
    mavenCentral()
}
