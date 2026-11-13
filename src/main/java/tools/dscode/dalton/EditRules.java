package tools.dscode.dalton;

import java.nio.file.Path;

/**
 * Extra validation that runs before a shared edit write. Category, secret,
 * and glue-annotation rules attach here without changing the JSON envelope.
 */
final class EditRules {
    private EditRules() {
    }

    static void validate(Path project, String path, byte[] body) {
        CategoryRules.validate(project, path, body);
        FileRules.validate(project, path, body);
        GlueRules.validate(project, path, body);
    }
}
