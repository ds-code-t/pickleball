//package io.cucumber.core.gherkin.messages;
//
//
//import io.cucumber.core.gherkin.Step;
//import io.cucumber.messages.types.PickleStep;
//import io.cucumber.messages.types.PickleStepArgument;
//
//import java.io.ByteArrayInputStream;
//import java.io.InputStream;
//import java.net.URI;
//import java.nio.charset.StandardCharsets;
//import java.util.Optional;
//import java.util.UUID;
//
//import static io.cucumber.core.runner.GlobalState.getGherkinDialect;
//import static tools.dscode.common.util.Reflect.getProperty;
//
///**
// * Minimal factory that builds a GherkinMessagesPickle containing a single step.
// * If {@code argument} is non-null, it is treated as a DocString body.
// */
//public final class SingleStepPickleFactory {
//
//    private static final URI DEFAULT_URI = URI.create("memory:/single.feature");
//
//    private SingleStepPickleFactory() {
//    }
//
//
//    public static GherkinMessagesStep createGherkinMessagesStep(String stepText, String argument) {
//        GherkinMessagesPickle gherkinMessagesPickle = createGherkinMessagesPickle(stepText, argument);
//        return (GherkinMessagesStep) gherkinMessagesPickle.getSteps().getFirst();
//    }
//
//
//    public static GherkinMessagesPickle createGherkinMessagesPickle(String stepText, String argument) {
//        System.out.println("@@createGherkinMessagesPickle1: " + stepText);
//        StringBuilder featureSrc = new StringBuilder()
//                .append("Feature: Virtual Feature\n")
//                .append("  Scenario: Virtual Scenario\n")
//                .append("    ").append(stepText).append("\n");
//
//        if (argument != null && !argument.isBlank()) {
//            featureSrc.append("      \"\"\"\n")
//                    .append(argument).append("\n")
//                    .append("      \"\"\"\n");
//        }
//
//        byte[] bytes = featureSrc.toString().getBytes(StandardCharsets.UTF_8);
//        try (InputStream in = new ByteArrayInputStream(bytes)) {
//            GherkinMessagesFeatureParser parser = new GherkinMessagesFeatureParser();
//            Optional<io.cucumber.core.gherkin.Feature> parsed =
//                    parser.parse(DEFAULT_URI, in, UUID::randomUUID);
//
//            GherkinMessagesFeature feature =
//                    (GherkinMessagesFeature) parsed.orElseThrow(
//                            () -> new IllegalStateException("No feature parsed from generated source"));
//
//            System.out.println("@@createGherkinMessagesPickle2: @@feature::: " + feature.getSource());
//
//            return (GherkinMessagesPickle) feature.getPickles()
//                    .stream()
//                    .findFirst()
//                    .orElseThrow(() -> new IllegalStateException("No pickles found"));
//        } catch (Exception e) {
//            throw new IllegalStateException("Error creating pickle", e);
//        }
//    }
//
//
//    public static String getGherkinStepText(io.cucumber.core.gherkin.Step gherkinMessagesStep) {
//        return gherkinMessagesStep.getKeyword() + gherkinMessagesStep.getText();
//    }
//
//    public static String getGherkinArgumentText(io.cucumber.core.gherkin.Step gherkinMessagesStep) {
//        PickleStepArgument pickleStepArgument = ((PickleStep) getProperty(gherkinMessagesStep, "pickleStep")).getArgument().orElse(null);
//        return toGherkin(pickleStepArgument);
//    }
//
//    public static String toGherkin(io.cucumber.messages.types.PickleStepArgument arg) {
//        if (arg == null) return "";
//
//        var dsOpt = arg.getDocString();
//        if (dsOpt.isPresent()) {
//            var ds = dsOpt.get();
//            String content = ds.getContent().replace("\r\n", "\n").replace("\r", "\n");
//            boolean hasTriple = content.lines().anyMatch(l -> l.equals("\"\"\""));
//            boolean hasTicks = content.lines().anyMatch(l -> l.equals("```"));
//            String fence = !hasTriple ? "\"\"\"" : (!hasTicks ? "```" : null);
//            if (fence == null) {
//                throw new IllegalArgumentException("DocString contains both \"\"\" and ``` lines; cannot render safely.");
//            }
//            String media = ds.getMediaType().filter(s -> !s.isBlank()).map(s -> " " + s.trim()).orElse("");
//            String body = content.lines().map(l -> "      " + l).collect(java.util.stream.Collectors.joining("\n"));
//            return "      " + fence + media + "\n" + body + "\n      " + fence;
//        }
//
//        var tableOpt = arg.getDataTable();
//        if (tableOpt.isPresent()) {
//            var rows = tableOpt.get().getRows();
//            if (rows.isEmpty()) throw new IllegalArgumentException("DataTable has zero rows; no legal Gherkin form.");
//            return rows.stream()
//                    .map(r -> "      | " + r.getCells().stream()
//                            .map(c -> {
//                                String v = c.getValue();
//                                return v == null ? "" : v.replace("\\", "\\\\").replace("|", "\\|");
//                            })
//                            .collect(java.util.stream.Collectors.joining(" | ")) + " |")
//                    .collect(java.util.stream.Collectors.joining("\n"));
//        }
//
//        return "";
//    }
//
//
//
//
//}
