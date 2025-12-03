package tools.dscode.common.treeparsing.xpathcomponents;

import com.xpathy.XPathy;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import tools.dscode.common.domoperations.WrappedWebElement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Holds the result of resolving a chain of XPathData:
 *  - remembers the driver + chain
 *  - keeps the latest delegates + wrapper list
 *  - can fully refresh both on demand or on stale delegate
 */
public final class XPathChainResult {

    public final SearchContext searchContext;
    private final XPathy xPathy;

    private List<WebElement> delegates = Collections.emptyList();
    private List<WrappedWebElement> wrappers = Collections.emptyList();

    public boolean isEmpty() {
        return delegates.isEmpty();
    }

    public XPathChainResult(SearchContext searchContext, XPathy xPathy) {
        this.searchContext = searchContext;
        this.xPathy = xPathy;
        refreshAll(); // initial resolve
    }

    /** Latest immutable snapshot of wrappers for this chain. */
    public List<WrappedWebElement> getWrappers() {
        return Collections.unmodifiableList(wrappers);
    }

    /** Delegate lookup for a given wrapper index. */
    public WebElement getDelegate(int index) {
        if (index < 0 || index >= delegates.size()) {
            throw new NoSuchElementException("No delegate at index " + index);
        }
        return delegates.get(index);
    }

    /** On-demand, single-attempt full refresh of delegates + wrappers. */
    public void refreshAll() {
        System.out.println("@@refreshAll :\n" + xPathy.getXpath() + "\n\n======\n");
        System.out.println("@@getLocator :\n" + xPathy.getLocator() + "\n\n======\n");
        List<WebElement> newDelegates = searchContext.findElements(xPathy.getLocator());
        System.out.println("@@newDelegates :\n" + newDelegates + "\n\n======\n");
        List<WrappedWebElement> oldWrappers = this.wrappers;
        List<WrappedWebElement> newWrappers = new ArrayList<>(newDelegates.size());

        for (int i = 0; i < newDelegates.size(); i++) {
            newWrappers.add(new WrappedWebElement(this, i, newDelegates.get(i)));
        }

        // wire previous/next for the new generation
        for (int i = 0; i < newWrappers.size(); i++) {
            WrappedWebElement prev = (i > 0) ? newWrappers.get(i - 1) : null;
            WrappedWebElement next = (i + 1 < newWrappers.size()) ? newWrappers.get(i + 1) : null;
            newWrappers.get(i).setNeighbors(prev, next);
        }

        // map old wrappers → new wrappers (or null if index gone)
        if (oldWrappers != null) {
            for (int i = 0; i < oldWrappers.size(); i++) {
                WrappedWebElement old = oldWrappers.get(i);
                if (i < newWrappers.size()) {
                    old.setCurrent(newWrappers.get(i));
                } else {
                    old.setCurrent(null);
                }
            }
        }

        this.delegates = newDelegates;
        this.wrappers = newWrappers;
    }

    /**
     * Refresh with limited retries. Used by wrappers when they detect stale or
     * inaccessible delegates.
     */
    public void refreshAllWithRetry(int maxRetries) {
        WebDriverException last = null;
        for (int i = 0; i < maxRetries; i++) {
            try {
                refreshAll();
                return;
            } catch (WebDriverException e) {
                last = e;
            }
        }
        if (last != null) {
            throw last;
        }
    }
}
