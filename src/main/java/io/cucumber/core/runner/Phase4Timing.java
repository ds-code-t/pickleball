package io.cucumber.core.runner;

import tools.dscode.launcher.WorkbenchLaunchTiming;

/**
 * Launch-segment marks. A normal {@code mvn test} pays one boolean read and
 * prints nothing. A Workbench discover or confirm records the coarse
 * stages ({@code discover-start}, {@code runner-ready}, {@code cucumber-discover-done},
 * {@code execute-start}, {@code scenario-start}) in {@code launch-timing.txt}.
 * Those lines stay off stderr unless {@code -Dpkb_workbench_timing=true}
 * or {@code PKB_WORKBENCH_TIMING=1}. Per-file parse marks stay off
 * unless {@code -Dpickleball.phase4.timing=true}.
 */
public final class Phase4Timing {
    private static final boolean ENABLED = Boolean.getBoolean("pickleball.phase4.timing");
    private static final long STARTED = System.currentTimeMillis();

    private Phase4Timing() {
    }

    public static void mark(String segment) {
        boolean coarse = coarse(segment) && workbenchRun();
        if (!ENABLED && !coarse) return;
        long elapsed = System.currentTimeMillis() - STARTED;
        if (ENABLED) {
            System.err.println("phase4 timing segment=" + segment + " elapsedMs=" + elapsed);
        }
        if (coarse) {
            WorkbenchLaunchTiming.record("test", segment, elapsed, "");
        }
    }

    private static boolean workbenchRun() {
        String purpose = System.getProperty("pkb_run_purpose", "");
        return purpose.startsWith("workbench-");
    }

    private static boolean coarse(String segment) {
        return "discover-start".equals(segment)
                || "runner-ready".equals(segment)
                || "runner-initialized".equals(segment)
                || "cucumber-discover-done".equals(segment)
                || "discover-cache-hit".equals(segment)
                || "execute-start".equals(segment)
                || "scenario-start".equals(segment);
    }
}