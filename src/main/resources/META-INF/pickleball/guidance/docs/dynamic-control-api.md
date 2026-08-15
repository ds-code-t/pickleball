# Dynamic Control API

Pickleball exposes a small core interception contract plus a separately organized `pickleball-control-api` source module for dynamic tooling. The control API classes are bundled into the main `tools.dscode:pickleball` artifact; consumers do not add a second Maven dependency. The source module is intentionally independent of MCP, Spring AI, GUIs, and process orchestration so those layers can be added without coupling them to Pickleball core.

## Artifact

All consumers, including tooling that uses dynamic control, depend only on Pickleball:

```xml
<dependency>
  <groupId>tools.dscode</groupId>
  <artifactId>pickleball</artifactId>
  <version>2.1.6</version>
  <scope>test</scope>
</dependency>
```

`pickleball-control-api` remains a separate Gradle source module inside the Pickleball repository for organization and architectural separation. It is not published as a separate Maven artifact. Its `tools.dscode.control.api` classes are bundled into the main shaded Pickleball JAR and its Java sources are included in the main Pickleball sources JAR.

## Compatibility rule

Installing Pickleball with no control handler and never calling the bundled control API preserves normal execution behavior. Normal scenario traversal continues to create, inherit, resolve, and mutate its `ParsingMap`/`NodeMap` state exactly as before. Mapping interception advice immediately proceeds with the original key/value/result when no handler is installed.

Dynamic mapping contexts, scoped map replacement, mapping snapshots, and value interception are opt-in control operations. They are never activated merely because the bundled control API classes are present on the classpath.

## Dynamic execution

`DynamicControl` creates and executes Cucumber/Pickleball steps against the currently active Pickleball test context. Detached calls reuse the active TestCase, glue, browser/service state, and other live scenario resources, but the exploratory step is not inserted into the scenario's parent/child/sibling traversal.

```java
ControlCallResult<Object> result = DynamicControl.executeStep(
        ", verify \"ready\" equals \"ready\""
);
```

A call failure is returned as `ControlCallResult` with status `FAILED` rather than being propagated through `CurrentScenarioState.runningStep`. This lets a controller try another action without automatically failing or terminating the paused scenario. Effects that occurred before failure are not rolled back. Detached failures also suppress the runner's raw `Throwable.printStackTrace()` output; the structured `ControlError` still retains the failure type, message, and stack trace, and normal scenario execution keeps its existing console behavior. This suppression is limited to the raw stack-trace dump; ordinary Pickleball log entries emitted by the attempted step before it fails remain available.

`createSteps` and `executeSteps` deliberately continue after individual failures and return one result per request. `executePickle` runs the compiled steps from a parsed Cucumber `Pickle`, while `executeTree` walks a temporary `StepExtension` tree in pre-order without entering normal scenario traversal.

Dynamic execution currently requires an active Pickleball test context. Calls made without one return `UNAVAILABLE` rather than synthesizing a Cucumber runtime.

## Caller-defined mapping contexts

Detached execution can optionally use an exact caller-defined set of mapping sources instead of inheriting the live scenario `ParsingMap`.

```java
NodeMap values = MappingControl.nodeMap(
        MapConfigurations.MapType.OVERRIDE_MAP,
        Map.of("status", "READY")
);

MappingContext context = MappingControl.single(values);

ControlCallResult<Object> result = DynamicControl.executeStep(
        ", verify \"<status>\" equals \"READY\"",
        context
);
```

`MappingControl.single(nodeMap)` supplies exactly one external `NodeMap` resolution source. Pickleball may still create temporary execution-local phrase state required to execute the dynamic step, but it does not pull the live scenario's STEP, PASSED, EXAMPLE, RUN, SINGLETON, DEFAULT, or other maps into that detached call.

Convenience contexts are provided for common experiments:

```java
MappingContext isolated = MappingControl.overrideOnly(
        Map.of("status", "READY")
);

MappingContext minimalWithGlobals = MappingControl.overrideWithGlobals(
        Map.of("status", "READY")
);

MappingContext custom = MappingControl.custom(map1, map2, map3);
```

`overrideWithGlobals` contains only a fresh OVERRIDE map followed by a detached snapshot of the current GLOBAL node. The snapshot prevents exploratory writes from modifying the shared live globals. `custom` preserves caller-specified map-type resolution order; multiple maps of the same type preserve their insertion order within that type.

