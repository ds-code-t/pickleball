// src/main/java/tools/ds/modkit/patches/PickleStepRunPatch.java
package tools.ds.modkit.patches;

import io.cucumber.core.backend.TestCaseState;
import io.cucumber.core.eventbus.EventBus;
import io.cucumber.plugin.event.TestCase;
import tools.ds.modkit.blackbox.Registry;
import tools.ds.modkit.state.ScenarioState;
import tools.ds.modkit.util.CallScope;

import static tools.ds.modkit.blackbox.Plans.on;
import static tools.ds.modkit.state.ScenarioState.getScenarioState;
import static tools.ds.modkit.trace.ObjDataRegistry.*;
import static tools.ds.modkit.util.ExecutionModes.RUN;

public final class PickleStepRunPatch {
    private PickleStepRunPatch() {
    }

    /**
     * Register the step-run interception.
     */
    public static void register() {
        Registry.register(
                on("io.cucumber.core.runner.PickleStepTestStep", "run", 4)
                        .returns("io.cucumber.core.runner.ExecutionMode")

                        .before(args -> {
                            System.out.println("@@before");
                        })

                        .around(
                                args -> {
                                    Object testCase = args[0]; // io.cucumber.core.runner.TestCase (non-public)
                                    ObjFlags st = getFlag(testCase);
                                    if (st.equals(ObjFlags.RUNNING))
                                        return false;
                                    Object self = CallScope.currentSelf();
                                    if (st.equals(ObjFlags.NOT_SET)) {
                                        ScenarioState.setScenarioStateValues((TestCase) testCase, (EventBus) args[1], (TestCaseState) args[2]);
                                        setFlag(testCase, ObjFlags.INITIALIZING);
                                    }

                                    if (containsFlags(self, ObjFlags.LAST)) {
                                        getScenarioState().getStepExecution().runSteps(RUN(args[2]));
                                    }
                                    //                                    return false;
                                    return true; // true => skip original
                                }
                                ,
                                args -> {
//                                    Object self = CallScope.currentSelf();
//                                    System.out.println("@@self2: " + self);
//                                    if (containsFlags(self, ObjFlags.LAST)) {
//                                        getScenarioState().getStepExecution().runSteps(RUN(args[2]));
//                                    }
                                    return args[3];
                                }
                        )

                        .after((args, ret, thr) -> {
                            return ret;
                        })

                        .build()
        );
    }
}
