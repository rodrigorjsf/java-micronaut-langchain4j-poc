package io.github.rodrigorjsf.agenticchat.evals.scenario;

import io.github.rodrigorjsf.agenticchat.testsupport.Git;

import java.nio.file.Path;
import java.util.HashMap;
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
        if (Git.run(repositoryRoot, "rev-parse", "--verify", "--quiet", ref + "^{commit}").exitCode() != 0) {
            throw new IllegalArgumentException(ScenarioSelection.CHANGED_SINCE + " names '" + ref
                    + "', which git cannot resolve to a commit in " + repositoryRoot);
        }
        var changedPaths = new TreeSet<String>();
        changedPaths.addAll(Git.lines(repositoryRoot, "diff", "--name-only", "--relative", "--no-renames", ref, "--", "src/main"));
        changedPaths.addAll(Git.lines(repositoryRoot, "ls-files", "--others", "--exclude-standard", "--", "src/main"));
        changedPaths.removeIf(path -> !ArtifactInventory.isInventoried(path));

        var after = ArtifactInventory.readWorkingTree(repositoryRoot);
        var before = new HashMap<>(after);
        for (String path : changedPaths) {
            before.remove(path);
            var atRef = Git.run(repositoryRoot, "show", ref + ":" + path);
            if (atRef.exitCode() == 0) {
                before.put(path, atRef.stdout());
            }
        }
        return ArtifactInventory.changed(ArtifactInventory.scan(before), ArtifactInventory.scan(after));
    }
}
