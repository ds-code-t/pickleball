package tools.dscode.engine.convert;

import io.cucumber.messages.types.Background;
import io.cucumber.messages.types.Examples;
import io.cucumber.messages.types.Feature;
import io.cucumber.messages.types.FeatureChild;
import io.cucumber.messages.types.GherkinDocument;
import io.cucumber.messages.types.Location;
import io.cucumber.messages.types.Pickle;
import io.cucumber.messages.types.PickleDocString;
import io.cucumber.messages.types.PickleStep;
import io.cucumber.messages.types.PickleStepArgument;
import io.cucumber.messages.types.PickleStepType;
import io.cucumber.messages.types.PickleTable;
import io.cucumber.messages.types.PickleTableCell;
import io.cucumber.messages.types.PickleTableRow;
import io.cucumber.messages.types.PickleTag;
import io.cucumber.messages.types.Rule;
import io.cucumber.messages.types.RuleChild;
import io.cucumber.messages.types.Scenario;
import io.cucumber.messages.types.Step;
import io.cucumber.messages.types.TableCell;
import io.cucumber.messages.types.TableRow;
import io.cucumber.messages.types.Tag;
import io.cucumber.plugin.event.Status;
import tools.dscode.engine.model.DocText;
import tools.dscode.engine.model.EngineFeature;
import tools.dscode.engine.model.EngineScenario;
import tools.dscode.engine.model.EngineStep;
import tools.dscode.engine.model.ExampleTable;
import tools.dscode.engine.model.SourceLocation;
import tools.dscode.engine.model.StepArgument;
import tools.dscode.engine.model.Table;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bidirectional conversion between the Pickleball model and Cucumber types.
 * Used only at the adapter edge. Runtime code keeps the neutral types.
 */
public final class CucumberModelConverter {
    private CucumberModelConverter() {
    }

    public static tools.dscode.engine.model.Status toStatus(Status status) {
        if (status == null) return tools.dscode.engine.model.Status.UNDEFINED;
        return switch (status) {
            case PASSED -> tools.dscode.engine.model.Status.PASSED;
            case SKIPPED -> tools.dscode.engine.model.Status.SKIPPED;
            case PENDING -> tools.dscode.engine.model.Status.PENDING;
            case UNDEFINED -> tools.dscode.engine.model.Status.UNDEFINED;
            case AMBIGUOUS -> tools.dscode.engine.model.Status.AMBIGUOUS;
            case FAILED, UNUSED -> tools.dscode.engine.model.Status.FAILED;
        };
    }

    public static Status toCucumberStatus(tools.dscode.engine.model.Status status) {
        if (status == null) return Status.UNDEFINED;
        return switch (status) {
            case PASSED -> Status.PASSED;
            case SKIPPED -> Status.SKIPPED;
            case PENDING -> Status.PENDING;
            case UNDEFINED -> Status.UNDEFINED;
            case AMBIGUOUS -> Status.AMBIGUOUS;
            case FAILED -> Status.FAILED;
        };
    }

    public static Table toTable(io.cucumber.datatable.DataTable table) {
        if (table == null || table.isEmpty()) return Table.empty();
        return new Table(table.cells());
    }

    public static io.cucumber.datatable.DataTable toDataTable(Table table) {
        if (table == null || table.isEmpty()) return io.cucumber.datatable.DataTable.emptyDataTable();
        return io.cucumber.datatable.DataTable.create(copyRows(table.rows()));
    }

    public static DocText toDocText(io.cucumber.docstring.DocString doc) {
        if (doc == null) return DocText.of("");
        return new DocText(doc.getContent(), doc.getContentType());
    }

    public static io.cucumber.docstring.DocString toDocString(DocText doc) {
        if (doc == null) return io.cucumber.docstring.DocString.create("");
        if (doc.mediaType().isBlank()) return io.cucumber.docstring.DocString.create(doc.content());
        return io.cucumber.docstring.DocString.create(doc.content(), doc.mediaType());
    }

