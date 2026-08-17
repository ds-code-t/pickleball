package tools.dscode.studio.runtime;

/** Compact metadata for one Studio-retained mapping snapshot. */
public record RuntimeMappingSnapshotSummary(
        String snapshotId,
        String capturedAt,
        String runtimeId,
        String scenarioId,
        String mapReference,
        String mapType,
        String mapClass,
        boolean restorable
) {
}
