# Pickleball Feature Map
This file maps consumer-visible capabilities to their likely implementation anchors, focused tests, executable consumer examples, and canonical documentation.
Agents must use it for navigation, verify all paths and symbols against current source, and update it when ownership, canonical examples, public syntax, or contracts change.
Implementation entries may be exact paths, package roots, class-name patterns, or search anchors. Replace broad anchors with exact canonical paths as the repository evolves.
| Capability | Implementation and search anchors | Framework tests | Maven consumer coverage | Canonical documentation |
|---|---|---|---|---|
| Build, publication, and Java compatibility | `build.gradle`; `src/main/aspectj`; search `publishing`, `shadowJar`, `aspectj`, `JavaLanguageVersion` | `src/test`; build verification | `maven-consumer-project/pom.xml`; `maven-consumer-project/mvnw`; `maven-consumer-project/mvnw.cmd`; `maven-consumer-project/.mvn/wrapper/maven-wrapper.properties`; `maven-consumer-project/src/test/java/com/example/pickleball/PickleballTests.java` | `README.md`; `docs/getting-started.md`; `docs/cucumber-compatibility.md` |
| Dynamic steps and expression execution | `src/main/java/tools/dscode/coredefinitions/DynamicSteps.java`; `src/main/java/tools/dscode/common/treeparsing`; `src/main/java/tools/dscode/pickleruntime/CucumberOptionResolver.java`; search `PhraseExecution`, `Step`, `Expression` | Search `src/test` for matching step/expression classes | `maven-consumer-project/src/test/resources/features/dynamic-steps.feature`; `forms-dynamic-steps.feature` | `docs/dynamic-steps.md` |
| Selenium navigation and element interaction | `src/main/java/tools/dscode/coredefinitions/BrowserSteps.java`; `src/main/java/tools/dscode/coredefinitions/NavigationSteps.java`; `src/main/java/tools/dscode/common/seleniumextensions/ElementWrapper.java`; `src/main/java/tools/dscode/common/domoperations/HumanInteractions.java`; `SeleniumUtils.java` | Search `src/test` for element and interaction tests | `navigation.feature`; `forms-dynamic-steps.feature`; `dialogs.feature`; `catalog-context.feature`; pages under `maven-consumer-project/src/test/resources/site` | `docs/dynamic-steps.md`; `docs/custom-element-definitions.md` |
| Custom element definitions and catalog context | Search `src/main/java` for `ElementDefinition`, `Catalog`, `Selector`, `Locator` | Search `src/test` for catalog/selector tests | `catalog-context.feature`; `forms-dynamic-steps.feature`; `site/catalog.html`; `site/forms.html` | `docs/custom-element-definitions.md`; `docs/config-files-and-resource-mapping.md` |
| Keyboard and key-expression DSL | `src/main/java/tools/dscode/common/domoperations/KeyParser.java`; mapping query classes under `src/main/java/tools/dscode/common/mappings`; search `Keyboard`, `Tokenized`, `Query` | Search `src/test` for `Key`, `Tokenized`, parser/query tests | `maven-consumer-project/src/test/resources/features/keyboard.feature`; `site/keyboard.html` | `docs/key-parser-dsl.md` |
| Mapping, parsing maps, Data Elements, and template resolution | `src/main/java/tools/dscode/coredefinitions/MappingSteps.java`; `src/main/java/tools/dscode/common/mappings/FileAndDataParsing.java`; `src/main/java/tools/dscode/common/mappings/custommappings/ValConverter.java`; `src/main/java/tools/dscode/common/dataelements` (`DataContext`, `DataContextNodeMap`, `TabularMatrix`, `DataQueryEngine`, `CollectionDataAdapter`, `CollectionQueryEngine`, `FormatQueryEngine`, `StructuredDataConverter`, `DataMaterializer`, `DataElementRuntime`, `DataResultPolicy`); `src/main/java/tools/dscode/common/treeparsing/parsedComponents/DataElementMatch.java`; `ElementMatchFactory.java`; `src/main/java/tools/dscode/common/treeparsing/parsedComponents/Phrase.java`; `src/main/java/tools/dscode/common/dataoperations/TextOp.java`; `src/main/java/tools/dscode/common/dataoperations/TextPredicateMatcher.java`; `NodeMap.java`; `ParsingMap.java`; `MappingProcessor.java`; `ValueFormatting.java`; `DataTableDefinitions.java`; `DocStringDefinitions.java`; dynamic `SAVE` under `common/treeparsing`; query classes under `common/mappings` | Consumer-hosted `MappingDataRefactorChecks.java`; `DataTableConversionChecks.java`; `DataElementPhaseOneChecks.java`; `DataElementPhaseTwoChecks.java`; `DataElementPhaseThreeChecks.java`; `DataElementPhaseFourAndSixChecks.java`; `DataElementPhaseFiveChecks.java`; search `src/test` for mapping, `NodeMap`, template, and type-preservation tests | `mapping-and-resources.feature`; `mapping-value-type-preservation.feature` (native/JSON DataTable row loops and JsonNode composition); `scenario-data-references.feature` (implicit unnamed-marker Data/Data Table lookup); `internal-framework-java-checks.feature` including `@data-element-phase-1`, `@data-element-phase-2`, `@data-element-phase-3`, `@data-element-phase-4`, `@data-element-phase-5`, and `@data-element-phase-6`; `data-element-phase-3.feature`; `calls/service-call-definitions.feature`; example configs and test data | `docs/mapping-and-templating.md`; `docs/data-values-and-elements.md`; `docs/data-element-query-runtime.md`; `docs/config-files-and-resource-mapping.md` |
| Configuration and resource lookup | `src/main/java/tools/dscode/common`; `src/main/java/tools/dscode/registry`; search `Configuration`, `Resource`, `Properties`, `Yaml` | Search `src/test` for configuration/resource tests | `maven-consumer-project/src/test/resources/configs`; `mapping-and-resources.feature`; consumer `pom.xml` profiles/properties | `docs/configuration.md`; `docs/config-files-and-resource-mapping.md`; `docs/getting-started.md` |
| Nested steps and block conditionals | Search `src/main/java` for `Nested`, `Conditional`, `Block`, `Condition` | Search `src/test` for nested/conditional tests | `nested-and-block-conditionals.feature`; `site/workflow.html` | `docs/nested-steps.md`; `docs/block-conditionals.md` |
| Component scenarios, step markers, and marker data lookup | `src/main/java/tools/dscode/coredefinitions/ModularScenarios.java`; `src/main/java/io/cucumber/core/runner/ScenarioStep.java`; `src/main/java/io/cucumber/core/runner/ScenarioStepData.java`; `src/main/java/io/cucumber/core/runner/StepExtension.java`; `src/main/java/io/cucumber/core/runner/modularexecutions/CucumberScanUtil.java`; `src/main/java/tools/dscode/common/mappings/ParsingMap.java`; search unified `RUN`, `SCENARIO:`, `COMPONENT:`, `RunKey`, `pkb_componentpath`, option filtering, ordering, limit, `Step_Marker`, marker padding, `getScenarioStepData`, and native marker argument references | `maven-consumer-project/src/test/java/tools/dscode/coredefinitions/ModularScenariosChecks.java`; `maven-consumer-project/src/test/java/io/cucumber/core/runner/ScenarioStepChecks.java`; `maven-consumer-project/src/test/java/io/cucumber/core/runner/ScenarioStepDataChecks.java` | `component-scenarios.feature`; `reusable-scenario-selection.feature`; `scenario-step-markers.feature`; `scenario-marker-data.feature`; `scenario-data-references.feature`; `calls/service-call-definitions.feature`; `site/components.html` | `docs/component-scenarios.md`; `docs/data-values-and-elements.md`; `docs/configuration.md` |
| Service-call definitions and execution | `src/main/java/tools/dscode/coredefinitions/ServiceCallSteps.java`; `src/main/java/tools/dscode/coredefinitions/ModularScenarios.java`; `src/main/java/io/cucumber/core/runner/ScenarioStep.java`; `src/main/java/tools/dscode/common/servicecalls/RestAssuredUtil.java`; mapping classes under `common/mappings`; search `RUN SERVICE CALL`, `CALL:`, `RunKey`, `pkb_callpath`, request/response, `Step_Marker`, and SOAP handling | `maven-consumer-project/src/test/java/tools/dscode/coredefinitions/ModularScenariosChecks.java`; `maven-consumer-project/src/test/java/io/cucumber/core/runner/ScenarioStepChecks.java`; search consumer checks for service, request, response, JSON, and XML tests | `service-call-execution.feature`; `reusable-scenario-selection.feature`; `scenario-step-markers.feature`; `calls/service-call-definitions.feature`; local server support under `maven-consumer-project/src/test/java`; resources under `site` | `docs/service-call-scenarios.md`; `docs/component-scenarios.md`; `docs/configuration.md`; `docs/mapping-and-templating.md` |
| Date/time utilities | `src/main/java/tools/dscode/coredefinitions/DateTimeUtilitySteps.java`; `src/main/java/tools/dscode/common/util/datetime` | `maven-consumer-project/src/test/java/tools/dscode/common/util/datetime/BusinessTemporalDeltaChecks.java`; `BusinessTimePostModifierChecks.java` | `date-time-utilities.feature`; `internal-framework-java-checks.feature`; `site/datetime.html` | `docs/date-time-utilities.md` |
| Dialog handling | `src/main/java/tools/dscode/common/browseroperations/BrowserAlerts.java`; browser steps under `src/main/java/tools/dscode/coredefinitions`; search `Alert`, `Dialog` | Search `src/test` for dialog/alert tests | `dialogs.feature`; `site/dialogs.html` | Relevant dynamic-step/custom-element documentation; add a dedicated guide if behavior grows |
| Cucumber compatibility and weaving | `src/main/aspectj/io/cucumber`; `src/main/java/io/cucumber`; `build.gradle`; search patched/extended Cucumber classes | Search compatibility tests under `src/test` | Consumer runner and all consumer features | `docs/cucumber-compatibility.md`; `docs/feature-status-notes.md` |
| Test-site server and fixtures | `maven-consumer-project/src/test/java/com/example/pickleball/support/LocalTestSite.java` or current equivalent; `maven-consumer-project/src/test/resources/site` | Consumer-side support tests if present | All browser and service-call features | `maven-consumer-project/README.md`; relevant capability guide |
| Execution configuration, profiles, and tagging | Search framework runtime/configuration packages; `build.gradle` | Search execution/config tests | `maven-consumer-project/pom.xml`; configs such as `CHROME`, `CHROME_HEADLESS`, `TAGGING`, `URL` | `docs/configuration.md`; `docs/getting-started.md` |
## Capability-change checklist
For the affected row:

