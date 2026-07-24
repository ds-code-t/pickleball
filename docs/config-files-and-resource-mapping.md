# Configuration Files and Resource Mapping

> **Working feature example:** [`mapping-and-resources.feature`](../maven-consumer-project/src/test/resources/features/mapping-and-resources.feature) resolves values from the consumer project's YAML, JSON, CSV, text, and on-demand resource files.

Pickleball can expose files under `src/test/resources` as nested scenario data.

Use two approaches:

1. place commonly used data under `configs`, where it is loaded for every scenario; or
2. load a file or directory on demand with a template beginning with `/`.

## Shared `configs` directory

```text
src/test/resources/configs/
```

Directory names become nested groups. File names become property names without their extensions.

Supported resource types include:

| Type | Result |
|---|---|
| YAML / YML | nested objects, arrays, and scalar values |
| JSON | nested objects, arrays, and scalar values |
| XML | nested data based on XML content |
| CSV | an array of row objects using the header names |
| TXT | one text value |

Unsupported file types are ignored.

The consumer project contains real examples:

```text
configs/
├── CALENDARS.yaml
├── CHROME.yaml
├── CHROME_HEADLESS.yaml
├── EDGE.yaml
├── GRID_CHROME.yaml
├── GRID_EDGE.yaml
├── REGEX.yaml
├── REMOTE_CHROME.yaml
├── REMOTE_EDGE.yaml
├── SAUCE_CHROME.yaml
├── SAUCE_EDGE.yaml
├── TEST_DATA.yaml
├── URL.yaml
├── jsonfiles/
└── otherfiles/
```

## Reading shared values

Given [`TEST_DATA.yaml`](../maven-consumer-project/src/test/resources/configs/TEST_DATA.yaml):

```yaml
siteName: Pickleball Test Lab
users:
  - name: Ava
    city: Phoenix
    accountType: Premium
```

Feature files can use:

```gherkin
* , ensure "<configs.TEST_DATA.siteName>" equals "Pickleball Test Lab"
* , ensure "<configs.TEST_DATA.users #1.name>" equals "Ava"
```

URLs can be placed in [`URL.yaml`](../maven-consumer-project/src/test/resources/configs/URL.yaml):

```gherkin
* navigate to: URL.forms
```

The URL shorthand and normal `<configs...>` templates are both available where their corresponding steps support them.

## Loading one file on demand

A template beginning with `/` loads a resource relative to `src/test/resources`:

```text
</files/customers #1.name>
</files/customers #2.city>
```

File extensions may be omitted.

Given [`files/customers.yaml`](../maven-consumer-project/src/test/resources/files/customers.yaml), the first expression returns `Ava` and the second returns `Tempe`.

## Loading a directory

A `/` template can load a directory and continue with a nested query. Prefer loading a known file when possible because it avoids reading unrelated resources.

```text
</configs/jsonfiles.accounts #1.id>
```

Forward slashes identify physical resource paths. Dots, indexes, `#` positions, and wildcards query the loaded data.

## Recommended use

Use `configs` for data shared broadly across the suite:

- application and service URLs;
- browser definitions;
- environments and feature flags;
- calendars and time zones;
- regular expressions;
- reusable non-secret test identities; and
- common expected values.

Use on-demand files for larger or specialized data needed by only a few scenarios.

Do not commit passwords, access tokens, private keys, or other secrets. Supply sensitive values through local or CI configuration.

## Working examples

- [Resource-mapping feature](../maven-consumer-project/src/test/resources/features/mapping-and-resources.feature)
- [All shared configuration files](../maven-consumer-project/src/test/resources/configs)
- [On-demand files](../maven-consumer-project/src/test/resources/files)

[Previous: Mapping and Templating](mapping-and-templating.md) · [Documentation home](README.md) · [Next: Nested Steps](nested-steps.md)
