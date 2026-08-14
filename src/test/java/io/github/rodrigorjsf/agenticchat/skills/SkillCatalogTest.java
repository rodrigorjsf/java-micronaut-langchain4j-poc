package io.github.rodrigorjsf.agenticchat.skills;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The skill catalogue is where the token budget of the whole tool surface is
 * decided, so it is tested as a budget and not only as wiring.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SkillCatalogTest {

    private ApplicationContext ctx;
    private SkillCatalog catalog;

    @BeforeAll
    void setUp() {
        ctx = ApplicationContext.run(Map.of(
                "agentic.llm.credentials.google-api-key", "fake",
                "agentic.llm.credentials.openai-api-key", "fake"));
        catalog = ctx.getBean(SkillCatalog.class);
    }

    @AfterAll
    void tearDown() {
        if (ctx != null) {
            ctx.close();
        }
    }

    @Test
    void loadsEverySkillOnTheClasspath() {
        assertThat(catalog.names()).contains("brazil-civic-data", "geo-and-weather");
    }

    @Test
    void theSystemPromptBlockCarriesOnlyNamesAndDescriptions() {
        var block = catalog.availableSkillsBlock();

        assertThat(block).contains("<available_skills>", "brazil-civic-data", "geo-and-weather");
        assertThat(block)
                .as("tool schemas must not leak into the standing prompt — that is the whole point")
                .doesNotContain("lookup_cep")
                .doesNotContain("get_weather");
    }

    /**
     * The index ships on every request for the life of every conversation, so it is
     * budgeted rather than left to grow. The comparison that justifies the design:
     * 50 tool schemas cost roughly 80 tokens each — about 4000 tokens standing —
     * whereas the index costs one description per skill.
     *
     * <p>The per-skill ceiling is deliberately generous at 90 tokens. A description
     * is the only thing the model sees before activating a skill, so squeezing it
     * below the point where it carries routing information would save tokens by
     * making the routing worse.
     */
    @Test
    void theStandingCostOfTheToolSurfaceStaysWithinBudget() {
        String block = catalog.availableSkillsBlock();
        int approxTokens = block.length() / 4;
        int perSkill = approxTokens / catalog.names().size();

        System.out.printf("skills index: %d skills, %d chars, ~%d tokens (~%d per skill)%n",
                catalog.names().size(), block.length(), approxTokens, perSkill);

        assertThat(perSkill)
                .as("per-skill index cost")
                .isLessThanOrEqualTo(90);
        assertThat(approxTokens)
                .as("total standing cost of the whole tool surface")
                .isLessThan(1_500);
    }

    @Test
    void everySkillDescriptionSaysWhenToUseIt() {
        // The description is the only thing the model sees before activating, so it
        // has to carry routing information, not just a title.
        for (String name : catalog.names()) {
            String description = descriptionOf(name);
            assertThat(description)
                    .as("description of '%s'", name)
                    .hasSizeGreaterThan(60)
                    .hasSizeLessThan(400)
                    .containsIgnoringCase("use");
        }
    }

    @Test
    void skillsExposeADynamicToolProvider() {
        var provider = catalog.skills().toolProvider();

        assertThat(provider.isDynamic())
                .as("skill-scoped tools only appear after activate_skill, which requires a dynamic provider")
                .isTrue();
    }

    /**
     * A house-style check applied to every tool in the application. These
     * descriptions are the routing signal, so drift in them is a behaviour
     * regression that no functional test would catch.
     */
    @Test
    void everyToolFollowsTheDescriptionHouseStyle() {
        List<SkillTools> beans = ctx.getBeansOfType(SkillTools.class).stream().toList();
        assertThat(beans).isNotEmpty();

        for (SkillTools bean : beans) {
            for (ToolSpecification spec : ToolSpecifications.toolSpecificationsFrom(bean)) {
                assertThat(spec.name())
                        .as("tool name %s", spec.name())
                        .matches("[a-z][a-z0-9_]{2,63}");
                assertThat(spec.description())
                        .as("description of %s", spec.name())
                        .isNotNull()
                        .hasSizeGreaterThan(40)
                        .hasSizeLessThan(600);
                spec.parameters().properties().forEach((param, schema) ->
                        assertThat(schema.description())
                                .as("parameter %s of %s must document its format", param, spec.name())
                                .isNotNull()
                                .isNotBlank());
            }
        }
    }

    @Test
    @DisplayName("no tool parameter is named like a credential")
    void noToolAcceptsASecret() {
        // A @Tool parameter called apiKey or token is an invitation: the model will
        // eventually fill it, and the value leaves the process in a query string.
        var forbidden = java.util.regex.Pattern.compile(
                "(?i).*(api[_-]?key|secret|token|password|passwd|credential|authorization).*");

        for (SkillTools bean : ctx.getBeansOfType(SkillTools.class)) {
            for (ToolSpecification spec : ToolSpecifications.toolSpecificationsFrom(bean)) {
                assertThat(spec.parameters().properties().keySet())
                        .as("tool %s", spec.name())
                        .noneMatch(param -> forbidden.matcher(param).matches());
            }
        }
    }

    @Test
    @DisplayName("tool names are unique across every skill")
    void toolNamesAreUniqueAcrossSkills() {
        // Two tools with one name means the model cannot address one of them, and
        // which one it reaches depends on map ordering.
        var seen = new java.util.HashMap<String, String>();
        for (SkillTools bean : ctx.getBeansOfType(SkillTools.class)) {
            for (ToolSpecification spec : ToolSpecifications.toolSpecificationsFrom(bean)) {
                var previous = seen.put(spec.name(), bean.skillName());
                assertThat(previous)
                        .as("tool '%s' is declared by both %s and %s", spec.name(), previous, bean.skillName())
                        .isNull();
            }
        }
    }

    private String descriptionOf(String skillName) {
        var block = catalog.availableSkillsBlock();
        int nameAt = block.indexOf("<name>" + skillName + "</name>");
        int descAt = block.indexOf("<description>", nameAt);
        int end = block.indexOf("</description>", descAt);
        return block.substring(descAt + "<description>".length(), end);
    }
}
