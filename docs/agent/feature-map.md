# Pickleball Feature Map

This file maps consumer-visible capabilities to their likely implementation anchors, focused tests, executable consumer examples, and canonical documentation. Agents must use it for navigation, verify all paths and symbols against current source, and update it when ownership, canonical examples, public syntax, or contracts change.

| Capability | Implementation and search anchors | Framework tests | Maven consumer coverage | Canonical documentation |
|---|---|---|---|---|
| Build, publication, and Java compatibility | `build.gradle`; `src/main/aspectj`; search `publishing`, `shadowJar`, `aspectj`, `JavaLanguageVersion` | `src/test`; build verification | `maven-consumer-project/pom.xml`; Maven wrappers; `PickleballTests.java` | `README.md`; `docs/getting-started.md`; `docs/cucumber-compatibility.md`; `docs/consumer-project.md` |
| Pickleball Studio isolated application, Swing workspace/editor and live runtime-control UI, workspace/file/process services, managed process lifecycle/history/output, self-contained Maven and Gradle Wrapper execution, Gradle Tooling API project/source/task models, Java/Gherkin source definition navigation, targeted private live consumer-runtime control with mapping inspection/mutation, bounded Studio-owned mapping snapshots/restore, bounded semantic event history with a desktop timeline, selected-scenario browser page/screenshot evidence, and Streamable-HTTP MCP foundation | `pickleball-studio`; `pickleball-studio/src/main/java/tools/dscode/studio/gui`; `pickleball-studio/src/main/java/tools/dscode/studio/runtime`; `pickleball-studio/src/main/java/tools/dscode/studio/workspace`; `pickleball-studio/src/main/java/tools/dscode/studio/process`; `pickleball-studio/src/main/java/tools/dscode/studio/build`; `pickleball-studio/src/main/java/tools/dscode/studio/gradle`; `pickleball-studio/src/main/java/tools/dscode/studio/language`; `pickleball-studio/src/main/java/tools/dscode/studio/mcp`; `src/main/java/tools/dscode/studio/launcher`; `gradle/pickleball-studio.gradle`; search `StudioApplication`, `StudioDesktopApplication`, `StudioDesktopSession`, `StudioFrame`, `RuntimeControlDialog`, `RuntimeEventPanel`, `RuntimeEventTimeline`, `RuntimeDesktopState`, `RuntimeBridgeService`, `RuntimeBridgeClient`, `RuntimeEvent`, `RuntimeEventPage`, `RuntimeEvidenceMcpTools`, `RuntimeMappingMcpTools`, `RuntimeBrowserEvidenceMcpTools`, `RuntimeMappingSnapshotStore`, `RuntimeMappingSnapshot`, `RuntimeScenarioStatus`, `RuntimeValueResult`, `RuntimeLaunchResult`, `WorkspaceFileService`, `ManagedProcessService`, `MavenBuildService`, `GradleBuildService`, `GradleProjectModelService`, `WorkspaceLanguageService`, `StudioMcpTools`, `runtime_scenarios`, `runtime_events`, `runtime_mapping_get`, `runtime_mapping_put`, `runtime_mapping_resolve`, `runtime_mapping_snapshot`, `runtime_mapping_snapshots`, `runtime_mapping_restore`, `runtime_browser_page`, `runtime_browser_screenshot`, `verifyBundledStudio` | `pickleball-studio/src/test/java`; `StudioDesktopSessionTest`; `StudioDesktopRuntimeControlTest`; `RuntimeEventTimelineTest`; `WorkspaceTreeBuilderTest`; `RuntimeBridgeServiceTest`; `RuntimeBridgeClientTest`; `RuntimeMappingSnapshotStoreTest`; `WorkspaceFileServiceTest`; `WorkspaceProcessServiceTest`; `ManagedProcessServiceTest`; `MavenBuildServiceTest`; `GradleBuildServiceTest`; `GradleProjectModelServiceTest`; `WorkspaceLanguageServiceTest`; `StudioMcpToolsTest`; `StudioMcpContextTest`; `:pickleball-studio:test`; `:pickleball-studio:verifyBundledStudio` | `control-bridge.feature`; consumer `ControlBridgeTestSteps.java` validates authenticated loopback scenario discovery/targeting, bounded scenario-filtered semantic event reads/cursors while paused, live `OVERRIDE` mapping baseline capture/mutation/restore plus write/read/resolve, selected-scenario page/DOM and PNG screenshot evidence, pause/fail-retry/success/resume behavior against a live scenario; the consumer dependency remains the single `tools.dscode:pickleball` artifact; runtime launch remains explicit/opt-in with random loopback ports plus per-session bearer tokens; ordinary Studio **Run Tests** stays bridge-free while desktop **Runtime > Runtime Control... > Start Control Run** opts into the same runtime service used by MCP; the desktop **Events** tab reads the same bounded event service with cursor/gap semantics and a separate 1000-event presentation bound; desktop/MCP mapping snapshot controls share the same bounded 50-per-session Studio snapshot store | `docs/pickleball-studio.md`; `docs/studio-runtime-bridge.md`; `pickleball-studio/AGENTS.md` |
| Dynamic steps and expression execution | `src/main/java/tools/dscode/coredefinitions/DynamicSteps.java`; `src/main/java/tools/dscode/common/treeparsing`; `CucumberOptionResolver.java` | Search `src/test` for step/expression checks | `dynamic-steps.feature`; `forms-dynamic-steps.feature` | `docs/dynamic-steps.md` |
| Dynamic control API, mapping emulation, semantic interception hooks, consumer-side Studio bridge runtime, bounded mapping snapshot transport/restore, bounded semantic bridge evidence, and read-only browser page/screenshot evidence | `pickleball-control-api`; `pickleball-control-api/src/main/java/tools/dscode/control/bridge`; `src/main/java/tools/dscode/common/control`; `src/main/aspectj/tools/dscode/common/control/ControlRuntimeAspect.aj` (annotation-style woven aspect; `.java` path is an inert Javadoc compatibility placeholder); `DynamicExecution.java`; `StepExtension.java`; `MappingProcessor.java`; `ParsingMap.java`; `NodeMap.java`; search `ControlHook`, `ControlValueEvent`, `ControlExecutionScope`, `ControlRuntime.addObserver`, `DynamicControl`, `ControlBridgeBootstrap`, `ControlBridgeRuntime`, `ControlBridgeCoordinator`, `ControlBridgeEventRecorder`, `ControlBridgeEvent`, `ControlBridgeEventPage`, `ControlBridgeMappingSnapshot`, `ControlBridgeMappingSnapshotResult`, `ControlBridgeBrowserPageResult`, `ControlBridgeBrowserScreenshotResult`, `ControlBridgeScenarioStatus`, `ControlBridgeValueResult`, `MappingControl`, `MappingContext`, `MappingSnapshot`, `BEFORE_MAPPING_RESOLVE`, `BEFORE_MAPPING_LOOKUP`, `BEFORE_MAPPING_WRITE`, `BEFORE_DOM_ACCESS`, `BEFORE_DRIVER_COMMAND` | Build/weaving verification | Consumer-hosted `DynamicControlApiChecks.java` and `ControlRuntimeObserverChecks.java` through `internal-framework-java-checks.feature`; `control-bridge.feature`; `ControlBridgeTestSteps.java` covers explicit scenario targeting, bounded semantic event history/cursors, direct mapping get/put/resolve, materialized ordinary-NodeMap snapshot/restore, selected-scenario browser page/screenshot evidence, and retry-friendly detached execution; `maven-consumer-project/pom.xml` verifies the APIs through the single main Pickleball dependency | `docs/dynamic-control-api.md`; `docs/studio-runtime-bridge.md` |
| Selenium navigation and element interaction | `BrowserSteps.java`; `NavigationSteps.java`; `ElementWrapper.java`; `HumanInteractions.java`; `SeleniumUtils.java` | Search element/interaction tests | `navigation.feature`; `forms-dynamic-steps.feature`; `dialogs.feature`; `catalog-context.feature`; test-site pages | `docs/dynamic-steps.md`; `docs/custom-element-definitions.md` |
| Custom element definitions and catalog context | Search `src/main/java` for `ElementDefinition`, `Catalog`, `Selector`, `Locator` | Search catalog/selector tests | `catalog-context.feature`; `forms-dynamic-steps.feature`; `site/catalog.html`; `site/forms.html` | `docs/custom-element-definitions.md`; `docs/config-files-and-resource-mapping.md` |
| Keyboard and key-expression DSL | `KeyParser.java`; mapping query classes; search `Keyboard`, `Tokenized`, `Query` | Search key/query tests | `keyboard.feature`; `site/keyboard.html` | `docs/key-parser-dsl.md` |
| Mapping, parsing maps, directives, pipelines, Data Elements, templates, `file:`, marker `data:`, rooted `data:/`, and live control/snapshot integration | `MappingSteps.java`; `FileAndDataParsing.java`; `MappingProcessor.java`; `NodeMap.java`; `ParsingMap.java`; `ParsingMap.MappingDirectiveResolver`; `ValueFormatting.java`; `ValConverter.java`; `common/dataelements`; `Tokenized.java`; search `~unresolved;`, `~unquoted;`, `~merge;`, `~JSON;`, `~^^`, `value:`, `::` | Consumer-hosted `MappingDataRefactorChecks.java` and Data Element checks | `mapping-and-resources.feature`; `mapping-value-type-preservation.feature`; `scenario-data-references.feature`; `data-element-*`; `internal-framework-java-checks.feature`; `data/escaped-data-records.feature`; `data/files/customerPayload.json`; service-call definitions | `docs/mapping-and-templating.md`; `docs/data-values-and-elements.md`; `docs/data-element-query-runtime.md`; `docs/config-files-and-resource-mapping.md`; `docs/configuration.md` |
| Configuration and resource lookup | `ParsingMap.java` (`CONFIGS_MAP_ROOT`, `DEFAULT_CONFIG_PATH`, `initializeConfigs`); `FileAndDataParsing.java`; `PKB_props.java`; `PkbPropertyValueNormalizer.java`; `PkbSystemPropertyValueAspect.aj`; search `pkb_configpath`, `PKB_CONFIG_PATH`, `PKB_DATA_PATH` | `PkbPropertyValueNormalizerChecks.java`; `ProfileConfigurationChecks.java`; search configuration/resource tests | `configuration-system-properties.feature`; `profiles.yaml`; `profiles_local.yaml`; `pickleball.properties`; `pickleball_local.properties`; `configs`; `mapping-and-resources.feature`; `scenario-data-references.feature`; consumer runner | `docs/configuration.md`; `docs/getting-started.md`; `docs/ai-run-configuration.md`; `docs/config-files-and-resource-mapping.md`; `docs/consumer-project.md` |
| Consumer dependency guidance export, reference snapshot, and `.pickleball` lifecycle | `DiagnosticCli.java`; `settings.gradle`; `gradle/consumer-guidance.gradle`; `scripts/sync_consumer_guidance.py`; `scripts/verify_agent_contract.py`; packaged guidance index; search `export-guidance`, `GUIDANCE-MANIFEST.json`, `maven-consumer-project/`, `.pickleball`, `ensureGuidanceIgnored` | Consumer-hosted `PickleballGuidanceChecks.java` | `maven-consumer-project/AGENTS.md`; `maven-consumer-project/.gitignore`; canonical `pom.xml`, runner, `LocalTestSite`, feature/call/config/data/site resources used to generate the reference snapshot; consumer guidance contract checks | `docs/consumer-agent-guide.md`; `docs/consumer-project.md`; `docs/README.md`; `docs/agent/README.md` |
| Diagnostic reporting, sparse-first agent navigation, controlled reruns, CLI comparison/rebuild, compressed deep trace evidence, source/Git provenance, step-origin/capability metadata, evidence retention, screenshots, fingerprints, and run comparison | `src/main/java/tools/dscode/common/reporting/diagnostic`; `src/main/aspectj/tools/dscode/common/reporting/diagnostic`; `DiagnosticCli.java`; packaged consumer guidance under `src/main/resources/META-INF/pickleball/guidance`; `scripts/sync_consumer_guidance.py`; `DiagnosticRunComparator.java`; `VisualFingerprintComparator.java`; `DiagnosticIndexRebuilder.java`; `SourceProvenance.java`; `ScenarioIdentity.java`; `DiagnosticStepMetadata.java`; `PlatformLogFormatter.java`; `PlatformLogAspect.aj`; `gradle/pickleball-build-provenance.gradle`; `Entry.java`; `Log.java`; `SimpleHtmlReportConverter.java`; `CurrentScenarioState.java`; `PKB_props.java`; `PickleballProfiles.java`; search `pkb_runvars`, `pkb_run_profile`, `runProfileFingerprint`, `directRunProfile`, `DIAGNOSTIC_MODE`, `nativeCapabilitiesObserved`, `comparisonToPrevious`, `decodedPixelsExactlyEqual`, `failureSignatureVersion`, `failureSiteKey` | Consumer-hosted `DiagnosticReportingChecks.java`; `Diagnostic213CompletionChecks.java`; `PickleballGuidanceChecks.java`; `ProfileConfigurationChecks.java` | `internal-framework-java-checks.feature`; `diagnostic-reporting-validation.feature`; `configuration-system-properties.feature`; `InternalFrameworkTestSteps.java` | `docs/consumer-agent-guide.md`; `docs/consumer-project.md`; `docs/diagnostic-reporting.md`; `docs/ai-diagnostic-reporting-plan.md`; `docs/ai-run-configuration.md`; `docs/diagnostic-lineage-metadata.md`; `docs/configuration.md`; root `AGENTS.md` diagnostic protocols |
| Nested steps and block conditionals | Search `src/main/java` for `Nested`, `Conditional`, `Block`, `Condition` | Search nested/conditional tests | `nested-and-block-conditionals.feature`; `site/workflow.html` | `docs/nested-steps.md`; `docs/block-conditionals.md` |
| Component scenarios, reusable selector paths, step markers, and marker-data lookup | `ModularScenarios.java`; `ScenarioStep.java`; `ScenarioStepData.java`; `StepExtension.java`; `CucumberScanUtil.java`; search unified `RUN`, `splitEscapedPath`, `RunKey`, `pkb_componentpath`, `Step_Marker`, `getScenarioStepData`, `getScenarioMarkerData` | `ModularScenariosChecks.java`; `ScenarioStepChecks.java`; `ScenarioStepDataChecks.java` | `component-scenarios.feature`; `reusable-scenario-selection.feature`; `run-step-parameter-variations.feature`; `scenario-step-markers.feature`; `scenario-marker-data.feature`; `scenario-data-references.feature`; `data/escaped-data-records.feature` | `docs/component-scenarios.md`; `docs/data-values-and-elements.md`; `docs/configuration.md` |
| Service-call definitions and execution | `ServiceCallSteps.java`; `ModularScenarios.java`; `ScenarioStep.java`; `RestAssuredUtil.java`; mapping classes; search `RUN SERVICE CALL`, `CALL:`, `$CALL:`, `RunKey`, `pkb_callpath`, `Step_Marker` | `ModularScenariosChecks.java`; `ScenarioStepChecks.java`; service/request/response checks | `service-call-execution.feature`; `reusable-scenario-selection.feature`; `run-step-parameter-variations.feature`; `scenario-step-markers.feature`; `calls/service-call-definitions.feature`; local server support | `docs/service-call-scenarios.md`; `docs/component-scenarios.md`; `docs/configuration.md`; `docs/mapping-and-templating.md` |
| Date/time utilities | `DateTimeUtilitySteps.java`; `common/util/datetime` | `BusinessTemporalDeltaChecks.java`; `BusinessTimePostModifierChecks.java` | `date-time-utilities.feature`; `internal-framework-java-checks.feature`; `site/datetime.html` | `docs/date-time-utilities.md` |
| Dialog handling | `BrowserAlerts.java`; browser steps; search `Alert`, `Dialog` | Search dialog/alert tests | `dialogs.feature`; `site/dialogs.html` | Dynamic-step/custom-element docs |
| Cucumber compatibility and weaving | `src/main/aspectj/io/cucumber`; `src/main/java/io/cucumber`; `build.gradle` | Search compatibility tests | Consumer runner and all consumer features | `docs/cucumber-compatibility.md`; `docs/feature-status-notes.md` |
| Test-site server and fixtures | `maven-consumer-project/src/test/java/com/example/pickleball/support/LocalTestSite.java`; `maven-consumer-project/src/test/resources/site` | Consumer-side support checks | Browser and service-call features | `docs/consumer-project.md`; relevant guides |
| Execution configuration, named/composite profiles, controlled RunVars, canonical run profiles, diagnostic lineage, ReportPortal aliases, and tagging | `PickleballRunner.java`; `PickleballProfiles.java`; `PKB_props.java`; `SensitiveConfiguration.java`; `DynamicSuiteConfigUtils.java`; `ReportPortalBridge.java`; `ConfigurationProvenance.java`; search `pkb_profile`, `pkb_runvars`, `pkb_runvars.`, `pkb_run_profile`, `pkb_configpath`, `pkb_profile_`, `default_profile` | Consumer-hosted `ProfileConfigurationChecks.java` | `maven-consumer-project/src/test/resources/profiles.yaml`; `maven-consumer-project/src/test/resources/profiles_local.yaml`; `configuration-system-properties.feature`; `pickleball.properties`; `pickleball_local.properties`; `internal-framework-java-checks.feature`; consumer runner/pom | `docs/consumer-project.md`; `docs/configuration.md`; `docs/getting-started.md`; `docs/ai-run-configuration.md`; `docs/diagnostic-lineage-metadata.md`; `docs/diagnostic-reporting.md` |

