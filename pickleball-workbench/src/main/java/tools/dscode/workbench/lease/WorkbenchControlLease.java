package tools.dscode.workbench.lease;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Controller-owned live-control lease. Swing and MCP/HTTP adapters observe this
 * state; they do not keep a second copy.
 */
public final class WorkbenchControlLease {
    private static final long PERMISSION_WAIT_NS = TimeUnit.MINUTES.toNanos(30);

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition permissionAnswered = lock.newCondition();
    private final List<Consumer<WorkbenchControlLeaseSnapshot>> listeners = new CopyOnWriteArrayList<>();

    private WorkbenchLeaseHolder holder = WorkbenchLeaseHolder.HUMAN;
    private String agentDisplayName = "";
    private String currentAction = "";
    private boolean uiAttached;
    private WorkbenchPermissionRequest pendingPermission;
    private WorkbenchPermissionDecision pendingDecision;

    public void addListener(Consumer<WorkbenchControlLeaseSnapshot> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
        listener.accept(snapshot());
    }

    public void removeListener(Consumer<WorkbenchControlLeaseSnapshot> listener) {
        listeners.remove(listener);
    }

    public void attachUi() {
        lock.lock();
        try {
            uiAttached = true;
            if (holder == WorkbenchLeaseHolder.AGENT) {
                // A visible UI always starts with the human holding the floor.
            } else {
                holder = WorkbenchLeaseHolder.HUMAN;
            }
        } finally {
            lock.unlock();
        }
        notifyListeners();
    }

    public void detachUi() {
        lock.lock();
        try {
            uiAttached = false;
            failPendingLocked("The Workbench UI closed before the permission request was answered.");
        } finally {
            lock.unlock();
        }
        notifyListeners();
    }

    public WorkbenchControlLeaseSnapshot snapshot() {
        lock.lock();
        try {
            return snapshotLocked();
        } finally {
            lock.unlock();
        }
    }

    public WorkbenchControlLeaseSnapshot requestControl(String agentDisplayName) {
        String name = requiredName(agentDisplayName);
        lock.lock();
        try {
            if (holder == WorkbenchLeaseHolder.AGENT
                    && !this.agentDisplayName.isBlank()
                    && !this.agentDisplayName.equals(name)) {
                throw new IllegalStateException(
                        "Another AI agent already holds the Workbench control lease ("
                                + this.agentDisplayName + ")."
                );
            }
            holder = WorkbenchLeaseHolder.AGENT;
            this.agentDisplayName = name;
            if (currentAction.isBlank()) {
                currentAction = "Waiting to work in the live scenario.";
            }
            return snapshotLocked();
        } finally {
            lock.unlock();
            notifyListeners();
        }
    }

    public WorkbenchControlLeaseSnapshot releaseControl() {
        requireAgentCaller();
        lock.lock();
        try {
            if (holder != WorkbenchLeaseHolder.AGENT) {
                throw new IllegalStateException("The AI agent does not currently hold the Workbench control lease.");
            }
            failPendingLocked("The AI agent released control before the permission request was answered.");
            holder = WorkbenchLeaseHolder.HUMAN;
            agentDisplayName = "";
            currentAction = "";
            return snapshotLocked();
        } finally {
            lock.unlock();
            notifyListeners();
        }
    }

    public WorkbenchControlLeaseSnapshot takeControl() {
        lock.lock();
        try {
            failPendingLocked("The human took control before the permission request was answered.");
            holder = WorkbenchLeaseHolder.HUMAN;
            agentDisplayName = "";
            currentAction = "";
            return snapshotLocked();
        } finally {
            lock.unlock();
            notifyListeners();
        }
    }

    public WorkbenchControlLeaseSnapshot setCurrentAction(String text) {
        requireAgentCaller();
        lock.lock();
        try {
            requireHolderLocked(WorkbenchLeaseHolder.AGENT);
            currentAction = text == null ? "" : text.strip();
            return snapshotLocked();
        } finally {
            lock.unlock();
            notifyListeners();
        }
    }

    /**
     * Live testing and Mapping/evidence reads stay available to the lease holder.
     * Call this before mutating worker, player-buffer, Mapping write, or Save paths.
     */
    public void requireMutatingAccess() {
        WorkbenchLeaseHolder caller = WorkbenchCallContext.current();
        lock.lock();
        try {
            requireHolderLocked(caller);
        } finally {
            lock.unlock();
        }
    }

