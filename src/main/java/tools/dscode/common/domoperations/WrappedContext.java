package tools.dscode.common.domoperations;

import org.openqa.selenium.SearchContext;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.WrapsElement;


public abstract class WrappedContext implements WebDriver, SearchContext, WebElement, WrapsElement {
    public final SearchContext searchContext;
    public WrappedContext(SearchContext searchContext) {
        this.searchContext = searchContext;
    }
}
