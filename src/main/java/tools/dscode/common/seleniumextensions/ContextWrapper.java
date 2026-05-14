package tools.dscode.common.seleniumextensions;

import com.xpathy.XPathy;
import org.openqa.selenium.By;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.WebElement;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.domoperations.NestedByLocator;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;

import java.util.ArrayList;
import java.util.List;

import static tools.dscode.common.domoperations.NestedByLocator.findWithRetry;
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


    public List<WebElement> getElements() {
        printDebug("\n##ContextWrapper- getElements: " + elementMatch);
        SearchContext searchContext = getFinalSearchContext();
        if (searchContext == null) return new ArrayList<>();
        printDebug("####ContextWrapper-searchContext: " + searchContext.getClass().getSimpleName());
        printDebug("####ContextWrapper-elementMatch.parentPhrase: " + elementMatch.parentPhrase);
        printDebug("####ContextWrapper-elementTerminalXPath " + prettyPrintXPath(elementTerminalXPath));
        printDebug("\n\n##ContextWrapper-end##");
        return getElementListFromSearchContext(searchContext, elementTerminalXPath, elementMatch);
//        return searchContext.findElements(elementTerminalXPath.getLocator());
    }


    public SearchContext getFinalSearchContext() {

        printDebug("\n##ContextWrapper-getFinalSearchContext11");
        printDebug("\n##ContextWrapper-getFinalSearchContext: " + elementMatch.category + " , " + elementMatch.parentPhrase);
        printDebug("\n##ContextWrapper-elementMatch.parentPhrase.contextElement: " + elementMatch.parentPhrase.contextElement);
        SearchContext searchContext = elementMatch.parentPhrase.getSearchContext();
        PhraseData searchContextPhrase = elementMatch.parentPhrase;
        List<PhraseData> contextList = elementMatch.parentPhrase.getPhraseContextList();
        printDebug("##ContextWrapper-contextList.size(): " + contextList.size());
        printDebug("##ContextWrapper-contextList::::: " + contextList);
        printDebug("##ContextWrapper-searchContext1: " + searchContext.getClass().getSimpleName());
        List<XPathy> xPathyList = new ArrayList<>();
        printDebug("\n------\n##ContextWrapper-currentPhrase: " + elementMatch.parentPhrase);
        for (int j = 0; j < contextList.size(); j++) {
            PhraseData phraseData = contextList.get(j);

            printDebug("##ContextWrapper-phraseData1: " + phraseData);
            printDebug("##ContextWrapper-phraseData--phraseData.contextElement : " + phraseData.contextElement);
            printDebug("##ContextWrapper-phraseData--phraseData.categoryFlag : " + phraseData.categoryFlags);

            if (phraseData.contextElement != null) {
                printDebug("##ContextWrapper-#psearchContext1: " + searchContext);
                searchContext = phraseData.getSearchContext();
                searchContextPhrase = phraseData;
                printDebug("##ContextWrapper-psearchContext2: " + searchContext);
                printDebug("##ContextWrapper-phraseData.getSearchContext()222: " + phraseData.getSearchContext());
            } else if (phraseData.categoryFlags.contains(ExecutionDictionary.CategoryFlags.PAGE_CONTEXT)) {
                printDebug("##ContextWrapper-phraseData2: " + xPathyList);
                printDebug("##ContextWrapper-phraseData.searchContext: " + phraseData.getSearchContext());
                printDebug("##ContextWrapper-xPathyList:= " + xPathyList);
                if (!xPathyList.isEmpty()) {
                    XPathy combinedXPathy = combineAnd(xPathyList);
                    searchContext = getElementFromSearchContext(searchContext, combinedXPathy, elementMatch);

                    xPathyList.clear();
                }

                ElementMatch contextElementMatch = phraseData.getElementMatches().stream().filter(em -> em.categoryFlags.contains(ExecutionDictionary.CategoryFlags.PAGE_CONTEXT)).findFirst().orElse(null);

                printDebug("##ContextWrapper-searchContext-1 " + searchContext);
                searchContext = getExecutionDictionary().applyContextBuilder(contextElementMatch.category, contextElementMatch.defaultText, contextElementMatch.defaultTextOp, elementMatch.parentPhrase.getDriver(), searchContext);
                printDebug("##ContextWrapper-searchContext-2 " + searchContext);

                if(searchContext instanceof WebElement webElement){
                    phraseData.contextElement = new ElementWrapper(webElement, contextElementMatch, 1);
                }

                if (searchContext == null)
                    break;

                printDebug("##ContextWrapper-searchContext2: " + (searchContext == null ? "null" : searchContext.getClass().getSimpleName()));
            } else {
                printDebug("phraseData4: " + phraseData.contextXPathy);
                xPathyList.add(phraseData.contextXPathy);
            }
        }

        if(elementMatch.categoryFlags.contains(ExecutionDictionary.CategoryFlags.PAGE_CONTEXT)){
            if(!xPathyList.isEmpty()) {
                XPathy combinedXPathy = combineAnd(xPathyList);
                searchContext = getElementFromSearchContext(searchContext, combinedXPathy, elementMatch);
                xPathyList.clear();
            }
            searchContext = getExecutionDictionary().applyContextBuilder(elementMatch.category, elementMatch.defaultText, elementMatch.defaultTextOp, elementMatch.parentPhrase.getDriver(), searchContext);
            if(searchContext instanceof WebElement webElement){
                elementMatch.parentPhrase.contextElement = new ElementWrapper(webElement, elementMatch, 1);
            }
            return searchContext;
        }

        xPathyList.add(elementMatch.xPathy);

        printDebug("##ContextWrapper-xPathyList: " + xPathyList);
        initializeElementXPaths(xPathyList);
        return searchContext;
    }

    public static List<WebElement> getElementListFromSearchContext(SearchContext searchContext, XPathy xPathy, ElementMatch elementMatch) {
        String xpath = xPathy.getXpath();

        if (searchContext instanceof WebElement element) {
            System.out.println();
            PhraseData currentPhrase = elementMatch.parentPhrase.getPreviousPhrase();
            String relationToContextElement = "descendant-or-self::";
            while (currentPhrase != null) {
                if (currentPhrase.contextElement != null) {
                    if (currentPhrase.context.equals("after"))
                        relationToContextElement = "following::";
                    else if (currentPhrase.context.equals("before"))
                        relationToContextElement = "preceding::";
                    break;
                }
                currentPhrase = currentPhrase.getPreviousPhrase();
            }


            if (xpath.strip().replaceAll("\\(", "").startsWith("//"))
                xpath = xpath.replaceFirst("//", relationToContextElement);
        }
        printDebug("##XPath: getElementListFromSearchContext\n" + prettyPrintXPath(xpath) + "\n----------------");
        return findWithRetry(searchContext, new By.ByXPath(xpath), elementMatch);
//        return searchContext.findElements(new By.ByXPath(xpath));
    }

    public static WebElement getElementFromSearchContext(SearchContext searchContext, XPathy xPathy, ElementMatch elementMatch) {
        List<WebElement> list = getElementListFromSearchContext(searchContext, xPathy, elementMatch);
        if (list.isEmpty()) return null;
        return list.getFirst();
    }


    XPathy elementPath;
    XPathy elementTerminalXPath;

    public void initializeElementXPaths(List<XPathy> xPathyList) {
        if (elementTerminalXPath != null) return;
        elementPath = combineAnd(xPathyList);
        elementTerminalXPath = elementPath;
        if (!elementMatch.elementPosition.equalsIgnoreCase("last"))
            elementMatch.elementIndex = elementMatch.elementPosition.isEmpty() ? 1 : Integer.parseInt(elementMatch.elementPosition);

    }


}
