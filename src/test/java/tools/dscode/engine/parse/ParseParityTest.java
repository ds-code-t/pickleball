package tools.dscode.engine.parse;

import io.cucumber.gherkin.GherkinParser;
import io.cucumber.messages.types.Envelope;
import io.cucumber.messages.types.Pickle;
import io.cucumber.messages.types.PickleDocString;
import io.cucumber.messages.types.PickleStep;
import io.cucumber.messages.types.PickleStepArgument;
import io.cucumber.messages.types.PickleTable;
import io.cucumber.messages.types.PickleTableCell;
import io.cucumber.messages.types.PickleTableRow;
import io.cucumber.messages.types.PickleTag;
import org.junit.jupiter.api.Test;
import tools.dscode.engine.model.EngineFeature;
import tools.dscode.engine.model.EngineScenario;
import tools.dscode.engine.model.EngineStep;
import tools.dscode.engine.model.StepArgument;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Native expand versus the aspect-augmented Cucumber pickle compile.
 * The parser stays {@link GherkinParser}.
 */
class ParseParityTest {
    private static final String EMPTY_ROW = """
            Feature: Empty cells
              Scenario Outline: name <item>
                Given save "<item>" as "seen"
                  | <item> |
                  |        |
                Examples:
                  | item | Scenario Tags |
                  |      | @blank        |
                  | Ada  |               |
            """;

    @Test
    void everyRepoFeatureMatchesAugmentedPickles() throws IOException {
        List<String> problems = new ArrayList<>();
        for (Path file : featureFiles()) {
            problems.addAll(compare(file.toString(), Files.readString(file)));
        }
        problems.addAll(compare("memory:empty-cells.feature", EMPTY_ROW));
        assertTrue(problems.isEmpty(), problems.size() + "\n" + String.join("\n", problems.subList(0, Math.min(problems.size(), 30))));
    }

