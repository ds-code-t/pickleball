package tools.dscode.common.seleniumextensions;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import tools.dscode.common.composite.CompositeWebElement;
import tools.dscode.common.composite.ElementStateCache;
import tools.dscode.common.composite.ElementStateScript;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One element-scoped script shared by the Selenium and composite producers.
 *
 * <p>The function reads tag, text, value, selected child, attributes, sibling
 * index, same-tag index, and a stable selector. Selenium runs it with the
 * live element as {@code arguments[0]}. Composite cannot: dom-composite 2.4.0
 * has no eval command, and {@link CompositeWebElement} is not a WebDriver
 * node. Composite runs the same function through the driver's
 * {@link JavascriptExecutor} after the script locates that one element from
 * {@link SnapshotLookup} candidates, including same-origin frames. That is one
 * script evaluation, not a page snapshot.
 */
public final class ElementSnapshotScript {
    /**
     * Shared body. {@code pickleballSnapshot(el)} returns null when {@code el}
     * is missing. Callers append the invocation.
     */
    public static final String FUNCTION = """
            function pickleballSnapshot(el) {
              if (!el || !el.tagName) return null;
              var tag = String(el.tagName).toLowerCase();
              var value = '';
              try {
                if (el.value != null && typeof el.value !== 'object') value = String(el.value);
              } catch (ignoreValue) {}
              if (value === '') {
                var attrValue = el.getAttribute('value');
                if (attrValue != null) value = String(attrValue);
              }
              var text = '';
              if (tag === 'textarea' || tag === 'input') {
                var typed = el.getAttribute('value');
                text = typed == null ? '' : String(typed);
              } else if (tag === 'select') {
                var opt = null;
                if (el.options && el.selectedIndex >= 0 && el.selectedIndex < el.options.length) {
                  opt = el.options[el.selectedIndex];
                }
                text = opt ? String(opt.text == null ? '' : opt.text) : '';
              } else {
                try {
                  text = el.innerText == null ? '' : String(el.innerText);
                } catch (ignoreText) {
                  text = el.textContent == null ? '' : String(el.textContent);
                }
                if (text === '') {
                  var fallback = el.getAttribute('value');
                  text = fallback == null ? '' : String(fallback);
                }
              }
              var selectedValue = null;
              var buf = '';
              var any = false;
              var children = el.children;
              if (children) {
                var c;
                for (c = 0; c < children.length; c++) {
                  var child = children[c];
                  if (child && child.hasAttribute && child.hasAttribute('selected')) {
                    any = true;
                    var childValue = child.getAttribute('value');
                    if (childValue == null) {
                      try { childValue = child.innerText; } catch (ignoreChild) { childValue = null; }
                      if (childValue == null || childValue === '') childValue = child.textContent || '';
                    }
                    buf += childValue == null ? '' : String(childValue);
                  }
                }
              }
              if (any) selectedValue = buf;
              var attrs = {};
              if (el.attributes) {
                var i;
                for (i = 0; i < el.attributes.length; i++) {
                  var a = el.attributes[i];
                  if (a && a.name) attrs[a.name] = a.value == null ? '' : String(a.value);
                }
              }
              var siblingIndex = 0;
              var walk = el;
              while (walk.previousElementSibling) {
                walk = walk.previousElementSibling;
                siblingIndex++;
              }
              var sameTagIndex = 0;
              walk = el;
              var rawTag = el.tagName;
              while (walk.previousElementSibling) {
                walk = walk.previousElementSibling;
                if (walk.tagName === rawTag) sameTagIndex++;
              }
              return {
                tag: tag,
                text: text,
                value: value,
                selectedValue: selectedValue,
                attributes: attrs,
                siblingIndex: siblingIndex,
                sameTagIndex: sameTagIndex,
                stableSelector: pickleballStableSelector(el, tag)
              };
            }
            function pickleballCssIdent(value) {
              return !!value && /^[A-Za-z_][-A-Za-z0-9_]*$/.test(value);
            }
            function pickleballAttrSafe(value) {
              return value != null && value !== '' && String(value).indexOf('"') < 0
                && String(value).indexOf('\\\\') < 0 && String(value).indexOf(']') < 0;
            }
            function pickleballUnique(selector) {
              try { return document.querySelectorAll(selector).length === 1; }
              catch (ignoreUnique) { return false; }
            }
            function pickleballStableSelector(el, tag) {
              if (el.id && pickleballCssIdent(el.id) && pickleballUnique('#' + el.id)) return '#' + el.id;
              var names = ['data-testid', 'data-test', 'data-user-id', 'name', 'title', 'aria-label'];
              var n;
              for (n = 0; n < names.length; n++) {
                var raw = el.getAttribute(names[n]);
                if (!pickleballAttrSafe(raw)) continue;
                var withTag = tag + '[' + names[n] + '="' + raw + '"]';
                if (pickleballUnique(withTag)) return withTag;
                var bare = '[' + names[n] + '="' + raw + '"]';
                if (pickleballUnique(bare)) return bare;
              }
              if (el.id && pickleballAttrSafe(el.id)) {
                var byId = tag + '[id="' + el.id + '"]';
                if (pickleballUnique(byId)) return byId;
              }
              var parts = [];
              var cur = el;
              var depth = 0;
              while (cur && cur.tagName && depth < 8) {
                var curTag = String(cur.tagName).toLowerCase();
                if (cur.id && pickleballCssIdent(cur.id)) {
                  parts.unshift('#' + cur.id);
                  var rooted = parts.join(' > ');
                  if (pickleballUnique(rooted)) return rooted;
                  break;
                }
                var index = 1;
                var sib = cur;
                while (sib.previousElementSibling) {
                  sib = sib.previousElementSibling;
                  if (String(sib.tagName).toLowerCase() === curTag) index++;
                }
                parts.unshift(curTag + ':nth-of-type(' + index + ')');
                var candidate = parts.join(' > ');
                if (pickleballUnique(candidate)) return candidate;
                cur = cur.parentElement;
                depth++;
              }
              return parts.join(' > ');
            }
            """;