## Pickleball profile and controlled-run contract

Normal project configuration resolves before profile selection. Public normal source precedence, from strongest to weakest, is JVM system properties, `globalTestProperties()`, `pickleball_local.properties`, `pickleball.properties`, then `globalTestDefaults()`. The resolved Pickleball RunVars form the in-memory `default_profile`.

`profiles.yaml` and `profiles_local.yaml` add named profile definitions with property-level deep merge. `pkb_profile` accepts one or more comma-separated names and composes them left-to-right. Later selected profiles win. Profile objects remain available as template-reference namespaces.

`pkb_runvars` is the public controlled-run input. It accepts either one compact assignment string or expanded `pkb_runvars.<pkb_var>` members; the forms cannot be mixed. A selected YAML/inline profile may also provide `pkb_runvars` as an assignment string or RunVar map. When controlled RunVars are active, optional normal RunVars do not leak into the controlled execution.

A named profile or controlled run inherits only missing project execution-context RunVars: `pkb_glue`, `pkb_features`, `pkb_datapath`, `pkb_callpath`, `pkb_componentpath`, and `pkb_configpath`. An explicit blank/null member suppresses inheritance and remains a replayable blank tombstone in the final canonical profile so existing subsystem fallback semantics are preserved. The literal compact value `null` has no special meaning.

