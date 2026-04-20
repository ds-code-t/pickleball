package tools.dscode;

import com.xpathy.Tag;
import com.xpathy.XPathy;
import io.cucumber.java.en.Given;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import tools.dscode.common.annotations.LifecycleManager;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.domoperations.elementstates.BinaryStateConditions;
import tools.dscode.common.treeparsing.xpathcomponents.XPathyBuilder;


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


    private static void print(String label, XPathy xpathy) {
        System.out.println(label + ":");
        System.out.println("  " + xpathy.getXpath());
        System.out.println();
    }


}
