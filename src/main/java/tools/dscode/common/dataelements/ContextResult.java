package tools.dscode.common.dataelements;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public record ContextResult(
        DataSelection selection,
        List<?> values
) implements DataExecutionResult {
    public ContextResult {
        Objects.requireNonNull(selection, "selection");
        values = Collections.unmodifiableList(new ArrayList<>(values));
    }
}
