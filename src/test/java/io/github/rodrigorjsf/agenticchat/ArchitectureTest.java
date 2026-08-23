package io.github.rodrigorjsf.agenticchat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.dependencies.SliceRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The package boundaries described in {@code CONTEXT.md}, made executable.
 *
 * <p>A boundary that is only written down is a suggestion. These tests are the
 * reason the project can be one Maven module instead of several
 * ({@code docs/adr/0001}): the seams are enforced at test time rather than by the
 * build graph.
 */
class ArchitectureTest {

    private static final String ROOT = "io.github.rodrigorjsf.agenticchat";

    private static final java.util.Set<String> MUTABLE_COLLECTIONS = java.util.Set.of(
            "java.util.HashMap", "java.util.ArrayList", "java.util.HashSet",
            "java.util.LinkedHashMap", "java.util.TreeMap",
            "java.util.concurrent.ConcurrentHashMap");

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(location -> !location.contains("/generated"))
                .importPackages(ROOT);
    }

    @Test
    @DisplayName("no cycles between the bounded contexts")
    void noPackageCycles() {
        SliceRule rule = SlicesRuleDefinition.slices()
                .matching(ROOT + ".(*)..")
                .should().beFreeOfCycles();
        rule.check(classes);
    }

    @Test
    @DisplayName("infra is the bottom of the dependency graph")
    void infraDependsOnNothingAboveIt() {
        noClasses().that().resideInAPackage(ROOT + ".infra..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        ROOT + ".api..", ROOT + ".agent..", ROOT + ".conversation..",
                        ROOT + ".triage..", ROOT + ".guardrail..", ROOT + ".skills..",
                        ROOT + ".tools..")
                .because("infra holds clients and configuration; anything it knew about "
                        + "the domain would invert the dependency")
                .check(classes);
    }

    @Test
    @DisplayName("observability is the bottom of the dependency graph")
    void observabilityDependsOnNothingAboveIt() {
        // Without this rule the claim in CONTEXT.md is a sentence rather than a
        // constraint: `noPackageCycles` does not catch it, because api, guardrail and
        // tools do not import observability, so an observability -> tools edge would be a
        // ONE-WAY dependency and pass. A tracing layer that reached into the pipeline it
        // observes is exactly the cycle AgentTracer exists to prevent.
        noClasses().that().resideInAPackage(ROOT + ".observability..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        ROOT + ".api..", ROOT + ".agent..", ROOT + ".conversation..",
                        ROOT + ".triage..", ROOT + ".guardrail..", ROOT + ".skills..",
                        ROOT + ".tools..", ROOT + ".rag..", ROOT + ".memory..")
                .because("every other package imports AgentTracer; a tracing layer that "
                        + "knew the pipeline back would close the cycle it exists to avoid")
                .check(classes);
    }

    @Test
    @DisplayName("the skill catalogue knows nothing about the layers that use it")
    void skillsDependOnNothingAboveIt() {
        noClasses().that().resideInAPackage(ROOT + ".skills..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        ROOT + ".api..", ROOT + ".agent..", ROOT + ".conversation..",
                        ROOT + ".triage..", ROOT + ".guardrail..")
                .because("the catalogue is a leaf: the agent, triage and the API all read it, "
                        + "and none of them may be visible from inside it")
                .check(classes);
    }

    @Test
    @DisplayName("the HTTP layer never reaches past the conversation service")
    void apiTalksOnlyToTheConversationAndSkillLayers() {
        noClasses().that().resideInAPackage(ROOT + ".api..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(ROOT + ".llm..", ROOT + ".guardrail..", ROOT + ".tools..")
                .because("a controller reaching into the model or guardrail layer would put "
                        + "pipeline decisions in two places")
                .check(classes);
    }

    @Test
    @DisplayName("tools reach the network only through ToolHttpClient")
    void toolsDoNotOpenTheirOwnConnections() {
        noClasses().that().resideInAPackage(ROOT + ".tools..")
                .and().resideOutsideOfPackage(ROOT + ".tools.http..")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("io.micronaut.http.client.HttpClient")
                .orShould().dependOnClassesThat()
                .haveFullyQualifiedName("java.net.http.HttpClient")
                .orShould().dependOnClassesThat()
                .haveFullyQualifiedName("java.net.URL")
                .because("the single door is what makes the SSRF control, the response budget "
                        + "and the error redaction unavoidable")
                .check(classes);
    }

    @Test
    @DisplayName("no static field is reassignable")
    void noNonFinalStaticFields() {
        // A mutable static is a per-JVM singleton pretending to be global state, and a
        // non-volatile one carries no memory-visibility guarantee across threads at
        // all. With virtual threads serving concurrent conversations that is a
        // correctness problem, not a style one.
        //
        // allowEmptyShould: the rule currently matches nothing, which is the desired
        // state. Without it ArchUnit treats "no matches" as a failure, so a passing
        // codebase would fail its own rule.
        // haveNameNotMatching("\\$.*") skips compiler-synthesized fields. Eclipse's
        // compiler emits a non-final `$SWITCH_TABLE$...` for switches over enums,
        // and it is not code anyone wrote.
        fields().that().areStatic()
                .and().haveNameNotMatching("\\$.*")
                .should().beFinal()
                .allowEmptyShould(true)
                .because("a reassignable static is shared state with no owner and no "
                        + "visibility guarantee between threads")
                .check(classes);
    }

    @Test
    @DisplayName("no static field holds a mutable collection")
    void noStaticMutableCollections() {
        // `static final Map<..>` is still mutable: final protects the reference, not
        // the contents. A shared map used as a cache or a counter is the classic way
        // this arrives.
        var mutableCollection = com.tngtech.archunit.base.DescribedPredicate.describe(
                "a mutable collection type",
                (com.tngtech.archunit.core.domain.JavaClass type) -> MUTABLE_COLLECTIONS.contains(type.getName()));

        noFields().that().areStatic()
                .should().haveRawType(mutableCollection)
                .allowEmptyShould(true)
                .because("a static collection is a per-JVM cache with no eviction, no owner "
                        + "and no coordination between replicas")
                .check(classes);
    }
}