The same `MappingContext` can be supplied to `executeStep`, `executeSteps`, `executePickle`, or `executeTree`. Its `NodeMap` instances are reused across those calls, so deliberate writes to those isolated maps can persist across an investigation without leaking into the live scenario.

A `MappingContext` controls the `NodeMap` sources used for ordinary mapping lookup. Existing explicit Pickleball resolver behavior such as `file:`, `data:`, and `pkb_*` remains available because those forms are handled outside ordinary `NodeMap` traversal. When a controller needs a fully emulated result for one of those forms, it can redirect the lookup input or replace the resolution result through the mapping interception hooks.

## Direct Mapping control

`MappingControl` exposes retry-friendly access to the mapping structures already used by Pickleball:

```java
MappingControl.current();
MappingControl.currentNodeMap("OVERRIDE");
MappingControl.currentNodeMap("RUN");
MappingControl.currentNodeMap("STEP");
MappingControl.currentNodeMap("PARENT.STEP");
MappingControl.currentNodeMap("SCENARIO");

MappingControl.get("OVERRIDE", "customer.status");
MappingControl.put("OVERRIDE", "customer.status", "ACTIVE");
```

These methods return `ControlCallResult` rather than propagating ordinary exploratory errors.
`currentNodeMapCopy(reference)` and `copy(nodeMap)` create detached materialized copies when a controller wants to seed an isolated context from live RUN/STEP/SCENARIO state without sharing the original mutable `NodeMap` reference.

The existing OVERRIDE map is especially useful for low-cost hypothesis testing because it is normally first in Pickleball's resolution order. `overrideScope` temporarily mutates the live thread's existing OVERRIDE `NodeMap` and restores that same map object to its previous JSON state when closed:

```java
var scoped = MappingControl.overrideScope(Map.of(
        "customer.status", "ACTIVE"
));

if (scoped.successful()) {
    try (OverrideScope ignored = scoped.value()) {
        // run exploratory calls against the temporary live override
    }
}
```

Restoring the existing object instead of replacing the ThreadLocal reference is important because active `ParsingMap` instances may already hold that `NodeMap` reference.

## Temporarily swapping the running ParsingMap

`MappingControl.useCurrent(context)` can temporarily replace the currently running `ParsingMap`'s map references. `MappingScope.close()` restores the exact previous live `NodeMap` objects and resolution order, including specialized maps such as live Data Element contexts.

```java
var scope = MappingControl.useCurrent(context);
if (scope.successful()) {
    try (MappingScope ignored = scope.value()) {
        // direct calls in this block see the caller-defined map set
    }
}
```

`MappingControl.withCurrent(context, action)` provides the same behavior with automatic restoration around one action.

This is distinct from snapshot restore: a live mapping scope preserves and reinstalls the original object references rather than materializing them into plain JSON maps.

## Mapping snapshots

`MappingControl.snapshot(...)` materializes the current resolution sources into a versioned JSON-friendly `MappingSnapshot`. Snapshots can be saved and loaded from files:

```java
MappingSnapshot snapshot = MappingControl.snapshotCurrent().value();
MappingControl.saveSnapshot(snapshot, Path.of("mapping-state.json"));

MappingSnapshot loaded = MappingControl.loadSnapshot(
        Path.of("mapping-state.json")
).value();

MappingContext context = MappingControl.fromSnapshot(loaded).value();
```

A restored snapshot is intended for inspection and emulation. Ordinary JSON-backed `NodeMap` values round-trip naturally. Specialized live maps such as `DataContextNodeMap` are captured as their materialized state; a file cannot recreate their original live cursor/reference relationship. Use `MappingScope` when an exact live-object swap/restore is required.

## Resolution explanation

`MappingControl.explain(parsingMap, key)` walks ordinary resolution sources in their active order and reports which maps were inspected and which map supplied the first non-null value. This gives controller tooling a compact way to answer questions such as "why did this key resolve from STEP instead of RUN?" without dumping the entire map state.

## Mapping interception hooks

Core semantic hooks now include:

- `BEFORE_MAPPING_RESOLVE` / `AFTER_MAPPING_RESOLVE`;
- `BEFORE_MAPPING_LOOKUP` / `AFTER_MAPPING_LOOKUP`;
- `BEFORE_MAPPING_WRITE` / `AFTER_MAPPING_WRITE`.

