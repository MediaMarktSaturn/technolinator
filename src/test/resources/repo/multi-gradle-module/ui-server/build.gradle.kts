import java.net.URI
import com.google.protobuf.gradle.*

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.jfrog.artifactory")
    id("com.github.johnrengelman.shadow")
    id("io.gitlab.arturbosch.detekt")
    id("com.palantir.docker")
    id("org.openapi.generator")
    id("org.sonarqube") version "4.0.0.2929"
    id("com.google.protobuf")
    application
}

group = "some-group"

val junitVersion = "5.9.3"
val koinVersion = "3.4.0"
val ktorVersion = "2.3.0"
val micrometerVersion = "1.10.6"
val authentiktorVersion = "2.8.9"
val datastoreVersion = "2.14.4"

val mockkVersion = "1.13.5"
val restAssuredVersion = "5.3.0"
val strictVersion = "0.34.1"

val protobufVersion = "3.20.3"
val grpcKotlinVersion = "1.3.0"
val grpcVersion = "1.46.0"

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("com.google.cloud:google-cloud-datastore:$datastoreVersion")
    implementation("io.ktor:ktor-server-metrics-micrometer:$ktorVersion")
    implementation("io.ktor:ktor-client-java:$ktorVersion")
    implementation("io.ktor:ktor-server-double-receive:$ktorVersion")
    implementation("io.micrometer:micrometer-registry-prometheus:$micrometerVersion")
    implementation("io.grpc:grpc-protobuf:1.47.0")

    // world deps
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-serialization:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")

    // gRPC
    implementation("io.grpc:grpc-kotlin-stub:$grpcKotlinVersion")
    implementation("io.grpc:grpc-protobuf:$grpcVersion")
    implementation("com.google.protobuf:protobuf-kotlin:$protobufVersion")

    // test
    testImplementation("org.junit.jupiter:junit-jupiter-api:$junitVersion")
    testImplementation("org.junit.jupiter:junit-jupiter-params:$junitVersion")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitVersion")
    testImplementation("io.insert-koin:koin-test:$koinVersion")
    testImplementation("io.insert-koin:koin-test-junit5:$koinVersion")
    testImplementation("io.mockk:mockk:$mockkVersion")
    testImplementation("io.rest-assured:rest-assured:$restAssuredVersion")
    testImplementation("io.strikt:strikt-core:$strictVersion")
}

application {
    mainClass.set("ApplicationKt")
}

configurations.all {
    resolutionStrategy {
        failOnVersionConflict()
        preferProjectModules()
    }
}

tasks.test {
    useJUnitPlatform()
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
        id("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:$grpcKotlinVersion:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach {
            it.plugins {
                id("grpc")
                id("grpckt")
            }
            it.builtins {
                id("kotlin")
            }
        }
    }
}

tasks.compileKotlin.configure {
    dependsOn(tasks["buildAPISourcesWorkOrder"], tasks["buildAPISourcesSalesDoc"])
}

configure<SourceSetContainer> {
    named("main") {
        java.srcDir("$buildDir/generated/src/commonMain/kotlin")
        kotlin.srcDirs("$buildDir/generated/source/proto/main/grpckt", "$buildDir/generated/source/proto/main/kotlin")
    }
}
