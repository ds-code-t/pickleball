package tools.dscode.control.override;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class StepOverrideCompilerTest {
    @Test
    void missingSystemCompilerIsReportedAsUnavailableCapability() {
        assertThrows(
                StepOverrideCompiler.CompilerUnavailableException.class,
                () -> StepOverrideCompiler.compileWith(
                        null,
                        "scenario",
                        "rule",
                        StepOverridePatternType.REGEX,
                        "^step$",
                        "public final class {{CLASS_NAME}} {}"
                )
        );
    }
}
