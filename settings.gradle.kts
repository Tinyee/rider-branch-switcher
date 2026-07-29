pluginManagement {
    val useChinaMirrors = providers.gradleProperty("useChinaMirrors")
        .getOrElse("false")
        .toBoolean()
    repositories {
        gradlePluginPortal()
        mavenCentral()
        if (useChinaMirrors) {
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://mirrors.cloud.tencent.com/nexus/repository/gradle-plugins/")
            maven("https://repo.huaweicloud.com/repository/maven/")
        }
    }
}

rootProject.name = "submodule-branch-switcher"
include(":core")
