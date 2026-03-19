package tools.dscode.common.mappings;

import io.cucumber.core.runner.GlobalState;
import io.cucumber.core.runner.StepExtension;
import io.cucumber.core.stepexpression.DocStringArgument;

import java.util.Map;

import static io.cucumber.core.runner.GlobalState.getFromRunningParsingMap;
import static io.cucumber.core.runner.GlobalState.getFromRunningParsingMapCaseInsensitive;
import static io.cucumber.core.runner.GlobalState.resolveToStringWithRunningParsingMap;
import static tools.dscode.common.mappings.GlobalMappings.configsRoot;

public final class BracketLiteralMasker {
    private BracketLiteralMasker() {
    }

    // Unique, invisible Unicode control chars (use Private Use Area to avoid collisions).
    // Pick any 4 distinct code points you like.
    public static final char M_OPEN_CURLY = '\uE000';
    public static final char M_CLOSE_CURLY = '\uE001';
    public static final char M_OPEN_ANGLE = '\uE002';
    public static final char M_CLOSE_ANGLE = '\uE003';

    public static String resolveWithMasking(String s) {
        return unmaskAndEscape(resolveToStringWithRunningParsingMap(maskBrackets(s)));
    }


    public static String resolveFromDocStringOrConfig(String key) {
        StepExtension currentStep = GlobalState.getRunningStep();
        if(currentStep.argument instanceof DocStringArgument)
            return resolveWithMasking(currentStep.argument.getValue().toString());
       return getAndResolveKeyWithMasking(configsRoot + "." + key);
    }

    public static String resolveConfig(String key) {
        return getAndResolveKeyWithMasking(configsRoot + "." + key);
    }

    public static String getAndResolveKeyWithMasking(String key) {
        Object value = getFromRunningParsingMapCaseInsensitive(key);
        if (value == null) return null;
        return resolveWithMasking(String.valueOf(value));
    }


    public static String maskBrackets(String s) {
        if (s == null || s.isEmpty()) return s;

        var out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);

            // Handle "~{" and "~<": drop '~', keep bracket literal.
            if (c == '~' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (n == '{' || n == '<') {
                    out.append(n);
                    i++;
                    continue;
                }
            }

            // Handle "}~" and ">~": drop trailing '~', keep bracket literal.
            if ((c == '}' || c == '>') && i + 1 < s.length() && s.charAt(i + 1) == '~') {
                out.append(c);
                i++;
                continue;
            }

            // Mask unescaped brackets.
            out.append(switch (c) {
                case '{' -> M_OPEN_CURLY;
                case '}' -> M_CLOSE_CURLY;
                case '<' -> M_OPEN_ANGLE;
                case '>' -> M_CLOSE_ANGLE;
                default -> c;
            });
        }
        return out.toString();
    }

    public static String unmaskAndEscape(String s) {
        if (s == null || s.isEmpty()) return s;

        // 1) Escape literal brackets (but ignore masks for now).
        var tmp = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);

            if ((c == '{' || c == '<') && (i == 0 || s.charAt(i - 1) != '~')) tmp.append('~');
            tmp.append(c);
            if ((c == '}' || c == '>') && (i + 1 >= s.length() || s.charAt(i + 1) != '~')) tmp.append('~');
        }

        // 2) Replace masks with literal brackets.
        for (int i = 0; i < tmp.length(); i++) {
            char c = tmp.charAt(i);
            tmp.setCharAt(i, switch (c) {
                case M_OPEN_CURLY -> '{';
                case M_CLOSE_CURLY -> '}';
                case M_OPEN_ANGLE -> '<';
                case M_CLOSE_ANGLE -> '>';
                default -> c;
            });
        }
        return tmp.toString();
    }

    // Optional: if you prefer to keep masks configurable:
    public static Map<Character, Character> maskMap() {
        return Map.of('{', M_OPEN_CURLY, '}', M_CLOSE_CURLY, '<', M_OPEN_ANGLE, '>', M_CLOSE_ANGLE);
    }
}