After template resolution, the final effective RunVars are always serialized deterministically into `pkb_run_profile`. `pkb_run_profile` is canonical derived output only; compact or expanded external input is rejected. Sensitive nonblank values serialize through `${protected:<pkb-key>}`; genuinely blank sensitive values stay blank.

`pkb_configpath` is one of the inherited execution-context RunVars. It selects the physical/classpath source loaded after RunVar resolution. Runtime mappings support recommended `<config:...>` syntax plus legacy `<configs...>` compatibility. Missing/blank config path uses the historical Java fallback `configs`. Config mappings do not participate in resolving `default_profile`, named profiles, `pkb_runvars`, or `pkb_run_profile`.

Diagnostic lineage keys `pkb_investigation_id`, `pkb_run_purpose`, `pkb_parent_run_id`, `pkb_baseline_run_id`, and `pkb_changed_variables` are metadata rather than RunVars. They are supplied separately, survive controlled mode, and do not participate in `pkb_run_profile` or `runProfileFingerprint`. The diagnostic JSON compatibility field `directRunProfile` continues to indicate direct controlled execution.

Agents launching tests with known settings should default to `pkb_runvars`. For diagnostic replay, reuse retained `runProfile` through `pkb_runvars`, change only intended RunVars, pass lineage separately, and verify `runProfileFingerprint`. See `docs/ai-run-configuration.md`.

