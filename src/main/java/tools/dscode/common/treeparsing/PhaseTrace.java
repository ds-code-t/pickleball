package tools.dscode.common.treeparsing;

import java.util.List;

/** Top-level result container for a parse run. */
public record PhaseTrace(
        MatchNode topLevelRootMatch,
        String topLevelOutput,
        List<PhaseRun> topLevelPhaseRuns
) { }
