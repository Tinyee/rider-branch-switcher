pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // Chinese mirrors as fallback for local dev behind GFW
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://mirrors.cloud.tencent.com/nexus/repository/gradle-plugins/")
        maven("https://repo.huaweicloud.com/repository/maven/")
    }
}

rootProject.name = "rider-branch-switcher"
include(":core")
