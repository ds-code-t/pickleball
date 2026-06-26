package io.cucumber.core.cli;

import tools.dscode.testengine.DynamicSuiteBootstrap;
import tools.dscode.testengine.PickleballRunner;

public aspect MainCliBootstrapAspect {

    /** Adds a new public static field to io.cucumber.core.cli.Main */
    public static boolean Main.launchedViaCli = false;

    /**
     * Intercept Main.main(String...) before execution. Varargs compiles to
     * String[] at bytecode level.
     */
    before(String[] argv): execution(public static void io.cucumber.core.cli.Main.main(String[]))
            && args(argv) {
        bootstrapFromCli(argv);
    }

    /**
     * Main.main(...) and Main.run(String...) both delegate here. Keep this
     * interception too so callers that invoke run(String[], ClassLoader)
     * directly are captured.
     */
    before(String[] argv, ClassLoader classLoader):
            execution(public static byte io.cucumber.core.cli.Main.run(String[], ClassLoader))
                    && args(argv, classLoader) {
        bootstrapFromCli(argv);
    }

    private static void bootstrapFromCli(String[] argv) {
        if (!Main.launchedViaCli) {
            Main.launchedViaCli = true;
        }
        PickleballRunner runner = DynamicSuiteBootstrap.initializeFromRuntimeClasspath();
        runner.captureCucumberCliArgs(argv);
    }
}
