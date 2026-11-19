package tools.dscode.workbench.worker;

import tools.dscode.control.protocol.AgentEvents;
import tools.dscode.control.protocol.AgentFastLog;
import tools.dscode.control.protocol.LiveBridgeFile;
import tools.dscode.control.protocol.speed.ProcessTree;

import java.nio.file.Path;

/**
 * Stops a worker and the browser processes it owns.
 * The quit runnable runs in the caller (the controller posts {@code /v1/browser/release}
 * so the worker calls {@code WebDriver.quit()}). This class then kills the process tree.
 */
public final class WorkerShutdown {
    private WorkerShutdown() {
    }

    public static void release(Process process, Runnable quitDriver, Path project, String sessionId) {
        if (quitDriver != null) {
            try {
                quitDriver.run();
            } catch (RuntimeException ignored) {
                // The tree kill still runs when the bridge is already gone.
            }
        }
        if (process != null) {
            try {
                ProcessTree.kill(process.toHandle());
            } catch (RuntimeException ignored) {
                try {
                    process.destroyForcibly();
                } catch (RuntimeException nested) {
                    // Already gone.
                }
            }
        }
        AgentEvents.append(project, "browser_lost", sessionId);
        LiveBridgeFile.delete(project);
        AgentFastLog.log(project, "shutdown", "browser_lost session=" + (sessionId == null ? "" : sessionId));
    }
}
