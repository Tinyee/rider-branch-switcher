plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
    id("info.solidsoft.pitest") version "1.19.0"
}

val useChinaMirrors = providers.gradleProperty("useChinaMirrors")
    .getOrElse("false")
    .toBoolean()

repositories {
    mavenCentral()
    if (useChinaMirrors) {
        maven("https://maven.aliyun.com/repository/public")
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        maven("https://repo.huaweicloud.com/repository/maven/")
    }
}

dependencies {
    // Compile against Kotlin without packaging a newer stdlib than the oldest supported IDE provides.
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib")
    // Provided by IntelliJ Platform at plugin runtime; core tests bring their own copies.
    compileOnly("com.google.code.gson:gson:2.11.0")
    testImplementation("com.google.code.gson:gson:2.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    }
}

tasks.test {
    useJUnitPlatform()
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
            "com.submodule.branchswitcher.model.PresetValidationKt",
            "com.submodule.branchswitcher.switch.DeriveNotification*",
            "com.submodule.branchswitcher.presentation.PresetImportResultKt",
            "com.submodule.branchswitcher.presentation.SwitchPreviewRulesKt",
            "com.submodule.branchswitcher.switch.SwitchPreflight",
        )
    )
    targetTests.set(
        setOf(
            "com.submodule.branchswitcher.settings.*Test",
            "com.submodule.branchswitcher.switch.BranchNameRulesTest",
            "com.submodule.branchswitcher.switch.DeriveNotificationTest",
            "com.submodule.branchswitcher.switch.SwitchPreflightTest",
            "com.submodule.branchswitcher.presentation.PresetImportRulesTest",
            "com.submodule.branchswitcher.presentation.SwitchPreviewRulesTest",
        )
    )
    avoidCallsTo.set(setOf("kotlin.jvm.internal"))
    outputFormats.set(setOf("HTML", "XML"))
    mutationThreshold.set(95)
    timestampedReports.set(false)
    threads.set(1)
}
