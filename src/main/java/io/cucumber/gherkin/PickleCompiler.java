/*
 * This file incorporates work covered by the following copyright and permission notice:
 *
 * Copyright (c) Cucumber Ltd
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.cucumber.gherkin;

import io.cucumber.messages.IdGenerator;
import io.cucumber.messages.types.DataTable;
import io.cucumber.messages.types.DocString;
import io.cucumber.messages.types.Examples;
import io.cucumber.messages.types.Feature;
import io.cucumber.messages.types.FeatureChild;
import io.cucumber.messages.types.GherkinDocument;
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
import io.cucumber.messages.types.StepKeywordType;
import io.cucumber.messages.types.TableCell;
import io.cucumber.messages.types.TableRow;
import io.cucumber.messages.types.Tag;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;

public class PickleCompiler {

    private final IdGenerator idGenerator;

    public PickleCompiler(IdGenerator idGenerator) {
        this.idGenerator = idGenerator;
    }

    public List<Pickle> compile(GherkinDocument gherkinDocument, String uri) {
        List<Pickle> pickles = new ArrayList<>();
        if (!gherkinDocument.getFeature().isPresent()) {
            return pickles;
        }
        Feature feature = gherkinDocument.getFeature().get();
        String language = feature.getLanguage();
        compileFeature(pickles, feature, language, uri);
        return pickles;
    }

    private void compileFeature(List<Pickle> pickles, Feature feature, String language, String uri) {
        List<Tag> tags = feature.getTags();
        List<Step> featureBackgroundSteps = new ArrayList<>();
        for (FeatureChild featureChild : feature.getChildren()) {
            if (featureChild.getBackground().isPresent()) {
                featureBackgroundSteps.addAll(featureChild.getBackground().get().getSteps());
            } else if (featureChild.getRule().isPresent()) {
                compileRule(pickles, featureChild.getRule().get(), tags, featureBackgroundSteps, language, uri);
            } else if (featureChild.getScenario().isPresent()) {
                Scenario scenario = featureChild.getScenario().get();
                if (scenario.getExamples().isEmpty()) {
                    compileScenario(pickles, scenario, tags, featureBackgroundSteps, language, uri);
                } else {
                    compileScenarioOutline(pickles, scenario, tags, featureBackgroundSteps, language, uri);
                }
            }
        }
    }

    private void compileRule(List<Pickle> pickles, Rule rule, List<Tag> parentTags, List<Step> featureBackgroundSteps, String language, String uri) {
        List<Step> ruleBackgroundSteps = new ArrayList<>(featureBackgroundSteps);

        List<Tag> ruleTags = new ArrayList<>();
        ruleTags.addAll(parentTags);
        ruleTags.addAll(rule.getTags());

        for (RuleChild ruleChild : rule.getChildren()) {
            if (ruleChild.getBackground().isPresent()) {
                ruleBackgroundSteps.addAll(ruleChild.getBackground().get().getSteps());
            } else if (ruleChild.getScenario().isPresent()) {
                Scenario scenario = ruleChild.getScenario().get();
                if (scenario.getExamples().isEmpty()) {
                    compileScenario(pickles, scenario, ruleTags, ruleBackgroundSteps, language, uri);
                } else {
                    compileScenarioOutline(pickles, scenario, ruleTags, ruleBackgroundSteps, language, uri);
                }
            }
        }
    }

    private void compileScenario(List<Pickle> pickles, Scenario scenario, List<Tag> parentTags, List<Step> backgroundSteps, String language, String uri) {
        List<PickleStep> steps = new ArrayList<>();
        if (!scenario.getSteps().isEmpty()) {
            List<Step> allSteps = new ArrayList<>();
            allSteps.addAll(backgroundSteps);
            allSteps.addAll(scenario.getSteps());

            StepKeywordType lastKeywordType = StepKeywordType.UNKNOWN;
            for (Step step : allSteps) {
                if (step.getKeywordType().get() != StepKeywordType.CONJUNCTION)
                    lastKeywordType = step.getKeywordType().get();

                steps.add(pickleStep(step, lastKeywordType));
            }
        }

        List<Tag> scenarioTags = new ArrayList<>();
        scenarioTags.addAll(parentTags);
        scenarioTags.addAll(scenario.getTags());

        List<String> sourceIds = singletonList(scenario.getId());

        Pickle pickle = new Pickle(
                idGenerator.newId(),
                uri,
                scenario.getName(),
                language,
                steps,
                pickleTags(scenarioTags),
                sourceIds,
                backgroundSteps.size(),
                scenario.getName(),
                null,
                null
        );
        pickles.add(pickle);
    }

    private void compileScenarioOutline(List<Pickle> pickles, Scenario scenario, List<Tag> featureTags, List<Step> backgroundSteps, String language, String uri) {
        for (final Examples examples : scenario.getExamples()) {
            if (!examples.getTableHeader().isPresent()) continue;
            List<TableCell> variableCells = examples.getTableHeader().get().getCells();
            for (final TableRow valuesRow : examples.getTableBody()) {
                List<TableCell> valueCells = valuesRow.getCells();

                List<PickleStep> steps = new ArrayList<>();
                StepKeywordType lastKeywordType = StepKeywordType.UNKNOWN;

                if (!scenario.getSteps().isEmpty())
                    for (Step step : backgroundSteps) {
                        if (step.getKeywordType().get() != StepKeywordType.CONJUNCTION)
                            lastKeywordType = step.getKeywordType().get();

                        steps.add(pickleStep(step, lastKeywordType));
                    }


                List<Tag> tags = new ArrayList<>();
                tags.addAll(featureTags);
                tags.addAll(scenario.getTags());
                tags.addAll(examples.getTags());
                if (examples.scenarioTagsIndex != -1) {
                    TableCell cell = valueCells.get(examples.scenarioTagsIndex);
                    tags.addAll(Arrays.stream(cell.getValue().trim().split("\\s+")).map(s -> new Tag(cell.getLocation(), s, "asd")).toList());
                }
                for (Step scenarioOutlineStep : scenario.getSteps()) {
                    if (scenarioOutlineStep.getKeywordType().get() != StepKeywordType.CONJUNCTION)
                        lastKeywordType = scenarioOutlineStep.getKeywordType().get();

                    PickleStep pickleStep = pickleStep(scenarioOutlineStep, variableCells, valuesRow, lastKeywordType);

                    steps.add(pickleStep);
                }

                List<String> sourceIds = asList(scenario.getId(), valuesRow.getId());
                Pickle pickle = new Pickle(
                        idGenerator.newId(),
                        uri,
                        interpolate(scenario.getName(), variableCells, valueCells),
                        language,
                        steps,
                        pickleTags(tags),
                        sourceIds,
                        backgroundSteps.size(),
                        scenario.getName(),
                        variableCells,
                        valuesRow
                );
                pickles.add(pickle);
            }
        }
    }

    private PickleTable pickleDataTable(DataTable dataTable, List<TableCell> variableCells, List<TableCell> valueCells) {
        List<TableRow> rows = dataTable.getRows();
        List<PickleTableRow> newRows = new ArrayList<>(rows.size());
        for (TableRow row : rows) {
            List<TableCell> cells = row.getCells();
            List<PickleTableCell> newCells = new ArrayList<>();
            for (TableCell cell : cells) {
                newCells.add(new PickleTableCell(interpolate(cell.getValue(), variableCells, valueCells)));
            }
            newRows.add(new PickleTableRow(newCells));
        }
        return new PickleTable(newRows);
    }

    private PickleDocString pickleDocString(DocString docString, List<TableCell> variableCells, List<TableCell> valueCells) {
        return new PickleDocString(
                docString.getMediaType().isPresent() ? interpolate(docString.getMediaType().get(), variableCells, valueCells) : null,
                interpolate(docString.getContent(), variableCells, valueCells)
        );
    }


    public static final Map<StepKeywordType, PickleStepType> pickleStepTypeFromKeywordType = new HashMap<>();

    static {
        pickleStepTypeFromKeywordType.put(StepKeywordType.UNKNOWN, PickleStepType.UNKNOWN);
        pickleStepTypeFromKeywordType.put(StepKeywordType.CONTEXT, PickleStepType.CONTEXT);
        pickleStepTypeFromKeywordType.put(StepKeywordType.ACTION, PickleStepType.ACTION);
        pickleStepTypeFromKeywordType.put(StepKeywordType.OUTCOME, PickleStepType.OUTCOME);
    }

    private PickleStep pickleStep(Step step, List<TableCell> variableCells, TableRow valuesRow, StepKeywordType keywordType) {

        List<TableCell> emptyList = Collections.emptyList();
        String stepText = step.getText();
        PickleStepArgument argument = null;
        if (step.getDataTable().isPresent()) {
            argument = new PickleStepArgument(null, pickleDataTable(step.getDataTable().get(), emptyList, emptyList));
        } else if (step.getDocString().isPresent()) {
            argument = new PickleStepArgument(pickleDocString(step.getDocString().get(), emptyList, emptyList), null);
        }


        List<String> astNodeIds;
        if (valuesRow != null) {
            astNodeIds = Stream.of(singletonList(step.getId()), singletonList(valuesRow.getId()))
                    .flatMap(Collection::stream)
                    .collect(Collectors.toList());

        } else {
            astNodeIds = singletonList(step.getId());
        }

        return new PickleStep(
                step,
                argument,
                astNodeIds,
                idGenerator.newId(),
                pickleStepTypeFromKeywordType.get(keywordType),
                stepText
        );
    }

    private PickleStep pickleStep(Step step, StepKeywordType keywordType) {
        return pickleStep(step, Collections.emptyList(), null, keywordType);
    }

    private String interpolate(String name, List<TableCell> variableCells, List<TableCell> valueCells) {
        int col = 0;
        for (TableCell variableCell : variableCells) {
            TableCell valueCell = valueCells.get(col++);
            String header = variableCell.getValue();
            String value = valueCell.getValue();
            name = name.replace("<" + header + ">", value);
        }
        return name;
    }

    private List<PickleTag> pickleTags(List<Tag> tags) {
        List<PickleTag> result = new ArrayList<>();
        for (Tag tag : tags) {
            result.add(pickleTag(tag));
        }
        return result;
    }

    private PickleTag pickleTag(Tag tag) {
        return new PickleTag(tag.getName(), tag.getId());
    }

}
