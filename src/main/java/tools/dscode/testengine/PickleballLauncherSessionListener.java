package tools.dscode.testengine;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

public final class PickleballLauncherSessionListener implements LauncherSessionListener {

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        EngineFilterBootstrap.ensureEngineFilterApplied("LauncherSessionListener");
        // Before Cucumber, and before a console appender that snapshots System.out.
        tools.dscode.launcher.WorkbenchConsoleTap.installIfActive();
    }

    @Override
    public void launcherSessionClosed(LauncherSession session) {
        // no-op
    }
}