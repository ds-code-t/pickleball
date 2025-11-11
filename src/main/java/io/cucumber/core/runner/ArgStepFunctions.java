package io.cucumber.core.runner;

import io.cucumber.core.backend.ParameterInfo;
import io.cucumber.core.backend.StepDefinition;
import io.cucumber.core.gherkin.Step;
import io.cucumber.core.stepexpression.Argument;

import java.net.URI;
import java.util.List;

import static io.cucumber.core.runner.util.ArgumentUtility.emptyDataTable;
import static io.cucumber.core.runner.util.ArgumentUtility.emptyDocString;
import static tools.dscode.common.util.Reflect.getProperty;


public class ArgStepFunctions {

    public static PickleStepDefinitionMatch updatePickleStepDefinitionMatch(PickleStepDefinitionMatch pickleStepDefinitionMatch) {
        List<Argument> args = pickleStepDefinitionMatch.getArguments();
        StepDefinition stepDefinition = pickleStepDefinitionMatch.getStepDefinition();
        if (!stepDefinition.getClass().getName().equals("io.cucumber.core.runner.CoreStepDefinition"))
            return pickleStepDefinitionMatch;
        CoreStepDefinition coreStepDefinition = (CoreStepDefinition) stepDefinition;
        List<ParameterInfo> parameterInfoList = coreStepDefinition.parameterInfos();
        if (args.size() == parameterInfoList.size())
            return pickleStepDefinitionMatch;
        int mismatchCount = parameterInfoList.size() - args.size();
        if (mismatchCount > 0) {
            for (int i = args.size(); i < parameterInfoList.size(); i++) {
                ParameterInfo p = parameterInfoList.get(i);
                if (p.getType().getTypeName().equals("io.cucumber.datatable.DataTable")) {
                    args.add(emptyDataTable());
                } else if (p.getType().getTypeName().equals("io.cucumber.docstring.DocString")) {
                    args.add(emptyDocString());
                }
            }
        } else {
            if (parameterInfoList.stream()
                    .noneMatch(p -> p.getType().getTypeName().equals("io.cucumber.datatable.DataTable"))) {
                args = args.stream()
                        .filter(arg -> !(arg instanceof io.cucumber.core.stepexpression.DataTableArgument))
                        .toList();
            }
            if (parameterInfoList.stream()
                    .noneMatch(p -> p.getType().getTypeName().equals("io.cucumber.docstring.DocString"))) {
                args = args.stream()
                        .filter(arg -> !(arg instanceof io.cucumber.core.stepexpression.DocStringArgument))
                        .toList();
            }
        }

        return new PickleStepDefinitionMatch(
                args, coreStepDefinition, (URI) getProperty(pickleStepDefinitionMatch, "uri"), (Step) getProperty(pickleStepDefinitionMatch, "step"));
    }


}
