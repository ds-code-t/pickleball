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
import static tools.dscode.common.treeparsing.DefinitionContext.getExecutionDictionary;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly.combineOr;
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



        ExecutionDictionary dict = getExecutionDictionary();

        dict.category("Menu").andAnyCategories("idMatch", "cellLabel")
                .addBase("//select");


        dict.category("cellLabel")
                .and(

                        (category, v, op) -> {
                            if (v == null || v.isNullOrBlank()) {
                                return null; // no label text to match, skip this builder
                            }
                            String textXpath = dict.andThenOr(CONTAINS_TEXT, v, op).getXpath().replaceAll("^//\\*", "");
                            printDebug("##textXpath genericLabel: " + textXpath);

                            XPathy xPathy =  new XPathy("//*[self::select or self::input or self::textarea]" +
                                    "  [ancestor::tr[count(td)=2" +
                                    "     and count(descendant::*[self::select or self::input or self::textarea])=1" +
                                    "     and td[1]" + textXpath +
                                    "     and td[2][descendant::*[self::select or self::input or self::textarea]]" +
                                    "  ]]");

                            return xPathy;
                        }
                );

        dict.category("idMatch")
                .or(
                        (category, v, op) ->{ XPathy x = XPathyBuilder.buildIfAllTrue(any, id, v, op, v != null);
                            return x;
                        }
                );


        dict.category("label")
                .and(
                        (category, v, op) -> {
                            if (v == null || v.isNullOrBlank()) {
                                return null; // no label text to match, skip this builder
                            }
                            String textXpath = v.asNormalizedText();
                            printDebug("##textXpath forLabel:1 " + textXpath);

                            XPathy ret = combineOr(
                                    new XPathy("//select[@id = //label[normalize-space(.)='"+ v +"']/@for]"),
                                    new XPathy("//*[preceding-sibling::*[1][self::label[normalize-space(.)='"+ v +"']]]")
                            );

                            onMatch("##textXpath forLabel:2 ", (matchedString) ->
                                    System.out.println(matchedString + ret)
                            );
                            return ret;
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
