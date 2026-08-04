# 2.1.2 surgical fixes

Copy the repository-relative files over the matching files in the `2.1.2`
checkout.

Changes:
- `ModularScenarios` converts `DataTable.cells()` into string maps, so
  Cucumber-injected and `DataTable.create(...)` tables behave the same.
- Reusable scenario execution is unified under typed `RUN` steps, with
  synchronous singular convenience steps sharing the same selection and
  template-resolution path.
- Service-call and component-scenario discovery support `pkb_callpath` and
  `pkb_componentpath`.
- `ScenarioStep` stores its original source Pickle, scenario name, and feature
  URI at construction time.
- `ScenarioDataSteps` expects unresolved Examples placeholders from unresolved
  getters.
- `MappingProcessor` resolves `file:` and `data:` source prefixes independently
  from the `&` step-return prefix.
- Scenario-data consumer checks and documentation use `<data:...>`.
Validation commands:

```powershell
.\gradlew.bat clean test publishToMavenLocal
.\maven-consumer-project\mvnw.cmd -f maven-consumer-project\pom.xml -U test -Dpkb_tags=@all -Dpkb_browser=CHROME_HEADLESS
```
