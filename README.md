# 2.1.2 surgical fixes

Copy the three repository-relative files over the matching files in the
`2.1.2` checkout.

Changes:

- `ModularScenarios` converts `DataTable.cells()` into string maps, so
  Cucumber-injected and `DataTable.create(...)` tables behave the same.
- `ScenarioStep` stores its original source Pickle, scenario name, and feature
  URI at construction time.
- `ScenarioDataSteps` expects unresolved Examples placeholders from unresolved
  getters.

Validation commands:

```powershell
.\gradlew.bat clean test publishToMavenLocal
.\maven-consumer-project\mvnw.cmd -f maven-consumer-project\pom.xml -U test -Dpkb_tags=@all -Dpkb_browser=CHROME_HEADLESS
```
