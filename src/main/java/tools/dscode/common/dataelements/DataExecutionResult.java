package tools.dscode.common.dataelements;

import java.util.List;

public sealed interface DataExecutionResult
        permits ContextResult, IterationResult, TerminalResult {
    DataSelection selection();

    List<?> values();
}
