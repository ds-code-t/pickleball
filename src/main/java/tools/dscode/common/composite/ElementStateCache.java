package tools.dscode.common.composite;

import org.openqa.selenium.WebElement;
import tools.dscode.common.seleniumextensions.ElementWrapper;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * States captured with the snapshot or the batched describe.
 * A later check looks here and does not search again.
 */
public final class ElementStateCache {
    private static final Map<WebElement, ElementState> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ElementStateCache() {
    }

    public static void remember(WebElement element, ElementState state) {
        if (element == null || state == null) return;
        CACHE.put(element, state);
    }

    public static ElementState recall(WebElement element) {
        if (element == null) return null;
        if (element instanceof ElementWrapper wrapper) {
            ElementState held = wrapper.snapshot().elementState();
            if (held != null) return held;
            WebElement raw = wrapper.getWrappedElement();
            return raw == null ? null : CACHE.get(raw);
        }
        return CACHE.get(element);
    }

    public static void resetForTests() {
        CACHE.clear();
    }
}
