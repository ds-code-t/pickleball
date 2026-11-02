package tools.dscode.common.runtime;

import tools.dscode.control.protocol.speed.ProcessTree;

import java.time.Duration;

/**
 * Kills a process and every descendant. Workbench uses this when a run watchdog
 * interrupts a wrapper, and when {@code stop --force} runs. A normal test run
 * does not call it. The kill itself is {@link ProcessTree}.
 */
public final class ProcessTrees {
    public static final Duration KILL_WAIT = ProcessTree.KILL_WAIT;

    private ProcessTrees() {
    }

    public static void killTree(ProcessHandle handle) {
        ProcessTree.kill(handle);
    }
}
