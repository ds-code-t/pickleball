package tools.dscode.workbench.lease;

import java.util.Objects;
import java.util.Optional;

/** Immutable view of the controller-owned control lease. */
public record WorkbenchControlLeaseSnapshot(
        WorkbenchLeaseHolder holder,
        String agentDisplayName,
        String currentAction,
        boolean uiAttached,
        WorkbenchPermissionRequest pendingPermission
) {
    public WorkbenchControlLeaseSnapshot {
        Objects.requireNonNull(holder, "holder");
        agentDisplayName = agentDisplayName == null ? "" : agentDisplayName;
        currentAction = currentAction == null ? "" : currentAction;
    }

    public boolean agentHolds() {
        return holder == WorkbenchLeaseHolder.AGENT;
    }

    public boolean humanHolds() {
        return holder == WorkbenchLeaseHolder.HUMAN;
    }

    public Optional<WorkbenchPermissionRequest> pending() {
        return Optional.ofNullable(pendingPermission);
    }

    public String bannerText() {
        if (!agentHolds()) return "";
        String name = agentDisplayName.isBlank() ? "AI agent" : agentDisplayName;
        if (currentAction.isBlank()) {
            return name + " is in control of Workbench.";
        }
        return name + " is in control — " + currentAction;
    }
}