    public boolean uiAttached() {
        lock.lock();
        try {
            return uiAttached;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Blocks when a UI is attached until Allow, Deny, or Take control. Headless
     * stdio has no banner, so the explicit tool call itself is the approval.
     */
    public WorkbenchPermissionDecision awaitPermission(WorkbenchPermissionRequest request) {
        Objects.requireNonNull(request, "request");
        requireAgentCaller();
        lock.lock();
        try {
            requireHolderLocked(WorkbenchLeaseHolder.AGENT);
            if (pendingPermission != null) {
                throw new IllegalStateException("Another Workbench permission request is already pending.");
            }
            if (!uiAttached) {
                return WorkbenchPermissionDecision.ALLOW;
            }
            pendingPermission = request;
            pendingDecision = null;
        } finally {
            lock.unlock();
        }
        notifyListeners();

        lock.lock();
        try {
            long remaining = PERMISSION_WAIT_NS;
            while (pendingDecision == null && remaining > 0) {
                remaining = permissionAnswered.awaitNanos(remaining);
            }
            WorkbenchPermissionDecision decision = pendingDecision;
            pendingPermission = null;
            pendingDecision = null;
            if (decision == null) {
                throw new WorkbenchPermissionCancelledException(
                        "Timed out waiting for a human Allow/Deny decision."
                );
            }
            if (decision == WorkbenchPermissionDecision.CANCELLED) {
                throw new WorkbenchPermissionCancelledException();
            }
            return decision;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            pendingPermission = null;
            pendingDecision = null;
            throw new WorkbenchPermissionCancelledException("Interrupted while waiting for Save permission.");
        } finally {
            lock.unlock();
            notifyListeners();
        }
    }

    public void answerPermission(String requestId, boolean allow) {
        lock.lock();
        try {
            if (pendingPermission == null || !pendingPermission.id().equals(requestId)) {
                throw new IllegalStateException("No matching Workbench permission request is pending.");
            }
            pendingDecision = allow ? WorkbenchPermissionDecision.ALLOW : WorkbenchPermissionDecision.DENY;
            permissionAnswered.signalAll();
        } finally {
            lock.unlock();
        }
        notifyListeners();
    }

    public static String newPermissionId() {
        return UUID.randomUUID().toString();
    }

    private void failPendingLocked(String message) {
        if (pendingPermission == null) return;
        pendingDecision = WorkbenchPermissionDecision.CANCELLED;
        permissionAnswered.signalAll();
        // Keep pendingPermission until the waiter clears it so the UI can drop the banner.
        pendingPermission = pendingPermission;
    }

    private void requireHolderLocked(WorkbenchLeaseHolder expected) {
        if (holder == expected) return;
        if (expected == WorkbenchLeaseHolder.AGENT) {
            throw new IllegalStateException(
                    "The AI agent does not hold the Workbench control lease. Call workbench_request_control first."
            );
        }
        throw new IllegalStateException(
                "An AI agent currently holds the Workbench control lease. Take control before using these controls."
        );
    }

    private static void requireAgentCaller() {
        if (WorkbenchCallContext.current() != WorkbenchLeaseHolder.AGENT) {
            throw new IllegalStateException("Only an attached AI agent can use this control-lease action.");
        }
    }

    private WorkbenchControlLeaseSnapshot snapshotLocked() {
        return new WorkbenchControlLeaseSnapshot(
                holder,
                agentDisplayName,
                currentAction,
                uiAttached,
                pendingPermission
        );
    }

    private void notifyListeners() {
        WorkbenchControlLeaseSnapshot snapshot = snapshot();
        for (Consumer<WorkbenchControlLeaseSnapshot> listener : listeners) {
            try {
                listener.accept(snapshot);
            } catch (RuntimeException ignored) {
                // Presentation listeners must not break lease transitions.
            }
        }
    }

    private static String requiredName(String agentDisplayName) {
        if (agentDisplayName == null || agentDisplayName.isBlank()) {
            throw new IllegalArgumentException("Agent display name must not be blank.");
        }
        return agentDisplayName.strip();
    }
}
