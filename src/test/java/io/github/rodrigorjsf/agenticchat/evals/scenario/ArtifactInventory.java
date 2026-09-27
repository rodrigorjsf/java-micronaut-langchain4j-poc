package io.github.rodrigorjsf.agenticchat.evals.scenario;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Every artifact of this repository that can change what the model answers, each with a
 * fingerprint of the text that defines it.
 *
 * <p>An artifact identifier is what a scenario's {@code dependsOn} names:
 * <table>
 *   <caption>Artifact identifiers</caption>
 *   <tr><th>Kind</th><th>Identifier</th><th>Defined by</th></tr>
 *   <tr><td>skill</td><td>directory name, e.g. {@code geo-and-weather}</td>
 *       <td>every file under {@code src/main/resources/skills/<name>/}</td></tr>
 *   <tr><td>tool</td><td>method name, e.g. {@code get_weather}</td>
 *       <td>its {@code @Tool} header plus the code its class shares</td></tr>
 *   <tr><td>system-prompt section</td><td>{@code system-prompt#<heading slug>}, e.g. {@code system-prompt#role}</td>
 *       <td>a {@code # Heading} of {@code SystemPromptBuilder}'s text block, a {@code prompt/*.md}
 *       appendix, or the voice document ({@code system-prompt#voice})</td></tr>
 *   <tr><td>catalogue key</td><td>the key under {@code agentic.tools.apis}, e.g. {@code open-meteo-forecast}</td>
 *       <td>that entry's parsed value in {@code application.yml}</td></tr>
 *   <tr><td>sub-agent</td><td>its {@code @Agent(name = ...)}, e.g. {@code weather_reporter}</td>
 *       <td>the whole source file</td></tr>
 * </table>
 *
 * <p>Diff mode compares two inventories. A fingerprint may be coarser than the artifact, never
 * finer: editing a tool's description changes that tool only, but editing code its class shares
 * changes every tool of the class, because the scan cannot tell which tools reach that code.
 * Over-selecting costs a scenario run; under-selecting would let a change ship untested.
 *
 * @param fingerprints artifact to the text that defines it
 */
public record ArtifactInventory(Map<Artifact, String> fingerprints) {

    public enum Kind { SKILL, TOOL, SYSTEM_PROMPT_SECTION, CATALOGUE_KEY, SUB_AGENT }

    public record Artifact(Kind kind, String id) {
    }

    static final String SKILLS = "src/main/resources/skills/";
    static final String JAVA = "src/main/java/";
    static final String PROMPT_APPENDICES = "src/main/resources/prompt/";
    static final String VOICE = "src/main/resources/voice/VOICE.md";
    static final String CONFIG = "src/main/resources/application.yml";
    static final String PROMPT_BUILDER = "SystemPromptBuilder.java";
    static final String SECTION_PREFIX = "system-prompt#";

    private static final Pattern TOOL_ANNOTATION = Pattern.compile("(?m)^[ \\t]*@Tool\\b");
    private static final Pattern PUBLIC_METHOD = Pattern.compile("(?m)^[ \\t]*public\\s+[^=;(]*?\\b(\\w+)\\s*\\(");
    private static final Pattern AGENT_NAME = Pattern.compile("@Agent\\s*\\([^)]*?\\bname\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern HEADING = Pattern.compile("^[ \\t]*# (.+)$");

    public ArtifactInventory {
        fingerprints = Collections.unmodifiableMap(new LinkedHashMap<>(fingerprints));
    }

    public Set<Artifact> artifacts() {
        return fingerprints.keySet();
    }

    public Set<String> ids() {
        return fingerprints.keySet().stream().map(Artifact::id).collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * The identifiers of every artifact whose fingerprint differs between the two inventories,
     * including artifacts present in only one of them.
     */
    public static Set<String> changed(ArtifactInventory before, ArtifactInventory after) {
        var changed = new TreeSet<String>();
        var all = new HashSet<>(before.fingerprints.keySet());
        all.addAll(after.fingerprints.keySet());
        for (Artifact artifact : all) {
            if (!Objects.equals(before.fingerprints.get(artifact), after.fingerprints.get(artifact))) {
                changed.add(artifact.id());
            }
        }
        return changed;
    }

    /**
     * @param files repository-relative path ({@code /}-separated) to file content
     */
    public static ArtifactInventory scan(Map<String, String> files) {
        var fingerprints = new LinkedHashMap<Artifact, String>();
        var sorted = new TreeMap<>(files);
        skills(sorted, fingerprints);
        sorted.forEach((path, content) -> {
            if (path.startsWith(JAVA) && path.endsWith(".java")) {
                tools(path, content, fingerprints);
                subAgent(content, fingerprints);
                if (path.endsWith("/" + PROMPT_BUILDER)) {
                    promptSections(content, fingerprints);
                }
            } else if (path.startsWith(PROMPT_APPENDICES) && path.endsWith(".md")) {
                String fallback = path.substring(path.lastIndexOf('/') + 1, path.length() - ".md".length());
                String first = content.lines().findFirst().orElse("");
                Matcher heading = HEADING.matcher(first);
                put(fingerprints, Kind.SYSTEM_PROMPT_SECTION,
                        SECTION_PREFIX + slug(heading.matches() ? heading.group(1) : fallback), content);
            } else if (path.equals(VOICE)) {
                put(fingerprints, Kind.SYSTEM_PROMPT_SECTION, SECTION_PREFIX + "voice", content);
            } else if (path.equals(CONFIG)) {
                catalogue(content, fingerprints);
            }
        });
        return new ArtifactInventory(fingerprints);
    }

    /** The inventory of a checkout as it is on disk, uncommitted edits included. */
    public static ArtifactInventory ofWorkingTree(Path repositoryRoot) {
        return scan(readWorkingTree(repositoryRoot));
    }

    /** Reads every file under {@code src/main} of a checkout, keyed the way {@link #scan} expects. */
    public static Map<String, String> readWorkingTree(Path repositoryRoot) {
        var main = repositoryRoot.resolve("src/main");
        try (Stream<Path> paths = Files.walk(main)) {
            var files = new HashMap<String, String>();
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                String relative = repositoryRoot.relativize(file).toString().replace('\\', '/');
                if (isInventoried(relative)) {
                    files.put(relative, Files.readString(file, StandardCharsets.UTF_8));
                }
            }
            return files;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the sources under " + main, e);
        }
    }

    /** Whether a repository-relative path can hold an artifact at all. */
    static boolean isInventoried(String path) {
        return path.startsWith(SKILLS)
                || (path.startsWith(JAVA) && path.endsWith(".java"))
                || (path.startsWith(PROMPT_APPENDICES) && path.endsWith(".md"))
                || path.equals(VOICE)
                || path.equals(CONFIG);
    }

    private static void skills(Map<String, String> sorted, Map<Artifact, String> fingerprints) {
        var bySkill = new TreeMap<String, StringBuilder>();
        sorted.forEach((path, content) -> {
            if (path.startsWith(SKILLS) && path.indexOf('/', SKILLS.length()) > 0) {
                String skill = path.substring(SKILLS.length(), path.indexOf('/', SKILLS.length()));
                bySkill.computeIfAbsent(skill, k -> new StringBuilder())
                        .append(path).append('\n').append(content).append('\n');
            }
        });
        bySkill.forEach((skill, text) -> put(fingerprints, Kind.SKILL, skill, text.toString()));
    }

    /**
     * A tool's header runs from its {@code @Tool} to the opening brace of its method: the
     * description and parameters the model reads. What is left of the file once every header is
     * cut out is code the class's tools may share, so it is part of every tool's fingerprint.
     */
    private static void tools(String path, String source, Map<Artifact, String> fingerprints) {
        var starts = new ArrayList<Integer>();
        Matcher annotation = TOOL_ANNOTATION.matcher(source);
        while (annotation.find()) {
            starts.add(annotation.start());
        }
        var headers = new ArrayList<Header>();
        for (int i = 0; i < starts.size(); i++) {
            int limit = i + 1 < starts.size() ? starts.get(i + 1) : source.length();
            Matcher method = PUBLIC_METHOD.matcher(source).region(starts.get(i), limit);
            int brace = method.find() ? source.indexOf('{', method.end()) : -1;
            if (brace < 0 || brace > limit) {
                // Crediting the annotation to the next tool's method would drop this tool from the
                // inventory without a trace, so it can neither be selected nor reported uncovered.
                throw new IllegalStateException("A @Tool in " + path
                        + " is not followed by a public method before the next @Tool; the inventory cannot name it");
            }
            headers.add(new Header(method.group(1), starts.get(i), brace));
        }
        var shared = new StringBuilder();
        int from = 0;
        for (Header header : headers) {
            shared.append(source, from, header.start());
            from = header.end();
        }
        shared.append(source.substring(from));
        headers.forEach(header -> put(fingerprints, Kind.TOOL, header.name(),
                source.substring(header.start(), header.end()) + "\n--\n" + shared));
    }

    /** The span of a tool's header: from its {@code @Tool} to its method's opening brace. */
    private record Header(String name, int start, int end) {
    }

    private static void subAgent(String source, Map<Artifact, String> fingerprints) {
        Matcher agent = AGENT_NAME.matcher(source);
        while (agent.find()) {
            put(fingerprints, Kind.SUB_AGENT, agent.group(1), source);
        }
    }

    /** Sections are the {@code # Heading} lines inside the prompt text block, and only those. */
    private static void promptSections(String source, Map<Artifact, String> fingerprints) {
        int start = source.indexOf("this.prompt = \"\"\"");
        if (start < 0) {
            return;
        }
        int end = source.indexOf("\"\"\"", start + "this.prompt = \"\"\"".length());
        List<String> lines = source.substring(start, end < 0 ? source.length() : end).lines().toList();
        String section = null;
        var body = new StringBuilder();
        for (String line : lines) {
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                if (section != null) {
                    put(fingerprints, Kind.SYSTEM_PROMPT_SECTION, section, body.toString());
                }
                section = SECTION_PREFIX + slug(heading.group(1));
                body.setLength(0);
            }
            body.append(line.strip()).append('\n');
        }
        if (section != null) {
            put(fingerprints, Kind.SYSTEM_PROMPT_SECTION, section, body.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private static void catalogue(String yaml, Map<Artifact, String> fingerprints) {
        Object node = new Yaml().load(yaml);
        for (String key : List.of("agentic", "tools", "apis")) {
            node = node instanceof Map<?, ?> map ? map.get(key) : null;
        }
        if (node instanceof Map<?, ?> apis) {
            ((Map<String, Object>) apis).forEach((key, value) ->
                    put(fingerprints, Kind.CATALOGUE_KEY, key, String.valueOf(value)));
        }
    }

    /**
     * A {@code dependsOn} entry is a bare identifier, so two artifacts sharing one — of the same
     * kind or not — could not be told apart: an edit to one would select, or cover, the other.
     */
    private static void put(Map<Artifact, String> fingerprints, Kind kind, String id, String text) {
        fingerprints.keySet().stream().filter(existing -> existing.id().equals(id)).findFirst()
                .ifPresent(existing -> {
                    throw new IllegalStateException("Two artifacts are named '" + id + "' (" + existing.kind()
                            + " and " + kind + "); a scenario's dependsOn could not tell them apart");
                });
        fingerprints.put(new Artifact(kind, id), text);
    }

    private static String slug(String heading) {
        return heading.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }
}
