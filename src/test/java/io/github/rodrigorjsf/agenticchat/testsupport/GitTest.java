package io.github.rodrigorjsf.agenticchat.testsupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitTest {

    @TempDir
    Path repository;

    @Test
    @DisplayName("a failing command reports git's own error, so a bad ref is diagnosable")
    void aFailureCarriesGitsStderr() {
        Git.runOrThrow(repository, "init", "--quiet");

        assertThatThrownBy(() -> Git.runOrThrow(repository, "diff", "--name-only", "no-such-ref"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("git diff --name-only no-such-ref failed")
                .hasMessageContaining("no-such-ref");
        assertThat(Git.run(repository, "diff", "--name-only", "no-such-ref").stderr())
                .as("stderr is captured apart from stdout")
                .isNotBlank();
    }

    @Test
    @DisplayName("stdout stays pure: nothing git prints on stderr leaks into it")
    void stdoutExcludesStderr() {
        Git.runOrThrow(repository, "init", "--quiet");

        var result = Git.run(repository, "rev-parse", "--verify", "no-such-ref");

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.stdout()).isEmpty();
    }
}
