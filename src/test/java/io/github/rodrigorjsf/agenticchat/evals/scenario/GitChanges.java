package io.github.rodrigorjsf.agenticchat.evals.scenario;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Diff mode's git side: which artifacts differ between a ref and the working tree.
 *
 * <p>The working tree, not {@code HEAD}: the change a developer is about to commit is exactly the
 * one whose scenarios they want to run. Uncommitted edits and untracked files both count.
 *
 * <p>Paths are relative to {@code repositoryRoot} on both sides ({@code --relative}), so the root
 * need not be the top of the git checkout.
 *
 * <p>Only the files git reports as changed are read at the ref; every other file is the same on
 * both sides, so the "before" tree is the working tree with those files swapped back.
 */
public final class GitChanges {

    private GitChanges() {
    }

    public static Set<String> changedArtifactsSince(Path repositoryRoot, String ref) {
        if (run(repositoryRoot, "rev-parse", "--verify", "--quiet", ref + "^{commit}").exitCode() != 0) {
            throw new IllegalArgumentException(ScenarioSelection.CHANGED_SINCE + " names '" + ref
                    + "', which git cannot resolve to a commit in " + repositoryRoot);
        }
        var changedPaths = new TreeSet<String>();
        changedPaths.addAll(lines(runOrThrow(repositoryRoot, "diff", "--name-only", "--relative", "--no-renames", ref, "--", "src/main")));
        changedPaths.addAll(lines(runOrThrow(repositoryRoot, "ls-files", "--others", "--exclude-standard", "--", "src/main")));
        changedPaths.removeIf(path -> !ArtifactInventory.isInventoried(path));

        var after = ArtifactInventory.readWorkingTree(repositoryRoot);
        var before = new HashMap<>(after);
        for (String path : changedPaths) {
            before.remove(path);
            var atRef = run(repositoryRoot, "show", ref + ":" + path);
            if (atRef.exitCode() == 0) {
                before.put(path, atRef.output());
            }
        }
        return ArtifactInventory.changed(ArtifactInventory.scan(before), ArtifactInventory.scan(after));
    }

    private record Result(int exitCode, String output) {
    }

    private static String runOrThrow(Path directory, String... args) {
        var result = run(directory, args);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + result.output());
        }
        return result.output();
    }

    private static Result run(Path directory, String... args) {
        var command = new ArrayList<String>();
        command.add("git");
        command.addAll(List.of(args));
        try {
            var process = new ProcessBuilder(command).directory(directory.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new Result(process.waitFor(), output);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not run git in " + directory, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running git", e);
        }
    }

    private static List<String> lines(String output) {
        return output.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
    }
}
