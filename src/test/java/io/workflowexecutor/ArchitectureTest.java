package io.workflowexecutor;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import io.workflowexecutor.execution.TaskActivities;
import java.time.Instant;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Structural rules that a code review would otherwise have to check by hand. */
@AnalyzeClasses(packages = "io.workflowexecutor", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule packagesHaveNoCycles =
            slices().matching("io.workflowexecutor.(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule dependenciesPointInwards = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("api").definedBy("..api..")
            .layer("run").definedBy("..run..")
            .layer("execution").definedBy("..execution..")
            .layer("definition").definedBy("..definition..")
            .layer("config").definedBy("..config..")
            .whereLayer("api").mayNotBeAccessedByAnyLayer()
            .whereLayer("run").mayOnlyBeAccessedByLayers("api")
            .whereLayer("execution").mayOnlyBeAccessedByLayers("run", "config")
            .whereLayer("config").mayOnlyBeAccessedByLayers("run")
            .whereLayer("definition").mayOnlyBeAccessedByLayers("api", "run", "execution", "config");

    @ArchTest
    static final ArchRule definitionRulesKnowNothingAboutTemporalOrSpring = noClasses()
            .that().resideInAPackage("..definition..")
            .should().dependOnClassesThat().resideInAnyPackage("io.temporal..", "org.springframework..")
            .because("the definition contract and its rules are plain Java that any runtime can host");

    /** Temporal replays workflow code from history and expects the same decisions every time. */
    @ArchTest
    static final ArchRule workflowCodeIsDeterministic = noClasses()
            .that().resideInAPackage("..execution..")
            .and().areNotAssignableTo(TaskActivities.class)
            .should().callMethod(System.class, "currentTimeMillis")
            .orShould().callMethod(System.class, "nanoTime")
            .orShould().callMethod(Instant.class, "now")
            .orShould().callMethod(UUID.class, "randomUUID")
            .orShould().dependOnClassesThat().belongToAnyOf(Thread.class, Random.class, ThreadLocalRandom.class)
            .because("workflow code must take time from Workflow.currentTimeMillis and wait through Temporal");
}
