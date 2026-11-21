package tools.dscode.control.protocol;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Whether the recorded browser is actually running.
 * A host JVM with a dead Chrome pid is down. A missing file is down.
 */
public final class BrowserPresence {
    private BrowserPresence() {
    }

    /** The Workbench worker bridge pid is alive. {@link LiveBridgeFile#read} already drops a dead pid. */
    public static boolean workerUp(Path project) {
        return LiveBridgeFile.read(project).isPresent();
    }

    /** The start-host JVM recorded in {@code live-browser/current.json} is alive. */
    public static boolean hostUp(Path project) {
        Optional<LiveHostFile.Host> host = LiveHostFile.read(project);
        return host.isPresent() && alive(host.get().pid());
    }

    /** The Chrome pid recorded by that host is alive. A missing or zero pid is not Chrome. */
    public static boolean chromeUp(Path project) {
        Optional<LiveHostFile.Host> host = LiveHostFile.read(project);
        return host.isPresent() && alive(host.get().chromePid());
    }

    /**
     * Up when the worker is alive, or when both the start host and its Chrome process are alive.
     * A dead Chrome process is down even if the host JVM is still up.
     */
    public static boolean browserUp(Path project) {
        return workerUp(project) || (hostUp(project) && chromeUp(project));
    }

    private static boolean alive(long pid) {
        return pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }
}
