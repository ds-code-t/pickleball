package io.cucumber.core.cli;

import tools.dscode.testengine.DynamicSuiteBootstrap;

public aspect MainCliBootstrapAspect {

    /** Adds a new public static field to io.cucumber.core.cli.Main */
    public static boolean Main.launchedViaCli = false;

    /**
     * Intercept Main.main(String...) before execution.
     * Varargs compiles to String[] at bytecode level.
     */
    before(): execution(public static void io.cucumber.core.cli.Main.main(String[])) {
        if (!Main.launchedViaCli) {
            Main.launchedViaCli = true;
            DynamicSuiteBootstrap.initializeFromRuntimeClasspath();
        }
    }
}