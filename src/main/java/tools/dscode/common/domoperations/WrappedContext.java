//package tools.dscode.common.domoperations;
//
//import com.xpathy.XPathy;
//import org.openqa.selenium.JavascriptExecutor;
//import org.openqa.selenium.SearchContext;
//import org.openqa.selenium.WebDriver;
//import org.openqa.selenium.WebElement;
//import org.openqa.selenium.WrapsElement;
//import org.openqa.selenium.interactions.Interactive;
//
//import java.util.List;
//
//import static tools.dscode.common.treeparsing.DefinitionContext.getExecutionDictionary;
//import static tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly.combineAnd;
//
//
//public abstract class SearchContext implements WebDriver, SearchContext, WebElement, WrapsElement , JavascriptExecutor, Interactive {
//    public final SearchContext searchContext;
//    public SearchContext(SearchContext searchContext) {
//        this.searchContext = searchContext;
//    }
//
//    public List<WebElement> findElementsBaseWithFilter(XPathy xpathy) {
//       return findElements(combineAnd(xpathy, getExecutionDictionary().getCategoryXPathy(ExecutionDictionary.BASE_CATEGORY)).getLocator());
//    }
//
//    public WebElement findElementWithBaseFilter(XPathy xpathy) {
//        System.out.println("@@findElementWithBaseFilter: " + xpathy);
//        System.out.println("@@getBaseXPathy: " + getExecutionDictionary().getCategoryXPathy(ExecutionDictionary.BASE_CATEGORY));
//        return findElement(combineAnd(xpathy, getExecutionDictionary().getCategoryXPathy(ExecutionDictionary.BASE_CATEGORY)).getLocator());
//    }
//
//}
