package tools.dscode;

import com.xpathy.Tag;
import com.xpathy.XPathy;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import tools.dscode.common.annotations.LifecycleHook;
import tools.dscode.common.annotations.Phase;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.domoperations.WrappedContext;

import static com.xpathy.Attribute.id;
import static com.xpathy.Attribute.role;
import static com.xpathy.Attribute.type;
import static com.xpathy.Case.LOWER;
import static com.xpathy.Tag.input;
import static tools.dscode.common.domoperations.SeleniumUtils.wrapContext;
import static tools.dscode.common.treeparsing.DefinitionContext.getExecutionDictionary;
import static tools.dscode.common.util.DebugUtils.printDebug;

public class Registrations {
    @LifecycleHook(Phase.BEFORE_CUCUMBER_RUN)
    public static void beforeRun() {
        System.out.println("@@=--- beforeRun");

//        getExecutionDictionary().category(ExecutionDictionary.STARTING_CONTEXT).and(
//                (category, v, op) ->
//                        XPathy.from(Tag.iframe).byAttribute(id).equals("IframeResult")
//        );


        getExecutionDictionary().category("Submit Button").or(
                (category, v, op) -> input.byAttribute(type).equals("submit")
        );

        getExecutionDictionary().registerStartingContext((category, v, op, ctx) ->
        {
            try {
                System.out.println("@@registerStartingContext: " + category);
                WebDriver webDriver = ctx.switchTo().defaultContent();
                System.out.println("@@webDriver: " + webDriver);
                System.out.println("@@XPathy..getLocator(): " + XPathy.from(Tag.iframe).byAttribute(id).equals("iframeResult").getLocator());
                WebElement webElement = ctx.findElement(XPathy.from(Tag.iframe).byAttribute(id).equals("iframeResult").getLocator());
                System.out.println("@@webElement: " + webElement);
                ctx.switchTo().frame(webElement);
//                WrappedContext wrappedContext = wrapContext(ctx);
                System.out.println("@@wrappedContext: " + ctx);
                return ctx;
//            return wrapContext(ctx.findElement(XPathy.from(Tag.iframe).byAttribute(id).equals("IframeResult").getLocator()));
            }
            catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        });

//        getExecutionDictionary().category("IframeResult").flags(ExecutionDictionary.CategoryFlags.PAGE_CONTEXT);

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
