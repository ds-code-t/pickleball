package tools.dscode.workbench.player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** One navigable target parsed from a Gherkin step or RUN table row. */
public record GherkinReference(
        Kind kind,
        String selector,
        String runType,
        String displayLabel,
        List<String> invocationValues
) {
    public enum Kind {
        SCENARIO,
        COMPONENT,
        SERVICE_CALL,
        DATA_FILE,
        UNKNOWN
    }

    public GherkinReference {
        Objects.requireNonNull(kind, "kind");
        selector = selector == null ? "" : selector;
        runType = runType == null ? "" : runType;
        displayLabel = displayLabel == null || displayLabel.isBlank() ? selector : displayLabel;
        invocationValues = List.copyOf(invocationValues == null ? List.of() : invocationValues);
    }

    public static List<GherkinReference> parse(String stepText, List<String> followingLines) {
        String text = stepText == null ? "" : stepText.strip();
        List<String> table = tableRows(followingLines);
        if (text.isEmpty()) return List.of();

        Optional<GherkinReference> data = dataFile(text);
        if (data.isPresent()) return List.of(data.get());

        Kind inlineKind = inlineKind(text);
        String inlineSelector = inlineSelector(text);
        if (table.isEmpty()) {
            if (inlineKind == Kind.UNKNOWN && inlineSelector.isBlank()) return List.of();
            Kind kind = inlineKind == Kind.UNKNOWN ? Kind.SCENARIO : inlineKind;
            return List.of(new GherkinReference(kind, inlineSelector, kind.name(), inlineSelector, List.of()));
        }

        List<String> header = GherkinPlayPlan.tableCells(table.getFirst());
        List<GherkinReference> refs = new ArrayList<>();
        for (int i = 1; i < table.size(); i++) {
            List<String> cells = GherkinPlayPlan.tableCells(table.get(i));
            String runType = cell(header, cells, "RunType");
            Kind kind = kindFromRunType(runType, inlineKind);
            String selector = firstNonBlank(
                    cell(header, cells, "Run Tags"),
                    cell(header, cells, "pkb_name"),
                    cell(header, cells, "RunKey"),
                    inlineSelector
            );
            List<String> values = new ArrayList<>();
            for (int c = 0; c < header.size() && c < cells.size(); c++) {
                if (!cells.get(c).isBlank()) {
                    values.add(header.get(c) + "=" + cells.get(c));
                }
            }
            if (!selector.isBlank() || kind != Kind.UNKNOWN) {
                refs.add(new GherkinReference(kind, selector, runType, selector, values));
            }
        }
        return refs;
    }

    public static Optional<GherkinReference> dataFile(String text) {
        int index = text.indexOf("data:/");
        if (index < 0) return Optional.empty();
        int start = index + "data:/".length();
        int end = start;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end)) && text.charAt(end) != '>') {
            end++;
        }
        String path = text.substring(start, end).strip();
        if (path.isBlank()) return Optional.empty();
        return Optional.of(new GherkinReference(Kind.DATA_FILE, path, "DATA", path, List.of()));
    }

    private static Kind inlineKind(String text) {
        String upper = text.toUpperCase(Locale.ROOT);
        if (upper.contains("COMPONENT SCENARIO") || upper.contains("COMPONENT:")) return Kind.COMPONENT;
        if (upper.contains("SERVICE CALL") || upper.contains("CALL:")) return Kind.SERVICE_CALL;
        if (upper.contains("RUN SCENARIO") || upper.contains("SCENARIO:")) return Kind.SCENARIO;
        if (upper.contains("RUN")) return Kind.SCENARIO;
        return Kind.UNKNOWN;
    }

    private static Kind kindFromRunType(String runType, Kind inline) {
        String upper = runType.toUpperCase(Locale.ROOT);
        if (upper.contains("COMPONENT")) return Kind.COMPONENT;
        if (upper.contains("SERVICE")) return Kind.SERVICE_CALL;
        if (upper.contains("SCENARIO")) return Kind.SCENARIO;
        return inline == Kind.UNKNOWN ? Kind.SCENARIO : inline;
    }

    private static String inlineSelector(String text) {
        int colon = text.lastIndexOf(':');
        if (colon >= 0 && colon < text.length() - 1) {
            String after = text.substring(colon + 1).strip();
            if (!after.isBlank() && !after.startsWith("|")) return after;
        }
        return "";
    }

    private static List<String> tableRows(List<String> following) {
        List<String> rows = new ArrayList<>();
        if (following == null) return rows;
        for (String line : following) {
            if (line == null) continue;
            String trimmed = line.strip();
            if (trimmed.isBlank()) {
                if (rows.isEmpty()) continue;
                break;
            }
            if (!trimmed.startsWith("|")) break;
            rows.add(trimmed);
        }
        return rows;
    }

    private static String cell(List<String> header, List<String> cells, String name) {
        for (int i = 0; i < header.size(); i++) {
            if (header.get(i).equalsIgnoreCase(name) && i < cells.size()) {
                return cells.get(i);
            }
        }
        return "";
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.strip();
        }
        return "";
    }
}