    private static List<Path> featureFiles() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        List<Path> files = new ArrayList<>();
        for (String start : List.of("src", "maven-consumer-project")) {
            Path dir = root.resolve(start);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(path -> path.toString().endsWith(".feature"))
                        .filter(path -> !path.toString().contains("/build/"))
                        .sorted()
                        .forEach(files::add);
            }
        }
        assertTrue(files.size() > 20, "features=" + files.size());
        return files;
    }

    private static List<String> compare(String uri, String text) {
        List<String> problems = new ArrayList<>();
        List<Pickle> pickles;
        EngineFeature expanded;
        try {
            pickles = pickles(uri, text);
        } catch (RuntimeException failure) {
            problems.add(uri + " pickle compile failed " + failure.getMessage());
            return problems;
        }
        try {
            expanded = GherkinEngineParser.expand(GherkinEngineParser.parse(uri, text));
        } catch (RuntimeException failure) {
            problems.add(uri + " native parse failed " + failure.getMessage());
            return problems;
        }
        if (pickles.size() != expanded.scenarios().size()) {
            problems.add(uri + " count pickle=" + pickles.size() + " native=" + expanded.scenarios().size()
                    + " names=" + expanded.scenarios().stream().map(EngineScenario::name).toList());
            return problems;
        }
        for (int index = 0; index < pickles.size(); index++) {
            String prefix = uri + "#" + index;
            problems.addAll(diff(prefix, pickles.get(index), expanded.scenarios().get(index)));
        }
        return problems;
    }

    private static List<String> diff(String prefix, Pickle pickle, EngineScenario scenario) {
        List<String> problems = new ArrayList<>();
        if (!pickle.getName().equals(scenario.name())) {
            problems.add(prefix + " name pickle=[" + pickle.getName() + "] native=[" + scenario.name() + "]");
        }
        List<String> pickleTags = pickle.getTags().stream().map(PickleTag::getName).toList();
        if (!pickleTags.equals(scenario.tags())) {
            problems.add(prefix + " tags pickle=" + pickleTags + " native=" + scenario.tags());
        }
        if (!cells(headerRow(pickle)).equals(cellsOf(scenario.metadata().get("exampleHeaders")))) {
            problems.add(prefix + " headers pickle=" + show(headerRow(pickle)) + " native=" + scenario.metadata().get("exampleHeaders"));
        }
        if (!cells(valueRow(pickle)).equals(cellsOf(scenario.metadata().get("exampleValues")))) {
            problems.add(prefix + " values pickle=" + show(valueRow(pickle)) + " native=" + scenario.metadata().get("exampleValues"));
        }
        if (pickle.getSteps().size() != scenario.steps().size()) {
            problems.add(prefix + " steps pickle=" + pickle.getSteps().size() + " native=" + scenario.steps().size());
            return problems;
        }
        for (int index = 0; index < pickle.getSteps().size(); index++) {
            problems.addAll(diffStep(prefix + " step " + index, pickle.getSteps().get(index), scenario.steps().get(index)));
        }
        return problems;
    }

    private static List<String> diffStep(String prefix, PickleStep pickle, EngineStep step) {
        List<String> problems = new ArrayList<>();
        if (!pickle.getText().equals(step.text())) {
            problems.add(prefix + " text pickle=[" + pickle.getText() + "] native=[" + step.text() + "]");
        }
        String meta = textOf(pickle, "getMetaText");
        String nativeMeta = step.metadata().getOrDefault("metaText", "");
        if (!meta.equals(nativeMeta) && (meta.length() > 0 || step.text().contains("\u207A-F"))) {
            problems.add(prefix + " meta pickle=[" + meta + "] native=[" + nativeMeta + "] text=[" + step.text() + "]");
        }
        if (flag(pickle, "isFromBackground") != step.fromBackground()) {
            problems.add(prefix + " background pickle=" + flag(pickle, "isFromBackground") + " native=" + step.fromBackground() + " text=[" + pickle.getText() + "]");
        }
        String pickleArg = argument(pickle.getArgument().orElse(null));
        String nativeArg = argument(step.argument());
        if (!pickleArg.equals(nativeArg)) {
            problems.add(prefix + " arg pickle=[" + pickleArg + "] native=[" + nativeArg + "]");
        }
        return problems;
    }

    private static String argument(PickleStepArgument argument) {
        if (argument == null) return "";
        if (argument.getDataTable().isPresent()) return table(argument.getDataTable().get());
        if (argument.getDocString().isPresent()) {
            PickleDocString doc = argument.getDocString().get();
            return "doc:" + doc.getMediaType().orElse("") + ":" + doc.getContent();
        }
        return "";
    }

    private static String argument(StepArgument argument) {
        if (argument instanceof StepArgument.TableArg table) return table(table.table().rows());
        if (argument instanceof StepArgument.DocArg doc) {
            return "doc:" + doc.doc().mediaType() + ":" + doc.doc().content();
        }
        return "";
    }

    private static String table(PickleTable table) {
        List<List<String>> rows = new ArrayList<>();
        for (PickleTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (PickleTableCell cell : row.getCells()) cells.add(cell.getValue());
            rows.add(cells);
        }
        return table(rows);
    }

    private static String table(List<List<String>> rows) {
        StringBuilder out = new StringBuilder("table:");
        for (List<String> row : rows) {
            out.append('[');
            for (int index = 0; index < row.size(); index++) {
                if (index > 0) out.append('|');
                String cell = row.get(index);
                out.append(cell == null ? "<NULL>" : cell);
            }
            out.append(']');
        }
        return out.toString();
    }

    private static List<String> cells(List<String> values) {
        if (values == null) return List.of();
        List<String> cells = new ArrayList<>();
        for (String value : values) cells.add(value == null ? "<NULL>" : value);
        return cells;
    }

    private static List<String> cellsOf(String joined) {
        if (joined == null) return List.of();
        return List.of(joined.split("\t", -1));
    }

    private static List<String> headerRow(Pickle pickle) {
        return stringList(pickle, "getHeaderRow");
    }

    private static List<String> valueRow(Pickle pickle) {
        return stringList(pickle, "getValueRow");
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object target, String name) {
        Object value = invoke(target, name);
        if (value == null) return List.of();
        return (List<String>) value;
    }

    private static String textOf(Object target, String name) {
        Object value = invoke(target, name);
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean flag(Object target, String name) {
        Object value = invoke(target, name);
        return value instanceof Boolean bool && bool;
    }

    private static Object invoke(Object target, String name) {
        try {
            Method method = target.getClass().getMethod(name);
            return method.invoke(target);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(target.getClass().getName() + "." + name, failure);
        }
    }

    private static String show(List<String> values) {
        return cells(values).toString();
    }

    private static List<Pickle> pickles(String uri, String text) {
        GherkinParser parser = GherkinParser.builder()
                .includeSource(false)
                .includePickles(true)
                .includeGherkinDocument(false)
                .build();
        List<Pickle> pickles = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (Envelope envelope : parser.parse(uri, text.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toList()) {
            envelope.getParseError().ifPresent(error -> errors.add(error.getMessage()));
            envelope.getPickle().ifPresent(pickles::add);
        }
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("\n", errors));
        return pickles;
    }
}
