package tools.dscode.common.composite;

import tools.dscode.control.protocol.JavaEngineLauncher;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Launcher for the in-repo Java engine. Explicit {@code pkb_composite_bin} wins
 * before this. {@code pkb_composite_engine=rust} skips it. The launcher itself
 * lives in the JDK-only protocol module ({@link JavaEngineLauncher}) so the
 * Workbench direct-control provider resolves the same engine the same way.
 */
final class JavaEngineLocator {
    private JavaEngineLocator() {
    }

    static Optional<Path> script(ClassLoader loader) {
        Optional<Path> script = JavaEngineLauncher.script(loader);
        if (script.isEmpty() && JavaEngineLauncher.available(loader)) {
            CompositeLog.debug("location", "java engine is on the classpath but no launcher could be written");
        }
        return script;
    }
}
