package tools.dscode.common.reporting.logging;

import static tools.dscode.common.evaluations.AviatorUtil.isTruthy;
import static tools.dscode.common.variables.RunVars.resolveFromVarsOrDefault;

public final class CleanupTrace {
    private CleanupTrace() {
    }

    // Can be flipped directly in code:
    // CleanupTrace.ENABLED = true;
    //
    // Or enabled from Maven/GitHub Actions with:
    // -Dcleanup.trace=true
    public static boolean ENABLED = isTruthy(resolveFromVarsOrDefault("pkb_lifecycleprint", false));

    public static void print(String message) {
        if (!ENABLED) return;

        System.out.println(message);
        System.out.flush();
    }

    public static void printThrowable(String message, Throwable t) {
        if (!ENABLED) return;

        System.out.println(message);
        t.printStackTrace(System.out);
        System.out.flush();
    }
}