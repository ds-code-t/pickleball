package tools.ds.modkit.util;

import io.cucumber.messages.types.PickleDocString;
import io.cucumber.messages.types.PickleStepArgument;
import io.cucumber.messages.types.PickleTable;
import io.cucumber.messages.types.PickleTableCell;
import io.cucumber.messages.types.PickleTableRow;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.function.Function;
import java.util.function.UnaryOperator;

//public class MyTransformer {
//    public String myExternalModify(String s) {
//        return ">> " + s + " <<";
//    }
//}
//
//MyTransformer transformer = new MyTransformer();
//UnaryOperator<String> external = transformer::myExternalModify;


/**
 * Utilities to transform PickleStepArgument by text.
 * <p>
 * Supports:
 * - PickleDocString: uses its content verbatim (mediaType preserved on rebuild)
 * - PickleTable: serialized to a Gherkin-like pipe table with escaping:
 * escape: "\" -> "\\", "|" -> "\|", "\n" -> "\n"
 */
public final class PickleStepArgUtils {

    private PickleStepArgUtils() {
    }

    /**
     * Transform a PickleStepArgument by converting it to text, applying {@code textTransformer},
     * and converting the result back into the same kind of PickleStepArgument.
     */
    public static PickleStepArgument transformPickleArgument(
            PickleStepArgument arg,
            Function<String, String> textTransformer
    ) {
        Objects.requireNonNull(arg, "arg");
        Objects.requireNonNull(textTransformer, "textTransformer");

        final Kind kind = kindOf(arg);
        final String originalText = toText(arg);
        final String modifiedText = textTransformer.apply(originalText);
        return fromTextLike(modifiedText, arg, kind);
    }

    /**
     * Overload for UnaryOperator.
     */
    public static PickleStepArgument transformPickleArgument(
            PickleStepArgument arg,
            UnaryOperator<String> textTransformer
    ) {
        if (arg == null) return null;
        return transformPickleArgument(arg, (Function<String, String>) textTransformer);
    }

    /**
     * Convert a PickleStepArgument to plain text.
     */
    public static String toText(PickleStepArgument arg) {
        Objects.requireNonNull(arg, "arg");
        Optional<PickleDocString> ds = arg.getDocString();
        if (ds.isPresent()) {
            return ds.get().getContent();
        }
        Optional<PickleTable> table = arg.getDataTable();
        if (table.isPresent()) {
            return serializeTable(table.get());
        }
        // Per schema it's optional; if neither present, return empty.
        return "";
    }

    /**
     * Rebuild a PickleStepArgument from text, matching the type of {@code like}.
     * For DocString: preserves mediaType from {@code like}.
     * For Table: parses pipe-delimited rows into cells.
     */
    public static PickleStepArgument fromTextLike(String text, PickleStepArgument like) {
        return fromTextLike(text, like, kindOf(like));
    }

    // ———————————————————
    // Internals
    // ———————————————————

    private enum Kind {DOC_STRING, TABLE, EMPTY}

    private static Kind kindOf(PickleStepArgument arg) {
        if (arg.getDocString().isPresent()) return Kind.DOC_STRING;
        if (arg.getDataTable().isPresent()) return Kind.TABLE;
        return Kind.EMPTY;
    }

    public static PickleStepArgument createDataTableArg(String text){
        PickleTable rebuilt = rebuildTable(text == null ? "" : text);
        return PickleStepArgument.of(rebuilt);
    }

    public static PickleStepArgument createDocStringArg(String text, String mediaType){
        PickleDocString ds = new PickleDocString(mediaType, text == null ? "" : text);
        return PickleStepArgument.of(ds);
    }


    private static PickleStepArgument fromTextLike(String text, PickleStepArgument like, Kind kind) {
        switch (kind) {
            case DOC_STRING -> {
                // Preserve mediaType from original doc string (if any).
                String mediaType = like.getDocString().flatMap(PickleDocString::getMediaType).orElse(null);
                PickleDocString ds = new PickleDocString(mediaType, text == null ? "" : text);
                return PickleStepArgument.of(ds);
            }
            case TABLE -> {
                PickleTable rebuilt = rebuildTable(text == null ? "" : text);
                return PickleStepArgument.of(rebuilt);
            }
            case EMPTY -> {
                // If no original kind, default to a doc string for safety.
                PickleDocString ds = new PickleDocString(null, text == null ? "" : text);
                return PickleStepArgument.of(ds);
            }
            default -> throw new IllegalStateException("Unexpected kind: " + kind);
        }
    }

    // ------- Table Serialization / Parsing -------

    private static String serializeTable(PickleTable table) {
        StringJoiner sj = new StringJoiner(System.lineSeparator());
        for (PickleTableRow row : table.getRows()) {
            StringJoiner line = new StringJoiner("|", "|", "|");
            for (PickleTableCell cell : row.getCells()) {
                line.add(escapeCell(cell.getValue()));
            }
            sj.add(line.toString());
        }
        return sj.toString();
    }

    private static PickleTable rebuildTable(String text) {
        List<PickleTableRow> rows = new ArrayList<>();
        if (text.isEmpty()) {
            return new PickleTable(rows);
        }

        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (String rawLine : lines) {
            // Accept both "|a|b|" and "a|b" styles; trim outer pipes if present
            String line = trimOptionalOuterPipes(rawLine);
            List<String> cells = parseCells(line);
            List<PickleTableCell> cellObjs = new ArrayList<>(cells.size());
            for (String c : cells) {
                cellObjs.add(new PickleTableCell(unescapeCell(c)));
            }
            rows.add(new PickleTableRow(cellObjs));
        }

        return new PickleTable(rows);
    }

    /**
     * Parse a single pipe-delimited line honoring backslash escapes.
     */
    private static List<String> parseCells(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean escaping = false;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (escaping) {
                // Support \|, \\, \n
                switch (ch) {
                    case '|':
                        cur.append('|');
                        break;
                    case '\\':
                        cur.append('\\');
                        break;
                    case 'n':
                        cur.append('\n');
                        break;
                    default:
                        cur.append(ch);
                        break; // unknown escape -> literal char
                }
                escaping = false;
            } else if (ch == '\\') {
                escaping = true;
            } else if (ch == '|') {
                cells.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        // add last cell
        cells.add(cur.toString());
        return cells;
    }

    private static String trimOptionalOuterPipes(String s) {
        String t = s;
        if (!t.isEmpty() && t.charAt(0) == '|') t = t.substring(1);
        if (!t.isEmpty() && t.charAt(t.length() - 1) == '|') t = t.substring(0, t.length() - 1);
        return t;
    }

    private static String escapeCell(String v) {
        String s = v == null ? "" : v;
        // escape backslash first, then pipes, then newlines
        s = s.replace("\\", "\\\\");
        s = s.replace("|", "\\|");
        s = s.replace("\n", "\\n");
        return s;
    }

    private static String unescapeCell(String v) {
        String s = v == null ? "" : v;
        StringBuilder out = new StringBuilder(s.length());
        boolean escaping = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (escaping) {
                switch (ch) {
                    case '|':
                        out.append('|');
                        break;
                    case '\\':
                        out.append('\\');
                        break;
                    case 'n':
                        out.append('\n');
                        break;
                    default:
                        out.append(ch);
                        break;
                }
                escaping = false;
            } else if (ch == '\\') {
                escaping = true;
            } else {
                out.append(ch);
            }
        }
        if (escaping) out.append('\\'); // trailing backslash -> literal
        return out.toString();
    }
}
