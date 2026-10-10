package tools.dscode.common.seleniumextensions;

import com.xpathy.XPathy;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static tools.dscode.common.reporting.logging.LogForwarder.logWarn;
import static tools.dscode.common.treeparsing.DefinitionContext.getExecutionDictionary;

/**
 * Opt-in scan for {@link tools.dscode.common.domoperations.ExecutionDictionary.CategoryFlags#ALL_CONTEXTS}.
 * Searches the current document, then the top document, then frames and open shadow roots.
 * The phrase's inherited context is ignored. Implicit wait is zero for the scan.
 * The driver is put back in one {@code finally}.
 */
public final class AllContextsScan {

    public static final int MAX_DEPTH = 5;
    public static final int MAX_FRAMES = 20;

    public static final String DEPTH_WARNING = "ALL_CONTEXTS scan reached depth " + MAX_DEPTH;
    public static final String FRAME_WARNING = "ALL_CONTEXTS scan reached " + MAX_FRAMES + " frames";
    public static final String PATH_WARNING = "ALL_CONTEXTS scan could not record the starting frame path";

    private static final String FRAME_PATH_JS = """
            try {
              var path = [];
              var w = window;
              var guard = 0;
              while (w.frameElement && guard < 50) {
                var fe = w.frameElement;
                var id = fe.getAttribute('id') || '';
                var name = fe.getAttribute('name') || '';
                var frames = fe.ownerDocument.querySelectorAll('iframe, frame');
                var index = 0;
                for (var i = 0; i < frames.length; i++) {
                  if (frames[i] === fe) { index = i; break; }
                }
                path.unshift({id: id, name: name, index: index});
                w = w.parent;
                guard++;
              }
              return path;
            } catch (e) {
              return null;
            }
            """;

    private static final String SHADOW_MATCH_JS = """
            var host = arguments[0];
            var xpath = arguments[1];
            var root = host && host.shadowRoot;
            if (!root || !root.querySelectorAll) return [];
            var out = [];
            root.querySelectorAll('*').forEach(function(el) {
              try {
                var snap = document.evaluate(xpath, el, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
                if (snap.snapshotLength > 0 && snap.snapshotItem(0) === el) out.push(el);
              } catch (e) {}
            });
            return out;
            """;

    private static final String SHADOW_HOSTS_JS = """
            var root = arguments[0];
            var scope = root && root.querySelectorAll ? root : document;
            var hosts = [];
            scope.querySelectorAll('*').forEach(function(el) {
              if (el.shadowRoot) hosts.push(el);
            });
            return hosts;
            """;

    private AllContextsScan() {
    }

    public static List<ElementWrapper> find(ElementMatch elementMatch) {
        if (elementMatch == null || elementMatch.parentPhrase == null) {
            return List.of();
        }
        WebDriver driver = elementMatch.parentPhrase.getDriver();
        if (driver == null) {
            return List.of();
        }
        return new Scan(driver, elementMatch).run();
    }

    private static final class Scan {
        private final WebDriver driver;
        private final ElementMatch elementMatch;
        private final String xpath;
        private final List<ElementWrapper> matches = new ArrayList<>();
        private final Set<String> searched = new HashSet<>();
        private final Set<String> foreignNoted = new HashSet<>();
        private List<FrameRef> startPath;
        private boolean pathWarned;
        private boolean depthWarned;
        private boolean frameWarned;
        private boolean stopFrames;
        private int frameCount;

        private Scan(WebDriver driver, ElementMatch elementMatch) {
            this.driver = driver;
            this.elementMatch = elementMatch;
            this.xpath = xpathOf(elementMatch);
        }

        private List<ElementWrapper> run() {
            Duration originalWait = Duration.ZERO;
            String originalWindow = null;
            try {
                originalWait = driver.manage().timeouts().getImplicitWaitTimeout();
            } catch (WebDriverException ignored) {
                originalWait = Duration.ZERO;
            }
            try {
                originalWindow = driver.getWindowHandle();
            } catch (WebDriverException ignored) {
                originalWindow = null;
            }
            try {
                driver.manage().timeouts().implicitlyWait(Duration.ZERO);
                startPath = capturePath();
                searchDocument(driver, startPath == null ? List.of() : startPath, 0, false);
                if (startPath != null) {
                    searched.add(key(startPath));
                }
                if (startPath == null || !startPath.isEmpty()) {
                    driver.switchTo().defaultContent();
                    searchDocument(driver, List.of(), 0, true);
                    searched.add(key(List.of()));
                }
                driver.switchTo().defaultContent();
                scanFrames(driver, List.of(), 0);
            } catch (WebDriverException ignored) {
                // A closed window or a driver that cannot switch still returns whatever matched.
            } finally {
                restoreBrowser(originalWindow, originalWait);
            }
            return limit(matches);
        }

        private void searchDocument(SearchContext context, List<FrameRef> path, int depth, boolean foreign) {
            searchLight(context, path, foreign);
            searchShadows(context, path, depth, foreign);
        }

