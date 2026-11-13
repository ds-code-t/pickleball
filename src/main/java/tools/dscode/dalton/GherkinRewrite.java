package tools.dscode.dalton;

import java.nio.file.Path;
import java.util.List;

/**
 * Scenario and step edits regenerate Gherkin, then call feature put.
 * Unavailable until the regeneration body is installed, so the shared model
 * accepts the command and writes nothing.
 */
final class GherkinRewrite {
    private GherkinRewrite() {
    }

    static boolean available() {
        return false;
    }

    static DaltonEdit.Outcome apply(Path project, String action, String feature, List<String> tokens) {
        throw new IllegalStateException("gherkin regeneration is not installed");
    }
}
