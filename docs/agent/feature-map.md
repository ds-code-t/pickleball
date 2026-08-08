# Pickleball Feature Map

This file maps consumer-visible capabilities to their likely implementation anchors, focused tests, executable consumer examples, and canonical documentation.
Agents must use it for navigation, verify all paths and symbols against current source, and update it when ownership, canonical examples, public syntax, or contracts change.

| Capability | Implementation and search anchors | Framework tests | Maven consumer coverage | Canonical documentation |
|---|---|---|---|---|
| Build, publication, and Java compatibility | `build.gradle`; `src/main/aspectj`; search `publishing`, `shadowJar`, `aspectj`, `JavaLanguageVersion` | `src/test`; build verification | `maven-consumer-project/pom.xml`; Maven wrappers; `PickleballTests.java` | `README.md`; `docs/getting-started.md`; `docs/cucumber-compatibility.md` |
| Dynamic steps and expression execution | `src/main/java/tools/dscode/coredefinitions/DynamicSteps.java`; `src/main/java/tools/dscode/common/treeparsing`; `CucumberOptionResolver.java` | Search `src/test` for step/expression checks | `dynamic-steps.feature`; `forms-dynamic-steps.feature` | `docs/dynamic-steps.md` |
| Selenium navigation and element interaction | `BrowserSteps.java`; `NavigationSteps.java`; `ElementWrapper.java`; `HumanInteractions.java`; `SeleniumUtils.java` | Search element/interaction tests | `navigation.feature`; `forms-dynamic-steps.feature`; `dialogs.feature`; `catalog-context.feature`; test-site pages | `docs/dynamic-steps.md`; `docs/custom-element-definitions.md` |
| Custom element definitions and catalog context | Search `src/main/java` for `ElementDefinition`, `Catalog`, `Selector`, `Locator` | Search catalog/selector tests | `catalog-context.feature`; `forms-dynamic-steps.feature`; `site/catalog.html`; `site/forms.html` | `docs/custom-element-definitions.md`; `docs/config-files-and-resource-mapping.md` |
| Keyboard and key-expression DSL | `KeyParser.java`; mapping query classes; search `Keyboard`, `Tokenized`, `Query` | Search key/query tests | `keyboard.feature`; `site/keyboard.html` | `docs/key-parser-dsl.md` |
| Mapping, parsing maps, Data Elements, templates, `file:`, marker `data:`, and rooted `data:/` | `MappingSteps.java`; `FileAndDataParsing.java`; `MappingProcessor.java`; `NodeMap.java`; `ParsingMap.java`; `ValueFormatting.java`; `common/dataelements`; query classes | Consumer-hosted mapping/Data Element checks | `mapping-and-resources.feature`; `mapping-value-type-preservation.feature`; `scenario-data-references.feature`; `data-element-*`; `internal-framework-java-checks.feature`; `data/escaped-data-records.feature`; `data/files/customerPayload.json`; service-call definitions | `docs/mapping-and-templating.md`; `docs/data-values-and-elements.md`; `docs/data-element-query-runtime.md`; `docs/config-files-and-resource-mapping.md`; `docs/configuration.md` |
| Configuration and resource lookup | `src/main/java/tools/dscode/common`; `src/main/java/tools/dscode/registry`; `PKB_props.java`; search `Configuration`, `Resource`, `Properties`, `Yaml`, `PKB_DATA_PATH` | Search configuration/resource tests | `configs`; `mapping-and-resources.feature`; `scenario-data-references.feature`; consumer `pom.xml` | `docs/configuration.md`; `docs/config-files-and-resource-mapping.md`; `docs/getting-started.md` |
| Diagnostic reporting, compressed deep trace evidence, source/Git provenance, step-origin/capability metadata, evidence retention, screenshots, fingerprints, and run comparison | `src/main/java/tools/dscode/common/reporting/diagnostic`; `src/main/aspectj/tools/dscode/common/reporting/diagnostic`; `SourceProvenance.java`; `DiagnosticStepMetadata.java`; `PlatformLogFormatter.java`; `PlatformLogAspect.aj`; `gradle/pickleball-build-provenance.gradle`; `Entry.java`; `Log.java`; `SimpleHtmlReportConverter.java`; `CurrentScenarioState.java`; search `pkb_reportingmode`, `pkb_reportretention`, `pkb_platformlog`, `pkb_gitsnapshot`, `DIAGNOSTIC_MODE`, `nativeCapabilitiesObserved` | Consumer-hosted `DiagnosticReportingChecks.java` | `internal-framework-java-checks.feature`; `InternalFrameworkTestSteps.java` | `docs/diagnostic-reporting.md`; `docs/ai-diagnostic-reporting-plan.md`; `docs/configuration.md` |
| Nested steps and block conditionals | Search `src/main/java` for `Nested`, `Conditional`, `Block`, `Condition` | Search nested/conditional tests | `nested-and-block-conditionals.feature`; `site/workflow.html` | `docs/nested-steps.md`; `docs/block-conditionals.md` |
| Component scenarios, reusable selector paths, step markers, and marker-data lookup | `src/main/java/tools/dscode/coredefinitions/ModularScenarios.java`; `ScenarioStep.java`; `ScenarioStepData.java`; `StepExtension.java`; `CucumberScanUtil.java`; search unified `RUN`, `splitEscapedPath`, `RunKey`, `pkb_componentpath`, `Step_Marker`, `getScenarioStepData`, `getScenarioMarkerData` | `maven-consumer-project/src/test/java/tools/dscode/coredefinitions/ModularScenariosChecks.java`; `ScenarioStepChecks.java`; `ScenarioStepDataChecks.java` | `component-scenarios.feature`; `reusable-scenario-selection.feature`; `run-step-parameter-variations.feature`; `scenario-step-markers.feature`; `scenario-marker-data.feature`; `scenario-data-references.feature`; `data/escaped-data-records.feature` | `docs/component-scenarios.md`; `docs/data-values-and-elements.md`; `docs/configuration.md` |
| Service-call definitions and execution | `ServiceCallSteps.java`; `ModularScenarios.java`; `ScenarioStep.java`; `RestAssuredUtil.java`; mapping classes; search `RUN SERVICE CALL`, `CALL:`, `$CALL:`, `RunKey`, `pkb_callpath`, `Step_Marker` | `ModularScenariosChecks.java`; `ScenarioStepChecks.java`; service/request/response checks | `service-call-execution.feature`; `reusable-scenario-selection.feature`; `run-step-parameter-variations.feature`; `scenario-step-markers.feature`; `calls/service-call-definitions.feature`; local server support | `docs/service-call-scenarios.md`; `docs/component-scenarios.md`; `docs/configuration.md`; `docs/mapping-and-templating.md` |
| Date/time utilities | `DateTimeUtilitySteps.java`; `common/util/datetime` | `BusinessTemporalDeltaChecks.java`; `BusinessTimePostModifierChecks.java` | `date-time-utilities.feature`; `internal-framework-java-checks.feature`; `site/datetime.html` | `docs/date-time-utilities.md` |
| Dialog handling | `BrowserAlerts.java`; browser steps; search `Alert`, `Dialog` | Search dialog/alert tests | `dialogs.feature`; `site/dialogs.html` | Dynamic-step/custom-element docs |
| Cucumber compatibility and weaving | `src/main/aspectj/io/cucumber`; `src/main/java/io/cucumber`; `build.gradle` | Search compatibility tests | Consumer runner and all consumer features | `docs/cucumber-compatibility.md`; `docs/feature-status-notes.md` |
| Test-site server and fixtures | `maven-consumer-project/src/test/java/com/example/pickleball/support/LocalTestSite.java`; `maven-consumer-project/src/test/resources/site` | Consumer-side support checks | Browser and service-call features | `maven-consumer-project/README.md`; relevant guides |
| Execution configuration, profiles, and tagging | Framework runtime/configuration packages; `build.gradle` | Search execution/config tests | `maven-consumer-project/pom.xml`; configuration resources | `docs/configuration.md`; `docs/getting-started.md` |

