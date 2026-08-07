package tools.dscode.common.dataelements;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public record TerminalResult(
        DataSelection selection,
        List<?> values
) implements DataExecutionResult {
    public TerminalResult {
        Objects.requireNonNull(selection, "selection");
        values = Collections.unmodifiableList(new ArrayList<>(values));
    }
}