1. Confirm the exact implementation classes and callers.
2. Confirm focused framework tests.
3. Confirm the canonical consumer scenario and any supporting call, config, endpoint, or page.
4. Confirm the canonical guide.
5. Update all affected surfaces.
6. Refine this map when a broad anchor can be replaced by a stable exact path.
## Maintenance rules
Update this map when:
- A capability is added, removed, renamed, or split.
- Responsibility moves between classes or modules.
- A new canonical test or consumer scenario is introduced.
- A service definition, local endpoint, page, or configuration becomes canonical.
- Public syntax, defaults, constraints, value types, or compatibility changes.
Do not update it for a purely internal refactor when all listed ownership and public contracts remain accurate.
## Internal or provisional features
### `?`-prefixed fallback keys
Status: internal/provisional; do not add to public documentation.
A root property named `?key` may provide a fallback value for `key` when the
normal value is missing, null, empty, or blank. This behavior may be revised or
deprecated, so examples and public syntax documentation must omit it unless the
project owner explicitly requests otherwise.
### Scenario marker data references and consumer-hosted internal checks
- Implementation:
  - `src/main/java/tools/dscode/coredefinitions/ModularScenarios.java`
  - `src/main/java/io/cucumber/core/runner/ScenarioStep.java`
  - `src/main/java/io/cucumber/core/runner/ScenarioStepData.java`
  - `src/main/java/tools/dscode/common/mappings/MappingProcessor.java`
  - `src/main/java/tools/dscode/common/mappings/ParsingMap.java`
  - `src/main/java/tools/dscode/common/dataelements`
  - `src/main/java/tools/dscode/common/dataoperations/TextPredicateMatcher.java`
