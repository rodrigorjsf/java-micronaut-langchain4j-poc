package io.github.rodrigorjsf.agenticchat.evals.scenario;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitChangesTest {

    @TempDir
    Path repo;

    @Test
    @DisplayName("artifacts changed since a ref: committed after it, edited in the working tree, or untracked")
    void mapsEveryKindOfChangeToItsArtifact() throws Exception {
        write("src/main/resources/skills/geo-and-weather/SKILL.md", "weather body");
        write("src/main/resources/skills/brazil-finance/SKILL.md", "finance body");
        write("src/main/resources/skills/fun-and-trivia/SKILL.md", "trivia body");
        write("README.md", "readme");
        git("init", "-q");
        git("add", ".");
        git("commit", "-q", "-m", "base");
        git("tag", "base");

        write("src/main/resources/skills/brazil-finance/SKILL.md", "finance body, committed later");
        git("commit", "-q", "-am", "later");
        write("src/main/resources/skills/geo-and-weather/SKILL.md", "weather body, edited");
        write("src/main/resources/skills/science-and-space/SKILL.md", "new, untracked");
        write("README.md", "readme, edited");

        assertThat(GitChanges.changedArtifactsSince(repo, "base"))
                .containsExactlyInAnyOrder("brazil-finance", "geo-and-weather", "science-and-space");
    }

    @Test
    @DisplayName("a ref git cannot resolve is refused with its name")
    void anUnknownRefIsRefused() throws Exception {
        write("README.md", "readme");
        git("init", "-q");
        git("add", ".");
        git("commit", "-q", "-m", "base");

        assertThatThrownBy(() -> GitChanges.changedArtifactsSince(repo, "no-such-ref"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no-such-ref");
    }

    private void write(String path, String content) throws IOException {
        var file = repo.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private void git(String... args) throws Exception {
        var command = new java.util.ArrayList<>(java.util.List.of("git", "-c", "user.name=test",
                "-c", "user.email=test@example.com", "-c", "commit.gpgsign=false"));
        command.addAll(java.util.List.of(args));
        var process = new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).as(output).isZero();
    }
}
