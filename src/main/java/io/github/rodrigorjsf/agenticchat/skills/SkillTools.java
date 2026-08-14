package io.github.rodrigorjsf.agenticchat.skills;

/**
 * A bean carrying {@code @Tool} methods that belong to one skill.
 *
 * <p>The binding is declared by the tool bean rather than by a central list, so
 * adding a tool is one file and never a merge conflict, and a tool cannot end up
 * orphaned in the catalogue without a skill to disclose it.
 *
 * <p>Why the grouping matters at all: with 50+ tools, putting every schema in the
 * system prompt would cost thousands of tokens on every single turn and degrade
 * selection accuracy — the model picks worse from a list of fifty than from a list
 * of six. Skills make disclosure progressive: the prompt carries only skill names
 * and one-line descriptions, and a skill's tools appear only after the model
 * activates it.
 */
public interface SkillTools {

    /**
     * @return the {@code name} in the skill's {@code SKILL.md} front matter
     */
    String skillName();
}
