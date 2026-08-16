package tools.dscode.studio.process;

import tools.dscode.studio.workspace.WorkspaceInfo;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class WorkspaceProcessService {
    public static final int DEFAULT_TIMEOUT_SECONDS = 120;
    static final int MAX_CAPTURE_CHARS = 2 * 1024 * 1024;

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
        int timeout = timeoutSeconds == null ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds;
        ProcessExecution execution = start(command, workingDirectory, timeout, Map.of());
        return await(execution, timeout);
    }

    public ProcessResult run(
            List<String> command,
            Path workingDirectory,
            int timeoutSeconds,
            Map<String, String> environment
    ) {
        ProcessExecution execution = start(command, workingDirectory, timeoutSeconds, environment);
        return await(execution, timeoutSeconds);
    }

    ProcessExecution start(
            List<String> command,
            String workingDirectory,
            int timeoutSeconds,
            Map<String, String> environment
    ) {
        String value = workingDirectory == null || workingDirectory.isBlank() ? "." : workingDirectory;
        return start(command, Path.of(value), timeoutSeconds, environment);
    }

    ProcessExecution start(
            List<String> command,
            Path workingDirectory,
            int timeoutSeconds,
            Map<String, String> environment
    ) {
        validate(command, timeoutSeconds);
        Path directory = resolveWorkingDirectory(workingDirectory);
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
        builder.environment().putAll(environment);

        try {
            return new ProcessExecution(
                    List.copyOf(command),
                    workspaceRoot.relativize(directory).toString(),
                    builder.start()
            );
        } catch (IOException error) {
            throw new IllegalStateException("Unable to start process: " + String.join(" ", command), error);
        }
    }

    private ProcessResult await(ProcessExecution execution, int timeoutSeconds) {
        long started = System.nanoTime();
        try {
            boolean finished = execution.process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                execution.terminate();
            }

            execution.joinCapture();
            long durationMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
            BufferSnapshot stdout = execution.stdout.snapshot();
            BufferSnapshot stderr = execution.stderr.snapshot();

            return new ProcessResult(
                    execution.command,
                    execution.workingDirectory,
                    finished ? execution.process.exitValue() : -1,
                    !finished,
                    durationMillis,
                    stdout.text(),
                    stderr.text(),
                    stdout.truncated(),
                    stderr.truncated()
            );
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            execution.terminateQuietly();
            throw new IllegalStateException("Process execution was interrupted", error);
        }
    }

    private static void validate(List<String> command, int timeoutSeconds) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("Process command must not be empty");
        }
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("Process timeout must be greater than zero");
        }
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

    static final class ProcessExecution {
        private final List<String> command;
        private final String workingDirectory;
        private final Process process;
        private final OutputBuffer stdout = new OutputBuffer();
        private final OutputBuffer stderr = new OutputBuffer();
        private final StreamCapture stdoutCapture;
        private final StreamCapture stderrCapture;
        private final Thread stdoutThread;
        private final Thread stderrThread;

        private ProcessExecution(List<String> command, String workingDirectory, Process process) {
            this.command = command;
            this.workingDirectory = workingDirectory;
            this.process = process;
            this.stdoutCapture = new StreamCapture(process.getInputStream(), stdout);
            this.stderrCapture = new StreamCapture(process.getErrorStream(), stderr);
            this.stdoutThread = Thread.ofVirtual().name("studio-process-stdout").start(stdoutCapture);
            this.stderrThread = Thread.ofVirtual().name("studio-process-stderr").start(stderrCapture);
        }

        List<String> command() {
            return command;
        }

        String workingDirectory() {
            return workingDirectory;
        }

        Process process() {
            return process;
        }

        OutputBuffer stdout() {
            return stdout;
        }

        OutputBuffer stderr() {
            return stderr;
        }

        void joinCapture() throws InterruptedException {
            stdoutThread.join();
            stderrThread.join();
            stdoutCapture.rethrow();
            stderrCapture.rethrow();
        }

        void terminate() throws InterruptedException {
            List<ProcessHandle> descendants = process.descendants().toList();
            descendants.forEach(ProcessHandle::destroy);
            process.destroy();

            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                descendants.stream()
                        .filter(ProcessHandle::isAlive)
                        .forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor();
            } else {
                descendants.stream()
                        .filter(ProcessHandle::isAlive)
                        .forEach(ProcessHandle::destroyForcibly);
            }
        }

        void terminateQuietly() {
            try {
                terminate();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }

    static final class OutputBuffer {
        private final StringBuilder text = new StringBuilder();
        private long baseOffset;
        private boolean truncated;

        synchronized void append(char[] chars, int length) {
            text.append(chars, 0, length);
            int excess = text.length() - MAX_CAPTURE_CHARS;
            if (excess > 0) {
                text.delete(0, excess);
                baseOffset += excess;
                truncated = true;
            }
        }

        synchronized BufferSnapshot snapshot() {
            return new BufferSnapshot(baseOffset, text.toString(), baseOffset + text.length(), truncated);
        }

        synchronized BufferSlice read(long requestedOffset, int maxChars) {
            long startOffset = Math.max(requestedOffset, baseOffset);
            boolean gap = requestedOffset < baseOffset;
            int start = Math.toIntExact(startOffset - baseOffset);
            int end = Math.min(text.length(), start + maxChars);
            String value = text.substring(start, end);
            return new BufferSlice(startOffset, value, startOffset + value.length(), gap, truncated);
        }
    }

    record BufferSnapshot(long offset, String text, long nextOffset, boolean truncated) {
    }

    record BufferSlice(long offset, String text, long nextOffset, boolean gap, boolean truncated) {
    }

    private static final class StreamCapture implements Runnable {
        private final InputStream input;
        private final OutputBuffer output;
        private volatile IOException failure;

        private StreamCapture(InputStream input, OutputBuffer output) {
            this.input = input;
            this.output = output;
        }

        @Override
        public void run() {
            char[] buffer = new char[4096];
            try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                int read;
                while ((read = reader.read(buffer)) >= 0) {
                    output.append(buffer, read);
                }
            } catch (IOException error) {
                failure = error;
            }
        }

        private void rethrow() {
            if (failure != null) {
                throw new IllegalStateException("Unable to capture process output", failure);
            }
        }
    }
}
