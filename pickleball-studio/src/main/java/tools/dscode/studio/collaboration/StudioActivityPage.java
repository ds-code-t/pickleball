package tools.dscode.studio.collaboration;

import java.util.List;

public record StudioActivityPage(
        long oldestSequence,
        long latestSequence,
        boolean gap,
        List<StudioActivity> activities
) {
    public StudioActivityPage {
        activities = List.copyOf(activities);
    }
}
