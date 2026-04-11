package tools.dscode;

import com.xpathy.Tag;
import com.xpathy.XPathy;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import tools.dscode.common.annotations.LifecycleHook;
import tools.dscode.common.annotations.Phase;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.domoperations.elementstates.BinaryStateConditions;
import tools.dscode.common.treeparsing.xpathcomponents.XPathyBuilder;
import tools.dscode.pickleruntime.CucumberOptionResolver;

import java.util.List;

import static com.xpathy.Attribute.aria_label;
import static com.xpathy.Attribute.id;
import static com.xpathy.Attribute.name;
import static com.xpathy.Attribute.role;
import static com.xpathy.Attribute.title;
import static com.xpathy.Attribute.type;
import static com.xpathy.Case.LOWER;
import static com.xpathy.Tag.any;
import static com.xpathy.Tag.input;
import static tools.dscode.common.domoperations.ExecutionDictionary.CONTAINS_TEXT;
import static tools.dscode.common.domoperations.ExecutionDictionary.STARTING_CONTEXT;
import static tools.dscode.common.treeparsing.DefinitionContext.getExecutionDictionary;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly.combineOr;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.colocatedDeepNormalizedVisibleText;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.customElementSuffixPredicate;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.deepNormalizedVisibleText;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.descendantDeepNormalizedVisibleText;
import static tools.dscode.common.util.debug.DebugUtils.onMatch;
import static tools.dscode.common.util.debug.DebugUtils.printDebug;

public class Registrations {


    public static void main(String[] args) {

        System.out.println("=== BinaryStateConditions XPathy Outputs ===");
        System.out.println();

        print("onElement()", BinaryStateConditions.onElement());
        print("offElement()", BinaryStateConditions.offElement());
//        print("checkedElement()", BinaryStateConditions.checkedElement());
//        print("selectedElement()", BinaryStateConditions.selectedElement());

        System.out.println();
        System.out.println("=== Done ===");
    }

    private static void print(String label, XPathy xpathy) {
        System.out.println(label + ":");
        System.out.println("  " + xpathy.getXpath());
        System.out.println();
    }


    @LifecycleHook(Phase.BEFORE_CUCUMBER_RUN)
    public static void beforeRun() {
        System.out.println("Lifecycle hook invoked.");
        System.out.println("Resolved options: " + CucumberOptionResolver.getAllOptions());

        if (CucumberOptionResolver.getAllOptions().isEmpty()) {
            throw new IllegalStateException("CucumberOptionResolver returned an empty options map.");
        }

        ExecutionDictionary dict = getExecutionDictionary();


        dict.category("Elm").addBase("//div");


        dict.category("Ddd").andAnyCategories("fff").inheritsFrom(CONTAINS_TEXT).or(
                "//select",
                "//div"
        );

//                .addBase("//select");


        dict.category("fff").addBase("//div");

        dict.category("sff")
                .and(
                        (category, v, op) -> {

                            XPathy returnXpath = combineOr(
                                    new XPathy("//select"),
                                    new XPathy("//textarea")
//                                    new XPathy("//*[@id and string-length(normalize-space(@id)) > 0 and @id = (ancestor::div[3]//descendant::*" + deepNormalizedVisibleText + "[@for and string-length(normalize-space(@for)) > 0][1]/@for)]"),
//                                    new XPathy("//*[@aria-labelledby and string-length(normalize-space(@aria-labelledby)) > 0 and @aria-labelledby = (ancestor::div[3]//descendant::*" + deepNormalizedVisibleText + "[@id and string-length(normalize-space(@id)) > 0][1]/@id)]"),
//                                    new XPathy("//*[ @headers and string-length(normalize-space(@headers)) > 0 and contains(concat(' ', @headers, ' '), concat(' ', (ancestor::div[3]//descendant::*" + deepNormalizedVisibleText + "[@id and string-length(normalize-space(@id)) > 0][1]/@id), ' ')) ]"),
//                                    new XPathy("//*[ancestor-or-self::*[position() <= 7]" +
//                                            "  [preceding-sibling::*[1]        " +
//                                            descendantDeepNormalizedVisibleText(v, op) +
//                                            "         [not(descendant::button or descendant::input or descendant::textarea or descendant::select or descendant::a)]" +
//                                            "  ]" +
//                                            "]"),
//                                    XPathy.from("//*" + colocatedDeepNormalizedVisibleText(v, op))
                            );
                            printDebug("##textXpath forLabel:2 " + returnXpath);
                            return returnXpath;
                        }
                );


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
                List<WebElement> frames = webDriver.findElements(xpathy.getLocator());
                if (frames.isEmpty())
                    return webDriver;
                webDriver.switchTo().frame(frames.getFirst());

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
//        ThreadDumps.dumpStacksAsync("cucumber AFTER_CUCUMBER_RUN");
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