    public static final String ON_ELEMENT = FUNCTION + ElementStateScript.FUNCTION + """
            var shot = pickleballSnapshot(arguments[0]);
            if (shot) shot.elementState = pickleballElementState(arguments[0]);
            return shot;
            """;

    public static final String ON_CANDIDATES = FUNCTION + ElementStateScript.FUNCTION + """
            function pickleballResolve(doc, selectors) {
              if (!doc || !selectors) return null;
              var s;
              for (s = 0; s < selectors.length; s++) {
                var sel = selectors[s];
                if (!sel) continue;
                if (sel.charAt(0) === '/' || sel.charAt(0) === '(') {
                  try {
                    var found = doc.evaluate(sel, doc, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
                    if (found.snapshotLength === 1) return found.snapshotItem(0);
                  } catch (ignoreXpath) {}
                } else {
                  try {
                    var all = doc.querySelectorAll(sel);
                    if (all.length === 1) return all[0];
                  } catch (ignoreCss) {}
                }
              }
              var frames = [];
              try { frames = doc.querySelectorAll('iframe,frame'); } catch (ignoreFrames) {}
              var i;
              for (i = 0; i < frames.length; i++) {
                try {
                  var child = frames[i].contentDocument;
                  if (child) {
                    var hit = pickleballResolve(child, selectors);
                    if (hit) return hit;
                  }
                } catch (ignoreFrame) {}
              }
              return null;
            }
            var live = pickleballResolve(document, arguments[0]);
            var shot = pickleballSnapshot(live);
            if (shot) shot.elementState = pickleballElementState(live);
            return shot;
            """;

    private ElementSnapshotScript() {
    }

