package io.pickleball;

import io.cucumber.core.exception.CucumberException;
import io.cucumber.core.gherkin.StepType;
import io.cucumber.core.gherkin.messages.GherkinMessagesPickle;
import io.cucumber.core.gherkin.messages.GherkinMessagesStep;
import io.cucumber.core.runner.*;
import io.cucumber.messages.IdGenerator;
import io.cucumber.messages.types.*;
import io.pickleball.cucumberutilities.SimpleIdGenerator;

import java.lang.Exception;
import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.cucumber.gherkin.PickleCompiler.pickleStepTypeFromKeywordType;
import static java.util.Collections.singletonList;

public class StepFactory {

    static IdGenerator idGenerator = new SimpleIdGenerator();



    public static PickleStepTestStep remapPickleStepTestStep(Runner runner, PickleStepTestStep pickleStepTestStep, GherkinMessagesPickle pickle) {
        GherkinMessagesStep gherkinMessagesStep = (GherkinMessagesStep) pickleStepTestStep.getStep();
        PickleStep pickleStep  =createPickleStep(gherkinMessagesStep.getPickleStep().getStepTemplate() , pickle.getPickle().getHeaderRow(), pickle.getPickle().getValueRow(), gherkinMessagesStep.getPickleStep().getStepTemplate().getKeywordType().orElse(null));
        return createPickleStepTestStep(runner, pickleStep, pickle);
    }


    public static PickleStepTestStep createPickleStepTestStep(Runner runner, PickleStepTestStep pickleStepTestStep, GherkinMessagesPickle pickle) {
        GherkinMessagesStep gherkinMessagesStep = createGherkinMessagesStep(pickleStepTestStep, pickle);

        PickleStepDefinitionMatch match = runner.matchStepToStepDefinition(pickle, gherkinMessagesStep);
        List<HookTestStep> afterStepHookSteps = runner.createAfterStepHooks(pickle.getTags());
        List<HookTestStep> beforeStepHookSteps = runner.createBeforeStepHooks(pickle.getTags());
        return new PickleStepTestStep(runner.bus.generateId(), pickle.getUri(), gherkinMessagesStep, beforeStepHookSteps,
                afterStepHookSteps, match);
    }

    public static GherkinMessagesStep createGherkinMessagesStep(PickleStepTestStep pickleStepTestStep, GherkinMessagesPickle pickle) {
        return new GherkinMessagesStep(
                ((GherkinMessagesStep) pickleStepTestStep.getStep()).getPickleStep(),
                pickle.getDialect(),
                pickleStepTestStep.getStep().getPreviousGivenWhenThenKeyword(),
                pickleStepTestStep.getPickle().getLocation(),
                pickleStepTestStep.getPickle().getKeyword()
        );

    }




    public static PickleStepTestStep createPickleStepTestStep(Runner runner, PickleStep pickleStep, GherkinMessagesPickle pickle) {
        GherkinMessagesStep gherkinMessagesStep = createGherkinMessagesStep(pickleStep, pickle);

        PickleStepDefinitionMatch match = runner.matchStepToStepDefinition(pickle, gherkinMessagesStep);
        if(match.method == null)
            throw new CucumberException("No matching method found for step '" + pickleStep.getText() + "'");

        List<HookTestStep> afterStepHookSteps = runner.createAfterStepHooks(pickle.getTags());
        List<HookTestStep> beforeStepHookSteps = runner.createBeforeStepHooks(pickle.getTags());
        return new PickleStepTestStep(runner.bus.generateId(), pickle.getUri(), gherkinMessagesStep, beforeStepHookSteps,
                afterStepHookSteps, match);
    }

    public static GherkinMessagesStep createGherkinMessagesStep(PickleStep pickleStep, GherkinMessagesPickle pickle) {

        String previousGivenWhenThen = pickle.getDialect().getGivenKeywords()
                .stream()
                .filter(s -> !StepType.isAstrix(s))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No Given keyword for dialect: " + pickle.getDialect().getName()));

        return new GherkinMessagesStep(
                pickleStep,
                pickle.getDialect(), previousGivenWhenThen,
                new io.cucumber.plugin.event.Location(Math.toIntExact(pickleStep.getStepTemplate().getLocation().getLine()), 0),
//                pickleStep.keyWord());
                pickleStep.getStepTemplate().getKeyword());
    }


    public static PickleStep createPickleStep(Step step, List<TableCell> variableCells, TableRow valuesRow, StepKeywordType keywordType) {

        List<TableCell> valueCells = valuesRow == null ? Collections.emptyList() : valuesRow.getCells();
        String stepText = interpolate(step.getText(), variableCells, valueCells);

        PickleStepArgument argument = null;
        if (step.getDataTable().isPresent()) {
            argument = new PickleStepArgument(null, pickleDataTable(step.getDataTable().get(), variableCells, valueCells));
        }

        if (step.getDocString().isPresent()) {
            argument = new PickleStepArgument(pickleDocString(step.getDocString().get(), variableCells, valueCells), null);
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


    public static PickleTable pickleDataTable(DataTable dataTable, List<TableCell> variableCells, List<TableCell> valueCells) {
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

    public static PickleDocString pickleDocString(DocString docString, List<TableCell> variableCells, List<TableCell> valueCells) {
        return new PickleDocString(
                docString.getMediaType().isPresent() ? interpolate(docString.getMediaType().get(), variableCells, valueCells) : null,
                interpolate(docString.getContent(), variableCells, valueCells)
        );
    }


    public static String interpolate(String name, List<TableCell> variableCells, List<TableCell> valueCells) {
        int col = 0;
        for (TableCell variableCell : variableCells) {
            TableCell valueCell = valueCells.get(col++);
            String header = variableCell.getValue();
            String value = valueCell.getValue();
            name = name.replace("<" + header + ">", value);
        }
        return name;
    }


}
