package io.github.rodrigorjsf.agenticchat.evals.scenario;

import io.github.rodrigorjsf.agenticchat.evals.scenario.ArtifactInventory.Artifact;
import io.github.rodrigorjsf.agenticchat.evals.scenario.ArtifactInventory.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactInventoryTest {

    private static final String TOOLS = "src/main/java/demo/tools/WeatherTools.java";
    private static final String AGENT = "src/main/java/demo/workflow/ReporterAgent.java";
    private static final String PROMPT = "src/main/java/demo/agent/SystemPromptBuilder.java";
    private static final String CONFIG = "src/main/resources/application.yml";

    private static final String TOOLS_SOURCE = """
            package demo.tools;

            public class WeatherTools {

                @Tool(\"""
                        Find a place by name.\""")
                public String find_place(
                        @P("The place name") String name) {
                    return helper(name);
                }

                @Tool("Get the forecast for coordinates.")
                public String get_weather(@P("latitude") double lat, @P("longitude") double lon) {
                    return helper(lat + "," + lon);
                }

                private String helper(String q) {
                    return q;
                }
            }
            """;

    private static final String PROMPT_SOURCE = """
            package demo.agent;

            /**
             * Sections: {@code # Role}, {@code # Skills}.
             */
            public class SystemPromptBuilder {
                SystemPromptBuilder() {
                    this.prompt = \"""
                            # Role

                            You are the assistant.

                            # Skills

                            Activate a skill first.

                            %s
                            \""".formatted(skills);
                }
            }
            """;

    private static final String CONFIG_SOURCE = """
            agentic:
              tools:
                apis:
                  open-meteo-forecast:
                    base-url: https://api.open-meteo.com
                    max-response-bytes: 16384
                  brasilapi:
                    base-url: https://brasilapi.com.br/api
            """;

    private static Map<String, String> tree() {
        var files = new HashMap<String, String>();
        files.put("src/main/resources/skills/geo-and-weather/SKILL.md", "---\nname: geo-and-weather\n---\nBody.");
        files.put("src/main/resources/skills/brazil-finance/SKILL.md", "---\nname: brazil-finance\n---\nBody.");
        files.put(TOOLS, TOOLS_SOURCE);
        files.put(AGENT, """
                public interface ReporterAgent {
                    @Agent(name = "weather_reporter", description = "Reports weather")
                    String report(@V("place") String place);
                }
                """);
        files.put(PROMPT, PROMPT_SOURCE);
        files.put("src/main/resources/prompt/CALCULATION.md", "# Arithmetic\n\nUse calculate.\n");
        files.put("src/main/resources/voice/VOICE.md", "# Voice\n\n## Tone\n\nWarm.\n");
        files.put(CONFIG, CONFIG_SOURCE);
        files.put("src/main/java/demo/Other.java", "class Other { /* A {@code @Tool} mention */ }");
        return files;
    }

    @Test
    @DisplayName("the inventory names every skill, tool, prompt section, catalogue key and sub-agent")
    void inventoriesEveryArtifactKind() {
        var inventory = ArtifactInventory.scan(tree());

        assertThat(inventory.artifacts()).containsExactlyInAnyOrder(
                new Artifact(Kind.SKILL, "brazil-finance"),
                new Artifact(Kind.SKILL, "geo-and-weather"),
                new Artifact(Kind.TOOL, "find_place"),
                new Artifact(Kind.TOOL, "get_weather"),
                new Artifact(Kind.SYSTEM_PROMPT_SECTION, "system-prompt#role"),
                new Artifact(Kind.SYSTEM_PROMPT_SECTION, "system-prompt#skills"),
                new Artifact(Kind.SYSTEM_PROMPT_SECTION, "system-prompt#arithmetic"),
                new Artifact(Kind.SYSTEM_PROMPT_SECTION, "system-prompt#voice"),
                new Artifact(Kind.CATALOGUE_KEY, "open-meteo-forecast"),
                new Artifact(Kind.CATALOGUE_KEY, "brasilapi"),
                new Artifact(Kind.SUB_AGENT, "weather_reporter"));
    }

    @Test
    @DisplayName("editing one skill's document changes that skill only")
    void aSkillEditChangesTheSkill() {
        var after = tree();
        after.put("src/main/resources/skills/geo-and-weather/SKILL.md", "---\nname: geo-and-weather\n---\nNew body.");

        assertThat(changed(tree(), after)).containsExactly("geo-and-weather");
    }

    @Test
    @DisplayName("a resource file inside a skill directory belongs to that skill")
    void aSkillResourceBelongsToTheSkill() {
        var after = tree();
        after.put("src/main/resources/skills/brazil-finance/references/selic.md", "SELIC notes");

        assertThat(changed(tree(), after)).containsExactly("brazil-finance");
    }

    @Test
    @DisplayName("editing one tool's description changes that tool, not its neighbour in the same class")
    void aToolDescriptionEditChangesThatToolOnly() {
        var after = tree();
        after.put(TOOLS, TOOLS_SOURCE.replace("Get the forecast for coordinates.", "Get a 7-day forecast."));

        assertThat(changed(tree(), after)).containsExactly("get_weather");
    }

    @Test
    @DisplayName("editing code a tool class shares changes every tool it declares")
    void aSharedCodeEditChangesEveryToolOfTheClass() {
        var after = tree();
        after.put(TOOLS, TOOLS_SOURCE.replace("return q;", "return q.strip();"));

        assertThat(changed(tree(), after)).containsExactlyInAnyOrder("find_place", "get_weather");
    }

    @Test
    @DisplayName("editing one system-prompt section changes that section only")
    void aPromptSectionEditChangesThatSection() {
        var after = tree();
        after.put(PROMPT, PROMPT_SOURCE.replace("You are the assistant.", "You are a careful assistant."));

        assertThat(changed(tree(), after)).containsExactly("system-prompt#role");
    }

    @Test
    @DisplayName("the arithmetic and voice documents are system-prompt sections of their own")
    void appendedDocumentsAreSections() {
        var after = tree();
        after.put("src/main/resources/prompt/CALCULATION.md", "# Arithmetic\n\nAlways use calculate.\n");
        after.put("src/main/resources/voice/VOICE.md", "# Voice\n\n## Tone\n\nDry.\n");

        assertThat(changed(tree(), after))
                .containsExactlyInAnyOrder("system-prompt#arithmetic", "system-prompt#voice");
    }

    @Test
    @DisplayName("editing one catalogue entry changes that key only; a comment changes nothing")
    void aCatalogueEditChangesThatKey() {
        var after = tree();
        after.put(CONFIG, CONFIG_SOURCE.replace("16384", "32768") + "# a trailing comment\n");

        assertThat(changed(tree(), after)).containsExactly("open-meteo-forecast");
    }

    @Test
    @DisplayName("editing a sub-agent changes it")
    void aSubAgentEditChangesIt() {
        var after = tree();
        after.put(AGENT, after.get(AGENT).replace("Reports weather", "Reports the weather in plain words"));

        assertThat(changed(tree(), after)).containsExactly("weather_reporter");
    }

    @Test
    @DisplayName("an artifact added or removed since the ref counts as changed")
    void addedAndRemovedArtifactsAreChanged() {
        var after = tree();
        after.remove("src/main/resources/skills/brazil-finance/SKILL.md");
        after.put("src/main/resources/skills/science-and-space/SKILL.md", "---\nname: science-and-space\n---\n");

        assertThat(changed(tree(), after)).containsExactlyInAnyOrder("brazil-finance", "science-and-space");
    }

    @Test
    @DisplayName("the repository's own sources yield the artifacts the committed dataset names")
    void scansThisRepository() {
        var inventory = ArtifactInventory.scan(ArtifactInventory.readWorkingTree(Path.of(System.getProperty("user.dir"))));

        assertThat(inventory.ids()).contains("geo-and-weather", "get_weather", "open-meteo-forecast",
                "weather_reporter", "trip_briefing", "system-prompt#role", "system-prompt#non-negotiable-rules",
                "system-prompt#how-to-answer", "system-prompt#arithmetic", "system-prompt#voice");
        assertThat(inventory.ids()).doesNotContain("system-prompt#code");
    }

    private static java.util.Set<String> changed(Map<String, String> before, Map<String, String> after) {
        return ArtifactInventory.changed(ArtifactInventory.scan(before), ArtifactInventory.scan(after));
    }
}
