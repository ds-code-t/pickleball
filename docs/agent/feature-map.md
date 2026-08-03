# Pickleball Feature Map

This file maps consumer-visible capabilities to their likely implementation anchors, focused tests, executable consumer examples, and canonical documentation.

Agents must use it for navigation, verify all paths and symbols against current source, and update it when ownership, canonical examples, public syntax, or contracts change.

Implementation entries may be exact paths, package roots, class-name patterns, or search anchors. Replace broad anchors with exact canonical paths as the repository evolves.

| Capability | Implementation and search anchors | Framework tests | Maven consumer coverage | Canonical documentation |
|---|---|---|---|---|
| Build, publication, and Java compatibility | `build.gradle`; `src/main/aspectj`; `src/main/java/io/cucumber`; search `publishing`, `shadowJar`, `aspectj`, `JavaLanguageVersion` | `src/test`; build verification | `maven-consumer-project/pom.xml`; `maven-consumer-project/mvnw`; `maven-consumer-project/mvnw.cmd`; `maven-consumer-project/.mvn/wrapper/maven-wrapper.properties`; `maven-consumer-project/src/test/java/com/example/pickleball/PickleballTests.java` | `README.md`; `docs/getting-started.md`; `docs/cucumber-compatibility.md` |
| Dynamic steps and expression execution | `src/main/java/tools/dscode/coredefinitions/DynamicSteps.java`; `src/main/java/tools/dscode/common/treeparsing`; `src/main/java/tools/dscode/pickleruntime/CucumberOptionResolver.java`; search `PhraseExecution`, `Step`, `Expression` | Search `src/test` for matching step/expression classes | `maven-consumer-project/src/test/resources/features/dynamic-steps.feature`; `forms-dynamic-steps.feature` | `docs/dynamic-steps.md` |
| Selenium navigation and element interaction | `src/main/java/tools/dscode/coredefinitions/BrowserSteps.java`; `src/main/java/tools/dscode/coredefinitions/NavigationSteps.java`; `src/main/java/tools/dscode/common/seleniumextensions/ElementWrapper.java`; `src/main/java/tools/dscode/common/domoperations/HumanInteractions.java`; `SeleniumUtils.java` | Search `src/test` for element and interaction tests | `navigation.feature`; `forms-dynamic-steps.feature`; `dialogs.feature`; `catalog-context.feature`; pages under `maven-consumer-project/src/test/resources/site` | `docs/dynamic-steps.md`; `docs/custom-element-definitions.md` |
| Custom element definitions and catalog context | Search `src/main/java` for `ElementDefinition`, `Catalog`, `Selector`, `Locator` | Search `src/test` for catalog/selector tests | `catalog-context.feature`; `forms-dynamic-steps.feature`; `site/catalog.html`; `site/forms.html` | `docs/custom-element-definitions.md`; `docs/config-files-and-resource-mapping.md` |
| Keyboard and key-expression DSL | `src/main/java/tools/dscode/common/domoperations/KeyParser.java`; mapping query classes under `src/main/java/tools/dscode/common/mappings`; search `Keyboard`, `Tokenized`, `Query` | Search `src/test` for `Key`, `Tokenized`, parser/query tests | `maven-consumer-project/src/test/resources/features/keyboard.feature`; `site/keyboard.html` | `docs/key-parser-dsl.md` |
| Mapping, parsing maps, and template resolution | `src/main/java/tools/dscode/coredefinitions/MappingSteps.java`; `src/main/java/tools/dscode/common/mappings/FileAndDataParsing.java`; `NodeMap.java`; `ParsingMap.java`; `MappingProcessor.java`; query classes under `common/mappings` | Search `src/test` for mapping, `NodeMap`, template, and type-preservation tests | `mapping-and-resources.feature`; `mapping-value-type-preservation.feature`; `calls/service-call-definitions.feature`; example configs and test data | `docs/mapping-and-templating.md`; `docs/config-files-and-resource-mapping.md` |
| Configuration and resource lookup | `src/main/java/tools/dscode/common`; `src/main/java/tools/dscode/registry`; search `Configuration`, `Resource`, `Properties`, `Yaml` | Search `src/test` for configuration/resource tests | `maven-consumer-project/src/test/resources/configs`; `mapping-and-resources.feature`; consumer `pom.xml` profiles/properties | `docs/configuration.md`; `docs/config-files-and-resource-mapping.md`; `docs/getting-started.md` |
| Nested steps and block conditionals | Search `src/main/java` for `Nested`, `Conditional`, `Block`, `Condition` | Search `src/test` for nested/conditional tests | `nested-and-block-conditionals.feature`; `site/workflow.html` | `docs/nested-steps.md`; `docs/block-conditionals.md` |
| Component scenarios, step markers, and marker data lookup | `src/main/java/tools/dscode/coredefinitions/ModularScenarios.java`; `src/main/java/io/cucumber/core/runner/ScenarioStep.java`; `src/main/java/io/cucumber/core/runner/ScenarioStepData.java`; `src/main/java/io/cucumber/core/runner/StepExtension.java`; `src/main/java/io/cucumber/core/runner/modularexecutions/CucumberScanUtil.java`; search scenario registration, option filtering, ordering, limit, `Step_Marker`, `stepMarkerText`, marker padding, and `getScenarioStepData` | `maven-consumer-project/src/test/java/tools/dscode/coredefinitions/ModularScenariosChecks.java`; `maven-consumer-project/src/test/java/io/cucumber/core/runner/ScenarioStepChecks.java`; `maven-consumer-project/src/test/java/io/cucumber/core/runner/ScenarioStepDataChecks.java` | `component-scenarios.feature`; `reusable-scenario-selection.feature`; `scenario-step-markers.feature`; `scenario-marker-data.feature`; `scenario-data-references.feature`; `calls/service-call-definitions.feature`; `site/components.html` | `docs/component-scenarios.md` |
| Service-call definitions and execution | `src/main/java/tools/dscode/coredefinitions/ServiceCallSteps.java`; `src/main/java/tools/dscode/coredefinitions/ModularScenarios.java`; `src/main/java/io/cucumber/core/runner/ScenarioStep.java`; `src/main/java/tools/dscode/common/servicecalls/RestAssuredUtil.java`; mapping classes under `common/mappings`; search request/response, scenario selection, `Step_Marker`, and SOAP handling | `maven-consumer-project/src/test/java/tools/dscode/coredefinitions/ModularScenariosChecks.java`; `maven-consumer-project/src/test/java/io/cucumber/core/runner/ScenarioStepChecks.java`; search consumer checks for service, request, response, JSON, and XML tests | `service-call-execution.feature`; `reusable-scenario-selection.feature`; `scenario-step-markers.feature`; `calls/service-call-definitions.feature`; local server support under `maven-consumer-project/src/test/java`; resources under `site` | `docs/service-call-scenarios.md`; `docs/component-scenarios.md`; `docs/mapping-and-templating.md` |
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


### Scenario marker data references and consumer-hosted internal checks

- Implementation:
  - `src/main/java/tools/dscode/coredefinitions/ModularScenarios.java`
  - `src/main/java/io/cucumber/core/runner/ScenarioStepData.java`
  - `src/main/java/tools/dscode/common/mappings/MappingProcessor.java`
- Public syntax:
  - `<data:marker>`
  - `<data:scenario.marker>`
  - `<data:feature.scenario.marker>`
  - `<&reference>` remains exclusively a step-return lookup
  - `pkb_datapath`, defaulting to `src/test/resources/data` for named-scenario lookups
- Consumer coverage:
  - `maven-consumer-project/src/test/resources/features/scenario-data-references.feature`
  - `maven-consumer-project/src/test/resources/data/data-reference-records.feature`
  - `maven-consumer-project/src/test/java/com/example/pickleball/DataReferenceSteps.java`
- Internal Java checks are compiled against the locally published dependency and
  run through:
  - `maven-consumer-project/src/test/resources/features/internal-framework-java-checks.feature`
  - `maven-consumer-project/src/test/java/com/example/pickleball/InternalFrameworkTestSteps.java`
  - `maven-consumer-project/src/test/java/com/example/pickleball/support/InternalJavaTestRunner.java`
