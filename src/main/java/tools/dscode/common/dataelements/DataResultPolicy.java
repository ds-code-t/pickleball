package tools.dscode.common.dataelements;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DataResultPolicy {

    public DataExecutionResult apply(DataSelection selection) {
        return switch (selection.query().resultUse()) {
            case CONTEXT -> new ContextResult(
                    selection,
                    aggregate(selection)
            );
            case ITERATION -> new IterationResult(
                    selection,
                    expand(selection)
            );
            case TERMINAL -> new TerminalResult(
                    selection,
                    aggregate(selection)
            );
        };
    }

    private List<?> aggregate(DataSelection selection) {
        Object value = selection.materializeTerminal();
        return Collections.singletonList(value);
    }

    private List<?> expand(DataSelection selection) {
        List<Object> values = new ArrayList<>(selection.size());
        DataAttribute returnAttribute =
                selection.query().returnAttribute();

        for (DataCandidate candidate : selection.candidates()) {
            values.add(returnAttribute == null
                    ? DataMaterializer.materialize(candidate)
                    : candidate.returnProjection(returnAttribute));
        }
        return Collections.unmodifiableList(values);
    }
}
