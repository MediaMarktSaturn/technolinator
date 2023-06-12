import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.8.22"

    id("org.springframework.boot") version "2.7.12"
    id("org.jlleitschuh.gradle.ktlint") version "11.4.0"
    id("java")

    application
    jacoco
}

repositories {
    mavenCentral()
    mavenLocal()
    maven {
        url = uri("https://repo.spring.io/milestone")
    }
    maven {
        url = uri("https://packages.confluent.io/maven/")
    }
    maven {
        url = uri("https://plugins.gradle.org/m2/")
    }
}

val springBootVersion = "3.1.0"
val eventSchemasVersion = "8.13.0"
val kotestVersion = "5.6.2"

dependencies {
    // Spring Boot Starters
    implementation("org.springframework.boot:spring-boot-starter-actuator:$springBootVersion")
    implementation("org.springframework.boot:spring-boot-starter-web:$springBootVersion")
    implementation("org.springframework.boot:spring-boot-starter-graphql:$springBootVersion")
    implementation("org.springframework.boot:spring-boot-starter-webflux:$springBootVersion")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server:$springBootVersion")

    // Spring Boot Kafka Support, Version is not always in Line with the Starter Components
    implementation("org.springframework.kafka:spring-kafka:3.0.7")

    // Spring Boot OAuth2-Client
    implementation("org.springframework.security:spring-security-oauth2-client:6.1.0")

    // COS Kafka Event Schemas
    implementation("com.mms.cos:event-schemas:$eventSchemasVersion")

    // COS Storage Service Access Client
    implementation("com.mms.cos:order-storage-service-client:3.7.3")

    implementation("com.google.protobuf:protobuf-java:3.23.2")
    implementation("io.confluent:kafka-streams-protobuf-serde:7.4.0")

    implementation("org.openapitools:jackson-databind-nullable:0.2.6")

    implementation("com.squareup.okhttp3:logging-interceptor:4.11.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.7.1")

    implementation("com.google.api.grpc:proto-google-common-protos:2.20.0")
    implementation("io.arrow-kt:arrow-core:1.1.2")
    // monitoring
    implementation("io.micrometer:micrometer-registry-prometheus:1.11.0")

    implementation("com.graphql-java:graphql-java-extended-scalars:20.2")

    implementation("me.paulschwarz:spring-dotenv:4.0.0")

    // To fix CVE-2022-25647 https://nvd.nist.gov/vuln/detail/CVE-2022-25647
    implementation("com.google.code.gson:gson:2.10.1")

    // To fix CVE-2022-1471 https://www.veracode.com/blog/research/resolving-cve-2022-1471-snakeyaml-20-release-0
    implementation("org.yaml:snakeyaml:2.0")

    // To fix CVE-2022-36944
    testImplementation("org.scala-lang:scala-library:2.13.11")

    // To fix CVE-2022-36944
    testImplementation("org.bitbucket.b_c:jose4j:0.9.3")

    // testing
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.8.22")
    testImplementation("io.kotest:kotest-framework-api-jvm:$kotestVersion")
    testImplementation("io.kotest:kotest-runner-junit5-jvm:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core-jvm:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-arrow-jvm:4.4.3")
    testImplementation("io.mockk:mockk:1.13.5") {
        exclude("net.bytebuddy", "byte-buddy")
    }
    testImplementation("net.bytebuddy:byte-buddy:1.14.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.springframework.boot:spring-boot-starter-test:$springBootVersion") {
        exclude("org.junit.vintage", "junit-vintage-engine")
    }
    testImplementation("io.kotest.extensions:kotest-extensions-spring:1.1.3")
    testImplementation("org.springframework.kafka:spring-kafka-test:3.0.7")
    testImplementation("org.testcontainers:kafka:1.18.3")
    // https://mvnrepository.com/artifact/org.springframework.graphql/spring-graphql-test
    testImplementation("org.springframework.graphql:spring-graphql-test:1.2.0")
}

configurations.all {
    // force use of the latest grpc-api as order-storage-service-client depends on an old and conflicting version
    resolutionStrategy.force("io.grpc:grpc-api:1.55.1")
}

val integrationTest = task<Test>("integrationTest") {
    useJUnitPlatform()
    description = "Runs the integration tests"
    group = "verification"
    filter {
        includeTestsMatching("*IT")
    }
    mustRunAfter(tasks["test"])
}

tasks.check {
    dependsOn(integrationTest)
}

springBoot {
    mainClass.set("some.ApplicationKt")
}

tasks.withType<Jar> {
    archiveBaseName.set("app")
    project.version = ""
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xjsr305=strict")
        jvmTarget = "17"
    }
}

kotlin {
    jvmToolchain {
        (this as JavaToolchainSpec).languageVersion.let {
            JavaLanguageVersion.of(17)
        }
    }
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xjvm-default=all")
    }
    dependsOn("processResources")
}

sourceSets.getByName("main") {
    java.srcDir("$buildDir/src/main/java")
    java.srcDir("$buildDir/generated/src/main/java")
    java.srcDir("$buildDir/generated/src/main/kotlin")
    resources.srcDir("$buildDir/generated/src/main/resources")
}

tasks.test {
    filter {
        // Exclude integration tests from regular test phase
        excludeTestsMatching("*IT")
    }
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

jacoco {
    toolVersion = "0.8.7"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(false)
        csv.required.set(false)
    }
}

ktlint {
    filter {
        exclude { entry ->
            entry.file.toString().contains("generated")
        }
    }
    outputToConsole.set(true)
}

// we disable the default generateClientCode task of the graphQL generation plugin
// this is done to remove the generated custom client code, as we are using only
// the model objects generated
tasks.whenTaskAdded({
    if (this.name.equals("generateClientCode")) this.enabled = false
})