Resource-path normalization across `pkb_features`, `pkb_datapath`, `pkb_callpath`, `pkb_componentpath`, and `pkb_configpath` is intentionally deferred core technical debt documented in root `AGENTS.md`. Do not normalize those existing path semantics opportunistically.

## Unified reusable-scenario selector contract

Named inline selectors use one grammar everywhere the shared parser is consumed:

```text
scenario
feature.scenario
feature.scenario.marker
```

Only unescaped periods delimit components:

```text
\\.    literal period
\\\\    literal backslash
```

Examples:

```text
RUN SCENARIO: Selection fixture A
RUN SCENARIO: Reusable scenario selection.Selection fixture B
RUN COMPONENT SCENARIO: Data\\.reference\\.records.Escaped\\.selector fixture.start\\.marker
RUN "health" SERVICE CALL: Reusable service call definitions.HealthCall
CALL: HealthCall
<$CALL:ExplicitNullReturnCall>
```

The former reusable-selector labels `FEATURE:`, `SCENARIO:`, and `START:` are removed from the inline argument grammar. Standalone convenience step names such as `SCENARIO:` and `CALL:` remain public steps and receive the selector path as their argument.

Tags beginning with `@` or `%` remain tag selectors. Invocation-table options such as `pkb_name`, `pkb_featurename`, `Run Tags`, `RunType`, `RunKey`, `pkb_order`, `pkb_limit`, path overrides, and `Step_Marker` keep their existing behavior. Inline feature/scenario/marker components overwrite their corresponding table selector fields; an inline marker overrides `Step_Marker`.

