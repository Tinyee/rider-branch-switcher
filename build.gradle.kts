plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.submodule"
version = "0.2.1"

repositories {
    maven("https://maven.aliyun.com/repository/public")
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        val riderPath = providers.gradleProperty("rider.path").orNull
        if (!riderPath.isNullOrBlank()) {
            local(riderPath)
        } else {
            rider("2026.1.1")
        }
        bundledPlugin("Git4Idea")
    }
}

kotlin {
    jvmToolchain(17)
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
            untilBuild = "261.*"
        }
    }
}

tasks {
    buildSearchableOptions {
        enabled = false
    }
}
