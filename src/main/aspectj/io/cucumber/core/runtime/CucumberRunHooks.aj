package io.cucumber.core.runtime;

import tools.dscode.common.annotations.LifecycleManager;
import tools.dscode.common.annotations.Phase;

public aspect CucumberRunHooks {
    private final LifecycleManager lifecycle = new LifecycleManager();
    /**
     * Earliest common entry point: called once per CucumberExecutionContext
     * when a test run starts (CLI, JUnit 4/5, TestNG, IntelliJ, etc.).
     */
    pointcut cucumberRunStart(CucumberExecutionContext ctx) :
            execution(void CucumberExecutionContext.startTestRun()) &&
                    this(ctx);

    before(CucumberExecutionContext ctx) : cucumberRunStart(ctx) {
        lifecycle.fire(Phase.BEFORE_CUCUMBER_RUN);
    }


    /**
     * Final common exit point: called once per CucumberExecutionContext
     * at the very end of the run.
     */
    pointcut cucumberRunFinish(CucumberExecutionContext ctx) :
            execution(void CucumberExecutionContext.finishTestRun()) &&
                    this(ctx);

    after(CucumberExecutionContext ctx) : cucumberRunFinish(ctx) {
        lifecycle.fire(Phase.AFTER_CUCUMBER_RUN);
    }
}
