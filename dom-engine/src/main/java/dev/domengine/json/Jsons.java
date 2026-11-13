package dev.domengine.json;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class Jsons {
    public static final ObjectMapper MAPPER = new ObjectMapper();
    /**
     * Same layout as the Rust CLI ({@code serde_json::to_string_pretty}):
     * two-space indent, {@code "key": value}, one array element per line,
     * {@code []} and {@code {}} for empty containers, {@code \n} line ends.
     */
    private static final DefaultPrettyPrinter PRETTY;

    static {
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                .withSeparators(Separators.createDefaultInstance()
                        .withObjectFieldValueSpacing(Separators.Spacing.AFTER)
                        .withObjectEmptySeparator("")
                        .withArrayEmptySeparator(""));
        printer.indentObjectsWith(indenter);
        printer.indentArraysWith(indenter);
        PRETTY = printer;
    }

    private Jsons() {
    }

    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    public static String pretty(JsonNode node) {
        try {
            return MAPPER.writer(PRETTY).writeValueAsString(node) + "\n";
        } catch (Exception failure) {
            return String.valueOf(node) + "\n";
        }
    }

    public static String compact(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception failure) {
            return String.valueOf(node);
        }
    }

    public static ObjectNode error(String code, String message) {
        ObjectNode node = object();
        node.put("ok", false);
        node.put("error", message == null ? "" : message);
        node.put("code", code == null ? "failed" : code);
        return node;
    }
}
