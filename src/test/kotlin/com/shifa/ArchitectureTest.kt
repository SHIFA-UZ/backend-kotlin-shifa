package com.shifa

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaModifier
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.freeze.FreezingArchRule.freeze
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

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

        /** A class that depends on a repository, a JDBC query or an external gateway of some feature. */
        val useInfrastructureClasses = object : DescribedPredicate<JavaClass>("use infrastructure classes") {
            override fun test(input: JavaClass): Boolean = input.directDependenciesFromSelf.any {
                it.targetClass.packageName.startsWith("com.shifa.") && it.targetClass.packageName.contains(".infrastructure")
            }
        }

        /** Class-level `@Transactional`, or every public method annotated; reports each unannotated public method. */
        val beTransactional = object : ArchCondition<JavaClass>("be @Transactional on the class or on every public method") {
            override fun check(item: JavaClass, events: ConditionEvents) {
                if (item.isAnnotatedWith(Transactional::class.java)) return
                item.methods
                    .filter { JavaModifier.PUBLIC in it.modifiers && JavaModifier.STATIC !in it.modifiers && JavaModifier.SYNTHETIC !in it.modifiers }
                    .filterNot { it.name.contains('$') || it.name in setOf("equals", "hashCode", "toString") }
                    .filterNot { it.isAnnotatedWith(Transactional::class.java) }
                    .forEach { events.add(SimpleConditionEvent.violated(it, "${it.fullName} is public and not @Transactional")) }
            }
        }
    }

    /** Roadmap P3: an application service that touches repositories, queries or gateways runs its public methods in a transaction. */
    @ArchTest
    val applicationServicesAreTransactional: ArchRule = freeze(
        classes().that().resideInAPackage("com.shifa..application..")
            .and().areAnnotatedWith(Service::class.java)
            .and(useInfrastructureClasses)
            .should(beTransactional)
            .`as`("application services that use infrastructure classes are transactional (class-level or every public method)"),
    )

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
        // Every feature is evaluated, then all failures are reported together.
        val failures = FEATURES.mapNotNull { feature ->
            val result = freeze(
                noClasses().that().resideOutsideOfPackage("com.shifa.$feature..")
                    .should().dependOnClassesThat().resideInAPackage("com.shifa.$feature..infrastructure..")
                    .`as`("only the $feature feature depends on $feature infrastructure classes"),
            ).evaluate(classes)
            if (result.hasViolation()) result.failureReport.toString() else null
        }
        if (failures.isNotEmpty()) throw AssertionError(failures.joinToString("\n\n"))
    }
}