As with the other hooks, `onHook` can observe or pause execution by blocking. Safe mapping boundaries also honor `ControlDecision.SKIP`: a skipped resolve returns the unresolved input, a skipped lookup behaves as a miss, and a skipped write does not mutate the target `NodeMap`.

`ControlHookHandler` remains a functional interface. Existing lambdas only implement `onHook`. Controllers that need value replacement can additionally override `onValue(ControlValueEvent)`:

```java
ControlHookHandler handler = new ControlHookHandler() {
    @Override
    public ControlDecision onHook(ControlEvent event) {
        return ControlDecision.CONTINUE;
    }

    @Override
    public Object onValue(ControlValueEvent event) {
        if (event.hook() == ControlHook.BEFORE_MAPPING_LOOKUP
                && event.role().equals("key")
                && event.value().equals("alias")) {
            return "actualKey";
        }
        if (event.hook() == ControlHook.AFTER_MAPPING_RESOLVE
                && event.role().equals("result")) {
            return emulateValue(event.value());
        }
        return event.value();
    }
};
```

Mapping value roles are intentionally simple:

- `input` — full text/value resolution input;
- `key` — mapping lookup or write key;
- `value` — value about to be written;
- `result` — lookup or full resolution result.

Handler exceptions are isolated by `ControlRuntime`; the original value is retained and the handler failure is available through `getLastHandlerFailure()`.
Hook dispatch is also re-entrancy guarded: mapping reads/writes performed by the handler itself do not recursively invoke the handler again.

## Other semantic control hooks

The same core contract includes scenario start/end, before/after normal and detached steps, dynamic phrases, DOM synchronization, blur, page/entity readiness, framework fixed waits, WebElement access, browser interactions, WebDriver commands, service-call boundaries, and RemoteWebDriver construction.

Handlers are synchronous. A handler can pause execution simply by blocking in `onHook` until its controller allows it to continue. `ControlDecision.SKIP` is honored at the safe Phase 1 boundaries documented above. Observational browser/service/driver hooks can pause and inspect but do not fabricate return values for arbitrary external operations.

When no handler is installed, all interception paths immediately retain the original Pickleball behavior.

## Detached running-step scope

Detached dynamic phrases internally need `GlobalState.getRunningStep()` to point at the temporary step while the underlying Pickleball dynamic parser executes. `ControlExecutionScope` provides a thread-local override for that purpose. The override exists only for the duration of the detached call and is restored in `finally` cleanup.

The active scenario's `currentPhrase` is also restored after an exploratory call, including after a failed phrase. Persistent browser, service, or deliberately live Mapping effects are not automatically rolled back.

## Gherkin object utilities

`GherkinControl` provides text-oriented convenience access to the same Cucumber Gherkin parser used by Pickleball:

```java
ControlCallResult<Feature> parsed = GherkinControl.parseFeature(featureText);
List<Pickle> scenarios = GherkinControl.scenarios(parsed.value());
List<Step> steps = GherkinControl.steps(scenarios.getFirst());
String argument = GherkinControl.argumentText(steps.getFirst());
```

It returns native Cucumber feature/pickle/step objects rather than introducing a second Gherkin model.

## Step construction and relationships

`DynamicControl.createStep` resolves an arbitrary step against the active glue without executing it. `cloneStep` creates a detached single-step clone with replacement text. `addChild` / `addChildren` maintain direct parent, sibling, nesting, and ParsingMap relationships when composing temporary structures. `executeTree` executes those structures for effect in pre-order; it intentionally does not invoke `CurrentScenarioState.runStep`, so scenario traversal/failure bookkeeping remains isolated from exploratory calls.

These helpers are preferred over mutating `parentStep`, `childSteps`, `previousSibling`, and `nextSibling` directly.

## Error handling

The control API distinguishes the result of an exploratory call from the scenario's own result:

- `SUCCESS` — the control call completed.
- `FAILED` — the attempted action threw or could not execute; `ControlError` contains type, message, and stack trace.
- `UNAVAILABLE` — the required live Pickleball context does not exist.

A failed exploratory call does not by itself update `CurrentScenarioState` hard/soft failure state. If a caller intentionally wants normal scenario failure semantics, it should execute through normal scenario traversal rather than the detached control API.

## Scope of this phase

This foundation intentionally does not include MCP, Spring AI, a controller process, workspace isolation, build orchestration, a GUI, Java-agent IPC, or general JVM hot replacement. Those components can consume this API and hook contract later without changing the normal core execution model.
