package tools.dscode.common.browseroperations;

import org.openqa.selenium.NoSuchWindowException;
import org.openqa.selenium.WebDriver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import tools.dscode.common.treeparsing.parsedComponents.ElementMatch.TextOp;

/**
 * Window/tab utilities (filtering + switching are decoupled).
 *
 * Design:
 * 1) findMatchingHandles(...) returns a list of matching window handles (possibly empty).
 *    - It will temporarily switch while evaluating TITLE/URL predicates, then restores original focus.
 * 2) switchToHandleOrThrow(...) switches to a provided handle (must exist in getWindowHandles()).
 *
 * Notes:
 * - Selenium does not expose "last focused" window; HISTORY provides optional "PREVIOUS".
 * - "NEW" here means "any handle that isn't the current handle" (popup / other tab).
 */
public final class WindowSwitch {

    private WindowSwitch() {}

    public enum WindowSelectionType {
        URL,
        TITLE,
        INDEX,
        FIRST,
        LAST,
        NEW,
        PREVIOUS;

        public static final Map<String, WindowSelectionType> LOOKUP =
                Arrays.stream(values())
                        .collect(Collectors.toUnmodifiableMap(
                                WindowSelectionType::name,
                                Function.identity()
                        ));
    }

    // Optional focus history (only used for PREVIOUS switching / finding).
    private static final WeakHashMap<WebDriver, Deque<String>> HISTORY = new WeakHashMap<>();

    // --------------------------
    // Public API (decoupled)
    // --------------------------

    /**
     * Returns window handles matching the selection criteria.
     *
     * Behavior by type:
     * - FIRST/LAST: returns singleton list
     * - INDEX: returns singleton list (if in range), else empty
     * - NEW: returns all handles except the current handle (often 0 or 1, but can be more)
     * - PREVIOUS: returns the most recent still-open handle from history (singleton) or empty
     * - TITLE/URL: returns all handles whose title/url matches ALL provided TextOps
     *
     * For TITLE/URL:
     * - Temporarily switches to each handle to read title/url, then restores original focus.
     * - If textOps is null/empty: returns empty list (by design; avoids "match everything" surprises)
     */
    public static List<String> findMatchingHandles(WebDriver driver,
                                                   WindowSelectionType type,
                                                   List<TextOp> textOps) {
        Objects.requireNonNull(driver, "driver");
        Objects.requireNonNull(type, "type");

        List<String> handles = orderedHandles(driver);
        if (handles.isEmpty()) return List.of();

        return switch (type) {
            case FIRST -> List.of(handles.get(0));

            case LAST -> List.of(handles.get(handles.size() - 1));

            case INDEX -> {
                Integer idx = tryParseIndex(textOps);
                if (idx == null) yield List.of();
                if (idx < 0 || idx >= handles.size()) yield List.of();
                yield List.of(handles.get(idx));
            }

            case NEW -> {
                String current = safeCurrentHandle(driver);
                yield handles.stream().filter(h -> !h.equals(current)).toList();
            }

            case PREVIOUS -> {
                String prev = peekPreviousLiveHandle(driver);
                yield (prev == null) ? List.of() : List.of(prev);
            }

            case TITLE -> findByStringProperty(driver, handles, textOps, Property.TITLE);

            case URL -> findByStringProperty(driver, handles, textOps, Property.URL);
        };
    }

    /**
     * Switches to the provided handle (must exist in driver's current getWindowHandles()).
     * Also records focus history (so PREVIOUS works later).
     *
     * @return the handle switched to (echo)
     * @throws NoSuchWindowException with a descriptive message if handle is not present.
     */
    public static String switchToHandleOrThrow(WebDriver driver, String handle) {
        Objects.requireNonNull(driver, "driver");
        if (handle == null || handle.isBlank()) {
            throw new NoSuchWindowException("Cannot switch window: handle was null/blank.");
        }

        String current = safeCurrentHandle(driver);
        if (handle.equals(current)) return handle;

        Set<String> live = driver.getWindowHandles();
        if (!live.contains(handle)) {
            throw new NoSuchWindowException(
                    "Cannot switch window: handle not found in current session. " +
                            "Requested=" + handle + ", Current=" + current + ", LiveHandles=" + live
            );
        }

        pushHistory(driver, current);
        driver.switchTo().window(handle);
        return handle;
    }

    /** Optional: clear the tracked focus history for a driver. */
    public static void clearHistory(WebDriver driver) {
        if (driver == null) return;
        synchronized (HISTORY) {
            HISTORY.remove(driver);
        }
    }

