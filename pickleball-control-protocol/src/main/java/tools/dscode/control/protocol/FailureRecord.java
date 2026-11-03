package tools.dscode.control.protocol;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The one failure shape. Written from the throwable or from a worker/diagnostic
 * map, then read unchanged by execute-step, events, query, and the summary.
 */
public record FailureRecord(
        String phrase,
        String stepText,
        String message,
        List<String> causeChain,
        String stackHead,
        String cause
) {
    public FailureRecord {
        causeChain = causeChain == null ? List.of() : List.copyOf(causeChain);
    }

    private static final ThreadLocal<Boolean> VERBOSE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Query `--verbose` includes {@code causeChain}. The short {@code cause} is always enough. */
    public static void verbose(boolean enabled) {
        VERBOSE.set(enabled);
    }

    public static boolean verbose() {
        return Boolean.TRUE.equals(VERBOSE.get());
    }

    public static FailureRecord fromThrowable(Throwable error, String stepText) {
        if (error == null) return null;
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable cursor = error;
        while (cursor != null && seen.add(cursor) && chain.size() < 8) {
            chain.add(cursor);
            cursor = cursor.getCause();
        }
        Throwable chosen = chain.getFirst();
        for (int index = chain.size() - 1; index >= 0; index--) {
            Throwable candidate = chain.get(index);
            if (!wrapper(candidate.getMessage())) {
                chosen = candidate;
                break;
            }
        }
        List<String> causes = new ArrayList<>();
        for (Throwable item : chain) {
            String text = item.getMessage() == null ? "" : item.getMessage().strip();
            causes.add(text.isEmpty() ? item.getClass().getName() : item.getClass().getName() + ": " + text);
        }
        String message = chosen.getMessage() == null ? "" : chosen.getMessage().strip();
        if (wrapper(message)) message = "";
        String head = externalFrame(error);
        if (head == null) {
            head = message.isEmpty() ? chosen.getClass().getName() : chosen.getClass().getName() + ": " + message;
        }
        String cause = chain.size() > 1 ? causedBy(chain.get(1)) : null;
        return new FailureRecord(null, blank(stepText), blank(message), causes, head, cause);
    }

    /** Accepts a worker result, a nested {@code error} object, or a diagnostic failure event. */
    public static FailureRecord fromWorker(Map<String, ?> body) {
        if (body == null || body.isEmpty()) return null;
        String stepText = text(body.get("stepText"));
        if (stepText == null) stepText = text(body.get("step"));
        String phrase = firstText(body, "phrase", "failingPhrase", "phraseText");
        Object runtime = body.get("runtime");
        if (runtime instanceof Map<?, ?> map) {
            if (phrase == null) phrase = firstText(map, "phraseText", "phrase", "stepText");
            if (stepText == null) stepText = firstText(map, "stepText", "step", "text");
        }
        String message = firstRealText(body, "message", "errorMessage");
        String head = text(body.get("stackHead"));
        String trace = stackText(body.get("stackTrace"));
        if (trace == null) trace = stackText(body.get("stack"));
        String external = externalFrame(trace);
        if (external == null) external = externalFrame(head);
        if (head == null) head = firstLine(trace);
        if (external != null) head = external;
        List<String> causes = new ArrayList<>();
        Object error = body.get("error");
        if (error instanceof Map<?, ?> map) {
            if (message == null || wrapper(message)) {
                String nested = firstText(map, "message", "errorMessage");
                if (nested != null) message = nested;
            }
            if (head == null) head = text(map.get("stackHead"));
            if (head == null) head = firstStackLine(map.get("stackTrace"));
            if (head == null) head = firstStackLine(map.get("stack"));
            if (external == null) {
                external = externalFrame(stackText(map.get("stackTrace")));
                if (external == null) external = externalFrame(stackText(map.get("stack")));
                if (external != null) head = external;
            }
            String type = firstText(map, "type", "class");
            if (head == null && type != null && type.contains(".") && message != null && !wrapper(message)) {
                head = type + ": " + message;
            }
        } else if ((message == null || wrapper(message)) && error instanceof String text && !text.isBlank()) {
            message = text.strip();
        }
        Object failures = body.get("failures");
        if (failures instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Map<?, ?> failure) {
            collectChain(failure, causes);
            String failureMessage = chosenMessage(failure);
            String failureHead = chosenHead(failure, failureMessage);
            if (message == null || wrapper(message)) message = failureMessage;
            if (external == null) {
                external = externalFrame(stackText(failure.get("stackTrace")));
                if (external == null) external = externalFrame(stackText(failure.get("stack")));
                if (external != null) head = external;
            }
            if (external == null && (head == null || frame(head))) head = failureHead;
        }
        if (external == null) {
            head = firstLine(head);
            if (frame(head)) head = null;
        } else {
            head = firstLine(head);
        }
        if (wrapper(message)) message = null;
        if (message == null && head == null && phrase == null && stepText == null && causes.isEmpty()) return null;
        String cause = displayCause(stackText(body.get("stackTrace")));
        if (cause == null || !cause.startsWith("Caused by:")) cause = causedOr(cause, stackText(body.get("stack")));
        if (cause == null || !cause.startsWith("Caused by:")) cause = causedOr(cause, text(body.get("cause")));
        if ((cause == null || !cause.startsWith("Caused by:")) && causes.size() > 1) {
            cause = "Caused by: " + causes.get(1);
        }
        if (cause == null) cause = displayCause(stackText(body.get("stackTrace")));
        if (cause == null) cause = displayCause(stackText(body.get("stack")));
        return new FailureRecord(blank(phrase), blank(stepText), blank(message), causes, blank(head), cause);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (phrase != null) out.put("phrase", phrase);
        if (stepText != null) out.put("stepText", stepText);
        if (message != null) out.put("message", message);
        if (cause != null) out.put("cause", cause);
        if (verbose() && !causeChain.isEmpty()) out.put("causeChain", causeChain);
        if (stackHead != null) out.put("stackHead", stackHead);
        return out;
    }

    public boolean substantive() {
        return message != null || stackHead != null;
    }

    private static void collectChain(Map<?, ?> failure, List<String> causes) {
        addCause(causes, text(failure.get("class")), text(failure.get("message")));
        Object nested = failure.get("causes");
        if (!(nested instanceof List<?> list)) return;
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) addCause(causes, text(map.get("class")), text(map.get("message")));
        }
    }

    private static void addCause(List<String> causes, String type, String message) {
        if (type == null && message == null) return;
        if (type == null) causes.add(message);
        else if (message == null || message.isBlank()) causes.add(type);
        else causes.add(type + ": " + message);
    }

    private static String chosenMessage(Map<?, ?> failure) {
        String best = null;
        String own = text(failure.get("message"));
        if (!wrapper(own)) best = own;
        Object nested = failure.get("causes");
        if (nested instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) continue;
                String candidate = text(map.get("message"));
                if (!wrapper(candidate)) best = candidate;
            }
        }
        return best;
    }

    private static String chosenHead(Map<?, ?> failure, String message) {
        String line = firstStackLine(failure.get("stackTrace"));
        if (line == null) line = firstStackLine(failure.get("stack"));
        if (line != null && !frame(line) && (message == null || line.contains(message))) return line;
        String type = chosenClass(failure, message);
        if (type == null) return message;
        if (message == null || message.isBlank()) return type;
        return type + ": " + message;
    }

    private static String chosenClass(Map<?, ?> failure, String message) {
        String ownMessage = text(failure.get("message"));
        String type = text(failure.get("class"));
        if (message != null && message.equals(ownMessage)) return type;
        Object nested = failure.get("causes");
        if (nested instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) continue;
                if (message != null && message.equals(text(map.get("message")))) return text(map.get("class"));
            }
        }
        return type;
    }

    private static String firstStackLine(Object stack) {
        if (stack instanceof List<?> lines && !lines.isEmpty()) return firstLine(String.valueOf(lines.getFirst()));
        if (stack instanceof String text) return firstLine(text);
        return null;
    }

    private static String firstLine(String text) {
        if (text == null) return null;
        String value = text.strip();
        int newline = value.indexOf('\n');
        if (newline >= 0) value = value.substring(0, newline).strip();
        int carriage = value.indexOf('\r');
        if (carriage >= 0) value = value.substring(0, carriage).strip();
        return value.isEmpty() ? null : value;
    }

    private static boolean frame(String line) {
        if (line == null) return false;
        String trimmed = line.strip().toLowerCase(Locale.ROOT);
        return trimmed.startsWith("at ");
    }

    static boolean wrapper(String message) {
        if (message == null || message.isBlank()) return true;
        String trimmed = message.strip();
        return trimmed.matches("(?i)step:?\\s+'.*'\\s+failed.*")
                || trimmed.matches("(?i)step:?\\s+\".*\"\\s+failed.*");
    }

    private static String firstRealText(Map<?, ?> body, String... keys) {
        String fallback = null;
        for (String key : keys) {
            String value = text(body.get(key));
            if (value == null) continue;
            if (!wrapper(value)) return value;
            if (fallback == null) fallback = value;
        }
        return fallback;
    }

    private static String firstText(Map<?, ?> body, String... keys) {
        for (String key : keys) {
            String value = text(body.get(key));
            if (value != null) return value;
        }
        return null;
    }

    private static String text(Object value) {
        if (!(value instanceof String text) || text.isBlank()) return null;
        return text.strip();
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * Short cause for a stack dump: the top {@code Caused by:} line, otherwise the
     * first line. A one-line value is returned unchanged. Blank is null.
     */
    public static String displayCause(String stack) {
        if (stack == null || stack.isBlank()) return null;
        String caused = firstCausedBy(stack);
        if (caused != null) return caused;
        return firstLine(stack);
    }

    private static String causedOr(String current, String stack) {
        String caused = firstCausedBy(stack);
        return caused != null ? caused : current;
    }

    private static String causedBy(Throwable error) {
        if (error == null) return null;
        String text = error.getMessage() == null ? "" : error.getMessage().strip();
        String line = text.isEmpty() ? error.getClass().getName() : error.getClass().getName() + ": " + text;
        return "Caused by: " + line;
    }

    private static String firstCausedBy(String stack) {
        if (stack == null || stack.isBlank()) return null;
        for (String line : stack.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("Caused by:")) return trimmed;
        }
        return null;
    }

    private static String stackText(Object stack) {
        if (stack instanceof String text && !text.isBlank()) return text;
        if (stack instanceof List<?> lines && !lines.isEmpty()) {
            StringBuilder out = new StringBuilder();
            for (Object line : lines) {
                if (line == null) continue;
                if (!out.isEmpty()) out.append('\n');
                out.append(line);
            }
            String text = out.toString().strip();
            return text.isEmpty() ? null : text;
        }
        return null;
    }

    private static String externalFrame(Throwable error) {
        if (error == null) return null;
        Set<Throwable> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable cursor = error;
        while (cursor != null && seen.add(cursor)) {
            for (StackTraceElement element : cursor.getStackTrace()) {
                if (element == null || frameworkFrame(element.getClassName())) continue;
                return formatFrame(element);
            }
            cursor = cursor.getCause();
        }
        return null;
    }

    private static String externalFrame(String stack) {
        if (stack == null || stack.isBlank()) return null;
        for (String line : stack.split("\\R")) {
            String trimmed = line.strip();
            if (!trimmed.startsWith("at ")) continue;
            String className = frameClass(trimmed);
            if (className == null || frameworkFrame(className)) continue;
            return trimmed;
        }
        return null;
    }

    private static String formatFrame(StackTraceElement element) {
        String where;
        if (element.isNativeMethod()) where = "Native Method";
        else if (element.getFileName() == null) where = "Unknown Source";
        else if (element.getLineNumber() >= 0) where = element.getFileName() + ":" + element.getLineNumber();
        else where = element.getFileName();
        return "at " + element.getClassName() + "." + element.getMethodName() + "(" + where + ")";
    }

    /** {@code at com.example.ShopSteps.buy(ShopSteps.java:42)} -> {@code com.example.ShopSteps}. */
    private static String frameClass(String line) {
        String rest = line.strip();
        if (!rest.startsWith("at ")) return null;
        rest = rest.substring(3).strip();
        int paren = rest.indexOf('(');
        if (paren <= 0) return null;
        String method = rest.substring(0, paren);
        int dot = method.lastIndexOf('.');
        if (dot <= 0) return null;
        return method.substring(0, dot);
    }

    private static boolean frameworkFrame(String className) {
        if (className == null || className.isBlank()) return true;
        String name = className.strip();
        return name.startsWith("tools.dscode.")
                || name.startsWith("io.cucumber.")
                || name.startsWith("cucumber.")
                || name.startsWith("org.junit.")
                || name.startsWith("junit.")
                || name.startsWith("java.")
                || name.startsWith("javax.")
                || name.startsWith("jdk.")
                || name.startsWith("sun.")
                || name.startsWith("com.sun.")
                || name.startsWith("org.gradle.")
                || name.contains(".org.gradle.")
                || name.startsWith("org.opentest4j.")
                || name.startsWith("org.apache.maven.")
                || name.startsWith("org.codehaus.plexus.");
    }
}
