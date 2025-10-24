package tools.dscode.util;

import io.cucumber.core.gherkin.Pickle;
import io.cucumber.plugin.event.PickleStepTestStep;

import java.net.URI;

public final class KeyFunctions {

    private KeyFunctions() {
        /* no instances */ }

    /**
     * Returns a unique-ish key for supported objects: - PickleStepTestStep ->
     * "uri:stepLine" - Pickle -> "uri:line" - anything else ->
     * "fqcn@identityHash" (memory identity)
     */
    public static String getUniqueKey(Object obj) {
        if (obj == null) {
            return "null";
        }

        if (obj instanceof PickleStepTestStep step) {
            URI uri = step.getUri();
            int line = step.getStep() != null ? step.getStep().getLine() : -1;
            return safeUri(uri) + ":" + line;
        }

        if (obj instanceof Pickle pickle) {
            URI uri = pickle.getUri();
            Integer line = (pickle.getLocation() != null) ? pickle.getLocation().getLine() : -1;
            return safeUri(uri) + ":" + line;
        }

        // Fallback: identity-based key (approximates memory location)
        String cls = obj.getClass().getName();
        String idHex = Integer.toHexString(System.identityHashCode(obj));
        return cls + "@" + idHex;
    }

    private static String safeUri(URI uri) {
        return (uri == null) ? "unknown" : uri.toString();
    }
}
