package com.lms.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * A single deployable turns to mud quietly. These rules make it loud instead.
 *
 * <p>The repository rule used to name one package and exempt another, which meant it described
 * less than the README claimed. It now covers every feature.
 */
@AnalyzeClasses(packages = "com.lms", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule features_do_not_depend_on_each_other_in_a_circle =
            SlicesRuleDefinition.slices().matching("com.lms.(*)..").should().beFreeOfCycles();

    /**
     * Storage is private to its feature. Everything else goes through that feature's service,
     * which is what owns the transaction and the rules.
     */
    @ArchTest
    static final ArchRule a_features_repository_is_its_own = noClasses()
            .that().resideOutsideOfPackage("com.lms.user..")
            .should().accessClassesThat().resideInAPackage("com.lms.user.repository..")
            .because("user storage belongs to the user feature");

    @ArchTest
    static final ArchRule course_storage_is_private = noClasses()
            .that().resideOutsideOfPackage("com.lms.course..")
            .should().accessClassesThat().resideInAPackage("com.lms.course.repository..")
            .because("course storage belongs to the course feature");

    @ArchTest
    static final ArchRule enrollment_storage_is_private = noClasses()
            .that().resideOutsideOfPackage("com.lms.enrollment..")
            .should().accessClassesThat().resideInAPackage("com.lms.enrollment.repository..")
            .because("enrolment storage belongs to the enrolment feature");

    @ArchTest
    static final ArchRule payment_storage_is_private = noClasses()
            .that().resideOutsideOfPackage("com.lms.payment..")
            .should().accessClassesThat().resideInAPackage("com.lms.payment.repository..")
            .because("payment storage belongs to the payment feature");

    @ArchTest
    static final ArchRule analytics_storage_is_private = noClasses()
            .that().resideOutsideOfPackage("com.lms.analytics..")
            .should().accessClassesThat().resideInAPackage("com.lms.analytics.repository..")
            .because("reporting storage belongs to the analytics feature");

    @ArchTest
    static final ArchRule outbox_storage_is_private = noClasses()
            .that().resideOutsideOfPackage("com.lms.outbox..")
            .should().accessClassesThat().resideInAPackage("com.lms.outbox.repository..")
            .because("outbox storage belongs to the outbox feature");

    /** One feature's entities are its own; others get a record of the facts they need. */
    @ArchTest
    static final ArchRule features_do_not_share_entities = noClasses()
            .that().resideOutsideOfPackages("com.lms.course..", "com.lms.common..")
            .should().accessClassesThat().resideInAPackage("com.lms.course.model..")
            .because("other features take CourseFacts, not the catalog's storage model");

    @ArchTest
    static final ArchRule controllers_are_not_called_from_anywhere = noClasses()
            .that().resideOutsideOfPackage("com.lms..controller..")
            .should().accessClassesThat().resideInAPackage("com.lms..controller..")
            .because("controllers are an entry point, not a collaborator");

    @ArchTest
    static final ArchRule controllers_do_not_reach_past_services = noClasses()
            .that().resideInAPackage("com.lms..controller..")
            .should().accessClassesThat().resideInAPackage("com.lms..repository..")
            .because("controllers talk to services, which own the transaction boundary");

    @ArchTest
    static final ArchRule models_do_not_know_about_the_web = noClasses()
            .that().resideInAPackage("com.lms..model..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework.web..", "jakarta.servlet..", "com.lms..controller..",
                    "com.lms..dto..")
            .because("business rules must not be shaped by the HTTP layer");
}
