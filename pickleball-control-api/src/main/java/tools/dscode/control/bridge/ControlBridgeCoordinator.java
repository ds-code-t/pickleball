package tools.dscode.control.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.core.runner.GlobalState;
import io.cucumber.core.runner.StepExtension;
import tools.dscode.common.control.ControlDecision;
import tools.dscode.common.control.ControlEvent;
import tools.dscode.common.control.ControlHook;
import tools.dscode.common.control.ControlHookHandler;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.treeparsing.parsedComponents.Phrase;
import tools.dscode.control.api.ControlCallResult;
import tools.dscode.control.api.DynamicControl;
import tools.dscode.control.api.MappingControl;

import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

final class ControlBridgeCoordinator implements ControlHookHandler, AutoCloseable {
    static final int DEFAULT_WAIT_SECONDS = 30;
    static final int DEFAULT_PAUSE_LEASE_SECONDS = 120;
    static final int DEFAULT_COMMAND_TIMEOUT_SECONDS = 60;
    static final int MAX_WAIT_SECONDS = 600;
    static final int MAX_PAUSE_LEASE_SECONDS = 3600;
    static final int MAX_COMMAND_TIMEOUT_SECONDS = 3600;

    private static final int MAX_TEXT = 64 * 1024;
    private static final int MAX_MAPPING_SNAPSHOT_BYTES = 512 * 1024;
    private static final Object NOT_JSON_COMPATIBLE = new Object();

    private final String runtimeId;
    private final long pid;
    private final List<String> capabilities;
    private final ConcurrentHashMap<Long, ScenarioLane> lanes = new ConcurrentHashMap<>();
    private final AtomicBoolean pauseNextScenario = new AtomicBoolean();
    private final AtomicInteger nextPauseLeaseSeconds =
            new AtomicInteger(DEFAULT_PAUSE_LEASE_SECONDS);
    private final AtomicReference<CompletableFuture<ControlBridgeStatus>> nextPauseFuture =
            new AtomicReference<>();

    private volatile boolean closed;

    ControlBridgeCoordinator(
            String runtimeId,
            long pid,
            List<String> capabilities,
            boolean pauseFirstScenario
    ) {
        this.runtimeId = runtimeId;
        this.pid = pid;
        this.capabilities = List.copyOf(capabilities);
        this.pauseNextScenario.set(pauseFirstScenario);
    }

    @Override
    public ControlDecision onHook(ControlEvent event) {
        if (closed) {
            return ControlDecision.CONTINUE;
        }

        long threadId = Thread.currentThread().threadId();
        ScenarioLane lane = lanes.get(threadId);

        if (lane == null && GlobalState.getCurrentScenarioState() != null) {
            lane = lanes.computeIfAbsent(threadId, ScenarioLane::new);
            if (pauseNextScenario.compareAndSet(true, false)) {
                CompletableFuture<ControlBridgeStatus> future = nextPauseFuture.getAndSet(null);
                lane.requestPause(
                        nextPauseLeaseSeconds.getAndSet(DEFAULT_PAUSE_LEASE_SECONDS),
                        future
                );
            }
        }

        if (lane == null) {
            return ControlDecision.CONTINUE;
        }

        lane.capture(event);

        if (event.hook() == ControlHook.SCENARIO_END) {
            lane.finish();
            lanes.remove(threadId, lane);
            return ControlDecision.CONTINUE;
        }

        lane.drainCommands();
        if (lane.pauseRequested) {
            lane.pauseAtHook();
        }
        return ControlDecision.CONTINUE;
    }

    ControlBridgeStatus status() {
        ScenarioLane selected = selectedLane(null);
        int activeCount = lanes.size();

        if (selected == null) {
            boolean paused = lanes.values().stream().anyMatch(lane -> lane.paused);
            boolean pauseRequested = pauseNextScenario.get()
                    || lanes.values().stream().anyMatch(lane -> lane.pauseRequested);
            return new ControlBridgeStatus(
                    ControlBridgeRuntime.PROTOCOL_VERSION,
                    runtimeId,
                    pid,
                    activeCount,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    paused,
                    pauseRequested,
                    capabilities
            );
        }

        return selected.status(activeCount);
    }

    List<ControlBridgeScenarioStatus> scenarios() {
        return lanes.values().stream()
                .map(ScenarioLane::scenarioStatus)
                .sorted(Comparator
                        .comparing(
                                ControlBridgeScenarioStatus::scenarioId,
                                Comparator.nullsLast(String::compareTo)
                        )
                        .thenComparingLong(ControlBridgeScenarioStatus::threadId))
                .toList();
    }