    public static Feature toFeature(EngineFeature feature) {
        EngineFeature source = feature == null
                ? EngineFeature.of("", List.of())
                : feature;
        AtomicInteger ids = new AtomicInteger();
        List<FeatureChild> children = new ArrayList<>();
        if (!source.background().isEmpty()) {
            children.add(FeatureChild.of(toBackground(source, ids)));
        }
        String openRule = null;
        List<RuleChild> ruleChildren = null;
        List<String> ruleTags = List.of();
        SourceLocation ruleLocation = SourceLocation.unknown();
        for (EngineScenario scenario : source.scenarios()) {
            String rule = scenario.metadata().get(EngineScenario.RULE_NAME);
            if (rule == null || rule.isBlank()) {
                if (ruleChildren != null) {
                    children.add(FeatureChild.of(toRule(openRule, ruleTags, ruleLocation, ruleChildren, ids)));
                    ruleChildren = null;
                    openRule = null;
                }
                children.add(FeatureChild.of(toScenario(scenario, source.location().uri(), ids)));
                continue;
            }
            if (ruleChildren == null || !rule.equals(openRule)) {
                if (ruleChildren != null) {
                    children.add(FeatureChild.of(toRule(openRule, ruleTags, ruleLocation, ruleChildren, ids)));
                }
                openRule = rule;
                ruleChildren = new ArrayList<>();
                ruleTags = tagsNamed(scenario.metadata().get("ruleTags"));
                ruleLocation = scenario.location();
            }
            ruleChildren.add(RuleChild.of(toScenario(withoutRuleTags(scenario, ruleTags), source.location().uri(), ids)));
        }
        if (ruleChildren != null) {
            children.add(FeatureChild.of(toRule(openRule, ruleTags, ruleLocation, ruleChildren, ids)));
        }
        return new Feature(
                toLocation(source.location()),
                toTags(source.tags(), source.location().uri(), ids),
                source.language(),
                "Feature",
                source.name(),
                source.description(),
                children
        );
    }

    public static GherkinDocument toDocument(EngineFeature feature) {
        EngineFeature source = feature == null ? EngineFeature.of("", List.of()) : feature;
        String uri = source.location().uri().isBlank() ? null : source.location().uri();
        return new GherkinDocument(uri, toFeature(source), List.of());
    }

    public static EngineFeature fromDocument(GherkinDocument document) {
        if (document == null || document.getFeature().isEmpty()) return EngineFeature.of("", List.of());
        return fromFeature(document.getFeature().get(), document.getUri().orElse(""));
    }

    public static EngineFeature fromFeature(Feature feature) {
        return fromFeature(feature, "");
    }

    public static EngineFeature fromFeature(Feature feature, String uri) {
        if (feature == null) return EngineFeature.of("", List.of());
        String sourceUri = uri == null ? "" : uri;
        List<EngineStep> background = new ArrayList<>();
        List<EngineScenario> scenarios = new ArrayList<>();
        for (FeatureChild child : feature.getChildren()) {
            if (child.getBackground().isPresent()) {
                for (Step step : child.getBackground().get().getSteps()) {
                    background.add(fromStep(step, sourceUri, true));
                }
            }
            child.getScenario().ifPresent(scenario -> scenarios.add(fromScenario(scenario, sourceUri, "")));
            child.getRule().ifPresent(rule -> {
                String ruleName = rule.getName() == null ? "" : rule.getName();
                String ruleTags = joinTags(rule.getTags());
                List<EngineStep> ruleBackground = new ArrayList<>();
                for (RuleChild ruleChild : rule.getChildren()) {
                    if (ruleChild.getBackground().isPresent()) {
                        for (Step step : ruleChild.getBackground().get().getSteps()) {
                            ruleBackground.add(fromStep(step, sourceUri, true));
                        }
                        continue;
                    }
                    ruleChild.getScenario().ifPresent(scenario -> {
                        EngineScenario converted = fromScenario(scenario, sourceUri, ruleName);
                        if (!ruleBackground.isEmpty()) {
                            List<EngineStep> steps = new ArrayList<>(ruleBackground);
                            steps.addAll(converted.steps());
                            converted = new EngineScenario(
                                    converted.keyword(),
                                    converted.name(),
                                    converted.description(),
                                    converted.tags(),
                                    steps,
                                    converted.examples(),
                                    converted.location(),
                                    converted.metadata()
                            );
                        }
                        if (!ruleTags.isBlank() || !ruleName.isBlank()) {
                            LinkedHashMap<String, String> meta = new LinkedHashMap<>(converted.metadata());
                            if (!ruleName.isBlank()) meta.put(EngineScenario.RULE_NAME, ruleName);
                            if (!ruleTags.isBlank()) meta.put("ruleTags", ruleTags);
                            converted = new EngineScenario(
                                    converted.keyword(),
                                    converted.name(),
                                    converted.description(),
                                    converted.tags(),
                                    converted.steps(),
                                    converted.examples(),
                                    converted.location(),
                                    meta
                            );
                        }
                        scenarios.add(converted);
                    });
                }
            });
        }
        Location location = feature.getLocation();
        return new EngineFeature(
                feature.getName(),
                feature.getDescription(),
                feature.getLanguage(),
                tagNames(feature.getTags()),
                background,
                scenarios,
                fromLocation(sourceUri, location)
        );
    }