        private void searchLight(SearchContext context, List<FrameRef> path, boolean foreign) {
            if (xpath == null || context == null) {
                return;
            }
            List<WebElement> found;
            try {
                found = context.findElements(By.xpath(xpath));
            } catch (WebDriverException ignored) {
                return;
            }
            accept(found, path, foreign);
        }

        private void accept(List<WebElement> found, List<FrameRef> path, boolean foreign) {
            for (WebElement element : found) {
                try {
                    if (!element.isDisplayed()) {
                        continue;
                    }
                    if (foreign) {
                        if (foreignNoted.add(key(path))) {
                            matches.add(ElementWrapper.foreignPresence(elementMatch));
                        }
                    } else {
                        matches.add(new ElementWrapper(element, elementMatch, matches.size() + 1));
                    }
                } catch (WebDriverException ignored) {
                    // stale, detached, or cross-origin
                }
            }
        }

        private void searchShadows(SearchContext context, List<FrameRef> path, int depth, boolean foreign) {
            List<WebElement> hosts = shadowHosts(context);
            if (hosts.isEmpty()) {
                return;
            }
            if (depth + 1 > MAX_DEPTH) {
                warnDepth();
                return;
            }
            for (WebElement host : hosts) {
                try {
                    SearchContext shadow = host.getShadowRoot();
                    if (shadow == null) {
                        continue;
                    }
                    accept(matchHostShadow(host), path, foreign);
                    searchShadows(shadow, path, depth + 1, foreign);
                } catch (WebDriverException ignored) {
                    // closed shadow root, stale host, or cross-origin
                }
            }
        }

        private void scanFrames(SearchContext context, List<FrameRef> path, int depth) {
            if (stopFrames || context == null) {
                return;
            }
            List<WebElement> frames = safeFrames(context);
            if (!frames.isEmpty() && depth + 1 > MAX_DEPTH) {
                warnDepth();
            } else {
                for (int i = 0; i < frames.size(); i++) {
                    if (stopFrames) {
                        return;
                    }
                    if (frameCount >= MAX_FRAMES) {
                        warnFrames();
                        stopFrames = true;
                        return;
                    }
                    WebElement frame = frames.get(i);
                    if (!sameOriginFrame(frame)) {
                        continue;
                    }
                    FrameRef ref;
                    try {
                        ref = FrameRef.from(frame, i);
                    } catch (WebDriverException ignored) {
                        continue;
                    }
                    frameCount++;
                    List<FrameRef> child = new ArrayList<>(path);
                    child.add(ref);
                    try {
                        driver.switchTo().frame(frame);
                        String childKey = key(child);
                        boolean foreign = startPath == null || !child.equals(startPath);
                        if (!searched.contains(childKey)) {
                            searchDocument(driver, child, depth + 1, foreign);
                            searched.add(childKey);
                        }
                        scanFrames(driver, child, depth + 1);
                    } catch (WebDriverException ignored) {
                        // cross-origin, detached, or closed frame
                    } finally {
                        try {
                            restorePath(path);
                        } catch (WebDriverException ignored) {
                            // keep going; the outer finally still restores the window
                        }
                    }
                }
            }
            if (stopFrames) {
                return;
            }
            List<WebElement> hosts = shadowHosts(context);
            if (hosts.isEmpty()) {
                return;
            }
            if (depth + 1 > MAX_DEPTH) {
                warnDepth();
                return;
            }
            for (WebElement host : hosts) {
                if (stopFrames) {
                    return;
                }
                try {
                    SearchContext shadow = host.getShadowRoot();
                    if (shadow != null) {
                        scanFrames(shadow, path, depth + 1);
                    }
                } catch (WebDriverException ignored) {
                    // closed, stale, or cross-origin shadow root
                }
            }
        }

        private boolean sameOriginFrame(WebElement frame) {
            try {
                Object accessible = ((JavascriptExecutor) driver).executeScript(
                        "var frame = arguments[0];"
                                + " try { return !!(frame.contentDocument && frame.contentDocument.documentElement); }"
                                + " catch (e) { return false; }",
                        frame
                );
                return Boolean.TRUE.equals(accessible);
            } catch (WebDriverException ignored) {
                return false;
            }
        }

        private void restoreBrowser(String originalWindow, Duration originalWait) {
            try {
                if (originalWindow != null) {
                    driver.switchTo().window(originalWindow);
                }
                driver.switchTo().defaultContent();
                if (startPath != null) {
                    for (FrameRef ref : startPath) {
                        switchToRef(ref);
                    }
                }
            } catch (WebDriverException ignored) {
                if (!pathWarned) {
                    pathWarned = true;
                    logWarn(PATH_WARNING);
                }
            }
            try {
                driver.manage().timeouts().implicitlyWait(originalWait);
            } catch (WebDriverException ignored) {
                // the driver is already gone
            }
        }

        private void restorePath(List<FrameRef> path) {
            driver.switchTo().defaultContent();
            if (path == null) {
                return;
            }
            for (FrameRef ref : path) {
                switchToRef(ref);
            }
        }