## Unified reusable-scenario selector contract

Named inline selectors use one grammar everywhere the shared parser is consumed:

```text
scenario
feature.scenario
feature.scenario.marker
```

Only unescaped periods delimit components:

```text
\.    literal period
\\    literal backslash
```

Examples:

```text
RUN SCENARIO: Selection fixture A
RUN SCENARIO: Reusable scenario selection.Selection fixture B
RUN COMPONENT SCENARIO: Data\.reference\.records.Escaped\.selector fixture.start\.marker
RUN "health" SERVICE CALL: Reusable service call definitions.HealthCall
CALL: HealthCall
<$CALL:ExplicitNullReturnCall>
```

The former reusable-selector labels `FEATURE:`, `SCENARIO:`, and `START:` are removed from the inline argument grammar. The standalone convenience step names such as `SCENARIO:` and `CALL:` remain public steps; they simply receive the new selector path as their argument.

Tags beginning with `@` or `%` remain tag selectors. Invocation-table options such as `pkb_name`, `pkb_featurename`, `Run Tags`, `RunType`, `RunKey`, `pkb_order`, `pkb_limit`, path overrides, and `Step_Marker` keep their existing behavior. Inline feature/scenario/marker components overwrite their corresponding table selector fields; an inline marker overrides `Step_Marker`.

