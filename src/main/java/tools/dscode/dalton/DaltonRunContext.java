package tools.dscode.dalton;

import java.nio.file.Path;

/** The step execution a running scenario is inside, when linked tracking is recording. */
public final class DaltonRunContext {
    public record Frame(Path project, String stepExecId) {
    }

    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();

    private DaltonRunContext() {
    }

    public static void activate(Path project, String stepExecId) {
        if (project == null || stepExecId == null || stepExecId.isBlank()) {
            CURRENT.remove();
            return;
        }
        CURRENT.set(new Frame(project, stepExecId));
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static Frame current() {
        return CURRENT.get();
    }
}
