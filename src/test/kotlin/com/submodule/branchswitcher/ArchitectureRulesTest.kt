package com.submodule.branchswitcher

import com.tngtech.archunit.core.domain.JavaClass
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
                .should().dependOnClassesThat().resideInAnyPackage(
                    "com.submodule.branchswitcher.git.impl..",
                    "com.submodule.branchswitcher",
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
                    "com.submodule.branchswitcher",
                    "com.submodule.branchswitcher.ui..",
                    "com.submodule.branchswitcher.service..",
                    "com.submodule.branchswitcher.platform..",
                    "com.submodule.branchswitcher.action..",
                    "com.submodule.branchswitcher.workflow..",
                    "com.submodule.branchswitcher.git.impl..",
                )
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
    fun `layer packages scanned by the rules contain classes`() {
        assertPackagePresent("..workflow..")
        assertPackagePresent("..platform..")
        assertPackagePresent("..service..")
        assertPackagePresent("..switch..")
        assertPackagePresent("..git..")
    }

    /**
     * Every Git interface must eventually be implemented by a concrete class, so a
     * capability interface cannot silently become dead abstraction. A parent interface
     * (e.g. `GitWorkflowClient` extending several capability interfaces) is a consumer
     * only through its chain — the check requires a non-interface subclass somewhere
     * beneath it, i.e. `GitCommandClient`/`GitOps` for the real chain. Without this,
     * deleting the last implementor of an interface would pass every dependency rule
     * vacuously (no class to match) while leaving a pointless contract behind.
     */
    @Test
    fun `every git interface has a concrete implementing class`() {
        // Check against MAIN_CLASSES (both modules): the concrete implementors
        // GitCommandClient/GitOps live in the plugin module, so a core-only view would
        // report every capability interface as unimplemented. A capability interface is
        // "implemented" when some non-interface class is assignable to it — directly or
        // through the GitWorkflowClient chain.
        val interfacesWithoutImplementor = MAIN_CLASSES
            .that(resideInAPackage("com.submodule.branchswitcher.git"))
            .asSequence()
            .filter(JavaClass::isInterface)
            .filter { iface -> MAIN_CLASSES.asSequence().none { c -> !c.isInterface && c.isAssignableTo(iface.name) } }
            .map { it.simpleName }
            .toList()

        assertTrue(
            "git interfaces with no concrete implementor (dead abstraction): $interfacesWithoutImplementor",
            interfacesWithoutImplementor.isEmpty(),
        )
    }

    /**
     * Guards the cross-layer failure-contract constants: every `GIT_STDERR_*` sentinel
     * defined in core must be referenced by the plugin-layer emitter
     * (`GitProcessRunner`), so a sentinel cannot be added and forgotten, or renamed on
     * one side only. The list is derived from `GitTypes.kt` itself (not hardcoded here),
     * so a newly added sentinel is automatically required on the emitter side. The
     * classification side is already pinned by `GitResultTest`.
     */
    @Test
    fun `every git failure sentinel constant is referenced by the process runner`() {
        val gitTypesSource = java.nio.file.Files.readString(
            java.nio.file.Path.of("core/src/main/kotlin/com/submodule/branchswitcher/git/GitTypes.kt"),
        )
        val sentinels = Regex("""const val (GIT_STDERR_\w+)""")
            .findAll(gitTypesSource)
            .map { it.groupValues[1] }
            .toList()
        assertTrue("GitTypes.kt must define at least one GIT_STDERR_ sentinel", sentinels.isNotEmpty())

        val emitterSource = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/kotlin/com/submodule/branchswitcher/git/impl/GitProcessRunner.kt"),
        )
        // Match as an identifier (word-bounded), not a bare substring.
        val missing = sentinels.filter { name -> !Regex("""\b$name\b""").containsMatchIn(emitterSource) }
        assertTrue(
            "GIT_STDERR sentinels not referenced by the plugin-layer emitter: $missing",
            missing.isEmpty(),
        )
    }
}