    // --------------------------
    // Internals
    // --------------------------

    private enum Property { URL, TITLE }

    private static List<String> findByStringProperty(WebDriver driver,
                                                     List<String> handles,
                                                     List<TextOp> textOps,
                                                     Property property) {
        if (textOps == null || textOps.isEmpty()) {
            // Intentional: avoid accidental "match all windows"
            return List.of();
        }

        String original = safeCurrentHandle(driver);
        List<String> matches = new ArrayList<>();

        try {
            for (String h : handles) {
                System.out.println("@@WINDOW: " + h);
                driver.switchTo().window(h);

                String actual = (property == Property.URL)
                        ? safeString(driver.getCurrentUrl())
                        : safeString(driver.getTitle());

                if (matchesAll(actual, textOps)) {
                    matches.add(h);
                }
            }
        } finally {
            safeSwitchRestore(driver, original);
        }

        return List.copyOf(matches);
    }

    private static List<String> orderedHandles(WebDriver driver) {
        Set<String> set = driver.getWindowHandles();
        if (set == null || set.isEmpty()) return List.of();
        return new ArrayList<>(set);
    }

    /**
     * For INDEX selection:
     * - If textOps is null/empty => null
     * - If multiple TextOps are provided, all must parse to the same integer
     */
    private static Integer tryParseIndex(List<TextOp> textOps) {
        if (textOps == null || textOps.isEmpty()) return null;

        Integer idx = null;
        for (TextOp op : textOps) {
            if (op == null) continue;
            String s = safeTextOpText(op).trim();
            if (s.isEmpty()) continue;

            int parsed;
            try {
                parsed = Integer.parseInt(s);
            } catch (Exception e) {
                // If it isn't a number, treat as "no match possible" (rather than throw during filtering).
                return null;
            }

            if (idx == null) idx = parsed;
            else if (!idx.equals(parsed)) return null;
        }
        return idx;
    }

    private static boolean matchesAll(String actual, List<TextOp> textOps) {
        for (TextOp textOp : textOps) {
            if (textOp == null) continue;
            if (!matches(actual, textOp)) return false;
        }
        return true;
    }

    private static boolean matches(String actual, TextOp textOp) {
        String expected = safeTextOpText(textOp);
        var op = textOp.op();

        if (actual == null) actual = "";
        if (expected == null) expected = "";

        return switch (op) {
            case EQUALS -> actual.equals(expected);
            case CONTAINS -> actual.contains(expected);
            case STARTS_WITH -> actual.startsWith(expected);
            case ENDS_WITH -> actual.endsWith(expected);
            case MATCHES -> Pattern.compile(expected).matcher(actual).matches(); // full regex match
            default -> throw new IllegalStateException("Unexpected TextOp op: " + op);
        };
    }

    private static String safeTextOpText(TextOp textOp) {
        Object v = (textOp == null) ? null : textOp.text();
        return String.valueOf(v);
    }

    private static String safeCurrentHandle(WebDriver driver) {
        try {
            return driver.getWindowHandle();
        } catch (Exception e) {
            throw new NoSuchWindowException("Unable to read current window handle: " + e.getMessage());
        }
    }

    private static void safeSwitchRestore(WebDriver driver, String handle) {
        if (handle == null) return;
        try {
            if (driver.getWindowHandles().contains(handle)) {
                driver.switchTo().window(handle);
            }
        } catch (Exception ignored) {
            // best-effort restore only
        }
    }

    private static String safeString(String s) {
        return (s == null) ? "" : s;
    }

    // -------- focus history (PREVIOUS support) --------

    private static void pushHistory(WebDriver driver, String handle) {
        if (handle == null) return;
        synchronized (HISTORY) {
            HISTORY.computeIfAbsent(driver, d -> new ArrayDeque<>()).push(handle);
        }
    }

    /**
     * Returns the most recent handle in history that is still open and not the current handle,
     * without mutating the history (peek semantics).
     */
    private static String peekPreviousLiveHandle(WebDriver driver) {
        String current = safeCurrentHandle(driver);
        Set<String> live = driver.getWindowHandles();

        Deque<String> stack;
        synchronized (HISTORY) {
            stack = HISTORY.get(driver);
            if (stack == null || stack.isEmpty()) return null;

            // We don't want to mutate the real stack here; copy it.
            stack = new ArrayDeque<>(stack);
        }

        while (!stack.isEmpty()) {
            String prev = stack.pop();
            if (prev != null && live.contains(prev) && !prev.equals(current)) {
                return prev;
            }
        }
        return null;
    }
}
