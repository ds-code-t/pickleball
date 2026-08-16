package tools.dscode.studio.process;

import tools.dscode.studio.workspace.WorkspaceInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class WorkspaceProcessService {
    public static final int DEFAULT_TIMEOUT_SECONDS = 120;
    private static final int MAX_CAPTURE_BYTES = 2 * 1024 * 1024;

    private final Path workspaceRoot;
    private final Path realWorkspaceRoot;

    public WorkspaceProcessService(WorkspaceInfo workspace) {
        this.workspaceRoot = workspace.root();
        try {
            this.realWorkspaceRoot = workspace.root().toRealPath();
        } catch (IOException error) {
            throw new IllegalArgumentException("Unable to resolve workspace: " + workspace.root(), error);
        }
    }

    public ProcessResult run(List<String> command, String workingDirectory, Integer timeoutSeconds) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("Process command must not be empty");
        }

        int timeout = timeoutSeconds == null ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds;
        if (timeout <= 0) {
            throw new IllegalArgumentException("Process timeout must be greater than zero");
        }

        Path directory = resolveWorkingDirectory(workingDirectory);
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
        return execute(builder, command, directory, timeout);
    }

    public ProcessResult run(List<String> command, Path workingDirectory, int timeoutSeconds, Map<String, String> environment) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("Process command must not be empty");
        }
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("Process timeout must be greater than zero");
        }
        Path directory = resolveWorkingDirectory(workingDirectory);
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
        builder.environment().putAll(environment);
        return execute(builder, command, directory, timeoutSeconds);
    }

    private ProcessResult execute(ProcessBuilder builder, List<String> command, Path directory, int timeoutSeconds) {
        long started = System.nanoTime();
        try {
            Process process = builder.start();
            StreamCapture stdout = new StreamCapture(process.getInputStream());
            StreamCapture stderr = new StreamCapture(process.getErrorStream());
            Thread stdoutThread = Thread.ofVirtual().start(stdout);
            Thread stderrThread = Thread.ofVirtual().start(stderr);

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor();
                }
            }

            stdoutThread.join();
            stderrThread.join();
            stdout.rethrow();
            stderr.rethrow();

            long durationMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
            return new ProcessResult(
                    command,
                    workspaceRoot.relativize(directory).toString(),
                    finished ? process.exitValue() : -1,
                    !finished,
                    durationMillis,
                    stdout.text(),
                    stderr.text(),
                    stdout.truncated(),
                    stderr.truncated()
            );
        } catch (IOException error) {
            throw new IllegalStateException("Unable to start process: " + String.join(" ", command), error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Process execution was interrupted", error);
        }
    }

    private Path resolveWorkingDirectory(String requested) {
        String value = requested == null || requested.isBlank() ? "." : requested;
        return resolveWorkingDirectory(Path.of(value));
    }

    private Path resolveWorkingDirectory(Path requested) {
        Path resolved = requested.isAbsolute()
                ? requested.toAbsolutePath().normalize()
                : workspaceRoot.resolve(requested).normalize();
        if (!Files.isDirectory(resolved)) {
            throw new IllegalArgumentException("Process working directory does not exist: " + resolved);
        }
        try {
            Path real = resolved.toRealPath();
            if (!real.startsWith(realWorkspaceRoot)) {
                throw new IllegalArgumentException("Process working directory is outside the workspace: " + requested);
            }
            return resolved;
        } catch (IOException error) {
            throw new IllegalArgumentException("Unable to resolve process working directory: " + resolved, error);
        }
    }

    private static final class StreamCapture implements Runnable {
        private final InputStream input;
        private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        private volatile boolean truncated;
        private volatile IOException failure;

        private StreamCapture(InputStream input) {
            this.input = input;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[8192];
            try (input) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    int remaining = MAX_CAPTURE_BYTES - captured.size();
                    if (remaining > 0) {
                        captured.write(buffer, 0, Math.min(read, remaining));
                    }
                    if (read > remaining) {
                        truncated = true;
                    }
                }
            } catch (IOException error) {
                failure = error;
            }
        }

        private String text() {
            return captured.toString(StandardCharsets.UTF_8);
        }

        private boolean truncated() {
            return truncated;
        }

        private void rethrow() {
            if (failure != null) {
                throw new IllegalStateException("Unable to capture process output", failure);
            }
        }
    }
}
