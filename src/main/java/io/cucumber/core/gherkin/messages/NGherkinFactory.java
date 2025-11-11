package io.cucumber.core.gherkin.messages;

import io.cucumber.core.gherkin.Feature;
import io.cucumber.gherkin.GherkinDialect;
import io.cucumber.messages.types.PickleStep;
import io.cucumber.messages.types.PickleStepArgument;
import io.cucumber.plugin.event.Location;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
                .append("    ").append(keyword).append(stepText).append("\n");

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
            } else {
                // Emit as DocString
                featureSrc.append("      \"\"\"\n")
                        .append(argument.replace("\r\n", "\n").replace("\r", "\n")).append("\n")
                        .append("      \"\"\"\n");
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



    // step to text


    public static String getGherkinArgumentText(io.cucumber.core.gherkin.Step gherkinMessagesStep) {
        PickleStepArgument pickleStepArgument = ((PickleStep) getProperty(gherkinMessagesStep, "pickleStep")).getArgument().orElse(null);
        return argumentToGherkinText(pickleStepArgument);
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
                                return v == null ? "" : v.replace("\\", "\\\\").replace("|", "\\|");
                            })
                            .collect(java.util.stream.Collectors.joining(" | ")) + " |")
                    .collect(java.util.stream.Collectors.joining("\n"));
        }

        return "";
    }














}
