package tools.dscode.studio.process;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class ManagedProcessService implements AutoCloseable {
    public static final int DEFAULT_LIST_LIMIT = 20;
    public static final int DEFAULT_OUTPUT_CHARS = 64 * 1024;
    private static final int MAX_HISTORY = 100;
    private static final int MAX_OUTPUT_CHARS = 256 * 1024;

    private final WorkspaceProcessService processes;
    private final LinkedHashMap<String, ManagedRun> runs = new LinkedHashMap<>();

    public ManagedProcessService(WorkspaceProcessService processes) {
        this.processes = processes;
    }

    public ManagedProcessSummary start(
            List<String> command,
            String workingDirectory,
            Integer timeoutSeconds
    ) {
        int timeout = timeoutSeconds == null
                ? WorkspaceProcessService.DEFAULT_TIMEOUT_SECONDS
                : timeoutSeconds;
        return start(command, Path.of(workingDirectory == null || workingDirectory.isBlank() ? "." : workingDirectory),
                timeout, Map.of());
    }

    public ManagedProcessSummary start(
            List<String> command,
            Path workingDirectory,
            int timeoutSeconds,
            Map<String, String> environment
    ) {
        WorkspaceProcessService.ProcessExecution execution =
                processes.start(command, workingDirectory, timeoutSeconds, environment);
        ManagedRun run = new ManagedRun(UUID.randomUUID().toString(), execution, timeoutSeconds);
        synchronized (runs) {
            runs.put(run.id, run);
            trimHistory();
        }
        run.start();
        return run.summary();
    }

    public ManagedProcessSummary status(String id) {
        return require(id).summary();
    }

    public List<ManagedProcessSummary> list(Integer requestedLimit) {
        int limit = requestedLimit == null ? DEFAULT_LIST_LIMIT : requestedLimit;
        if (limit <= 0 || limit > MAX_HISTORY) {
            throw new IllegalArgumentException("Process history limit must be between 1 and " + MAX_HISTORY);
        }

        List<ManagedProcessSummary> summaries;
        synchronized (runs) {
            summaries = runs.values().stream()
                    .map(ManagedRun::summary)
                    .toList();
        }
        List<ManagedProcessSummary> newestFirst = new ArrayList<>(summaries);
        Collections.reverse(newestFirst);
        return newestFirst.stream().limit(limit).toList();
    }

    public ProcessOutputChunk output(
            String id,
            Long stdoutOffset,
            Long stderrOffset,
            Integer requestedMaxChars
    ) {
        long outOffset = stdoutOffset == null ? 0 : stdoutOffset;
        long errOffset = stderrOffset == null ? 0 : stderrOffset;
        if (outOffset < 0 || errOffset < 0) {
            throw new IllegalArgumentException("Process output offsets must not be negative");
        }

        int maxChars = requestedMaxChars == null ? DEFAULT_OUTPUT_CHARS : requestedMaxChars;
        if (maxChars <= 0 || maxChars > MAX_OUTPUT_CHARS) {
            throw new IllegalArgumentException(
                    "Process output maxChars must be between 1 and " + MAX_OUTPUT_CHARS
            );
        }

        ManagedRun run = require(id);
        WorkspaceProcessService.BufferSlice stdout = run.execution.stdout().read(outOffset, maxChars);
        WorkspaceProcessService.BufferSlice stderr = run.execution.stderr().read(errOffset, maxChars);

        return new ProcessOutputChunk(
                id,
                run.state,
                stdout.offset(),
                stdout.text(),
                stdout.nextOffset(),
                stdout.gap(),
                stdout.truncated(),
                stderr.offset(),
                stderr.text(),
                stderr.nextOffset(),
                stderr.gap(),
                stderr.truncated()
        );
    }

    public ManagedProcessSummary cancel(String id) {
        ManagedRun run = require(id);
        run.cancel();
        return run.summary();
    }

    @Override
    public void close() {
        List<ManagedRun> active;
        synchronized (runs) {
            active = runs.values().stream()
                    .filter(run -> run.state == ProcessState.RUNNING)
                    .toList();
        }
        active.forEach(ManagedRun::cancel);
    }

    private ManagedRun require(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Process id must not be blank");
        }
        synchronized (runs) {
            ManagedRun run = runs.get(id);
            if (run == null) {
                throw new IllegalArgumentException("Unknown Studio process id: " + id);
            }
            return run;
        }
    }

    private void trimHistory() {
        if (runs.size() <= MAX_HISTORY) {
            return;
        }
        Iterator<Map.Entry<String, ManagedRun>> iterator = runs.entrySet().iterator();
        while (runs.size() > MAX_HISTORY && iterator.hasNext()) {
            Map.Entry<String, ManagedRun> entry = iterator.next();
            if (entry.getValue().state != ProcessState.RUNNING) {
                iterator.remove();
            }
        }
    }

    private static final class ManagedRun {
        private final String id;
        private final WorkspaceProcessService.ProcessExecution execution;
        private final int timeoutSeconds;
        private final String startedAt = Instant.now().toString();

        private volatile ProcessState state = ProcessState.RUNNING;
        private volatile Integer exitCode;
        private volatile String completedAt;
        private volatile Thread lifecycle;

        private ManagedRun(
                String id,
                WorkspaceProcessService.ProcessExecution execution,
                int timeoutSeconds
        ) {
            this.id = id;
            this.execution = execution;
            this.timeoutSeconds = timeoutSeconds;
        }

        private void start() {
            lifecycle = Thread.ofVirtual()
                    .name("studio-process-" + id)
                    .start(this::awaitCompletion);
        }

        private void awaitCompletion() {
            try {
                boolean finished = execution.process().waitFor(timeoutSeconds, TimeUnit.SECONDS);
                if (!finished) {
                    synchronized (this) {
                        if (state == ProcessState.RUNNING) {
                            state = ProcessState.TIMED_OUT;
                        }
                    }
                    execution.terminate();
                }

                execution.process().waitFor();
                execution.joinCapture();
                exitCode = execution.process().exitValue();

                synchronized (this) {
                    if (state == ProcessState.RUNNING) {
                        state = exitCode == 0 ? ProcessState.SUCCEEDED : ProcessState.FAILED;
                    }
                    completedAt = Instant.now().toString();
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                execution.terminateQuietly();
                synchronized (this) {
                    if (state == ProcessState.RUNNING) {
                        state = ProcessState.CANCELLED;
                    }
                    completedAt = Instant.now().toString();
                }
            } catch (RuntimeException error) {
                synchronized (this) {
                    if (state == ProcessState.RUNNING) {
                        state = ProcessState.FAILED;
                    }
                    completedAt = Instant.now().toString();
                }
            }
        }

        private synchronized void cancel() {
            if (state != ProcessState.RUNNING || !execution.process().isAlive()) {
                return;
            }
            state = ProcessState.CANCELLED;
            execution.terminateQuietly();
        }

        private ManagedProcessSummary summary() {
            return new ManagedProcessSummary(
                    id,
                    execution.command(),
                    execution.workingDirectory(),
                    state,
                    exitCode,
                    startedAt,
                    completedAt,
                    timeoutSeconds
            );
        }
    }
}