    ControlBridgeCallResult requestPause(
            String scenarioId,
            Integer waitSeconds,
            Integer leaseSeconds
    ) {
        String targetId = normalizeScenarioId(scenarioId);
        int wait = bounded(
                waitSeconds,
                DEFAULT_WAIT_SECONDS,
                MAX_WAIT_SECONDS,
                "waitSeconds"
        );
        int lease = bounded(
                leaseSeconds,
                DEFAULT_PAUSE_LEASE_SECONDS,
                MAX_PAUSE_LEASE_SECONDS,
                "leaseSeconds"
        );

        ScenarioLane lane = selectedLane(targetId);
        if (lane == null) {
            if (targetId != null) {
                return unavailable("No active scenario with id " + targetId + ".");
            }
            if (!lanes.isEmpty()) {
                return unavailable(
                        "Pause requires a scenarioId when multiple scenarios are active."
                );
            }

            CompletableFuture<ControlBridgeStatus> future = new CompletableFuture<>();
            CompletableFuture<ControlBridgeStatus> previous = nextPauseFuture.getAndSet(future);
            if (previous != null && !previous.isDone()) {
                previous.completeExceptionally(
                        new IllegalStateException("Pause request was replaced by a newer request.")
                );
            }
            nextPauseLeaseSeconds.set(lease);
            pauseNextScenario.set(true);
            return awaitPause(future, wait);
        }

        if (lane.paused) {
            lane.extendPause(lease);
            return success("PAUSED", lane.status(lanes.size()));
        }

        CompletableFuture<ControlBridgeStatus> future = new CompletableFuture<>();
        lane.requestPause(lease, future);
        return awaitPause(future, wait);
    }

    ControlBridgeCallResult resume(String scenarioId) {
        String targetId = normalizeScenarioId(scenarioId);
        ScenarioLane lane = selectedLane(targetId);
        if (lane == null) {
            if (targetId != null) {
                return unavailable("No active scenario with id " + targetId + ".");
            }
            pauseNextScenario.set(false);
            nextPauseLeaseSeconds.set(DEFAULT_PAUSE_LEASE_SECONDS);
            CompletableFuture<ControlBridgeStatus> pending = nextPauseFuture.getAndSet(null);
            if (pending != null && !pending.isDone()) {
                pending.complete(status());
            }
            if (!lanes.isEmpty()) {
                return unavailable(
                        "Resume requires a scenarioId when multiple scenarios are active."
                );
            }
            return success("RUNNING", status());
        }

        lane.resume();
        return success("RUNNING", lane.status(lanes.size()));
    }

    ControlBridgeCallResult executeStep(
            String scenarioId,
            String text,
            String argument,
            Integer timeoutSeconds
    ) {
        if (text == null || text.isBlank()) {
            return unavailable("Dynamic step text must not be blank.");
        }

        int timeout = commandTimeout(timeoutSeconds);
        ScenarioLane lane = selectedLane(normalizeScenarioId(scenarioId));
        if (lane == null) {
            return unavailableForScenarioCommand(scenarioId, "Dynamic execution");
        }

        return submit(
                lane,
                timeout,
                () -> fromControlResult(
                        DynamicControl.executeStep(text, argument == null ? "" : argument),
                        lane
                ),
                (type, message) -> failed(type, message, lane.status(lanes.size())),
                message -> unavailable(message, lane.status(lanes.size()))
        );
    }

    ControlBridgeValueResult mappingGet(
            String scenarioId,
            String mapReference,
            String key,
            Integer timeoutSeconds
    ) {
        if (mapReference == null || mapReference.isBlank()) {
            return valueUnavailable("NodeMap reference must not be blank.");
        }
        if (key == null || key.isBlank()) {
            return valueUnavailable("Mapping key must not be blank.");
        }

        ScenarioLane lane = selectedLane(normalizeScenarioId(scenarioId));
        if (lane == null) {
            return valueUnavailableForScenarioCommand(scenarioId, "Mapping read");
        }

        return submit(
                lane,
                commandTimeout(timeoutSeconds),
                () -> fromValueResult(MappingControl.get(mapReference, key), lane),
                (type, message) -> valueFailed(type, message, lane.status(lanes.size())),
                message -> valueUnavailable(message, lane.status(lanes.size()))
        );
    }