        private void switchToRef(FrameRef ref) {
            List<WebElement> frames = driver.findElements(By.cssSelector("iframe, frame"));
            if (ref.id != null) {
                for (WebElement frame : frames) {
                    try {
                        if (ref.id.equals(frame.getAttribute("id"))) {
                            driver.switchTo().frame(frame);
                            return;
                        }
                    } catch (WebDriverException ignored) {
                        // try the next frame
                    }
                }
            }
            if (ref.name != null) {
                for (WebElement frame : frames) {
                    try {
                        if (ref.name.equals(frame.getAttribute("name"))) {
                            driver.switchTo().frame(frame);
                            return;
                        }
                    } catch (WebDriverException ignored) {
                        // try the next frame
                    }
                }
            }
            driver.switchTo().frame(ref.index);
        }

        private List<FrameRef> capturePath() {
            try {
                Object raw = ((JavascriptExecutor) driver).executeScript(FRAME_PATH_JS);
                if (!(raw instanceof List<?> list)) {
                    warnPath();
                    return null;
                }
                List<FrameRef> path = new ArrayList<>();
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> map)) {
                        warnPath();
                        return null;
                    }
                    path.add(new FrameRef(
                            blankToNull(map.get("id")),
                            blankToNull(map.get("name")),
                            number(map.get("index"))
                    ));
                }
                return path;
            } catch (WebDriverException ignored) {
                warnPath();
                return null;
            }
        }

        private List<WebElement> matchHostShadow(WebElement host) {
            String selfXpath = selfTest(xpath);
            if (selfXpath == null || host == null) {
                return List.of();
            }
            try {
                Object raw = ((JavascriptExecutor) driver).executeScript(SHADOW_MATCH_JS, host, selfXpath);
                return castElements(raw);
            } catch (WebDriverException ignored) {
                return List.of();
            }
        }

        private static String selfTest(String xpath) {
            if (xpath == null) {
                return null;
            }
            String trimmed = xpath.trim();
            if (trimmed.startsWith("//*")) {
                return "self::*" + trimmed.substring(3);
            }
            return null;
        }

        private List<WebElement> shadowHosts(SearchContext context) {
            try {
                Object raw = ((JavascriptExecutor) driver).executeScript(
                        SHADOW_HOSTS_JS,
                        context instanceof WebDriver ? null : context
                );
                return castElements(raw);
            } catch (WebDriverException ignored) {
                return List.of();
            }
        }

        private List<WebElement> safeFrames(SearchContext context) {
            try {
                return new ArrayList<>(context.findElements(By.cssSelector("iframe, frame")));
            } catch (WebDriverException ignored) {
                return List.of();
            }
        }

        private void warnDepth() {
            if (!depthWarned) {
                depthWarned = true;
                logWarn(DEPTH_WARNING);
            }
        }

        private void warnFrames() {
            if (!frameWarned) {
                frameWarned = true;
                logWarn(FRAME_WARNING);
            }
        }

        private void warnPath() {
            if (!pathWarned) {
                pathWarned = true;
                logWarn(PATH_WARNING);
            }
        }

        private List<ElementWrapper> limit(List<ElementWrapper> found) {
            if (found.isEmpty()) {
                return found;
            }
            String position = elementMatch.elementPosition == null ? "" : elementMatch.elementPosition;
            if ("last".equalsIgnoreCase(position)) {
                return List.of(found.getLast());
            }
            if (elementMatch.selectionType == null || elementMatch.selectionType.isBlank()) {
                return List.of(found.getFirst());
            }
            return found;
        }
    }

    private static String xpathOf(ElementMatch elementMatch) {
        try {
            if (elementMatch.xPathy != null) {
                String xpath = elementMatch.xPathy.getXpath();
                if (xpath != null && !xpath.isBlank()) {
                    return xpath;
                }
            }
        } catch (RuntimeException ignored) {
            // fall through to the category locator
        }
        try {
            XPathy fallback = getExecutionDictionary().getCategoryXPathy(elementMatch.category);
            return fallback == null ? null : fallback.getXpath();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static List<WebElement> castElements(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<WebElement> elements = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof WebElement element) {
                elements.add(element);
            }
        }
        return elements;
    }

    private static String key(List<FrameRef> path) {
        if (path == null) {
            return "";
        }
        StringBuilder key = new StringBuilder();
        for (FrameRef ref : path) {
            key.append(ref.id == null ? "" : ref.id)
                    .append('\u0001')
                    .append(ref.name == null ? "" : ref.name)
                    .append('\u0001')
                    .append(ref.index)
                    .append('\u0002');
        }
        return key.toString();
    }

    private static String blankToNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static int number(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private record FrameRef(String id, String name, int index) {
        static FrameRef from(WebElement frame, int index) {
            return new FrameRef(
                    blankToNull(frame.getAttribute("id")),
                    blankToNull(frame.getAttribute("name")),
                    index
            );
        }
    }
}
