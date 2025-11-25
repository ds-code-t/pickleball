package tools.dscode;

import com.xpathy.Tag;
import com.xpathy.XPathy;
import tools.dscode.common.annotations.LifecycleHook;
import tools.dscode.common.annotations.Phase;

import static com.xpathy.Attribute.role;
import static tools.dscode.common.domoperations.XPathyMini.orMap;
import static tools.dscode.common.domoperations.XPathyMini.textOp;
import static tools.dscode.common.util.DebugUtils.printDebug;

public class Registrations {
    @LifecycleHook(Phase.BEFORE_CUCUMBER_RUN)
    public static void beforeRun() {
        printDebug("@@=--- beforeRun");

//        XPathyRegistry.add("Zaaa", (v, op) ->
//                orMap(
//                        textOp(op, v),
//                        () -> XPathy.from(Tag.button),                                       // //button[…]
//                        () -> XPathy.from(Tag.img).byAttribute(role).equals("button") // //img[@role='button'][…]
//                )
//        );
    }

    @LifecycleHook(Phase.AFTER_CUCUMBER_RUN)
    public static void afterRun() {
        printDebug("@@=--- afterRun");
    }

    @LifecycleHook(Phase.BEFORE_SCENARIO_RUN)
    public static void beforeScenario() {
        printDebug("@@=--- beforeScenario");
    }

    @LifecycleHook(Phase.AFTER_SCENARIO_RUN)
    public static void afterScenario() {
        printDebug("@@=--- afterScenario");
    }
    
    @LifecycleHook(Phase.AFTER_SCENARIO_FAIL)
    public static void afterScenarioFail() {
        printDebug("@@=--- afterScenarioFail");
    }

    @LifecycleHook(Phase.AFTER_SCENARIO_PASS)
    public static void afterScenarioPass() {
        printDebug("@@=--- afterScenarioPass");
    }
}