    public static Pickle toPickle(EngineScenario scenario, String uri, String language) {
        EngineScenario source = scenario == null ? EngineScenario.of("", List.of()) : scenario;
        String id = "scenario-" + source.location().line() + "-" + source.name();
        List<PickleStep> steps = new ArrayList<>();
        int index = 0;
        for (EngineStep step : source.steps()) {
            steps.add(toPickleStep(step, id + "-step-" + index));
            index++;
        }
        List<PickleTag> tags = new ArrayList<>();
        int tagIndex = 0;
        for (String tag : source.tags()) {
            tags.add(new PickleTag(tag, id + "-tag-" + tagIndex));
            tagIndex++;
        }
        return new Pickle(
                id,
                uri == null ? source.location().uri() : uri,
                source.name(),
                language == null || language.isBlank() ? "en" : language,
                steps,
                tags,
                List.of(id)
        );
    }

    public static EngineScenario fromPickle(Pickle pickle) {
        if (pickle == null) return EngineScenario.of("", List.of());
        List<EngineStep> steps = new ArrayList<>();
        for (PickleStep step : pickle.getSteps()) {
            steps.add(fromPickleStep(step, pickle.getUri()));
        }
        List<String> tags = new ArrayList<>();
        for (PickleTag tag : pickle.getTags()) {
            tags.add(tag.getName());
        }
        return new EngineScenario(
                "Scenario",
                pickle.getName(),
                "",
                tags,
                steps,
                List.of(),
                new SourceLocation(pickle.getUri(), 0, 0),
                Map.of()
        );
    }

    private static PickleStep toPickleStep(EngineStep step, String id) {
        PickleStepArgument argument = null;
        if (step.argument() instanceof StepArgument.TableArg table) {
            argument = PickleStepArgument.of(toPickleTable(table.table()));
        } else if (step.argument() instanceof StepArgument.DocArg doc) {
            String media = doc.doc().mediaType().isBlank() ? null : doc.doc().mediaType();
            argument = PickleStepArgument.of(new PickleDocString(media, doc.doc().content()));
        }
        List<String> nodes = new ArrayList<>();
        nodes.add(id);
        nodes.add("kw:" + step.keyword());
        if (step.fromBackground()) nodes.add("bg-step");
        return new PickleStep(argument, nodes, id, pickleType(step.keyword()), step.text());
    }

    private static EngineStep fromPickleStep(PickleStep step, String uri) {
        StepArgument argument = StepArgument.none();
        Optional<PickleStepArgument> raw = step.getArgument();
        if (raw.isPresent()) {
            if (raw.get().getDataTable().isPresent()) {
                argument = StepArgument.table(fromPickleTable(raw.get().getDataTable().get()));
            } else if (raw.get().getDocString().isPresent()) {
                PickleDocString doc = raw.get().getDocString().get();
                argument = StepArgument.doc(new DocText(doc.getContent(), doc.getMediaType().orElse("")));
            }
        }
        String keyword = "";
        boolean background = false;
        for (String node : step.getAstNodeIds()) {
            if (node == null) continue;
            if (node.startsWith("kw:")) keyword = node.substring(3);
            if (node.startsWith("bg-")) background = true;
        }
        if (keyword.isEmpty()) {
            keyword = step.getType().map(CucumberModelConverter::keywordFor).orElse("");
        }
        return new EngineStep(keyword, step.getText(), argument, new SourceLocation(uri, 0, 0), Map.of(), background);
    }

    private static PickleStepType pickleType(String keyword) {
        if (keyword == null) return PickleStepType.UNKNOWN;
        String trimmed = keyword.trim();
        if (trimmed.startsWith("Given")) return PickleStepType.CONTEXT;
        if (trimmed.startsWith("When")) return PickleStepType.ACTION;
        if (trimmed.startsWith("Then")) return PickleStepType.OUTCOME;
        return PickleStepType.UNKNOWN;
    }

    private static String keywordFor(PickleStepType type) {
        return switch (type) {
            case CONTEXT -> "Given ";
            case ACTION -> "When ";
            case OUTCOME -> "Then ";
            case UNKNOWN -> "* ";
        };
    }

