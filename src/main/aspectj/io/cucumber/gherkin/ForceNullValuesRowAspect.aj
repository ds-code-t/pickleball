package io.cucumber.gherkin;

import java.util.List;

import io.cucumber.messages.types.PickleStep;
import io.cucumber.messages.types.Step;
import io.cucumber.messages.types.TableCell;
import io.cucumber.messages.types.TableRow;
import io.cucumber.messages.types.StepKeywordType;

/**
 * Forces PickleCompiler.pickleStep(..) to always receive a null valuesRow,
 * regardless of what the caller passes.
 *
 * Signature being intercepted:
 *   private PickleStep pickleStep(
 *       Step step,
 *       List<TableCell> variableCells,
 *       TableRow valuesRow,
 *       StepKeywordType keywordType)
 */
public privileged aspect ForceNullValuesRowAspect {

    /**
     * Execution of PickleCompiler.pickleStep(..) with its four parameters.
     * We bind all args so we can re-invoke with a null for the 3rd one.
     */
    pointcut execPickleStep(
            Step step,
            List<TableCell> variableCells,
            TableRow valuesRow,
            StepKeywordType keywordType
    ) :
            execution(io.cucumber.messages.types.PickleStep io.cucumber.gherkin.PickleCompiler.pickleStep(..))
                    && args(step, variableCells, valuesRow, keywordType);

    /**
     * Around advice: proceed with the same args, but force valuesRow = null.
     */
    PickleStep around(
            Step step,
            List<TableCell> variableCells,
            TableRow valuesRow,
            StepKeywordType keywordType
    ) : execPickleStep(step, variableCells, valuesRow, keywordType) {
        // Ignore the incoming valuesRow and pass null instead.
        return proceed(step, variableCells, null, keywordType);
    }
}