    ControlBridgeValueResult mappingPut(
            String scenarioId,
            String mapReference,
            String key,
            Object value,
            Integer timeoutSeconds
    ) {
        if (mapReference == null || mapReference.isBlank()) {
            return valueUnavailable("NodeMap reference must not be blank.");
        }
        if (key == null || key.isBlank()) {
            return valueUnavailable("Mapping key must not be blank.");
        }

        ScenarioLane lane = selectedLane(normalizeScenarioId(scenarioId));
        if (lane == null) {
            return valueUnavailableForScenarioCommand(scenarioId, "Mapping write");
        }

        return submit(
                lane,
                commandTimeout(timeoutSeconds),
                () -> {
                    ControlCallResult<NodeMap> written = MappingControl.put(mapReference, key, value);
                    if (!written.successful()) {
                        return fromValueFailure(written, lane);
                    }
                    return fromValueResult(MappingControl.get(mapReference, key), lane);
                },
                (type, message) -> valueFailed(type, message, lane.status(lanes.size())),
                message -> valueUnavailable(message, lane.status(lanes.size()))
        );
    }

    ControlBridgeMappingSnapshotResult mappingSnapshot(
            String scenarioId,
            String mapReference,
            Integer timeoutSeconds
    ) {
        if (mapReference == null || mapReference.isBlank()) {
            return snapshotUnavailable("NodeMap reference must not be blank.");
        }

        ScenarioLane lane = selectedLane(normalizeScenarioId(scenarioId));
        if (lane == null) {
            return snapshotUnavailableForScenarioCommand(scenarioId, "Mapping snapshot");
        }

        String reference = mapReference.trim();
        return submit(
                lane,
                commandTimeout(timeoutSeconds),
                () -> {
                    ControlCallResult<NodeMap> current = MappingControl.currentNodeMap(reference);
                    if (!current.successful()) {
                        return snapshotFromFailure(current, lane);
                    }
                    return snapshotSuccess(reference, current.value(), lane.status(lanes.size()));
                },
                (type, message) -> snapshotFailed(type, message, lane.status(lanes.size())),
                message -> snapshotUnavailable(message, lane.status(lanes.size()))
        );
    }

    ControlBridgeCallResult mappingRestore(
            String scenarioId,
            ControlBridgeMappingSnapshot snapshot,
            Integer timeoutSeconds
    ) {
        String validation = validateSnapshot(snapshot);
        if (validation != null) {
            return unavailable(validation);
        }

        ScenarioLane lane = selectedLane(normalizeScenarioId(scenarioId));
        if (lane == null) {
            return unavailableForScenarioCommand(scenarioId, "Mapping restore");
        }

        return submit(
                lane,
                commandTimeout(timeoutSeconds),
                () -> restoreSnapshot(snapshot, lane),
                (type, message) -> failed(type, message, lane.status(lanes.size())),
                message -> unavailable(message, lane.status(lanes.size()))
        );
    }

    ControlBridgeValueResult mappingResolve(
            String scenarioId,
            String input,
            Integer timeoutSeconds
    ) {
        if (input == null) {
            return valueUnavailable("Mapping resolution input must not be null.");
        }

        ScenarioLane lane = selectedLane(normalizeScenarioId(scenarioId));
        if (lane == null) {
            return valueUnavailableForScenarioCommand(scenarioId, "Mapping resolution");
        }

        return submit(
                lane,
                commandTimeout(timeoutSeconds),
                () -> {
                    var current = MappingControl.current();
                    if (!current.successful()) {
                        return fromValueFailure(current, lane);
                    }
                    Object value = current.value().resolveWholeValue(input);
                    return valueSuccess(value, lane.status(lanes.size()));
                },
                (type, message) -> valueFailed(type, message, lane.status(lanes.size())),
                message -> valueUnavailable(message, lane.status(lanes.size()))
        );
    }

    @Override
    public void close() {
        closed = true;
        pauseNextScenario.set(false);
        nextPauseLeaseSeconds.set(DEFAULT_PAUSE_LEASE_SECONDS);

        CompletableFuture<ControlBridgeStatus> pendingPause = nextPauseFuture.getAndSet(null);
        if (pendingPause != null && !pendingPause.isDone()) {
            pendingPause.complete(status());
        }

        lanes.values().forEach(ScenarioLane::finish);
        lanes.clear();
    }

