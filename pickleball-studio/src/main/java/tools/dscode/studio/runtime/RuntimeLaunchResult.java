
package tools.dscode.studio.runtime;

import tools.dscode.studio.process.ManagedProcessSummary;

public record RuntimeLaunchResult(
        String sessionId,
        String buildTool,
        ManagedProcessSummary process
) {
}
