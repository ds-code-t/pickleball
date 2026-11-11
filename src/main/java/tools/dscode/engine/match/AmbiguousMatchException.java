package tools.dscode.engine.match;

import java.util.List;

/** More than one glue step matched the same text. */
public final class AmbiguousMatchException extends IllegalStateException {
    private final String text;
    private final List<String> locations;

    public AmbiguousMatchException(String text, List<String> locations) {
        super("Ambiguous step '" + text + "': " + String.join(", ", locations));
        this.text = text == null ? "" : text;
        this.locations = locations == null ? List.of() : List.copyOf(locations);
    }

    public String text() {
        return text;
    }

    public List<String> locations() {
        return locations;
    }
}
