package io.cucumber.gherkin;

import java.util.List;
import io.cucumber.messages.types.TableCell;

/**
 * Disables placeholder substitution ONLY when interpolate(...) executes
 * while we're in the control flow of PickleCompiler.pickleStep(...).
 *
 * We do NOT change valuesRow or other params; we just bypass the string
 * substitution so step text stays as-is (e.g., "Given <X> ...").
 */
public privileged aspect ForceNullValuesRowAspect {

    /** Any execution of PickleCompiler.pickleStep(..). */
    pointcut inPickleStepExec():
            execution(* io.cucumber.gherkin.PickleCompiler.pickleStep(..));

    /** Any join points that occur under pickleStep's control flow. */
    pointcut underPickleStepFlow():
            cflowbelow(inPickleStepExec());

    /**
     * When interpolate(...) runs anywhere under the control flow of
     * pickleStep(...), return the original name untouched.
     *
     * This also covers interpolate() calls made indirectly via
     * pickleDataTable(...) and pickleDocString(...), because they're invoked
     * from within pickleStep(...).
     */
    String around(String name, List<TableCell> variableCells, List<TableCell> valueCells)
            : execution(String io.cucumber.gherkin.PickleCompiler.interpolate(String, java.util.List, java.util.List))
            && args(name, variableCells, valueCells)
            && underPickleStepFlow()
            {
                // No substitution when building PickleStep text / nested args.
                return name;
            }
}
