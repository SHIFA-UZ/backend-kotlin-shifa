package com.shifa

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.freeze.FreezingArchRule.freeze

/**
 * The feature-border rules of the modular monolith (CLAUDE.md "backend target architecture", roadmap Phase 5 step 4).
 *
 * Every rule is frozen: the violations that exist today are listed in src/test/resources/archunit-store and tolerated;
 * a new violation fails the build at once, and a violation that disappears is dropped from the store automatically
 * (commit the smaller store with the fix). See archunit.properties. Nested packages (ai.clinicalengine.domain) count.
 */
@AnalyzeClasses(packages = ["com.shifa"], importOptions = [ImportOption.DoNotIncludeTests::class])
class ArchitectureTest {

    companion object {
        val FEATURES = listOf(
            "admin", "ai", "appointments", "auth", "chat", "clinics", "doctors", "documents",
            "notifications", "patients", "payments", "tasks",
        )
    }

    /** `domain` holds entities, enums and pure rules: no web layer, no services, no repositories. */
    @ArchTest
    val domainDependsOnNothingAbove: ArchRule = freeze(
        noClasses().that().resideInAPackage("com.shifa..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("..api..", "..application..", "..infrastructure..", "org.springframework.web..")
            .`as`("domain classes depend on no api, application, infrastructure or Spring web class"),
    )

    /** Controllers call application services; repositories are not theirs to touch (step 3 of every feature). */
    @ArchTest
    val controllersDoNotUseRepositories: ArchRule = freeze(
        noClasses().that().resideInAPackage("com.shifa..api..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
            .`as`("api classes do not depend on infrastructure classes"),
    )

    /** Cross-cutting code must not know the features. */
    @ArchTest
    val sharedDependsOnNoFeature: ArchRule = freeze(
        noClasses().that().resideInAPackage("com.shifa.shared..")
            .should().dependOnClassesThat().resideInAnyPackage(*FEATURES.map { "com.shifa.$it.." }.toTypedArray())
            .`as`("shared classes do not depend on feature classes"),
    )

    /** One feature reaches another only through its application services, never its repositories. */
    @ArchTest
    fun featuresUseOtherFeaturesThroughApplicationOnly(classes: JavaClasses) {
        for (feature in FEATURES) {
            freeze(
                noClasses().that().resideOutsideOfPackage("com.shifa.$feature..")
                    .should().dependOnClassesThat().resideInAPackage("com.shifa.$feature..infrastructure..")
                    .`as`("only the $feature feature depends on $feature infrastructure classes"),
            ).check(classes)
        }
    }
}