## Scenario marker and data-file references

`data:` has two modes.

### Marker mode

Without a leading slash, marker addresses are right-aligned:

```text
<data:marker>
<data:scenario.marker>
<data:feature.scenario.marker>
<data:Data\.reference\.records.Customer\.record.payload\.marker>
```

Marker mapping references resolve to the attached native `DataTable` or `DocString`. Java marker APIs continue to return `ScenarioStepData`.

### File mode

A slash immediately after `data:` selects rooted file lookup:

```text
<data:/file>
<data:/files/customerPayload>
<data:/files/customerPayload.customer.orders[0].id>
```

The slash is a syntax discriminator, not an OS absolute path. Lookup uses the same already-resolved `pkb_datapath` and fallback as scenario marker data, then reuses `FileAndDataParsing` for suffix-agnostic file discovery, supported formats, and nested queries.

Structured results preserve Jackson types:

- whole JSON document -> `ObjectNode`;
- nested object -> `ObjectNode`;
- nested array -> `ArrayNode`;
- indexed object -> `ObjectNode`;
- nested scalar -> natural scalar value.

Canonical consumer fixtures:

- `maven-consumer-project/src/test/resources/data/escaped-data-records.feature`
- `maven-consumer-project/src/test/resources/data/files/customerPayload.json`
- `maven-consumer-project/src/test/resources/features/scenario-data-references.feature`
- `maven-consumer-project/src/test/resources/features/reusable-scenario-selection.feature`

## Capability-change checklist

For an affected row:

1. Confirm exact implementation classes and callers.
2. Confirm focused framework/internal checks.
3. Confirm the canonical consumer scenario and supporting resources.
4. Confirm the canonical guide.
5. Update all affected surfaces.
6. Refine broad anchors when stable exact paths are available.

## Maintenance rules

Update this map when:

- a capability is added, removed, renamed, or split;
- responsibility moves between classes/modules;
- a new canonical consumer fixture is introduced;
- public syntax, defaults, constraints, value types, or compatibility changes.

Do not update it for a purely internal refactor when all listed ownership and public contracts remain accurate.

## Internal or provisional features

### `?`-prefixed fallback keys

Status: internal/provisional; do not add to public documentation.

A root property named `?key` may provide a fallback for `key` when the normal value is missing, null, empty, or blank. This behavior may be revised or deprecated.

### Consumer-hosted internal Java checks

Internal Java checks are compiled against the locally published dependency and run through:

- `maven-consumer-project/src/test/resources/features/internal-framework-java-checks.feature`
- `maven-consumer-project/src/test/java/com/example/pickleball/InternalFrameworkTestSteps.java`
- `maven-consumer-project/src/test/java/com/example/pickleball/support/InternalJavaTestRunner.java`

The reusable selector/parser checks are in:

- `maven-consumer-project/src/test/java/tools/dscode/coredefinitions/ModularScenariosChecks.java`

The diagnostic reporting checks are in:

- `maven-consumer-project/src/test/java/tools/dscode/common/reporting/diagnostic/DiagnosticReportingChecks.java`