- Public syntax and value contract:
  - `<data:marker>`
  - `<data:scenario.marker>`
  - `<data:feature.scenario.marker>`
  - marker definitions discard additional leading hyphens and whitespace after the required `---`; internal hyphens remain part of the marker name
  - marker definitions that are empty after normalization remain unnamed
  - unnamed markers remain unavailable to explicit `<data:...>` addresses, but unquoted `Data Table` and `Data` elements can select their attached arguments by source-line proximity within the same scenario
  - implicit lookup ignores nesting, prefers the nearest qualifying unnamed marker below the referencing step, and falls back to the nearest qualifying marker above it
  - mapping references resolve directly to the marker's native `DataTable` or `DocString`
  - quoted `Data` resolves a mapping key or native reference, preserves existing `JsonNode` values, and converts native `DataTable` or `DocString` values through the standard JSON converters
  - unquoted `Data Table` preserves an existing active/direct table before applying unnamed-marker lookup
  - unquoted `Data` converts the selected unnamed marker argument through the same standard JSON converters
  - Java marker lookup APIs continue to return `ScenarioStepData`
  - `<&reference>` remains exclusively a step-return lookup
  - `pkb_datapath`, defaulting to `src/test/resources/data` for named-scenario lookups
- Consumer coverage:
  - `maven-consumer-project/src/test/resources/features/scenario-data-references.feature`
  - `maven-consumer-project/src/test/resources/features/internal-framework-java-checks.feature` (`@data-element-phase-1`, `@data-element-phase-2`, `@data-element-phase-3`, `@data-element-phase-4`, `@data-element-phase-5`, `@data-element-phase-6`)
  - `maven-consumer-project/src/test/resources/features/data-element-phase-3.feature`
  - `maven-consumer-project/src/test/resources/data/data-reference-records.feature`
  - `maven-consumer-project/src/test/java/com/example/pickleball/DataReferenceSteps.java`
  - `maven-consumer-project/src/test/java/io/cucumber/core/runner/ScenarioStepChecks.java`
  - `maven-consumer-project/src/test/java/tools/dscode/common/dataelements/DataElementPhaseOneChecks.java`
  - `maven-consumer-project/src/test/java/tools/dscode/common/dataelements/DataElementPhaseTwoChecks.java`
  - `maven-consumer-project/src/test/java/tools/dscode/common/dataelements/DataElementPhaseThreeChecks.java`
  - `maven-consumer-project/src/test/java/tools/dscode/common/dataelements/DataElementPhaseFourAndSixChecks.java`
  - `maven-consumer-project/src/test/java/tools/dscode/common/dataelements/DataElementPhaseFiveChecks.java`
- Internal Java checks are compiled against the locally published dependency and
  run through:
  - `maven-consumer-project/src/test/resources/features/internal-framework-java-checks.feature`
  - `maven-consumer-project/src/test/java/com/example/pickleball/InternalFrameworkTestSteps.java`
  - `maven-consumer-project/src/test/java/com/example/pickleball/support/InternalJavaTestRunner.java`
