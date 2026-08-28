package tools.dscode.workbench.mcp;

import tools.dscode.control.protocol.ControlBridgeCallResult;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Serial execute-step queue for a headless CLI session. Heartbeat stays alive
 * while a long Selenium/explicit wait is in flight so clients see
 * {@code STILL_WORKING} instead of {@code TIMEOUT}. {@code TIMEOUT} is only
 * reported when the session heartbeat is silent.
 */
final class WorkbenchCommandQueue implements AutoCloseable {
    static final String QUEUED = "QUEUED";
    static final String RUNNING = "RUNNING";
    static final String STILL_WORKING = "STILL_WORKING";
    static final String SUCCESS = "SUCCESS";
    static final String FAILED = "FAILED";
    static final String TIMEOUT = "TIMEOUT";

    private final Function<String, Object> executeStep;
    private final long staleAfterNanos;
    private final ConcurrentHashMap<String, Command> commands = new ConcurrentHashMap<>();
    private final ExecutorService worker;
    private final ScheduledExecutorService heartbeats;
    private final AtomicLong heartbeatNanos = new AtomicLong(System.nanoTime());
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean silenced = new AtomicBoolean();
    private volatile Runnable stopHandler;

    WorkbenchCommandQueue(Function<String, Object> executeStep) {
        this(executeStep, Duration.ofSeconds(2));
    }

    WorkbenchCommandQueue(Function<String, Object> executeStep, Duration heartbeatStaleAfter) {
        this.executeStep = Objects.requireNonNull(executeStep, "executeStep");
        this.staleAfterNanos = heartbeatStaleAfter.toNanos();
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pickleball-workbench-cli-queue");
            thread.setDaemon(true);
            return thread;
        });
        this.heartbeats = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pickleball-workbench-cli-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        this.heartbeats.scheduleAtFixedRate(this::beat, 0, 200, TimeUnit.MILLISECONDS);
    }

    void setStopHandler(Runnable stopHandler) {
        this.stopHandler = stopHandler;
    }

    Map<String, Object> enqueueExecuteStep(String text, String requestedId) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("execute-step text must not be blank.");
        }
        String id = requestedId == null || requestedId.isBlank()
                ? UUID.randomUUID().toString()
                : requestedId.strip();
        Command command = new Command(id, text);
        if (commands.putIfAbsent(id, command) != null) {
            throw new IllegalArgumentException("Command id already exists: " + id);
        }
        worker.execute(() -> run(command));
        return ack(command);
    }

    Map<String, Object> status(String id) {
        Command command = commands.get(id);
        if (command == null) {
            throw new IllegalArgumentException("Unknown command id: " + id);
        }
        return command.view(heartbeatFresh());
    }

    void requestStop() {
        Runnable handler = stopHandler;
        if (handler != null) handler.run();
    }

    void silenceHeartbeat() {
        silenced.set(true);
    }

    boolean heartbeatFresh() {
        if (silenced.get()) {
            return System.nanoTime() - heartbeatNanos.get() < staleAfterNanos;
        }
        return System.nanoTime() - heartbeatNanos.get() < staleAfterNanos;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        heartbeats.shutdownNow();
        worker.shutdownNow();
    }

    private void run(Command command) {
        command.markRunning();
        try {
            Object value = executeStep.apply(command.text);
            command.complete(resultStatus(value), value);
        } catch (RuntimeException failure) {
            LinkedHashMap<String, Object> error = new LinkedHashMap<>();
            error.put("error", failure.getClass().getSimpleName());
            error.put("message", failure.getMessage() == null ? failure.toString() : failure.getMessage());
            command.complete(FAILED, error);
        }
    }

    private void beat() {
        if (!silenced.get()) heartbeatNanos.set(System.nanoTime());
    }

    private static Map<String, Object> ack(Command command) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("ack", true);
        payload.put("id", command.id);
        payload.put("status", command.ackStatus());
        return payload;
    }

    private static String resultStatus(Object value) {
        if (value instanceof ControlBridgeCallResult call) {
            return "SUCCESS".equals(call.status()) ? SUCCESS : FAILED;
        }
        if (value instanceof Map<?, ?> map) {
            Object status = map.get("status");
            if (status != null && !"SUCCESS".equals(status.toString())) return FAILED;
        }
        return SUCCESS;
    }

    private static final class Command {
        private final String id;
        private final String text;
        private volatile String stored = QUEUED;
        private volatile Object result;

        private Command(String id, String text) {
            this.id = id;
            this.text = text;
        }

        private void markRunning() {
            stored = RUNNING;
        }

        private void complete(String status, Object result) {
            this.result = result;
            this.stored = status;
        }

        private String ackStatus() {
            return QUEUED.equals(stored) ? QUEUED : RUNNING;
        }

        private Map<String, Object> view(boolean heartbeatFresh) {
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", id);
            String status = stored;
            if (RUNNING.equals(status) || QUEUED.equals(status)) {
                if (!heartbeatFresh) status = TIMEOUT;
                else if (RUNNING.equals(stored)) status = STILL_WORKING;
            }
            payload.put("status", status);
            if (result != null) payload.put("result", result);
            return payload;
        }
    }
}
