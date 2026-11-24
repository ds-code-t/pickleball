package tools.dscode.browser;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.bidi.log.JavascriptLogEntry;
import org.openqa.selenium.bidi.log.StackFrame;
import org.openqa.selenium.bidi.module.LogInspector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;

/**
 * E3 {@code pkb_fail_on_js_error=off|warn|fail} (default off). Uncaught page errors and unhandled promise rejections
 * are collected per WebDriver over WebDriver BiDi ({@code log.entryAdded} type javascript, through Selenium's
 * {@link LogInspector}). At the end of each step the errors seen during it are checked: {@code warn} logs them,
 * {@code fail} fails the step with the message and stack. {@code pkb_js_error_ignore=<regex>} allow-lists errors whose
 * message matches.
 */
public final class JsErrorGate {
    private static final Map<WebDriver, Watch> WATCHES = new WeakHashMap<>();
    private static volatile boolean warnedNoBidi;

    private JsErrorGate() {
    }

    /** One uncaught page error. */
    public record PageError(String message, List<String> stack) {
        public String render() {
            StringBuilder out = new StringBuilder(message == null ? "" : message);
            for (String frame : stack) out.append("\n    at ").append(frame);
            return out.toString();
        }
    }

    private static final class Watch {
        final ConcurrentLinkedQueue<PageError> errors = new ConcurrentLinkedQueue<>();
        LogInspector inspector;
    }

    public static String mode() {
        String value = BrowserProtocols.setting("pkb_fail_on_js_error");
        if (value == null || value.isBlank()) return "off";
        String v = value.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "warn", "fail" -> v;
            default -> "off";
        };
    }

    static Pattern ignore() {
        String raw = BrowserProtocols.setting("pkb_js_error_ignore");
        if (raw == null || raw.isBlank()) return null;
        try {
            return Pattern.compile(raw);
        } catch (RuntimeException bad) {
            return Pattern.compile(Pattern.quote(raw));
        }
    }

    /** Starts collecting for this driver once (no-op when the mode is off). */
    public static void watch(WebDriver driver) {
        if (driver == null || "off".equals(mode())) return;
        synchronized (WATCHES) {
            if (WATCHES.containsKey(driver)) return;
            Watch watch = new Watch();
            WATCHES.put(driver, watch);
            try {
                watch.inspector = new LogInspector(driver);
                watch.inspector.onJavaScriptException(entry -> watch.errors.add(of(entry)));
            } catch (RuntimeException noBidi) {
                if (!warnedNoBidi) {
                    warnedNoBidi = true;
                    System.err.println("WARN pkb_fail_on_js_error needs WebDriver BiDi (webSocketUrl); page errors are not collected"
                            + " for this driver: " + noBidi.getClass().getSimpleName());
                }
            }
        }
    }

    static PageError of(JavascriptLogEntry entry) {
        List<String> frames = new ArrayList<>();
        if (entry.getStackTrace() != null && entry.getStackTrace().getCallFrames() != null) {
            for (StackFrame frame : entry.getStackTrace().getCallFrames()) {
                String fn = frame.getFunctionName() == null || frame.getFunctionName().isBlank() ? "<anonymous>" : frame.getFunctionName();
                frames.add(fn + " (" + frame.getUrl() + ":" + (frame.getLineNumber() + 1) + ":" + (frame.getColumnNumber() + 1) + ")");
            }
        }
        return new PageError(entry.getText(), frames);
    }

    /** Errors collected since the last drain, minus the ignore regex. */
    public static List<PageError> drain(WebDriver driver) {
        Watch watch;
        synchronized (WATCHES) {
            watch = WATCHES.get(driver);
        }
        if (watch == null) return List.of();
        Pattern ignore = ignore();
        List<PageError> out = new ArrayList<>();
        PageError error;
        while ((error = watch.errors.poll()) != null) {
            if (ignore != null && error.message() != null && ignore.matcher(error.message()).find()) continue;
            out.add(error);
        }
        return out;
    }

    /** End of a step: warn or fail on errors seen during it. A short settle lets a just-thrown error arrive. */
    public static void afterStep(WebDriver driver, String stepText) {
        if (driver == null) return;
        String mode = mode();
        if ("off".equals(mode)) return;
        watch(driver);
        try {
            Thread.sleep(50);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        List<PageError> errors = drain(driver);
        if (errors.isEmpty()) return;
        String message = message(stepText, errors);
        if ("fail".equals(mode)) throw new AssertionError(message);
        System.err.println("WARN " + message);
    }

    static String message(String stepText, List<PageError> errors) {
        StringBuilder out = new StringBuilder("Uncaught page error during step '").append(stepText == null ? "" : stepText).append("': ");
        out.append(errors.get(0).render());
        if (errors.size() > 1) out.append("\n(and ").append(errors.size() - 1).append(" more)");
        out.append("\nfix: fix the page error, allow it with pkb_js_error_ignore=<regex>, or set pkb_fail_on_js_error=warn to only log it.");
        return out.toString();
    }

    /** Test hook: forget every driver. */
    static void reset() {
        synchronized (WATCHES) {
            for (Watch w : WATCHES.values()) {
                try {
                    if (w.inspector != null) w.inspector.close();
                } catch (RuntimeException ignored) {
                    // Closed with its driver.
                }
            }
            WATCHES.clear();
        }
    }
}
