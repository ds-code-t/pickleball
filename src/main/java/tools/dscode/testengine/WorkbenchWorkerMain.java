package tools.dscode.testengine;

import io.cucumber.core.cli.Main;
import tools.dscode.common.resources.PathLists;

import java.util.ArrayList;
import java.util.List;

/** Thin consumer-worker entrypoint used by the separate Pickleball Workbench controller. */
public final class WorkbenchWorkerMain {
    private static final String CORE_GLUE = "tools.dscode.coredefinitions";

    private WorkbenchWorkerMain() {
    }

    public static void main(String[] args) {
        reportStartupStages();
        PickleballRunner runner = DynamicSuiteBootstrap.initializeFromRuntimeClasspath();
        List<String> cucumberArgs = new ArrayList<>();
        if (args != null) {
            cucumberArgs.addAll(List.of(args));
        }

        if (!hasGlueOption(cucumberArgs)) {
            addGlue(cucumberArgs, runner.get(PKB_props.PKB_GLUE));
        }
        addGlueIfMissing(cucumberArgs, CORE_GLUE);

        byte exitCode = Main.run(
                cucumberArgs.toArray(String[]::new),
                Thread.currentThread().getContextClassLoader()
        );

        // Always enter JVM shutdown so the consumer-side bridge shutdown hook runs,
        // including after a successful anchor completion.
        System.exit(exitCode);
    }

    /**
     * Earliest worker marks. {@code jvm-main} is the first line of main.
     * {@code classpath-scan} is the classpath and glue initialization that follows,
     * before the control bridge binds. Both go to stderr, which the controller
     * appends to the worker {@code *.err.log}.
     */
    static void reportStartupStages() {
        System.err.println("progress stage=jvm-main");
        System.err.println("progress stage=classpath-scan");
    }

    private static boolean hasGlueOption(List<String> args) {
        return args.stream().anyMatch(value -> "--glue".equals(value) || "-g".equals(value));
    }

    private static void addGlue(List<String> args, String glue) {
        if (glue == null || glue.isBlank()) return;
        for (String value : PathLists.split(glue)) {
            addGlueIfMissing(args, value);
        }
    }

    private static void addGlueIfMissing(List<String> args, String glue) {
        if (glue == null || glue.isBlank() || containsGlue(args, glue)) return;
        args.add(0, glue);
        args.add(0, "--glue");
    }

    private static boolean containsGlue(List<String> args, String expected) {
        for (int i = 0; i + 1 < args.size(); i++) {
            if (("--glue".equals(args.get(i)) || "-g".equals(args.get(i)))
                    && expected.equals(args.get(i + 1))) {
                return true;
            }
        }
        return false;
    }
}
