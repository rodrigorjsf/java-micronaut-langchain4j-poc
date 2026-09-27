package io.github.rodrigorjsf.agenticchat.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Runs git for the diff modes of the two test reports. One runner, so the two cannot drift on how
 * they treat stderr: stdout stays pure (it can be file content, as with {@code git show ref:path}),
 * and stderr is kept apart for the error message.
 */
public final class Git {

    private Git() {
    }

    /** What one git command produced. A non-zero exit is an answer here, not an error. */
    public record Result(int exitCode, String stdout, String stderr) {
    }

    public static Result run(Path directory, String... arguments) {
        var command = new ArrayList<String>();
        command.add("git");
        command.addAll(List.of(arguments));
        try {
            Process process = new ProcessBuilder(command).directory(directory.toFile()).start();
            // Drained concurrently: a command that fills the stderr pipe would otherwise block
            // while stdout is still being read.
            var stderr = CompletableFuture.supplyAsync(() -> read(process.getErrorStream()));
            String stdout = read(process.getInputStream());
            return new Result(process.waitFor(), stdout, stderr.join());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not run git in " + directory, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running git", e);
        }
    }

    /** Stdout of a command that must succeed; a failure carries git's own stderr. */
    public static String runOrThrow(Path directory, String... arguments) {
        var result = run(directory, arguments);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("git " + String.join(" ", arguments) + " failed: "
                    + result.stderr().strip());
        }
        return result.stdout();
    }

    /** Non-blank output lines, stripped. */
    public static List<String> lines(String output) {
        return output.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
    }

    private static String read(InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
