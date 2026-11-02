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
        String stackHead
) {
    public FailureRecord {
        causeChain = causeChain == null ? List.of() : List.copyOf(causeChain);
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
        String head = message.isEmpty() ? chosen.getClass().getName() : chosen.getClass().getName() + ": " + message;
        return new FailureRecord(null, blank(stepText), blank(message), causes, head);
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
        if (head == null) head = firstStackLine(body.get("stackTrace"));
        if (head == null) head = firstStackLine(body.get("stack"));
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
            if (head == null || frame(head)) head = failureHead;
        }
        head = firstLine(head);
        if (frame(head)) head = null;
        if (wrapper(message)) message = null;
        if (message == null && head == null && phrase == null && stepText == null && causes.isEmpty()) return null;
        return new FailureRecord(blank(phrase), blank(stepText), blank(message), causes, blank(head));
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (phrase != null) out.put("phrase", phrase);
        if (stepText != null) out.put("stepText", stepText);
        if (message != null) out.put("message", message);
        if (!causeChain.isEmpty()) out.put("causeChain", causeChain);
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
}
