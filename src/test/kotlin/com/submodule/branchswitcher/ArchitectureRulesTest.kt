package com.submodule.branchswitcher

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.Location
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.Test
import java.nio.file.Path

/**
 * Bytecode-level enforcement of the one-way dependency direction. This replaces the
 * structural quickCheck source scans (layer imports, core purity, process ownership)
 * with semantic checks on compiled classes.
 *
 * Only `main` classes are imported (plugin and core modules) so that test code can
 * reference other layers freely, matching quickCheck's `src/main/kotlin` scope.
 */
class ArchitectureRulesTest {

    companion object {
        private val MAIN_CLASSES: JavaClasses = ClassFileImporter().importLocations(
            listOf(
                Location.of(Path.of("build/classes/kotlin/main").toUri()),
                Location.of(Path.of("core/build/classes/kotlin/main").toUri()),
            )
        )
    }

    private fun check(rule: ArchRule) = rule.check(MAIN_CLASSES)

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
                    "com\\.submodule\\.branchswitcher\\.(?:git\\.)?(?:TaskBridge|Bundle|Notifier|GitOps|GitCommandClient|GitProcessRunner)"
                )
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
                .that().doNotHaveSimpleName("GitProcessRunner")
                .and().doNotHaveSimpleName("GitOps")
                .should().callConstructor(ProcessBuilder::class.java)
        )
    }
}
