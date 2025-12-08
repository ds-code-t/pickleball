package tools.dscode.common.seleniumextensions;

import com.xpathy.XPathy;
import io.cucumber.java.eo.Se;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;

import java.util.ArrayList;
import java.util.List;

import static tools.dscode.common.treeparsing.DefinitionContext.getExecutionDictionary;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly.combineAnd;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly.prettyPrintXPath;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.everyNth;
import static tools.dscode.coredefinitions.GeneralSteps.getBrowser;

public class ContextWrapper {

    public List<PhraseData> contextList;
    public ElementMatch elementMatch;

    public List<XPathy> paths = new ArrayList<>();

    public ContextWrapper(ElementMatch elementMatch) {
        this.elementMatch = elementMatch;
        this.contextList = elementMatch.getPhraseContextList();
    }


    public List<WebElement> refreshElement(WebDriver driver, XPathy XPathyWithID) {
        getFinalSearchContext(driver);
        XPathy refreshXpath = combineAnd(XPathyWithID, elementPath);
        return driver.findElements(refreshXpath.getLocator());
    }


    public List<WebElement> getElements(WebDriver driver) {
        getFinalSearchContext(driver);
        return driver.findElements(elementTerminalXPath.getLocator());
    }

    public SearchContext getFinalSearchContext(WebDriver driver) {
        driver.switchTo().defaultContent();
        SearchContext searchContext = driver;
        List<XPathy> xPathyList = new ArrayList<>();
        for (int j = 0; j < contextList.size(); j++) {
            PhraseData phraseData = contextList.get(j);
            System.out.println("@@phraseData-- " + phraseData);
            System.out.println("@@phraseData.categoryFlags-- " + phraseData.categoryFlags);
            if (phraseData.categoryFlags.contains(ExecutionDictionary.CategoryFlags.PAGE_CONTEXT)) {
                System.out.println("@@phraseData-- 1 " + xPathyList);
                if (!xPathyList.isEmpty()) {
                    XPathy combinedXPathy = combineAnd(xPathyList);
                    searchContext = searchContext.findElement(combinedXPathy.getLocator());
                    xPathyList.clear();
                }
                System.out.println("@@before-settingContext: " + phraseData.elementMatch.category + " , " +  phraseData.elementMatch.text+ " , " +  phraseData.elementMatch.textOp);
                searchContext = getExecutionDictionary().applyContextBuilder(phraseData.elementMatch.category, phraseData.elementMatch.text, phraseData.elementMatch.textOp, driver, searchContext);
            } else {
                xPathyList.add(phraseData.contextXPathy);
                System.out.println("@@phraseData-- 3 " + xPathyList);
            }

        }
        xPathyList.add(elementMatch.xPathy);
        initializeElementXPaths(xPathyList);
        return searchContext;
    }


    XPathy elementPath;
    XPathy elementTerminalXPath;

    public void initializeElementXPaths(List<XPathy> xPathyList) {
        if (elementTerminalXPath != null) return;
        elementPath = combineAnd(xPathyList);
        if (elementMatch.elementPosition.isEmpty() && elementMatch.selectionType.isEmpty())
            elementMatch.elementPosition = "1";
        if (elementMatch.elementPosition.isEmpty())
            elementTerminalXPath = elementPath;
        else if (elementMatch.elementPosition.equals("last")) {
            elementTerminalXPath = elementPath.last();
        } else {
            elementMatch.elementIndex = Integer.parseInt(elementMatch.elementPosition);
            if (elementMatch.selectionType.isEmpty()) {
                elementTerminalXPath = elementPath.nth(elementMatch.elementIndex);
            } else {
                elementTerminalXPath = everyNth(elementPath, elementMatch.elementIndex);
            }
        }

    }


}