    private static Background toBackground(EngineFeature feature, AtomicInteger ids) {
        List<Step> steps = new ArrayList<>();
        for (EngineStep step : feature.background()) {
            steps.add(toMessageStep(step, feature.location().uri(), ids));
        }
        return new Background(
                toLocation(feature.location()),
                "Background",
                "",
                "",
                steps,
                nextId("bg", ids)
        );
    }

    private static Scenario toScenario(EngineScenario scenario, String uri, AtomicInteger ids) {
        List<Step> steps = new ArrayList<>();
        for (EngineStep step : scenario.steps()) {
            steps.add(toMessageStep(step, uri, ids));
        }
        List<Examples> examples = new ArrayList<>();
        for (ExampleTable table : scenario.examples()) {
            examples.add(toExamples(table, uri, ids));
        }
        return new Scenario(
                toLocation(scenario.location()),
                toTags(scenario.tags(), uri, ids),
                scenario.keyword(),
                scenario.name(),
                scenario.description(),
                steps,
                examples,
                nextId("scenario", ids)
        );
    }

    private static EngineScenario fromScenario(Scenario scenario, String uri, String ruleName) {
        List<EngineStep> steps = new ArrayList<>();
        for (Step step : scenario.getSteps()) {
            steps.add(fromStep(step, uri, false));
        }
        List<ExampleTable> examples = new ArrayList<>();
        for (Examples table : scenario.getExamples()) {
            examples.add(fromExamples(table, uri));
        }
        Map<String, String> metadata = ruleName == null || ruleName.isBlank()
                ? Map.of()
                : Map.of(EngineScenario.RULE_NAME, ruleName);
        return new EngineScenario(
                scenario.getKeyword(),
                scenario.getName(),
                scenario.getDescription(),
                tagNames(scenario.getTags()),
                steps,
                examples,
                fromLocation(uri, scenario.getLocation()),
                metadata
        );
    }

    private static Step toMessageStep(EngineStep step, String uri, AtomicInteger ids) {
        io.cucumber.messages.types.DocString doc = null;
        io.cucumber.messages.types.DataTable table = null;
        if (step.argument() instanceof StepArgument.DocArg docArg) {
            String media = docArg.doc().mediaType().isBlank() ? null : docArg.doc().mediaType();
            doc = new io.cucumber.messages.types.DocString(
                    toLocation(step.location()),
                    media,
                    docArg.doc().content(),
                    "\"\"\""
            );
        } else if (step.argument() instanceof StepArgument.TableArg tableArg) {
            table = toMessageTable(tableArg.table(), step.location(), ids);
        }
        String id = step.fromBackground() ? nextId("bg", ids) : nextId("step", ids);
        return new Step(
                toLocation(step.location()),
                step.keyword(),
                null,
                step.text(),
                doc,
                table,
                id
        );
    }

    private static EngineStep fromStep(Step step, String uri, boolean background) {
        StepArgument argument = StepArgument.none();
        if (step.getDocString().isPresent()) {
            io.cucumber.messages.types.DocString doc = step.getDocString().get();
            argument = StepArgument.doc(new DocText(doc.getContent(), doc.getMediaType().orElse("")));
        } else if (step.getDataTable().isPresent()) {
            argument = StepArgument.table(fromMessageTable(step.getDataTable().get()));
        }
        boolean fromBackground = background || (step.getId() != null && step.getId().startsWith("bg-"));
        return new EngineStep(
                step.getKeyword(),
                step.getText(),
                argument,
                fromLocation(uri, step.getLocation()),
                Map.of(),
                fromBackground
        );
    }

    private static Examples toExamples(ExampleTable table, String uri, AtomicInteger ids) {
        TableRow header = toRow(table.headers(), table.location(), ids);
        List<TableRow> body = new ArrayList<>();
        for (List<String> row : table.rows()) {
            body.add(toRow(row, table.location(), ids));
        }
        return new Examples(
                toLocation(table.location()),
                toTags(table.tags(), uri, ids),
                "Examples",
                table.name(),
                "",
                header,
                body,
                nextId("examples", ids)
        );
    }

    private static ExampleTable fromExamples(Examples examples, String uri) {
        List<String> headers = examples.getTableHeader().map(CucumberModelConverter::cellValues).orElse(List.of());
        List<List<String>> rows = new ArrayList<>();
        for (TableRow row : examples.getTableBody()) {
            rows.add(cellValues(row));
        }
        return new ExampleTable(
                examples.getName(),
                tagNames(examples.getTags()),
                headers,
                rows,
                fromLocation(uri, examples.getLocation())
        );
    }