    public static ElementSnapshot onElement(WebDriver driver, org.openqa.selenium.WebElement element) {
        Object raw = executor(driver).executeScript(ON_ELEMENT, element);
        if (raw == null) {
            throw new IllegalStateException(
                    "error: element snapshot script returned nothing. mode=selenium"
                            + " fix: the element was detached before the script ran. Refresh once via its stable selector.");
        }
        ElementSnapshot snapshot = toSnapshot(raw, null, null);
        ElementStateCache.remember(element, snapshot.elementState());
        return snapshot;
    }

    /**
     * Composite path. One script call. Throws when no candidate matches exactly
     * one live node. Does not invent positions.
     */
    public static ElementSnapshot onComposite(WebDriver driver, CompositeWebElement element) {
        List<String> candidates = SnapshotLookup.candidates(element);
        if (candidates.isEmpty()) {
            throw new IllegalStateException(
                    "error: composite element has no selector the snapshot script can run. mode=composite"
                            + " nodeId=" + (element == null ? "" : element.nodeId())
                            + " tried=id,data-testid,data-test,data-user-id,name,text,selector"
                            + " fix: dom-composite 2.4.0 has no element eval. Add an id or data-testid,"
                            + " or a unique name, so the shared script can address the live node.");
        }
        Object raw = executor(driver).executeScript(ON_CANDIDATES, candidates);
        if (raw == null) {
            throw new IllegalStateException(
                    "error: composite snapshot script found no unique live element. mode=composite"
                            + " nodeId=" + element.nodeId()
                            + " tried=" + String.join(" | ", candidates)
                            + " fix: dom-composite 2.4.0 has no element eval, so Pickleball runs the shared"
                            + " script through the WebDriver. None of those selectors matched exactly one node.");
        }
        ElementSnapshot snapshot = toSnapshot(raw, element.nodeId(), bounds(element));
        ElementStateCache.remember(element, snapshot.elementState());
        return snapshot;
    }

    static ElementSnapshot toSnapshot(Object raw, String nodeId, ElementSnapshot.Bounds bounds) {
        Map<String, Object> map = castMap(raw);
        return new ElementSnapshot(
                blankToNull(nodeId),
                text(map.get("tag")).toLowerCase(Locale.ROOT),
                text(map.get("text")),
                text(map.get("value")),
                selected(map.get("selectedValue")),
                attributes(map.get("attributes")),
                bounds,
                number(map.get("siblingIndex")),
                number(map.get("sameTagIndex")),
                text(map.get("stableSelector")),
                ElementStateScript.parse(map.get("elementState"))
        );
    }

    private static ElementSnapshot.Bounds bounds(CompositeWebElement element) {
        if (element == null) return null;
        return new ElementSnapshot.Bounds(
                element.getLocation().getX(),
                element.getLocation().getY(),
                element.getSize().getWidth(),
                element.getSize().getHeight()
        );
    }

    private static JavascriptExecutor executor(WebDriver driver) {
        if (driver instanceof JavascriptExecutor js) return js;
        throw new IllegalArgumentException(
                "error: element snapshot needs a JavascriptExecutor. mode="
                        + (driver == null ? "none" : driver.getClass().getSimpleName())
                        + " fix: use a browser driver. The snapshot script is how both producers read positions.");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, value) -> {
                if (key != null) copy.put(String.valueOf(key), value);
            });
            return copy;
        }
        throw new IllegalStateException(
                "error: element snapshot script returned " + (raw == null ? "null" : raw.getClass().getName())
                        + " fix: the shared script must return an object.");
    }

    private static Map<String, String> attributes(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return Map.of();
        Map<String, String> copy = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (key != null && value != null) copy.put(String.valueOf(key), String.valueOf(value));
        });
        return copy;
    }

    private static String selected(Object raw) {
        if (raw == null) return null;
        return String.valueOf(raw);
    }

    private static String text(Object raw) {
        return raw == null ? "" : String.valueOf(raw);
    }

    private static String blankToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw;
    }

    private static int number(Object raw) {
        if (raw instanceof Number number) return Math.max(0, number.intValue());
        if (raw == null) return 0;
        try {
            return Math.max(0, Integer.parseInt(String.valueOf(raw)));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