    private ControlBridgeCallResult awaitPause(
            CompletableFuture<ControlBridgeStatus> future,
            int waitSeconds
    ) {
        try {
            ControlBridgeStatus paused = future.get(waitSeconds, TimeUnit.SECONDS);
            return paused.paused()
                    ? success("PAUSED", paused)
                    : unavailable("Scenario ended before the requested pause was reached.");
        } catch (TimeoutException failure) {
            if (nextPauseFuture.compareAndSet(future, null)) {
                pauseNextScenario.set(false);
                nextPauseLeaseSeconds.set(DEFAULT_PAUSE_LEASE_SECONDS);
            }
            lanes.values().forEach(lane -> lane.cancelPauseRequest(future));
            return unavailable("Timed out waiting for a semantic hook where execution could pause.");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return failed("INTERRUPTED", "Interrupted while waiting for the scenario to pause.");
        } catch (Exception failure) {
            return failed(failure.getClass().getName(), Objects.toString(failure.getMessage(), ""));
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T submit(
            ScenarioLane lane,
            int timeoutSeconds,
            Supplier<T> action,
            BiFunction<String, String, T> failureFactory,
            Function<String, T> unavailableFactory
    ) {
        ScenarioCommand command = new ScenarioCommand(
                action::get,
                (type, message) -> failureFactory.apply(type, message),
                unavailableFactory::apply
        );
        lane.commands.offer(command);

        try {
            return (T) command.result.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException failure) {
            command.cancelled.set(true);
            lane.commands.remove(command);
            return failureFactory.apply(
                    "CONTROL_TIMEOUT",
                    "Timed out waiting for the scenario thread to complete the control command. "
                            + "If execution had already started, it may still complete."
            );
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            command.cancelled.set(true);
            lane.commands.remove(command);
            return failureFactory.apply(
                    "INTERRUPTED",
                    "Interrupted while waiting for the control command."
            );
        } catch (Exception failure) {
            return failureFactory.apply(
                    failure.getClass().getName(),
                    Objects.toString(failure.getMessage(), "")
            );
        }
    }

    private ScenarioLane selectedLane(String scenarioId) {
        if (scenarioId != null) {
            return lanes.values().stream()
                    .filter(lane -> scenarioId.equals(lane.scenarioId))
                    .findFirst()
                    .orElse(null);
        }

        ScenarioLane paused = pausedLane();
        if (paused != null) {
            return paused;
        }
        if (lanes.size() != 1) {
            return null;
        }
        return lanes.values().iterator().next();
    }

    private ScenarioLane pausedLane() {
        ScenarioLane selected = null;
        for (ScenarioLane lane : lanes.values()) {
            if (!lane.paused) {
                continue;
            }
            if (selected != null) {
                return null;
            }
            selected = lane;
        }
        return selected;
    }

    private ControlBridgeCallResult unavailableForScenarioCommand(
            String scenarioId,
            String operation
    ) {
        String targetId = normalizeScenarioId(scenarioId);
        if (targetId != null) {
            return unavailable("No active scenario with id " + targetId + ".");
        }
        if (lanes.isEmpty()) {
            return unavailable(operation + " requires an active Pickleball scenario.");
        }
        return unavailable(operation + " requires a scenarioId when multiple scenarios are active.");
    }

    private ControlBridgeValueResult valueUnavailableForScenarioCommand(
            String scenarioId,
            String operation
    ) {
        String targetId = normalizeScenarioId(scenarioId);
        if (targetId != null) {
            return valueUnavailable("No active scenario with id " + targetId + ".");
        }
        if (lanes.isEmpty()) {
            return valueUnavailable(operation + " requires an active Pickleball scenario.");
        }
        return valueUnavailable(
                operation + " requires a scenarioId when multiple scenarios are active."
        );
    }

    private ControlBridgeMappingSnapshotResult snapshotUnavailableForScenarioCommand(
            String scenarioId,
            String operation
    ) {
        String targetId = normalizeScenarioId(scenarioId);
        if (targetId != null) {
            return snapshotUnavailable("No active scenario with id " + targetId + ".");
        }
        if (lanes.isEmpty()) {
            return snapshotUnavailable(operation + " requires an active Pickleball scenario.");
        }
        return snapshotUnavailable(
                operation + " requires a scenarioId when multiple scenarios are active."
        );
    }

    private ControlBridgeMappingSnapshotResult snapshotSuccess(
            String mapReference,
            NodeMap map,
            ControlBridgeStatus runtime
    ) {
        ObjectNode values = map.getRoot().deepCopy();
        values.remove(NodeMap.MAP_TYPE_KEY);
        int snapshotBytes = values.toString().getBytes(StandardCharsets.UTF_8).length;
        if (snapshotBytes > MAX_MAPPING_SNAPSHOT_BYTES) {
            return snapshotUnavailable(
                    "Materialized mapping snapshot exceeds "
                            + MAX_MAPPING_SNAPSHOT_BYTES + " UTF-8 JSON bytes.",
                    runtime
            );
        }
        return new ControlBridgeMappingSnapshotResult(
                "SUCCESS",
                new ControlBridgeMappingSnapshot(
                        ControlBridgeMappingSnapshot.CURRENT_VERSION,
                        mapReference,
                        map.getMapType().name(),
                        map.getClass().getName(),
                        dataSources(map),
                        map.getClass() == NodeMap.class,
                        values
                ),
                null,
                runtime
        );
    }

    private ControlBridgeMappingSnapshotResult snapshotFromFailure(
            ControlCallResult<?> result,
            ScenarioLane lane
    ) {
        var error = result.error();
        return new ControlBridgeMappingSnapshotResult(
                result.status().name(),
                null,
                error == null
                        ? null
                        : new ControlBridgeError(
                                error.type(),
                                clipped(error.message()),
                                clipped(error.stackTrace())
                        ),
                lane.status(lanes.size())
        );
    }

    private ControlBridgeMappingSnapshotResult snapshotUnavailable(String message) {
        return snapshotUnavailable(message, status());
    }

    private ControlBridgeMappingSnapshotResult snapshotUnavailable(
            String message,
            ControlBridgeStatus runtime
    ) {
        return new ControlBridgeMappingSnapshotResult(
                "UNAVAILABLE",
                null,
                new ControlBridgeError("UNAVAILABLE", message, ""),
                runtime
        );
    }

    private ControlBridgeMappingSnapshotResult snapshotFailed(
            String type,
            String message,
            ControlBridgeStatus runtime
    ) {
        return new ControlBridgeMappingSnapshotResult(
                "FAILED",
                null,
                new ControlBridgeError(type, message, ""),
                runtime
        );
    }

    private ControlBridgeCallResult restoreSnapshot(
            ControlBridgeMappingSnapshot snapshot,
            ScenarioLane lane
    ) {
        ControlCallResult<NodeMap> current = MappingControl.currentNodeMap(snapshot.mapReference());
        if (!current.successful()) {
            return callFromFailure(current, lane);
        }

        NodeMap target = current.value();
        if (target.getClass() != NodeMap.class) {
            return unavailable(
                    "Mapping restore only supports ordinary NodeMap instances. "
                            + "The current " + snapshot.mapReference() + " map is "
                            + target.getClass().getName() + ".",
                    lane.status(lanes.size())
            );
        }
        if (!target.getClass().getName().equals(snapshot.mapClass())) {
            return unavailable(
                    "The live map class no longer matches the captured snapshot.",
                    lane.status(lanes.size())
            );
        }
        if (!target.getMapType().name().equals(snapshot.mapType())) {
            return unavailable(
                    "The live map type no longer matches the captured snapshot.",
                    lane.status(lanes.size())
            );
        }
        if (!dataSources(target).equals(snapshot.dataSources())) {
            return unavailable(
                    "The live map data sources no longer match the captured snapshot.",
                    lane.status(lanes.size())
            );
        }

        ObjectNode values = snapshot.values();
        values.remove(NodeMap.MAP_TYPE_KEY);
        var mapType = target.getMapType();
        target.clearValues();
        target.setMapType(mapType);
        target.merge(values);
        return success("RESTORED", lane.status(lanes.size()));
    }

    private ControlBridgeCallResult callFromFailure(
            ControlCallResult<?> result,
            ScenarioLane lane
    ) {
        var error = result.error();
        return new ControlBridgeCallResult(
                result.status().name(),
                null,
                null,
                error == null
                        ? null
                        : new ControlBridgeError(
                                error.type(),
                                clipped(error.message()),
                                clipped(error.stackTrace())
                        ),
                lane.status(lanes.size())
        );
    }

    private static String validateSnapshot(ControlBridgeMappingSnapshot snapshot) {
        if (snapshot == null) {
            return "Mapping snapshot must not be null.";
        }
        if (snapshot.version() != ControlBridgeMappingSnapshot.CURRENT_VERSION) {
            return "Unsupported mapping snapshot version: " + snapshot.version() + ".";
        }
        if (snapshot.mapReference() == null || snapshot.mapReference().isBlank()) {
            return "Mapping snapshot mapReference must not be blank.";
        }
        if (snapshot.mapType() == null || snapshot.mapType().isBlank()) {
            return "Mapping snapshot mapType must not be blank.";
        }
        if (snapshot.mapClass() == null || snapshot.mapClass().isBlank()) {
            return "Mapping snapshot mapClass must not be blank.";
        }
        if (!snapshot.restorable()) {
            return "This materialized mapping snapshot is inspection-only and cannot be restored.";
        }
        if (snapshot.values() == null) {
            return "Mapping snapshot values must not be null.";
        }
        return null;
    }

    private static List<String> dataSources(NodeMap map) {
        return map.getDataSources().stream()
                .map(Enum::name)
                .sorted()
                .toList();
    }

    private ControlBridgeCallResult fromControlResult(
            ControlCallResult<Object> result,
            ScenarioLane lane
    ) {
        if (result == null) {
            return failed("NULL_RESULT", "Dynamic control returned no result.", lane.status(lanes.size()));
        }

        Object value = result.value();
        var error = result.error();
        return new ControlBridgeCallResult(
                result.status().name(),
                value == null ? null : value.getClass().getName(),
                value == null ? null : clipped(Objects.toString(value)),
                error == null
                        ? null
                        : new ControlBridgeError(
                                error.type(),
                                clipped(error.message()),
                                clipped(error.stackTrace())
                        ),
                lane.status(lanes.size())
        );
    }

    private ControlBridgeValueResult fromValueResult(
            ControlCallResult<?> result,
            ScenarioLane lane
    ) {
        if (result == null) {
            return valueFailed(
                    "NULL_RESULT",
                    "Dynamic control returned no result.",
                    lane.status(lanes.size())
            );
        }
        if (!result.successful()) {
            return fromValueFailure(result, lane);
        }
        return valueSuccess(result.value(), lane.status(lanes.size()));
    }

    private ControlBridgeValueResult fromValueFailure(
            ControlCallResult<?> result,
            ScenarioLane lane
    ) {
        var error = result.error();
        return new ControlBridgeValueResult(
                result.status().name(),
                null,
                error == null
                        ? null
                        : new ControlBridgeError(
                                error.type(),
                                clipped(error.message()),
                                clipped(error.stackTrace())
                        ),
                lane.status(lanes.size())
        );
    }

    private ControlBridgeValueResult valueSuccess(
            Object value,
            ControlBridgeStatus runtime
    ) {
        Object jsonValue = jsonCompatibleValue(value, new IdentityHashMap<>());
        boolean jsonCompatible = jsonValue != NOT_JSON_COMPATIBLE;
        return new ControlBridgeValueResult(
                "SUCCESS",
                new ControlBridgeValue(
                        value == null ? "null" : value.getClass().getName(),
                        jsonCompatible,
                        jsonCompatible ? jsonValue : null,
                        clipped(Objects.toString(value))
                ),
                null,
                runtime
        );
    }

    private ControlBridgeCallResult success(String value, ControlBridgeStatus runtime) {
        return new ControlBridgeCallResult(
                "SUCCESS",
                String.class.getName(),
                value,
                null,
                runtime
        );
    }

    private ControlBridgeCallResult unavailable(String message) {
        return unavailable(message, status());
    }

    private ControlBridgeCallResult unavailable(
            String message,
            ControlBridgeStatus runtime
    ) {
        return new ControlBridgeCallResult(
                "UNAVAILABLE",
                null,
                null,
                new ControlBridgeError("UNAVAILABLE", message, ""),
                runtime
        );
    }

    private ControlBridgeCallResult failed(String type, String message) {
        return failed(type, message, status());
    }

    private ControlBridgeCallResult failed(
            String type,
            String message,
            ControlBridgeStatus runtime
    ) {
        return new ControlBridgeCallResult(
                "FAILED",
                null,
                null,
                new ControlBridgeError(type, message, ""),
                runtime
        );
    }

    private ControlBridgeValueResult valueUnavailable(String message) {
        return valueUnavailable(message, status());
    }

    private ControlBridgeValueResult valueUnavailable(
            String message,
            ControlBridgeStatus runtime
    ) {
        return new ControlBridgeValueResult(
                "UNAVAILABLE",
                null,
                new ControlBridgeError("UNAVAILABLE", message, ""),
                runtime
        );
    }

    private ControlBridgeValueResult valueFailed(
            String type,
            String message,
            ControlBridgeStatus runtime
    ) {
        return new ControlBridgeValueResult(
                "FAILED",
                null,
                new ControlBridgeError(type, message, ""),
                runtime
        );
    }

    private static int commandTimeout(Integer timeoutSeconds) {
        return bounded(
                timeoutSeconds,
                DEFAULT_COMMAND_TIMEOUT_SECONDS,
                MAX_COMMAND_TIMEOUT_SECONDS,
                "timeoutSeconds"
        );
    }

    private static int bounded(
            Integer requested,
            int defaultValue,
            int maxValue,
            String name
    ) {
        int value = requested == null ? defaultValue : requested;
        if (value < 1 || value > maxValue) {
            throw new IllegalArgumentException(name + " must be between 1 and " + maxValue);
        }
        return value;
    }

    private static String normalizeScenarioId(String scenarioId) {
        return scenarioId == null || scenarioId.isBlank() ? null : scenarioId.trim();
    }

    private static String clipped(String value) {
        if (value == null || value.length() <= MAX_TEXT) {
            return value;
        }
        return value.substring(0, MAX_TEXT) + "\n...[truncated]";
    }

    private static Object jsonCompatibleValue(
            Object value,
            IdentityHashMap<Object, Boolean> path
    ) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Character character) {
            return character.toString();
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof JsonNode) {
            return value;
        }
        if (path.put(value, Boolean.TRUE) != null) {
            return NOT_JSON_COMPATIBLE;
        }

        try {
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> converted = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        return NOT_JSON_COMPATIBLE;
                    }
                    Object convertedValue = jsonCompatibleValue(entry.getValue(), path);
                    if (convertedValue == NOT_JSON_COMPATIBLE) {
                        return NOT_JSON_COMPATIBLE;
                    }
                    converted.put(key, convertedValue);
                }
                return converted;
            }
            if (value instanceof Iterable<?> iterable) {
                List<Object> converted = new ArrayList<>();
                for (Object item : iterable) {
                    Object convertedValue = jsonCompatibleValue(item, path);
                    if (convertedValue == NOT_JSON_COMPATIBLE) {
                        return NOT_JSON_COMPATIBLE;
                    }
                    converted.add(convertedValue);
                }
                return converted;
            }
            if (value.getClass().isArray()) {
                List<Object> converted = new ArrayList<>(Array.getLength(value));
                for (int index = 0; index < Array.getLength(value); index++) {
                    Object convertedValue = jsonCompatibleValue(Array.get(value, index), path);
                    if (convertedValue == NOT_JSON_COMPATIBLE) {
                        return NOT_JSON_COMPATIBLE;
                    }
                    converted.add(convertedValue);
                }
                return converted;
            }
            return NOT_JSON_COMPATIBLE;
        } finally {
            path.remove(value);
        }
    }

    private final class ScenarioLane {
        private final long threadId;
        private final BlockingQueue<ScenarioCommand> commands = new LinkedBlockingQueue<>();

        private volatile String scenarioId;
        private volatile String scenarioName;
        private volatile String stepText;
        private volatile String phraseText;
        private volatile String lastHook;
        private volatile String lastSignature;
        private volatile boolean paused;
        private volatile boolean pauseRequested;
        private volatile long pauseDeadlineNanos;
        private volatile CompletableFuture<ControlBridgeStatus> pauseFuture;

        private ScenarioLane(long threadId) {
            this.threadId = threadId;
        }

        private void capture(ControlEvent event) {
            CurrentScenarioState scenario = GlobalState.getCurrentScenarioState();
            StepExtension step = GlobalState.getRunningStep();
            Phrase phrase = GlobalState.getRunningPhrase();

            scenarioId = scenario == null ? null : scenario.id.toString();
            scenarioName = scenario == null ? null : scenario.scenarioName;
            stepText = safeStepText(step);
            phraseText = phrase == null ? null : clipped(Objects.toString(phrase));
            lastHook = event.hook().name();
            lastSignature = clipped(event.signature());
        }

        private ControlBridgeScenarioStatus scenarioStatus() {
            return new ControlBridgeScenarioStatus(
                    threadId,
                    scenarioId,
                    scenarioName,
                    stepText,
                    phraseText,
                    lastHook,
                    lastSignature,
                    paused,
                    pauseRequested
            );
        }

        private void requestPause(
                int leaseSeconds,
                CompletableFuture<ControlBridgeStatus> future
        ) {
            pauseRequested = true;
            extendPause(leaseSeconds);
            if (future != null) {
                pauseFuture = future;
            }
        }

        private void extendPause(int leaseSeconds) {
            pauseDeadlineNanos = System.nanoTime()
                    + Duration.ofSeconds(leaseSeconds).toNanos();
        }

        private synchronized void cancelPauseRequest(
                CompletableFuture<ControlBridgeStatus> future
        ) {
            if (!paused && pauseFuture == future) {
                pauseFuture = null;
                pauseRequested = false;
            }
        }

        private void pauseAtHook() {
            paused = true;
            CompletableFuture<ControlBridgeStatus> future = pauseFuture;
            pauseFuture = null;
            if (future != null && !future.isDone()) {
                future.complete(status(lanes.size()));
            }

            while (!closed && pauseRequested) {
                long remaining = pauseDeadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    pauseRequested = false;
                    break;
                }

                long waitMillis = Math.min(
                        250,
                        Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining))
                );
                try {
                    ScenarioCommand command = commands.poll(waitMillis, TimeUnit.MILLISECONDS);
                    if (command != null) {
                        command.execute();
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    pauseRequested = false;
                    break;
                }
            }

            paused = false;
        }

        private void drainCommands() {
            ScenarioCommand command;
            while ((command = commands.poll()) != null) {
                command.execute();
            }
        }

        private void resume() {
            pauseRequested = false;
            paused = false;
            commands.offer(ScenarioCommand.wakeup());
        }

        private void finish() {
            pauseRequested = false;
            paused = false;

            CompletableFuture<ControlBridgeStatus> future = pauseFuture;
            pauseFuture = null;
            if (future != null && !future.isDone()) {
                future.complete(status(Math.max(0, lanes.size() - 1)));
            }

            ScenarioCommand command;
            while ((command = commands.poll()) != null) {
                command.completeUnavailable(
                        "Scenario ended before the control command could execute."
                );
            }
        }

        private ControlBridgeStatus status(int activeCount) {
            return new ControlBridgeStatus(
                    ControlBridgeRuntime.PROTOCOL_VERSION,
                    runtimeId,
                    pid,
                    activeCount,
                    threadId,
                    scenarioId,
                    scenarioName,
                    stepText,
                    phraseText,
                    lastHook,
                    lastSignature,
                    paused,
                    pauseRequested,
                    capabilities
            );
        }

        private String safeStepText(StepExtension step) {
            if (step == null) {
                return null;
            }
            try {
                return clipped(step.getStepText());
            } catch (RuntimeException ignored) {
                return clipped(step.toString());
            }
        }
    }

    private static final class ScenarioCommand {
        private final Supplier<Object> action;
        private final BiFunction<String, String, Object> failureFactory;
        private final Function<String, Object> unavailableFactory;
        private final CompletableFuture<Object> result = new CompletableFuture<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final boolean wakeup;

        private ScenarioCommand(
                Supplier<Object> action,
                BiFunction<String, String, Object> failureFactory,
                Function<String, Object> unavailableFactory
        ) {
            this(action, failureFactory, unavailableFactory, false);
        }

        private ScenarioCommand(
                Supplier<Object> action,
                BiFunction<String, String, Object> failureFactory,
                Function<String, Object> unavailableFactory,
                boolean wakeup
        ) {
            this.action = action;
            this.failureFactory = failureFactory;
            this.unavailableFactory = unavailableFactory;
            this.wakeup = wakeup;
        }

        private void execute() {
            if (wakeup || cancelled.get() || result.isDone()) {
                return;
            }
            try {
                result.complete(action.get());
            } catch (Throwable failure) {
                result.complete(failureFactory.apply(
                        failure.getClass().getName(),
                        Objects.toString(failure.getMessage(), "")
                ));
            }
        }

        private void completeUnavailable(String message) {
            if (!wakeup && !result.isDone()) {
                result.complete(unavailableFactory.apply(message));
            }
        }

        private static ScenarioCommand wakeup() {
            return new ScenarioCommand(null, null, null, true);
        }
    }
}
