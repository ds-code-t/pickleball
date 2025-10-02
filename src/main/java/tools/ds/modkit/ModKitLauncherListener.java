package tools.ds.modkit;

import org.junit.platform.launcher.*;

public final class ModKitLauncherListener implements LauncherSessionListener {
    static { System.out.println("[modkit] LauncherSessionListener <static>"); }

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        System.out.println("[modkit] launcherSessionOpened → attaching agent");
        EnsureInstalled.ensureOrDie();
    }
}
