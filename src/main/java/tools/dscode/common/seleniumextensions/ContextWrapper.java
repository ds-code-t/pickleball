package tools.dscode.common.seleniumextensions;

import com.xpathy.XPathy;
import org.openqa.selenium.By;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.WebElement;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;

import java.util.ArrayList;
import java.util.List;

import static tools.dscode.common.domoperations.DeepestByLocator.findDeepestWithRetry;
import static tools.dscode.common.treeparsing.DefinitionContext.getExecutionDictionary;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly.combineAnd;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly.prettyPrintXPath;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.everyNth;
import static tools.dscode.common.util.debug.DebugUtils.printDebug;

public class ContextWrapper {

    //    public List<PhraseData> contextList;
    public ElementMatch elementMatch;

    public List<XPathy> paths = new ArrayList<>();

    public ContextWrapper(ElementMatch elementMatch) {
        this.elementMatch = elementMatch;
//        this.contextList = elementMatch.getPhraseContextList();
    }


//    public List<WebElement> refreshElement(WebDriver driver, XPathy XPathyWithID) {
//        getFinalSearchContext(driver);
//        XPathy refreshXpath = combineAnd(XPathyWithID, elementPath);
//        return driver.findElements(refreshXpath.getLocator());
//    }


    public List<WebElement> getElements() {
        printDebug("\n##getElements: " + elementMatch);
        SearchContext searchContext = getFinalSearchContext();
        if(searchContext == null) return new ArrayList<>();
        printDebug("\n##searchContext: " + searchContext.getClass().getSimpleName());
        printDebug("\n##elementMatch.parentPhrase: " + elementMatch.parentPhrase);
        printDebug("\n##elementTerminalXPath ");
        prettyPrintXPath(elementTerminalXPath);
        printDebug("\n\n##");
        return getElementListFromSearchContext(searchContext, elementTerminalXPath);
//        return searchContext.findElements(elementTerminalXPath.getLocator());
    }

    public SearchContext getFinalSearchContext() {
        printDebug("\n##getFinalSearchContext11");
        printDebug("\n##getFinalSearchContext: " + elementMatch.category + " , " + elementMatch.parentPhrase);
        printDebug("\n##elementMatch.parentPhrase.contextElement: " + elementMatch.parentPhrase.contextElement);
        SearchContext searchContext = elementMatch.parentPhrase.getSearchContext();
        List<PhraseData> contextList = elementMatch.getPhraseContextList();
        printDebug("## contextList.size(): " + contextList.size());
        printDebug("##searchContext1: " + searchContext.getClass().getSimpleName());
        List<XPathy> xPathyList = new ArrayList<>();
        printDebug("\n------\n##currentPhrase: " + elementMatch.parentPhrase);
        for (int j = 0; j < contextList.size(); j++) {
            PhraseData phraseData = contextList.get(j);

            printDebug("##phraseData1: " + phraseData);
            printDebug("##phraseData--phraseData.contextElement : " + phraseData.contextElement);
            printDebug("##phraseData--phraseData.categoryFlag : " + phraseData.categoryFlags);

            if (phraseData.contextElement != null) {
                printDebug("##psearchContext1: " + searchContext);
                searchContext = phraseData.getSearchContext();
                printDebug("##psearchContext2: " + searchContext);
                printDebug("##phraseData.getSearchContext()222: " + phraseData.getSearchContext());
            } else if (phraseData.categoryFlags.contains(ExecutionDictionary.CategoryFlags.PAGE_CONTEXT)) {
                printDebug("##phraseData2: " + xPathyList);
                printDebug("##phraseData.searchContext: " + phraseData.getSearchContext());
                printDebug("##xPathyList:= " + xPathyList);
                if (!xPathyList.isEmpty()) {
                    XPathy combinedXPathy = combineAnd(xPathyList);
                    searchContext = getElementFromSearchContext(searchContext, combinedXPathy);
//                    searchContext = searchContext.findElement(combinedXPathy.getLocator());
                    xPathyList.clear();
                }

                    printDebug("##searchContext-1 " + searchContext);
                    searchContext = getExecutionDictionary().applyContextBuilder(phraseData.getFirstElement().category, phraseData.getFirstElement().defaultText, phraseData.getFirstElement().defaultTextOp, elementMatch.parentPhrase.getDriver(), searchContext);
                    printDebug("##searchContext-2 " + searchContext);
                if(searchContext == null)
                    break;

                printDebug("##searchContext2: " + (searchContext == null ? "null" : searchContext.getClass().getSimpleName()));
            } else {
                printDebug("phraseData4: " + phraseData.contextXPathy);
                xPathyList.add(phraseData.contextXPathy);
            }
        }

        xPathyList.add(elementMatch.xPathy);
        printDebug("##xPathyList: " + xPathyList);
        initializeElementXPaths(xPathyList);
        return searchContext;
    }

    public static List<WebElement> getElementListFromSearchContext(SearchContext searchContext, XPathy xPathy) {
        String xpath = xPathy.getXpath();

        if (searchContext instanceof WebElement) {
            if (xpath.strip().replaceAll("\\(", "").startsWith("//"))
                xpath = xpath.replaceFirst("//", "descendant-or-self::");
        }

        printDebug("##XPath: getElementListFromSearchContext\n" + prettyPrintXPath(xpath) +"\n----------------" );

        return findDeepestWithRetry(searchContext, new By.ByXPath(xpath));
//        return searchContext.findElements(new By.ByXPath(xpath));
    }

    public static WebElement getElementFromSearchContext(SearchContext searchContext, XPathy xPathy) {
        List<WebElement> list = getElementListFromSearchContext(searchContext, xPathy);
        if (list.isEmpty()) return null;
        return list.getFirst();
    }


    XPathy elementPath;
    XPathy elementTerminalXPath;

    public void initializeElementXPaths(List<XPathy> xPathyList) {
        if (elementTerminalXPath != null) return;
//        XPathy xPathy = combineAnd(xPathyList);
//        elementPath = XPathy.from(XPathyUtils.maybeDeepestMatches(xPathy.getXpath()));
        elementPath = combineAnd(xPathyList);

//        if (elementMatch.elementPosition.isEmpty() && elementMatch.selectionType.isEmpty())
//            elementMatch.elementPosition = "1";
        if (elementMatch.elementPosition.isEmpty())
            elementTerminalXPath = elementPath;
        else if (elementMatch.elementPosition.equals("last")) {
            elementTerminalXPath = elementPath.last();
        } else {
            elementMatch.elementIndex = Integer.parseInt(elementMatch.elementPosition);
            if (elementMatch.selectionType.isEmpty()) {
                elementTerminalXPath = elementPath.nth(elementMatch.elementIndex);
            } else {
                if (elementMatch.elementIndex == 1) {
                    elementTerminalXPath = elementPath;
                } else {
                    elementTerminalXPath = everyNth(elementPath, elementMatch.elementIndex);
                }
            }
        }


    }


}
