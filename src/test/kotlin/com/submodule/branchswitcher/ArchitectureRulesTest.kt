package com.submodule.branchswitcher

import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.Location
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

/**
 * Bytecode-level enforcement of the one-way dependency direction. This replaces the
 * structural quickCheck source scans (layer imports, core purity, process ownership)
 * with semantic checks on compiled classes.
 *
 * Only `main` classes are imported (plugin and core modules) so that test code can
 * reference other layers freely, matching quickCheck's `src/main/kotlin` scope.
 *
 * Import paths are relative to the Gradle project root, which is the working
 * directory for `test` and IDE runs; `:test` compiles `main` classes first.
 */
class ArchitectureRulesTest {

    companion object {
        private val MAIN_CLASSES: JavaClasses = ClassFileImporter().importLocations(
            listOf(
                Location.of(Path.of("build/classes/kotlin/main").toUri()),
                Location.of(Path.of("core/build/classes/kotlin/main").toUri()),
            )
        )

        /**
         * Core only, so purity rules can target the pure-JVM module without the plugin
         * classes that share `com.submodule.branchswitcher.*` package names.
         */
        private val CORE_CLASSES: JavaClasses = ClassFileImporter().importLocations(
            listOf(
                Location.of(Path.of("core/build/classes/kotlin/main").toUri()),
            )
        )

        /**
         * Plugin-only classes in the mixed root package `com.submodule.branchswitcher`
         * (core classes like EnvironmentFailure and PresetLoader live there too, so the
         * package itself cannot be forbidden). Derived from the compiled classpath so a
         * new plugin class in the root package is covered automatically.
         */
        private val PLUGIN_ROOT_CLASSES = pluginClassesRegex("com.submodule.branchswitcher")

        /**
         * Every package the JDK's `java.desktop` module exports. Core must stay off all
         * of them: unlike `com.intellij.*`, `java.desktop` sits on core's compile
         * classpath (`jvmToolchain` compiles against the full JDK), so a desktop import
         * would compile silently — this bytecode rule is the only gate.
         */
        private val DESKTOP_UI_PACKAGES = arrayOf(
            "java..applet..",
            "java..awt..",
            "java..beans..",
            "javax..accessibility..",
            "javax..imageio..",
            "javax..print..",
            "javax..sound..",
            "javax..swing..",
        )

        /** Classes in [pkg] provided by the plugin module only (present in MAIN, absent from CORE). */
        private fun pluginClassesIn(pkg: String): Set<String> {
            fun inPackage(classes: JavaClasses) = classes.that(resideInAPackage(pkg)).map { it.name }.toSet()
            return inPackage(MAIN_CLASSES) - inPackage(CORE_CLASSES)
        }

        /** `^pkg\.(?:name1|name2|...)$` over the plugin classes in [pkg], for ArchUnit FQN matching. */
        private fun pluginClassesRegex(pkg: String): String {
            val escaped = pluginClassesIn(pkg).sorted().joinToString("|") { Regex.escape(it.substringAfterLast('.')) }
            return "^${pkg.replace(".", "\\.")}\\.(?:$escaped)$"
        }
    }

    private fun check(rule: ArchRule) = rule.check(MAIN_CLASSES)

    private fun checkCore(rule: ArchRule) = rule.check(CORE_CLASSES)

    /**
     * Vacuity guard: a rule whose package pattern matches no class would otherwise
     * "pass" silently. Each package the rules depend on must be non-empty.
     */
    private fun assertPackagePresent(pkg: String) {
        assertFalse(
            "no classes matched $pkg - its rules would pass vacuously",
            MAIN_CLASSES.that(resideInAPackage(pkg)).size == 0,
        )
    }

    @Test
    fun `workflow does not depend on platform, ui, or service`() {
        check(
            noClasses()
                .that().resideInAPackage("..workflow..")
                .should().dependOnClassesThat().resideInAnyPackage("..platform..", "..ui..", "..service..")
        )
    }

    @Test
    fun `workflow does not depend on IntelliJ Platform API`() {
        check(
            noClasses()
                .that().resideInAPackage("..workflow..")
                .should().dependOnClassesThat().resideInAnyPackage("com.intellij..")
        )
    }

