plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.submodule"
version = "0.4.0"

repositories {
    maven("https://maven.aliyun.com/repository/public")
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

val riderVersion = providers.gradleProperty("rider.version").get()
val riderPath = providers.gradleProperty("rider.path").orNull

dependencies {
    intellijPlatform {
        if (!riderPath.isNullOrBlank()) {
            local(riderPath)
        } else {
            rider(riderVersion)
        }
        bundledPlugin("Git4Idea")
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
}

kotlin {
    jvmToolchain(17)
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("plugin.sinceBuild").get()
            untilBuild = providers.gradleProperty("plugin.untilBuild").get()
        }
    }
}

tasks {
    buildSearchableOptions {
        enabled = false
    }
}
