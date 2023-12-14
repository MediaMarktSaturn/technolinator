import com.github.gradle.node.npm.proxy.ProxySettings
import com.github.gradle.node.npm.task.NpmTask

plugins {
    id("com.github.node-gradle.node")
}

repositories {
    mavenCentral {
    }
}

node {
    version.set("16.15.1")
    npmVersion.set("")
    npmInstallCommand.set("install")
    distBaseUrl.set("https://nodejs.org/dist")
    download.set(true)
    workDir.set(file("${project.projectDir}/.cache/nodejs"))
    npmWorkDir.set(file("${project.projectDir}/.cache/npm"))
    nodeProjectDir.set(file("${project.projectDir}"))
    nodeProxySettings.set(ProxySettings.SMART)
}

tasks.npmInstall {
    nodeModulesOutputFilter {
        exclude("notExistingFile")
    }
}

val testTaskUsingNpm = tasks.register<NpmTask>("testNpm") {
    dependsOn(tasks.npmInstall)
    npmCommand.set(listOf("run", "test"))
    ignoreExitValue.set(false)
    workingDir.set(projectDir)
    execOverrides {
        standardOutput = System.out
    }
    outputs.upToDateWhen {
        true
    }
}

val buildTaskUsingNpm = tasks.register<NpmTask>("buildNpm") {
    dependsOn(testTaskUsingNpm)
    npmCommand.set(listOf("run", "build"))
    inputs.dir("src")
    outputs.dir("${buildDir}")
    enabled = true
}

tasks.register<Zip>("package") {
    archiveFileName.set("app.zip")
    destinationDirectory.set(file("${projectDir}/archive"))
    from(buildTaskUsingNpm) {
        into("npm")
    }
}