    @Test
    fun `workflow does not depend on plugin implementation classes`() {
        check(
            noClasses()
                .that().resideInAPackage("..workflow..")
                .should().dependOnClassesThat().resideInAnyPackage("com.submodule.branchswitcher.git.impl..")
                .orShould().dependOnClassesThat().haveNameMatching("$PLUGIN_ROOT_CLASSES")
        )
    }

    /**
     * The pure-JVM contract enforced here is defense in depth: the Gradle module boundary
     * already keeps `com.intellij.*` and plugin classes off core's compile classpath, so
     * this catches a future build.gradle change (e.g. an IntelliJ dependency sneaking in)
     * before it silently starts coupling core to the platform.
     */
    @Test
    fun `core does not depend on the IntelliJ Platform or plugin implementation classes`() {
        checkCore(
            noClasses()
                .that().resideInAPackage("com.submodule.branchswitcher..")
                .should().dependOnClassesThat().resideInAnyPackage(
                    "com.intellij..",
                    "com.submodule.branchswitcher.ui..",
                    "com.submodule.branchswitcher.service..",
                    "com.submodule.branchswitcher.platform..",
                    "com.submodule.branchswitcher.action..",
                    "com.submodule.branchswitcher.git.impl..",
                )
                .orShould().dependOnClassesThat().haveNameMatching("$PLUGIN_ROOT_CLASSES")
        )
    }

    /**
     * Core must stay free of desktop UI: every `java.desktop` module package is banned
     * (see [DESKTOP_UI_PACKAGES]). The quickCheck text rule that used to trip on these
     * was removed when the text checks were consolidated, and this bytecode rule is what
     * keeps the pure-JVM contract enforceable.
     */
    @Test
    fun `core does not depend on desktop UI`() {
        checkCore(
            noClasses()
                .that().resideInAPackage("com.submodule.branchswitcher..")
                .should().dependOnClassesThat().resideInAnyPackage(*DESKTOP_UI_PACKAGES)
        )
    }

    @Test
    fun `platform does not depend on workflow, ui, or service`() {
        check(
            noClasses()
                .that().resideInAPackage("..platform..")
                .should().dependOnClassesThat().resideInAnyPackage("..workflow..", "..ui..", "..service..")
        )
    }

    @Test
    fun `service does not depend on workflow, platform, or ui`() {
        check(
            noClasses()
                .that().resideInAPackage("..service..")
                .should().dependOnClassesThat().resideInAnyPackage("..workflow..", "..platform..", "..ui..")
        )
    }

    @Test
    fun `switch does not depend on ui`() {
        check(
            noClasses()
                .that().resideInAPackage("..switch..")
                .should().dependOnClassesThat().resideInAnyPackage("..ui..")
        )
    }

    @Test
    fun `only GitProcessRunner and GitOps start operating-system processes`() {
        check(
            noClasses()
                .that().resideOutsideOfPackage("com.submodule.branchswitcher.git.impl")
                .should().callConstructor(ProcessBuilder::class.java)
        )
    }

    @Test
    fun `no class starts operating-system processes through Runtime exec`() {
        check(
            noClasses()
                .should().callMethod(Runtime::class.java, "exec")
        )
    }

    @Test
    fun `derived plugin class whitelist for the root package is non-empty`() {
        assertTrue(
            "root package must contain plugin classes",
            pluginClassesIn("com.submodule.branchswitcher").isNotEmpty(),
        )
    }

    @Test
    fun `derived whitelist includes known root plugin classes and excludes core classes`() {
        val rootClasses = pluginClassesIn("com.submodule.branchswitcher")
        assertTrue(rootClasses.contains("com.submodule.branchswitcher.TaskBridge"))
        assertFalse(rootClasses.contains("com.submodule.branchswitcher.PresetLoader"))
        // resideInAPackage must be exact-package matching: a subpackage class must not
        // leak into the root package's plugin set, or the whitelists would over-match.
        assertFalse(rootClasses.contains("com.submodule.branchswitcher.git.GitCommandClient"))
    }

    @Test
    fun `layer packages scanned by the rules contain classes`() {
        assertPackagePresent("..workflow..")
        assertPackagePresent("..platform..")
        assertPackagePresent("..service..")
        assertPackagePresent("..switch..")
        assertPackagePresent("..git..")
    }
}
