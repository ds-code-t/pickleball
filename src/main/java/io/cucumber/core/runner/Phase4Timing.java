package io.cucumber.core.runner;

/**
 * Launch-segment marks. Off unless {@code -Dpickleball.phase4.timing=true}
 * was set before this class loaded in the forked test JVM. A normal run
 * pays one boolean read per mark and prints nothing.
 */
public final class Phase4Timing {
    private static final boolean ENABLED = Boolean.getBoolean("pickleball.phase4.timing");
    private static final long STARTED = System.currentTimeMillis();

    private Phase4Timing() {
    }

    public static void mark(String segment) {
        if (!ENABLED) return;
        long elapsed = System.currentTimeMillis() - STARTED;
        System.err.println("phase4 timing segment=" + segment + " elapsedMs=" + elapsed);
    }
}
