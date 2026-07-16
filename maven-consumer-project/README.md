# Pickleball Multi-page Consumer

This Maven consumer project uses Pickleball as a test-scoped dependency and runs browser scenarios against a self-contained multi-page HTML5/JavaScript test site.

## Requirements

- JDK 21
- Maven 3.9 or newer
- Chrome available to Selenium, or another browser selected through Pickleball configuration
- Port `8765` available during the test run

## Run the complete suite

Every executable scenario has the aggregate `@all` tag. The test runner uses `@all` as its overridable default, so either command runs all functionality:

```powershell
mvn test
mvn test -Pall
```

The equivalent direct Pickleball/Cucumber tag command is:

```powershell
mvn test "-Dpkb_tags=@all"
```

`PickleballTests` starts a loopback-only server at `127.0.0.1:8765` before Cucumber begins and stops it after the run. `LocalTestSite` serves all files below `src/test/resources/site`, including HTML, CSS, and JavaScript assets.

## Functional entry points

Maven profiles provide short, single-purpose entry points. Each profile supplies one `pkb_tags` expression to Pickleball.

| Functionality | Maven entry point | Tag selected |
|---|---|---|
| All functionality | `mvn test -Pall` | `@all` |
| Smoke coverage | `mvn test -Psmoke` | `@smoke` |
| Full regression coverage | `mvn test -Pregression` | `@regression` |
| All browser scenarios | `mvn test -Pbrowser` | `@browser` |
| Data-only scenarios | `mvn test -Pdata` | `@data` |
| Navigation | `mvn test -Pnavigation` | `@navigation` |
| Forms and dynamic steps | `mvn test -Pforms` | `@forms` |
| Catalog and element context | `mvn test -Pcatalog` | `@catalog` |
| Mapping and templating | `mvn test -Pmapping` | `@mapping` |
| Shared resources | `mvn test -Presources` | `@resources` |
| Nested and conditional workflows | `mvn test -Pworkflow` | `@workflow` |
| Block conditionals only | `mvn test -Pconditionals` | `@block-conditionals` |
| Nested steps only | `mvn test -Pnested` | `@nested-steps` |
| Keyboard expressions | `mvn test -Pkeyboard` | `@keyboard` |
| Browser dialogs | `mvn test -Pdialogs` | `@dialogs` |
| Reusable component scenarios | `mvn test -Pcomponents` | `@components` |

## Direct tag expressions

Any Cucumber tag expression can be supplied without adding a Maven profile:

```powershell
mvn test "-Dpkb_tags=@forms"
mvn test "-Dpkb_tags=@forms and @state-assertions"
mvn test "-Dpkb_tags=@browser and not @dialogs"
mvn test "-Dpkb_tags=@mapping or @components"
```

Use either a Maven profile or a direct `-Dpkb_tags` expression for a run; there is normally no reason to combine them. Both become JVM properties and therefore take precedence over the runner default and local properties.

For IntelliJ or repeated local development without a Maven suite profile, copy `pickleball_local.properties.example` to `pickleball_local.properties` and set a tag expression there. A JVM `-Dpkb_tags` value still takes precedence over the local file.

See [TAGGING.md](TAGGING.md) for the complete tag taxonomy and scenario-to-tag matrix.

## Site pages

| Page | Main test purpose |
|---|---|
| `index.html` | Navigation links and page transitions |
| `forms.html` | Text entry, selections, state checks, pointer actions, chained dynamic steps |
| `catalog.html` | Context phrases, custom element words, repeated elements, ordinal positions, filtering |
| `workflow.html` | Nested steps, dynamic conditions, `IF:` / `ELSE-IF:` / `ELSE:` blocks |
| `keyboard.html` | Keyboard expressions and modifier-key timing |
| `dialogs.html` | Accepting and dismissing JavaScript alerts and confirmations |
| `components.html` | Reusable flows invoked with `RUN SCENARIOS` |

## Feature files

```text
src/test/resources/features/
├── navigation.feature
├── forms-dynamic-steps.feature
├── catalog-context.feature
├── mapping-and-resources.feature
├── nested-and-block-conditionals.feature
├── keyboard.feature
├── dialogs.feature
└── component-scenarios.feature
```

## Resource mapping examples

The project includes examples of every documented shared resource type:

```text
src/test/resources/configs/
├── URL.yaml
├── TEST_DATA.yaml
├── jsonfiles/accounts.json
└── otherfiles/
    ├── regions.csv
    └── banner.txt
```

It also includes `src/test/resources/files/customers.yaml` for an on-demand `/` template.

## Custom element vocabulary

The runner registers these project-specific categories before the Cucumber run:

- `Radio Button`
- `Test Panel`
- `Product Card`
- `Status Badge`

The feature files can therefore use business-readable phrases such as:

```gherkin
* , in the "Secondary Queue" Test Panel, click the "Approve" Button
* , ensure the "Starter Plan" Product Card is displayed
```

## Local overrides

Machine-specific values can be supplied in `src/test/resources/pickleball_local.properties`. It is excluded by `.gitignore`.
