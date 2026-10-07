package tools.dscode.common.seleniumextensions;

import com.xpathy.XPathy;
import org.openqa.selenium.By;
import org.openqa.selenium.Rectangle;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebElement;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static tools.dscode.common.domoperations.NestedByLocator.NestingMode;
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
    private final List<SpatialRelation> spatialRelations = new ArrayList<>();

    public ContextWrapper(ElementMatch elementMatch) {
        this.elementMatch = elementMatch;
//        this.contextList = elementMatch.getPhraseContextList();
    }


    List<WebElement> getElements(SearchContext searchContext) {
        logTrace("getElements: " + elementMatch);
        if (searchContext == null) return new ArrayList<>();
        logTrace("ContextWrapper-elementTerminalXPath " + prettyPrintXPath(elementTerminalXPath));
        if (spatialRelations.isEmpty()) {
            return getElementListFromSearchContext(searchContext, elementTerminalXPath, elementMatch);
        }
        List<WebElement> candidates = findAll(searchContext, elementTerminalXPath, elementMatch);
        List<WebElement> related = applySpatial(searchContext, candidates);
        return selectByPosition(related, elementMatch);
    }


    public SearchContext getFinalSearchContext() {

        SearchContext searchContext = elementMatch.parentPhrase.getSearchContext();
        List<PhraseData> contextList = elementMatch.parentPhrase.getPhraseContextList();
        spatialRelations.clear();
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

            } else if (isSpatialContext(phraseData.context)) {
                String anchorXPath = anchorXPath(phraseData);
                if (anchorXPath != null) {
                    spatialRelations.add(new SpatialRelation(phraseData.context, anchorXPath));
                }
            } else if (phraseData.contextXPathy != null) {
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
        String xpath = adjustedXPath(searchContext, xPathy.getXpath(), elementMatch);
        logDebug("XPATH: " + prettyPrintXPath(xpath));
        return findWithRetry(searchContext, new By.ByXPath(xpath), elementMatch);
    }

    private static String adjustedXPath(SearchContext searchContext, String xpath, ElementMatch elementMatch) {
        if (searchContext instanceof WebElement) {
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
        return xpath;
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

    private static boolean isSpatialContext(String context) {
        return "below".equals(context)
                || "above".equals(context)
                || "left of".equals(context)
                || "right of".equals(context);
    }

    private static String anchorXPath(PhraseData phraseData) {
        if (phraseData.getElementMatches() == null || phraseData.getElementMatches().isEmpty()) {
            return null;
        }
        XPathy xpath = phraseData.getElementMatches().getFirst().xPathy;
        return xpath == null ? null : xpath.getXpath();
    }

    private static List<WebElement> findAll(SearchContext searchContext, XPathy xPathy, ElementMatch elementMatch) {
        String xpath = adjustedXPath(searchContext, xPathy.getXpath(), elementMatch);
        logDebug("XPATH: " + prettyPrintXPath(xpath));
        boolean displayedElementsOnly =
                !elementMatch.categoryFlags.contains(ExecutionDictionary.CategoryFlags.NON_DISPLAY_ELEMENT);
        return findWithRetry(
                searchContext,
                new By.ByXPath(xpath),
                Duration.ofSeconds(10),
                displayedElementsOnly,
                nestingMode(elementMatch)
        );
    }

    private List<WebElement> applySpatial(SearchContext searchContext, List<WebElement> candidates) {
        List<WebElement> current = candidates;
        for (SpatialRelation relation : spatialRelations) {
            WebElement anchor = findAnchor(searchContext, relation.anchorXPath());
            if (anchor == null) {
                return List.of();
            }
            Rectangle anchorRect = anchor.getRect();
            List<WebElement> next = new ArrayList<>();
            for (WebElement candidate : current) {
                if (candidate.equals(anchor)) {
                    continue;
                }
                try {
                    if (spatiallyRelated(relation.relation(), anchorRect, candidate.getRect())) {
                        next.add(candidate);
                    }
                } catch (StaleElementReferenceException ignored) {
                    // A stale candidate is not a spatial match.
                }
            }
            current = next;
        }
        return current;
    }

    private static WebElement findAnchor(SearchContext searchContext, String xpath) {
        String effective = xpath;
        if (searchContext instanceof WebElement && effective.strip().startsWith("//")) {
            effective = effective.replaceFirst("//", ".//");
        }
        List<WebElement> found = searchContext.findElements(By.xpath(effective));
        return found.isEmpty() ? null : found.getFirst();
    }

    private static boolean spatiallyRelated(String relation, Rectangle anchor, Rectangle candidate) {
        int anchorLeft = anchor.x;
        int anchorRight = anchor.x + anchor.width;
        int anchorTop = anchor.y;
        int anchorBottom = anchor.y + anchor.height;
        int candidateLeft = candidate.x;
        int candidateRight = candidate.x + candidate.width;
        int candidateTop = candidate.y;
        int candidateBottom = candidate.y + candidate.height;
        boolean horizontalOverlap = candidateLeft < anchorRight && candidateRight > anchorLeft;
        boolean verticalOverlap = candidateTop < anchorBottom && candidateBottom > anchorTop;
        return switch (relation) {
            case "below" -> candidateTop >= anchorBottom - 1 && horizontalOverlap;
            case "above" -> candidateBottom <= anchorTop + 1 && horizontalOverlap;
            case "left of" -> candidateRight <= anchorLeft + 1 && verticalOverlap;
            case "right of" -> candidateLeft >= anchorRight - 1 && verticalOverlap;
            default -> false;
        };
    }

    private static List<WebElement> selectByPosition(List<WebElement> elements, ElementMatch elementMatch) {
        if (elements.isEmpty()) {
            return new ArrayList<>();
        }
        if (elementMatch.elementPosition.equalsIgnoreCase("last")) {
            return new ArrayList<>(List.of(elements.getLast()));
        }
        if (elementMatch.selectionType.isEmpty()) {
            if (elementMatch.elementIndex > elements.size()) {
                return new ArrayList<>();
            }
            return new ArrayList<>(List.of(elements.get(elementMatch.elementIndex - 1)));
        }
        if (elementMatch.elementIndex == 1) {
            return elements;
        }
        return sampleEvery(elements, elementMatch.elementIndex - 1, elementMatch.elementIndex);
    }

    private static ArrayList<WebElement> sampleEvery(List<WebElement> list, int startIndex, int step) {
        ArrayList<WebElement> out = new ArrayList<>();
        for (int i = startIndex; i < list.size(); i += step) {
            out.add(list.get(i));
        }
        return out;
    }

    private static NestingMode nestingMode(ElementMatch elementMatch) {
        if (elementMatch.categoryFlags.contains(ExecutionDictionary.CategoryFlags.NO_NESTING_FILTER)) {
            return NestingMode.NONE;
        }
        if (elementMatch.categoryFlags.contains(ExecutionDictionary.CategoryFlags.OUTER_NESTING_FILTER)) {
            return NestingMode.OUTERMOST_ONLY;
        }
        return NestingMode.DEEPEST_ONLY;
    }

    private record SpatialRelation(String relation, String anchorXPath) {
    }


}
