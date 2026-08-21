package tools.dscode.workbench;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Fail-fast proof that the controller process cannot see Pickleball execution classes. */
final class WorkbenchRuntimeBoundary {
    private static final List<String> FORBIDDEN_CLASSES = List.of(
            "tools.dscode.testengine.WorkbenchWorkerMain",
            "tools.dscode.common.control.ControlRuntime",
            "tools.dscode.coredefinitions.GeneralSteps",
            "tools.dscode.control.api.DynamicControl",
            "tools.dscode.control.bridge.ControlBridgeBootstrap"
    );

    private WorkbenchRuntimeBoundary() {
    }

    static void verify() {
        ClassLoader controllerLoader = WorkbenchRuntimeBoundary.class.getClassLoader();
        List<String> visible = new ArrayList<>();
        for (String className : FORBIDDEN_CLASSES) {
            try {
                Class.forName(className, false, controllerLoader);
                visible.add(className);
            } catch (ClassNotFoundException expected) {
                // Controller-only classpath: the consumer worker owns these classes.
            } catch (LinkageError failure) {
                visible.add(className + " (linkage failure: " + failure.getClass().getSimpleName() + ")");
            }
        }
        if (!visible.isEmpty()) {
            throw new IllegalStateException(
                    "Pickleball execution classes are visible in the Workbench JVM: " + visible
            );
        }

        List<String> coreEntries = List.of(
                        System.getProperty("java.class.path", "").split(
                                java.util.regex.Pattern.quote(File.pathSeparator)
                        )
                ).stream()
                .filter(entry -> !entry.isBlank())
                .map(Path::of)
                .map(path -> path.getFileName() == null ? path.toString() : path.getFileName().toString())
                .filter(WorkbenchRuntimeBoundary::looksLikePickleballCoreJar)
                .toList();
        if (!coreEntries.isEmpty()) {
            throw new IllegalStateException(
                    "Pickleball core JARs are present on the Workbench process classpath: " + coreEntries
            );
        }
    }

    private static boolean looksLikePickleballCoreJar(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.matches("pickleball-[0-9].*\\.jar")
                && !lower.startsWith("pickleball-workbench-")
                && !lower.startsWith("pickleball-control-protocol-");
    }
}
