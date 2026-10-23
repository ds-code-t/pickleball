package io.cucumber.core.gherkin.messages;

import io.cucumber.core.gherkin.Feature;
import io.cucumber.gherkin.GherkinDialect;
import io.cucumber.messages.types.InlinePickleArgument;
import io.cucumber.messages.types.PickleStep;
import io.cucumber.messages.types.PickleStepArgument;
import io.cucumber.messages.types.PickleTable;
import io.cucumber.messages.types.PickleTableCell;
import io.cucumber.messages.types.PickleTableRow;
import io.cucumber.plugin.event.Location;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static tools.dscode.common.util.Reflect.getProperty;

public class NGherkinFactory {

    private static final URI DEFAULT_URI = URI.create("memory:/single.feature");

    public static GherkinMessagesStep createGherkinMessagesStep(PickleStep pickleStep,
                                                                GherkinDialect dialect,
                                                                String previousGwtKeyWord,
                                                                Location location,
                                                                String keyword) {
        return new GherkinMessagesStep(pickleStep, dialect, previousGwtKeyWord, location, keyword);
    }


    // text to Step


    public static GherkinMessagesPickle createGherkinMessagesPickle(String keyword, String stepText, String argument) {
        StringBuilder featureSrc = new StringBuilder()
                .append("Feature: Virtual Feature\n")
                .append("  Scenario: Virtual Scenario\n")
                .append("    ").append(escapeStepLineBreaks(keyword)).append(escapeStepLineBreaks(stepText)).append("\n");
// --- decide how to render the argument ---
        if (argument != null && !argument.isBlank()) {
            // Heuristic: every non-blank line starts and ends with a pipe -> treat as DataTable
            var lines = argument.lines()
                    .map(String::stripTrailing)
                    .filter(l -> !l.isBlank())
                    .toList();
            boolean looksLikeTable = !lines.isEmpty()
                    && lines.stream().allMatch(l -> {
                String t = l.strip();
                return t.startsWith("|") && t.endsWith("|");
            });
            if (looksLikeTable) {
                // Emit a real Gherkin DataTable (no docstring fence)
                for (String l : lines) {
                    featureSrc.append("      ").append(l.strip()).append("\n");
                }
            } else if (alreadyFencedDocString(lines) || !containsLineBreak(argument)) {
                featureSrc.append(argument.replace("\r\n", "\n").replace("\r", "\n"));
                if (!argument.endsWith("\n") && !argument.endsWith("\r")) featureSrc.append('\n');
            } else {
                featureSrc.append(fenceDocString(argument));
            }
        }

        byte[] bytes = featureSrc.toString().getBytes(StandardCharsets.UTF_8);
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            GherkinMessagesFeatureParser parser = new GherkinMessagesFeatureParser();
            Optional<Feature> parsed =
                    parser.parse(DEFAULT_URI, in, UUID::randomUUID);

            GherkinMessagesFeature feature =
                    (GherkinMessagesFeature) parsed.orElseThrow(
                            () -> new IllegalStateException("No feature parsed from generated source"));
            return (GherkinMessagesPickle) feature.getPickles()
                    .stream()
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No pickles found"));
        } catch (Exception e) {
            throw new IllegalStateException("Error creating pickle", e);
        }
    }

    private static boolean containsLineBreak(String argument) {
        return argument.indexOf('\n') >= 0 || argument.indexOf('\r') >= 0;
    }

    /**
     * Private-use markers for a newline that must survive the temporary
     * {@code memory:/single.feature} parse as one Gherkin step line.
     * Restored in {@code PickleStepMetaTextAspect} before the step text is stored.
     */
    private static final char LINE_BREAK_SENTINEL = '\uE000';
    private static final char LINE_BREAK_ESCAPED_SENTINEL = '\uE001';
    private static final char LINE_BREAK_CRLF = '\uE002';
    private static final char LINE_BREAK_LF = '\uE003';
    private static final char LINE_BREAK_CR = '\uE004';

    /**
     * A newline inside resolved step text is not Gherkin. Escape it so the
     * temporary feature stays one step line. A value with no line break and no
     * sentinel is returned unchanged.
     */
    public static String escapeStepLineBreaks(String stepText) {
        if (stepText == null || !needsStepLineBreakEscape(stepText)) {
            return stepText;
        }
        StringBuilder escaped = new StringBuilder(stepText.length() + 8);
        for (int i = 0; i < stepText.length(); i++) {
            char current = stepText.charAt(i);
            if (current == '\r' && i + 1 < stepText.length() && stepText.charAt(i + 1) == '\n') {
                escaped.append(LINE_BREAK_SENTINEL).append(LINE_BREAK_CRLF);
                i++;
            } else if (current == '\n') {
                escaped.append(LINE_BREAK_SENTINEL).append(LINE_BREAK_LF);
            } else if (current == '\r') {
                escaped.append(LINE_BREAK_SENTINEL).append(LINE_BREAK_CR);
            } else if (current == LINE_BREAK_SENTINEL) {
                escaped.append(LINE_BREAK_SENTINEL).append(LINE_BREAK_ESCAPED_SENTINEL);
            } else {
                escaped.append(current);
            }
        }
        return escaped.toString();
    }

    /** Inverse of {@link #escapeStepLineBreaks(String)}. No sentinel means the same instance. */
    public static String restoreStepLineBreaks(String stepText) {
        if (stepText == null || stepText.indexOf(LINE_BREAK_SENTINEL) < 0) {
            return stepText;
        }
        StringBuilder restored = new StringBuilder(stepText.length());
        for (int i = 0; i < stepText.length(); i++) {
            char current = stepText.charAt(i);
            if (current == LINE_BREAK_SENTINEL && i + 1 < stepText.length()) {
                char code = stepText.charAt(++i);
                switch (code) {
                    case LINE_BREAK_CRLF -> restored.append("\r\n");
                    case LINE_BREAK_LF -> restored.append('\n');
                    case LINE_BREAK_CR -> restored.append('\r');
                    case LINE_BREAK_ESCAPED_SENTINEL -> restored.append(LINE_BREAK_SENTINEL);
                    default -> restored.append(LINE_BREAK_SENTINEL).append(code);
                }
            } else {
                restored.append(current);
            }
        }
        return restored.toString();
    }

    private static boolean needsStepLineBreakEscape(String stepText) {
        return stepText.indexOf('\n') >= 0
                || stepText.indexOf('\r') >= 0
                || stepText.indexOf(LINE_BREAK_SENTINEL) >= 0;
    }

    /** A value that is already a Gherkin docstring must not be fenced again. */
    private static boolean alreadyFencedDocString(List<String> lines) {
        if (lines.size() < 2) return false;
        String open = lines.getFirst().strip();
        String close = lines.getLast().strip();
        return (open.startsWith("\"\"\"") && "\"\"\"".equals(close))
                || (open.startsWith("```") && "```".equals(close));
    }

    private static String fenceDocString(String argument) {
        String content = argument.replace("\r\n", "\n").replace('\r', '\n');
        if (content.endsWith("\n")) content = content.substring(0, content.length() - 1);
        boolean hasTriple = content.lines().anyMatch(line -> "\"\"\"".equals(line.strip()));
        boolean hasTicks = content.lines().anyMatch(line -> "```".equals(line.strip()));
        String fence = !hasTriple ? "\"\"\"" : (!hasTicks ? "```" : null);
        if (fence == null) {
            throw new IllegalArgumentException("DocString contains both \"\"\" and ``` lines; cannot render safely.");
        }
        StringBuilder body = new StringBuilder();
        body.append("      ").append(fence).append('\n');
        for (String line : content.split("\n", -1)) {
            body.append("      ").append(line).append('\n');
        }
        body.append("      ").append(fence).append('\n');
        return body.toString();
    }


    // step to text


    public static String getGherkinArgumentText(io.cucumber.core.gherkin.Step gherkinMessagesStep) {
        PickleStep pickleStep = (PickleStep) getProperty(gherkinMessagesStep, "pickleStep");
        String inlineDataTableArgument = getInlineDataTableArgumentText(pickleStep);
        if (inlineDataTableArgument != null) {
            return inlineDataTableArgument;
        }

        PickleStepArgument pickleStepArgument = pickleStep.getArgument().orElse(null);
        return argumentToGherkinText(pickleStepArgument);
    }

    static String getInlineDataTableArgumentText(PickleStep pickleStep) {
        if (pickleStep == null || !pickleStep.hasInlineArgument()) {
            return null;
        }

        return gherkinDataTableForInlineType(
                pickleStep.getInlineArgumentType(),
                pickleStep.getInlineArgumentText());
    }

    /**
     * {@code DT} is only the table type name. Any other uppercase type stays
     * readable as inline argument text and does not become a data table.
     */
    public static String gherkinDataTableForInlineType(String type, String argumentText) {
        if (!"DT".equals(type) || argumentText == null || argumentText.isBlank()) {
            return null;
        }
        return inlineDataTableArgumentToGherkinText(argumentText);
    }

    /** The step's real data table or doc string. Ignores an inline marker. */
    public static String realGherkinArgument(io.cucumber.core.gherkin.Step step) {
        if (step == null) {
            return "";
        }
        PickleStep pickleStep = (PickleStep) getProperty(step, "pickleStep");
        if (pickleStep == null) {
            return "";
        }
        return argumentToGherkinText(pickleStep.getArgument().orElse(null));
    }

    static String inlineDataTableArgumentToGherkinText(String inlineArgumentText) {
        PickleTable table = new PickleTable(inlineDataTableRows(inlineArgumentText).stream()
                .map(row -> new PickleTableRow(row.stream()
                        .map(PickleTableCell::new)
                        .toList()))
                .toList());
        return argumentToGherkinText(PickleStepArgument.of(table));
    }

    static List<List<String>> inlineDataTableRows(String inlineText) {
        return InlinePickleArgument.inlineDataTableRows(inlineText);
    }

    public static String argumentToGherkinText(io.cucumber.messages.types.PickleStepArgument arg) {
        if (arg == null) return "";

        var dsOpt = arg.getDocString();
        if (dsOpt.isPresent()) {
            var ds = dsOpt.get();
            String content = ds.getContent().replace("\r\n", "\n").replace("\r", "\n");
            boolean hasTriple = content.lines().anyMatch(l -> l.equals("\"\"\""));
            boolean hasTicks = content.lines().anyMatch(l -> l.equals("```"));
            String fence = !hasTriple ? "\"\"\"" : (!hasTicks ? "```" : null);
            if (fence == null) {
                throw new IllegalArgumentException("DocString contains both \"\"\" and ``` lines; cannot render safely.");
            }
            String media = ds.getMediaType().filter(s -> !s.isBlank()).map(s -> " " + s.trim()).orElse("");
            String body = content.lines().map(l -> "      " + l).collect(java.util.stream.Collectors.joining("\n"));
            return "      " + fence + media + "\n" + body + "\n      " + fence;
        }

        var tableOpt = arg.getDataTable();
        if (tableOpt.isPresent()) {
            var rows = tableOpt.get().getRows();
            if (rows.isEmpty()) throw new IllegalArgumentException("DataTable has zero rows; no legal Gherkin form.");
            return rows.stream()
                    .map(r -> "      | " + r.getCells().stream()
                            .map(c -> {
                                String v = c.getValue();
                                return v == null ? "" : v
                                        .replace("\r\n", "\n")
                                        .replace("\r", "\n")
                                        .replace("\\", "\\\\")
                                        .replace("|", "\\|")
                                        .replace("\n", "\\n");
                            })
                            .collect(java.util.stream.Collectors.joining(" | ")) + " |")
                    .collect(java.util.stream.Collectors.joining("\n"));
        }

        return "";
    }


}
