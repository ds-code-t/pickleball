# Pickleball 2.1.2 data-prefix update

This package applies the requested source-prefix separation to a checkout of the
`2.1.2` branch.

## Apply

Extract the package at the repository root, then run:

```shell
python apply-data-prefix-update.py
```

The updater is idempotent. It changes these repository files:

- `src/main/java/tools/dscode/common/mappings/MappingProcessor.java`
- `maven-consumer-project/src/test/java/com/example/pickleball/DataReferenceSteps.java`
- `docs/component-scenarios.md`
- `docs/mapping-and-templating.md`
- `docs/agent/feature-map.md`
- `apply-update.py`
- `APPLY_UPDATE.md`
- `README.md`

## Behavior

- `<&reference>` always calls `getReturnValue(reference)`.
- `<file:...>` and `<data:...>` are handled together in `MappingProcessor.get`.
- `<data:...>` replaces `<&data:...>` for scenario-marker retrieval.
- Backtick-wrapped direct keys continue to bypass source-prefix handling.

## Validate

```shell
./gradlew test publishToMavenLocal
./maven-consumer-project/mvnw   -f maven-consumer-project/pom.xml   -U test   -Dpkb_tags=@all   -Dpkb_browser=CHROME_HEADLESS
```

On Windows:

```powershell
.\gradlew.bat test publishToMavenLocal
.\maven-consumer-project\mvnw.cmd `
  -f maven-consumer-project\pom.xml `
  -U test `
  -Dpkb_tags=@all `
  -Dpkb_browser=CHROME_HEADLESS
```