## Scenario marker and data-file references

`data:` has two modes.

### Marker mode

Without a leading slash, marker addresses are right-aligned:

```text
<data:marker>
<data:scenario.marker>
<data:feature.scenario.marker>
<data:Data\\.reference\\.records.Customer\\.record.payload\\.marker>
```

Marker mapping references resolve to the attached native `DataTable` or `DocString`. Java marker APIs continue to return `ScenarioStepData`.

### File mode

A slash immediately after `data:` selects rooted file lookup:

```text
<data:/file>
<data:/files/customerPayload>
<data:/files/customerPayload.customer.orders[0].id>
```

The slash is a syntax discriminator, not an OS absolute path. Lookup uses the resolved `pkb_datapath` and fallback, then reuses `FileAndDataParsing` for suffix-agnostic file discovery, supported formats, and nested queries.

Structured results preserve Jackson types: whole/nested objects remain `ObjectNode`, arrays remain `ArrayNode`, and nested scalars retain natural scalar values.

Canonical consumer fixtures include `data/escaped-data-records.feature`, `data/files/customerPayload.json`, `scenario-data-references.feature`, and `reusable-scenario-selection.feature`.

## Mapping directive contract

`ParsingMap.MappingDirectiveResolver` owns the consumer-visible directive layer on top of existing mapping resolution. Keep normal map precedence, source-qualified lookup, structured whole-value preservation, and both default bookend styles compatible while extending mapping references with these forms:

```text
~^^ ... ^^~
<source~JSON;>
<source~JSON;~XML-STRING;>
<source~unresolved;>
"<source~unquoted;>"
destination~merge;
destination~JSON;~merge;
<source::query>
<source~JSON;::items[<idx>].name>
<value:literal>
<^~NULL~^>
<^~NAN~^>
<^~INF~^>
<^~-INF~^>
<^~TAB~^>
<^~EMPTY~^>
```

Supported suffixes are `UNRESOLVED`, `UNQUOTED`, `MERGE`, `JSON`, `JSON-STRING`, `XML`, `XML-STRING`, `YAML`, `YAML-STRING`, `MAP`, `LIST`, `SET`, `MULTIMAP`, `DATATABLE`, `DOCSTRING`, `DATA`, and `STRING`. Conversion chains execute left-to-right. The same suffix parser is shared, but placement is carrier-specific: references accept conversions plus `UNRESOLVED` and directly quoted `UNQUOTED`; table row destination keys accept conversions plus `UNRESOLVED` and `MERGE`; `MAP "key" TEXT/OBJECT VALUE` destination keys accept `MERGE`; structured object keys and directive-only wrappers accept conversions only. A single structured object key consisting only of conversion directives converts the whole wrapped value. `MERGE` is never a reference or structured-object conversion directive.

Pipeline splitting is quote/bracket aware so `::` inside a structured string literal is not a separator. Missing/invalid query results are errors, not silent null results. `~unresolved;` preserves mapping references returned by the carrier while allowing values needed to form an outer source/query to resolve. `~unquoted;` removes one directly surrounding matching quote pair for raw insertion. Masks hide their contents from the mapping grammar and are removed at final restoration.

`NodeMap.put(String,Object)` owns `~merge;` semantics. Strip the suffix and call the ordinary `get(baseKey)` so existing latest-value and explicit-query selection semantics remain authoritative. A null incoming value is a no-op; a null get result falls back to normal put. `ObjectNode + ObjectNode` recursively mutates the selected object, `ArrayNode + ArrayNode` appends in place, nested object/object and array/array fields recurse/append, and other nested field collisions take the incoming field including JSON null. Any incompatible top-level type combination is a descriptive error. Do not implement merge as copy-on-write or as a second selection grammar: future gets must observe mutation of the same selected container without creating another history entry.

The old `~unquote` suffix and former `ValConverter` marker grammar (`~JSON~`, `~MAP~`, `~STRING~`, `~INT~`, `~RESOLVE~`, colon marker forms, and related aliases) are removed and must fail with migration guidance. `<&...>` remains compatibility syntax but logs a deprecation warning. Canonical executable coverage is `MappingDataRefactorChecks.java`, the `@mapping-directives` and `@mapping-merge` scenarios in `mapping-and-resources.feature`, and the umbrella `internal-framework-java-checks.feature`. Human syntax is canonical in `docs/mapping-and-templating.md`.

## Capability-change checklist

For an affected row:

1. Confirm exact implementation classes and callers.
2. Confirm focused framework/internal checks.
3. Confirm the canonical consumer scenario and supporting resources.
4. Confirm the canonical guide.
