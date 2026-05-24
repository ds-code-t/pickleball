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

import static tools.dscode.common.domoperations.NestedByLocator.findWithRetry;
import static tools.dscode.common.reporting.logging.LogForwarder.logDebug;
import static tools.dscode.common.reporting.logging.LogForwarder.logTrace;
import static tools.dscode.common.treeparsing.DefinitionContext.getExecutionDictionary;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly.combineAnd;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly.prettyPrintXPath;
public class ContextWrapper {

    //    public List<PhraseData> contextList;
    public ElementMatch elementMatch;

    public List<XPathy> paths = new ArrayList<>();

    public ContextWrapper(ElementMatch elementMatch) {
        this.elementMatch = elementMatch;
//        this.contextList = elementMatch.getPhraseContextList();
    }


    List<WebElement> getElements(SearchContext searchContext) {
        logTrace("getElements: " + elementMatch);
        if (searchContext == null) return new ArrayList<>();
        logTrace("ContextWrapper-elementTerminalXPath " + prettyPrintXPath(elementTerminalXPath));
        return getElementListFromSearchContext(searchContext, elementTerminalXPath, elementMatch);
//        return searchContext.findElements(elementTerminalXPath.getLocator());
    }


    public SearchContext getFinalSearchContext() {

        SearchContext searchContext = elementMatch.parentPhrase.getSearchContext();
        List<PhraseData> contextList = elementMatch.parentPhrase.getPhraseContextList();
        logTrace("getFinalSearchContext-contextList: " + contextList);
        logTrace("starting searchContext: " + searchContext);

        List<XPathy> xPathyList = new ArrayList<>();
        for (int j = 0; j < contextList.size(); j++) {
            PhraseData phraseData = contextList.get(j);

            logTrace("current-phrase: " + phraseData);
            logTrace("current-categoryFlags: " + phraseData.categoryFlags);
            logTrace("current-xPathyList: " + xPathyList);

            if (phraseData.contextElement != null) {
                logTrace("has contextElement");
                logTrace("current-contextElement:: " + phraseData.contextElement);
                searchContext = phraseData.getSearchContext();
                logTrace("new searchContext: " + searchContext);
            } else if (phraseData.categoryFlags.contains(ExecutionDictionary.CategoryFlags.PAGE_CONTEXT)) {
                logTrace("is PAGE_CONTEXT");
                if (!xPathyList.isEmpty()) {
                    XPathy combinedXPathy = combineAnd(xPathyList);
                    logTrace("combinedXPathy: " + searchContext);
                    searchContext = getElementFromSearchContext(searchContext, combinedXPathy, elementMatch);
                    logTrace("new searchContext: " + searchContext);
                    xPathyList.clear();
                }

                ElementMatch contextElementMatch = phraseData.getElementMatches().stream().filter(em -> em.categoryFlags.contains(ExecutionDictionary.CategoryFlags.PAGE_CONTEXT)).findFirst().orElse(null);

                searchContext = getExecutionDictionary().applyContextBuilder(contextElementMatch.category, contextElementMatch.defaultText, contextElementMatch.defaultTextOp, elementMatch.parentPhrase.getDriver(), searchContext);
                logTrace("new searchContext: " + searchContext);


                if(searchContext instanceof WebElement webElement){
                    phraseData.contextElement = new ElementWrapper(webElement, contextElementMatch, 1);
                    logTrace("new contextElement: " + phraseData.contextElement);
                }

                if (searchContext == null)
                    break;

            } else {
                logTrace("contextXPathy: " + phraseData.contextXPathy);
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

        logTrace("final-xPathyList: " + xPathyList);
        initializeElementXPaths(xPathyList);
        return searchContext;
    }

    public static List<WebElement> getElementListFromSearchContext(SearchContext searchContext, XPathy xPathy, ElementMatch elementMatch) {
        logTrace("getElementListFromSearchContext: " + elementMatch);

        String xpath = xPathy.getXpath();
        if (searchContext instanceof WebElement element) {
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
        logDebug("XPATH: " + prettyPrintXPath(xpath));
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
