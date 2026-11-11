package tools.dscode.engine;

import tools.dscode.engine.context.ExecutionContext;
import tools.dscode.engine.model.EngineScenario;
import tools.dscode.engine.model.RunResult;
import tools.dscode.engine.model.ScenarioResult;
import tools.dscode.engine.model.StepResult;
import tools.dscode.engine.source.DaltonScenarioSource;
import tools.dscode.engine.source.FileScenarioSource;
import tools.dscode.engine.source.ScenarioSource;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Native engine CLI. Gradle, Workbench, and MCP only forward here.
 */
public final class EngineCli {
    private EngineCli() {
    }

    public static void main(String[] args) {
        int code = execute(args, System.out, System.err);
        if (code != 0) System.exit(code);
    }

    public static boolean handles(String[] args) {
        return args != null && args.length > 0 && args[0] != null && "engine".equals(args[0].trim());
    }

    public static int execute(String[] args, PrintStream out, PrintStream err) {
        PrintStream stdout = out == null ? System.out : out;
        PrintStream stderr = err == null ? System.err : err;
        if (!handles(args)) {
            stderr.println("error: engine CLI expected the engine command");
            stderr.println("fix: engine help");
            return 2;
        }
        List<String> tokens = new ArrayList<>();
        for (int index = 1; index < args.length; index++) {
            if (args[index] != null) tokens.add(args[index]);
        }
        String command = tokens.isEmpty() ? "help" : tokens.get(0);
        if (command.startsWith("--")) command = "run";
        try {
            return switch (command) {
                case "help", "--help", "-h" -> {
                    stdout.print(help());
                    yield 0;
                }
                case "run" -> run(tokens, stdout, stderr);
                case "step" -> step(tokens, stdout, stderr);
                default -> {
                    stderr.println("error: Unknown engine command: " + command);
                    stderr.println("fix: engine help");
                    yield 2;
                }
            };
        } catch (Exception failure) {
            stderr.println("error: " + failure.getMessage());
            stderr.println("fix: engine help");
            return 1;
        }
    }

    public static String help() {
        return """
                Usage: engine help
                Usage: engine run [--project=<dir>] [--text=<gherkin>] [--file=<path>] [--steps=<line>] [--glue=<class>] [--name=<scenario>] [--dalton] [--feature=<path>]
                Usage: engine step --text=<step> [--argument=<text>] [--glue=<class>] [--project=<dir>]
                The engine runs steps without Cucumber. --text and --steps do not read a feature file.
                --dalton reads feature bytes from .pickleball/dalton/dalton.db. --glue is a glue class name.
                Workbench, MCP, and Gradle forward this command and do not run steps themselves.
                Logging: pkb_engine_log=run (or all). pkb_engine_log_entity limits lines. pkb_engine_log_max_bytes caps .pickleball/engine/engine.log.
                """;
    }

    private static int run(List<String> tokens, PrintStream out, PrintStream err) throws Exception {
        Path project = project(tokens);
        EngineOptions options = options(tokens, project);
        ScenarioSource source = source(tokens, project);
        RunResult result = PickleballEngine.run(source, options);
        for (ScenarioResult scenario : result.scenarios()) {
            out.println("scenario " + scenario.status() + " " + scenario.name());
            for (StepResult step : scenario.steps()) {
                out.println("step " + step.status() + " " + step.text());
            }
        }
        return result.status().isOk() ? 0 : 1;
    }

    private static int step(List<String> tokens, PrintStream out, PrintStream err) throws ClassNotFoundException {
        String text = value(tokens, "--text");
        if (text == null) {
            err.println("error: engine step requires --text");
            err.println("fix: engine step --text=<step>");
            return 2;
        }
        Path project = project(tokens);
        try (ExecutionContext context = ExecutionContext.open(EngineScenario.of("step", List.of()))) {
            context.resource("glue", options(tokens, project).glue());
            StepResult result = PickleballEngine.runStep(text, value(tokens, "--argument"), context);
            out.println("step " + result.status() + " " + result.text());
            return result.status().isOk() ? 0 : 1;
        }
    }

    private static ScenarioSource source(List<String> tokens, Path project) {
        if (has(tokens, "--dalton")) {
            return new DaltonScenarioSource(project, value(tokens, "--feature"));
        }
        String file = value(tokens, "--file");
        if (file != null) return new FileScenarioSource(project.resolve(file));
        List<String> steps = values(tokens, "--steps");
        if (!steps.isEmpty()) return new tools.dscode.engine.source.StepListScenarioSource(value(tokens, "--name"), steps);
        return new tools.dscode.engine.source.MemoryScenarioSource(value(tokens, "--text"));
    }

    private static EngineOptions options(List<String> tokens, Path project) throws ClassNotFoundException {
        List<Class<?>> glue = new ArrayList<>();
        for (String name : values(tokens, "--glue")) {
            glue.add(Class.forName(name));
        }
        return new EngineOptions(project, glue, value(tokens, "--name") == null ? "" : value(tokens, "--name"), false);
    }

    private static Path project(List<String> tokens) {
        String value = value(tokens, "--project");
        if (value == null || value.isBlank()) return Path.of("").toAbsolutePath().normalize();
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static boolean has(List<String> tokens, String flag) {
        for (String token : tokens) if (flag.equals(token)) return true;
        return false;
    }

    private static String value(List<String> tokens, String name) {
        List<String> found = values(tokens, name);
        return found.isEmpty() ? null : found.getFirst();
    }

    private static List<String> values(List<String> tokens, String name) {
        String prefix = name + "=";
        List<String> found = new ArrayList<>();
        for (int index = 0; index < tokens.size(); index++) {
            String token = tokens.get(index);
            if (token.startsWith(prefix)) found.add(token.substring(prefix.length()));
            else if (token.equals(name) && index + 1 < tokens.size()) found.add(tokens.get(++index));
        }
        return found;
    }
}
