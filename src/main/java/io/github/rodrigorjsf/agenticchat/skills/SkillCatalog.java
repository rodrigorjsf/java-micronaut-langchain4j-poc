package io.github.rodrigorjsf.agenticchat.skills;

import dev.langchain4j.skills.ClassPathSkillLoader;
import dev.langchain4j.skills.DefaultSkill;
import dev.langchain4j.skills.Skill;
import dev.langchain4j.skills.Skills;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Loads the skill definitions from the classpath and binds each one to its tools.
 *
 * <p>A skill is a directory under {@code resources/skills/} holding a
 * {@code SKILL.md} with YAML front matter ({@code name}, {@code description}) and a
 * Markdown body. Any sibling file becomes a resource the model can pull on demand.
 * The format is the Agent Skills specification, so these files are portable to any
 * harness that speaks it rather than being a project-local invention.
 *
 * <p><b>The token argument.</b> This project has 50+ tools. Declaring them all on
 * the AI service would put every schema in the system prompt on every turn — several
 * thousand tokens per request, unchanged for the life of the conversation, and a
 * measurably worse tool choice because the model selects less accurately from a list
 * of fifty than from a list of six. With skills, the standing cost is one line per
 * skill (name plus description) and two management tools; a skill's tools appear only
 * after the model calls {@code activate_skill}, and the full instructions arrive at
 * the same moment. That is progressive disclosure applied to the tool surface itself.
 *
 * <p><b>Why not tool search.</b> LangChain4j also ships a {@code ToolSearchStrategy}
 * that hides tools until the model searches for them. It is a real alternative, but
 * the two do not compose: a dynamic {@code ToolProvider} — which is what
 * {@code Skills} returns once any skill owns tools — bypasses the tool-search filter
 * entirely. Skills win here because the grouping is meaningful to a human as well
 * (a skill is a documented capability, not a search result) and because the
 * instructions and the tools are disclosed together, so the model never holds a tool
 * without the guidance for using it.
 *
 * <p>Rebuilt as {@link DefaultSkill} rather than kept as the loader's
 * {@code DefaultFileSystemSkill}: the file-system variant retains a base path, and
 * nothing reachable from an LLM should hold a live handle to the file system.
 */
@Singleton
public class SkillCatalog {

    private static final Logger LOG = LoggerFactory.getLogger(SkillCatalog.class);
    private static final String SKILLS_DIRECTORY = "skills";

    private final Skills skills;
    private final List<String> names;

    public SkillCatalog(List<SkillTools> toolBeans) {
        Map<String, List<SkillTools>> toolsBySkill = toolBeans.stream()
                .collect(Collectors.groupingBy(SkillTools::skillName));

        List<Skill> loaded = ClassPathSkillLoader.loadSkills(SKILLS_DIRECTORY);
        if (loaded.isEmpty()) {
            throw new IllegalStateException(
                    "No skills found on the classpath under '" + SKILLS_DIRECTORY + "'");
        }

        var bound = new ArrayList<Skill>(loaded.size());
        var byName = new LinkedHashMap<String, Integer>();
        for (Skill skill : loaded) {
            var owned = toolsBySkill.remove(skill.name());
            var builder = DefaultSkill.builder()
                    .name(skill.name())
                    .description(skill.description())
                    .content(skill.content())
                    .resources(skill.resources());
            if (owned != null && !owned.isEmpty()) {
                builder.tools(owned.toArray());
            }
            bound.add(builder.build());
            byName.put(skill.name(), owned == null ? 0 : owned.size());
        }

        if (!toolsBySkill.isEmpty()) {
            // A tool bean naming a skill that does not exist would simply never be
            // reachable. Failing here turns a silent capability gap into a build error.
            throw new IllegalStateException(
                    "Tool beans reference skills that have no SKILL.md: " + toolsBySkill.keySet());
        }

        this.skills = Skills.from(bound);
        this.names = List.copyOf(byName.keySet());
        byName.forEach((name, count) -> LOG.info("Skill '{}' bound to {} tool bean(s)", name, count));
    }

    public Skills skills() {
        return skills;
    }

    public List<String> names() {
        return names;
    }

    /**
     * The XML block listing every skill's name and description, for the system
     * prompt. This — not the tool schemas — is the standing cost of the whole tool
     * surface.
     */
    public String availableSkillsBlock() {
        return skills.formatAvailableSkills();
    }
}
