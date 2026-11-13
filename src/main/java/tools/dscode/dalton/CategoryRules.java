package tools.dscode.dalton;

import java.nio.file.Path;

/** Category-name rules. Phase 2 fills the checks. An empty body is already rejected. */
final class CategoryRules {
    private CategoryRules() {
    }

    static void validate(Path project, String path, byte[] body) {
        // Reserved names, built-in immutability, and Loading / ALL_CONTEXTS land in phase 2.
    }
}