    private static io.cucumber.messages.types.DataTable toMessageTable(Table table, SourceLocation location, AtomicInteger ids) {
        List<TableRow> rows = new ArrayList<>();
        for (List<String> row : table.rows()) {
            rows.add(toRow(row, location, ids));
        }
        return new io.cucumber.messages.types.DataTable(toLocation(location), rows);
    }

    private static Table fromMessageTable(io.cucumber.messages.types.DataTable table) {
        List<List<String>> rows = new ArrayList<>();
        for (TableRow row : table.getRows()) {
            rows.add(cellValues(row));
        }
        return new Table(rows);
    }

    private static PickleTable toPickleTable(Table table) {
        List<PickleTableRow> rows = new ArrayList<>();
        for (List<String> row : table.rows()) {
            List<PickleTableCell> cells = new ArrayList<>();
            for (String cell : row) {
                cells.add(new PickleTableCell(cell == null ? "" : cell));
            }
            rows.add(new PickleTableRow(cells));
        }
        return new PickleTable(rows);
    }

    private static Table fromPickleTable(PickleTable table) {
        List<List<String>> rows = new ArrayList<>();
        for (PickleTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (PickleTableCell cell : row.getCells()) {
                cells.add(cell.getValue());
            }
            rows.add(cells);
        }
        return new Table(rows);
    }

    private static TableRow toRow(List<String> values, SourceLocation location, AtomicInteger ids) {
        List<TableCell> cells = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                cells.add(new TableCell(toLocation(location), value == null ? "" : value));
            }
        }
        return new TableRow(toLocation(location), cells, nextId("row", ids));
    }

    private static List<String> cellValues(TableRow row) {
        List<String> values = new ArrayList<>();
        for (TableCell cell : row.getCells()) {
            values.add(cell.getValue());
        }
        return values;
    }

    private static Rule toRule(String name, List<String> tags, SourceLocation location, List<RuleChild> children, AtomicInteger ids) {
        return new Rule(
                toLocation(location),
                toTags(tags, location.uri(), ids),
                "Rule",
                name == null ? "" : name,
                "",
                children,
                nextId("rule", ids)
        );
    }

    private static List<Tag> toTags(List<String> names, String uri, AtomicInteger ids) {
        List<Tag> tags = new ArrayList<>();
        if (names == null) return tags;
        for (String name : names) {
            tags.add(new Tag(new Location(0L, 0L), name, nextId("tag", ids)));
        }
        return tags;
    }

    private static List<String> tagNames(List<Tag> tags) {
        List<String> names = new ArrayList<>();
        if (tags == null) return names;
        for (Tag tag : tags) {
            names.add(tag.getName());
        }
        return names;
    }

    private static String joinTags(List<Tag> tags) {
        return String.join(" ", tagNames(tags));
    }

    private static List<String> tagsNamed(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return List.of(raw.split(" "));
    }

    private static EngineScenario withoutRuleTags(EngineScenario scenario, List<String> ruleTags) {
        if (ruleTags == null || ruleTags.isEmpty()) return scenario;
        List<String> kept = new ArrayList<>();
        int index = 0;
        for (String tag : scenario.tags()) {
            if (index < ruleTags.size() && tag.equals(ruleTags.get(index))) {
                index++;
                continue;
            }
            kept.add(tag);
        }
        return new EngineScenario(
                scenario.keyword(),
                scenario.name(),
                scenario.description(),
                kept,
                scenario.steps(),
                scenario.examples(),
                scenario.location(),
                scenario.metadata()
        );
    }

    private static Location toLocation(SourceLocation location) {
        SourceLocation source = location == null ? SourceLocation.unknown() : location;
        Long column = source.column() == 0 ? null : (long) source.column();
        return new Location((long) source.line(), column);
    }

    private static SourceLocation fromLocation(String uri, Location location) {
        if (location == null) return new SourceLocation(uri == null ? "" : uri, 0, 0);
        int column = location.getColumn().map(Long::intValue).orElse(0);
        int line = location.getLine() == null ? 0 : location.getLine().intValue();
        return new SourceLocation(uri == null ? "" : uri, line, column);
    }

    private static String nextId(String prefix, AtomicInteger ids) {
        return prefix + "-" + ids.incrementAndGet();
    }

    private static List<List<String>> copyRows(List<List<String>> rows) {
        List<List<String>> copy = new ArrayList<>();
        for (List<String> row : rows) {
            copy.add(new ArrayList<>(row));
        }
        return copy;
    }
}
