# Step Overrides

Step Overrides let interactive Pickleball tooling temporarily replace the implementation selected for a matching Gherkin step without rebuilding or restarting the consumer worker. The feature is intended for investigation and experimental live authoring. With no active override, normal Cucumber glue matching and execution remain unchanged.

## Matching and replacement

Version 1 supports `REGEX` matching and `REPLACE` behavior only. Rules match the fully resolved step text before ordinary Cucumber glue lookup. A single matching override executes instead of the normal glue implementation. Zero matches fall through to ordinary Cucumber matching. Multiple matching overrides fail deterministically; Pickleball does not pick one based on registration order.

Regex capture groups are exposed to the handler as `StepOverrideContext.captures()`. DocStrings and DataTables remain separate step arguments through `StepOverrideContext.argument()` rather than becoming regex captures.

Removing a rule immediately restores normal glue fallback. Registering or compiling another rule with the same id replaces the previous implementation.

## Java handler contract

A handler implements:

```java
tools.dscode.control.override.StepOverrideHandler
```

Its single method receives a worker-side `StepOverrideContext` with the original/resolved step text, keyword, regex captures, Gherkin argument, active scenario, active `StepExtension`, and active `ParsingMap`.

Workbench authoring sends a Java source template containing the literal class-name token:

```text
{{CLASS_NAME}}
```

For example:

```java
import tools.dscode.control.api.MappingControl;
import tools.dscode.control.override.StepOverrideContext;
import tools.dscode.control.override.StepOverrideHandler;

public final class {{CLASS_NAME}} implements StepOverrideHandler {
    public Object execute(StepOverrideContext context) {
        MappingControl.put(
                "OVERRIDE",
                "experiment",
                context.captures().getFirst()
        );
        return null;
    }
}
```

The consumer worker, not the Workbench controller, substitutes a unique class name and compiles the handler with `javax.tools.JavaCompiler` against the active worker runtime classpath. Each generated implementation is loaded through its own classloader. Replacing or removing a rule drops the registry reference to the prior handler and closes its loader.

If the worker JVM has no system Java compiler, compilation is reported as `UNAVAILABLE`. Use a JDK-based worker runtime for live Java authoring.

## Workbench facade

`WorkbenchLiveSession` exposes the controller-side authoring surface:

```java
live.compileStepOverride(
        "experiment",
        "^EXPERIMENT ([A-Za-z]+)$",
        source
);

live.stepOverrides();
live.removeStepOverride("experiment");
live.clearStepOverrides();
```

These methods use the existing authenticated loopback control bridge and always target the currently owned paused scenario. They do not run Maven/Gradle, resynchronize the project, or restart the worker. After every operation `WorkbenchLiveSession` verifies that the same worker PID, bridge runtime id, and scenario id remain active and paused.

The corresponding bridge capabilities are `step_overrides` and `step_override_compile`, with management endpoints under `/v1/step-overrides`.

## MCP authoring

Workbench MCP exposes the same facade through:

```text
workbench_step_override_list
workbench_step_override_compile
workbench_step_override_remove
workbench_step_override_clear
```

`workbench_step_override_compile` accepts the override id, regex, and the same Java source template containing `{{CLASS_NAME}}`. MCP does not compile or match handlers itself; it delegates through the shared Workbench controller to the paused worker, so replacement, classloader lifetime, capture handling, and ordinary Cucumber fallback remain worker-side Pickleball behavior.

## Lifetime and cleanup

Overrides are scenario-scoped. Scenario teardown clears remaining rules, and restarting the worker provides a fresh JVM and classloader boundary. The generated-handler count is bounded per worker; restart the worker when the runtime reports that the generation limit has been reached.

Step Override effects are ordinary live effects. The override mechanism does not roll back browser state, service calls, mappings, or other changes performed by a handler.

## Validation

The focused consumer tag is:

```text
@step-override
```

For a bridge/protocol-only change, the smallest tag is `@step-override-bridge`; use `@step-override` when worker matching/compiler semantics changed too. Set `pkb_parallel=80` when practical and do not substitute `@all` for focused Workbench validation. Workbench `live-check` also compiles an override, executes override-only Gherkin, replaces the generated implementation, removes it, verifies fallback behavior, and confirms the same persistent worker context was retained.
