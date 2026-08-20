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
         * Plugin-implemented classes that live in the mixed `com.submodule.branchswitcher.git`
         * package (core interfaces share that package, so it cannot be forbidden wholesale).
         * Derived from the compiled classpath rather than hand-maintained: every plugin
         * class in the shared package is covered automatically, so adding a new
         * implementation can never silently weaken the rules below.
         */
        private val PLUGIN_GIT_IMPLS = pluginClassesRegex("com.submodule.branchswitcher.git")

        /**
         * Plugin-only classes in the mixed root package `com.submodule.branchswitcher`
         * (core classes like EnvironmentFailure and PresetLoader live there too, so the
         * package itself cannot be forbidden). Derived like [PLUGIN_GIT_IMPLS].
         */
        private val PLUGIN_ROOT_CLASSES = pluginClassesRegex("com.submodule.branchswitcher")

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
                .should().dependOnClassesThat().haveNameMatching(
                    "$PLUGIN_ROOT_CLASSES|$PLUGIN_GIT_IMPLS"
                )
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
                )
                .orShould().dependOnClassesThat().haveNameMatching(
                    "$PLUGIN_ROOT_CLASSES|$PLUGIN_GIT_IMPLS"
                )
        )
    }

    /**
     * Core must stay free of desktop UI. Unlike `com.intellij.*` (a Maven dependency
     * the module boundary excludes), `java.awt`/`javax.swing` live in the JDK's
     * `java.desktop` module, which `jvmToolchain(21)` puts on every compile classpath,
     * so a Swing import in core would compile silently. The quickCheck text rule that
     * used to trip on it was removed when the text checks were consolidated, and this
     * bytecode rule is what keeps the pure-JVM contract enforceable.
     */
    @Test
    fun `core does not depend on desktop UI`() {
        checkCore(
            noClasses()
                .that().resideInAPackage("com.submodule.branchswitcher..")
                .should().dependOnClassesThat().resideInAnyPackage("java..awt..", "javax..swing..")
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
                .that().haveNameNotMatching("^com\\.submodule\\.branchswitcher\\.git\\.(?:GitProcessRunner|GitOps)\$")
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
    fun `derived plugin class whitelists are non-empty`() {
        assertTrue(
            "git package must contain plugin classes",
            pluginClassesIn("com.submodule.branchswitcher.git").isNotEmpty(),
        )
        assertTrue(
            "root package must contain plugin classes",
            pluginClassesIn("com.submodule.branchswitcher").isNotEmpty(),
        )
    }

    @Test
    fun `derived whitelist includes known plugin classes and excludes core classes`() {
        val gitClasses = pluginClassesIn("com.submodule.branchswitcher.git")
        assertTrue(gitClasses.contains("com.submodule.branchswitcher.git.GitProcessRunner"))
        assertTrue(gitClasses.contains("com.submodule.branchswitcher.git.GitOutputDrainerKt"))
        assertFalse(gitClasses.contains("com.submodule.branchswitcher.git.GitQueryKt"))
        assertFalse(gitClasses.contains("com.submodule.branchswitcher.git.GitResult"))

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
