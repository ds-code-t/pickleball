package io.cucumber.core.runtime;

import io.cucumber.java.ro.Si;
import tools.dscode.common.annotations.LifecycleManager;
import tools.dscode.common.annotations.Phase;
import tools.dscode.common.reporting.WorkBook;

import java.io.IOException;

import static io.cucumber.core.runner.GlobalState.lifecycle;
import static io.cucumber.core.runner.GlobalState.workBookMap;

public aspect CucumberRunHooks {
//    private final LifecycleManager lifecycle = new LifecycleManager();
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
        for (WorkBook report : workBookMap.values())
        {
            try {

                report.write();
            } catch (IOException e)
            {
                e.printStackTrace();
                throw new RuntimeException("Report '" + report.outputFile + "'failed",e);
            }
        }
    }
}
