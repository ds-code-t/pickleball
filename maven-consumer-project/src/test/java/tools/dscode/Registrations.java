package tools.dscode;

import com.xpathy.Tag;
import com.xpathy.XPathy;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import tools.dscode.common.annotations.LifecycleHook;
import tools.dscode.common.annotations.Phase;
import tools.dscode.common.domoperations.ExecutionDictionary;

import static com.xpathy.Attribute.id;
import static com.xpathy.Attribute.role;
import static com.xpathy.Attribute.type;
import static com.xpathy.Case.LOWER;
import static com.xpathy.Tag.input;
import static tools.dscode.common.treeparsing.DefinitionContext.getExecutionDictionary;
import static tools.dscode.common.util.DebugUtils.printDebug;

public class Registrations {
    @LifecycleHook(Phase.BEFORE_CUCUMBER_RUN)
    public static void beforeRun() {



        ExecutionDictionary dict = getExecutionDictionary();

        dict.category("Submit Button").or(
                (category, v, op) -> input.byAttribute(type).equals("submit")
        );

        dict.category("Top Panel").startingContext((category, v, op, webDriver, ctx) ->
                ctx
        );

        dict.registerTopLevelIframe("FrameResult").and(
                (category, v, op) -> XPathy.from(Tag.iframe).byAttribute(id).equals("iframeResult")
        );

        dict.registerDefaultStartingContext((category, v, op, webDriver, ctx) ->
        {
            try {

                webDriver.switchTo().defaultContent();

                XPathy xpathy = dict.getCategoryXPathy("FrameResult");
                System.out.println("@@ dict.getCategoryXPathy(\"Frame\"): " +  dict.getCategoryXPathy("Frame").getLocator());
                WebElement frame = webDriver.findElement(xpathy.getLocator());

                webDriver.switchTo().frame(frame);

                return webDriver;
            } catch (Exception e) {

                e.printStackTrace();
                throw new RuntimeException(e);
            }
        });

        dict.category("IframeResult").flags(ExecutionDictionary.CategoryFlags.PAGE_CONTEXT);

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

    }

    @LifecycleHook(Phase.BEFORE_SCENARIO_RUN)
    public static void beforeScenario() {

    }

    @LifecycleHook(Phase.AFTER_SCENARIO_RUN)
    public static void afterScenario() {

    }

    @LifecycleHook(Phase.AFTER_SCENARIO_FAIL)
    public static void afterScenarioFail() {

    }

    @LifecycleHook(Phase.AFTER_SCENARIO_PASS)
    public static void afterScenarioPass() {

    }
}
