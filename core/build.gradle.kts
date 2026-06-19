plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
    id("info.solidsoft.pitest") version "1.19.0"
}

repositories {
    mavenCentral()
    maven("https://maven.aliyun.com/repository/public")
    maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
    maven("https://repo.huaweicloud.com/repository/maven/")
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    // IntelliJ Platform provides Gson at plugin runtime; tests bring their own copy explicitly.
    compileOnly("com.google.code.gson:gson:2.11.0")
    testImplementation("com.google.code.gson:gson:2.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform {
        excludeEngines("kotest")
    }
    maxParallelForks = 1
}
detekt {
    buildUponDefaultConfig = true
    config.setFrom(file("../detekt-config.yml"))
}

pitest {
    targetClasses.set(
        setOf(
            "com.submodule.branchswitcher.settings.SettingsRulesKt",
            "com.submodule.branchswitcher.switch.BranchNameRulesKt",
            "com.submodule.branchswitcher.switch.DeriveNotification*",
            "com.submodule.branchswitcher.ui.PresetImportResultKt",
        )
    )
    targetTests.set(
        setOf(
            "com.submodule.branchswitcher.settings.*Test",
            "com.submodule.branchswitcher.switch.BranchNameRulesTest",
            "com.submodule.branchswitcher.switch.DeriveNotificationTest",
            "com.submodule.branchswitcher.ui.PresetImportRulesTest",
        )
    )
    avoidCallsTo.set(setOf("kotlin.jvm.internal"))
    outputFormats.set(setOf("HTML", "XML"))
    mutationThreshold.set(95)
    timestampedReports.set(false)
    threads.set(1)
}
