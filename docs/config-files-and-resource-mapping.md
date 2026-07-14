# Shared Configuration Files and Resource Data

Feature files often need shared URLs, browser choices, calendars, regular expressions, users, or test data. Pickleball can turn files under `src/test/resources` into nested values that can be read with ordinary templates.

There are two ways to use resource data:

1. Put commonly used data in `configs`, where it is available to every scenario.
2. Load another file or directory only when a template beginning with `/` requests it.

## The shared `configs` directory

Place shared files under:

```text
src/test/resources/configs
```

At the start of a run, directory names and file names become property names in one nested `configs` structure:

- directory names become nested groups;
- file names become property names without the file extension;
- the content of each supported file becomes objects, lists, numbers, Booleans, text, or null values; and
- unsupported file types are ignored.

Every scenario can use the resulting values without first running a loading step.

## Supported file types

| File type | How it is represented |
|---|---|
| `.yaml`, `.yml` | Nested properties, lists, and simple values |
| `.json` | Nested properties, lists, and simple values |
| `.xml` | A nested data structure based on the XML content |
| `.csv` | A list of rows, with column headings used as property names |
| `.txt` | One text value |

## Example directory structure

```text
src/test/resources/configs/
├── CALENDARS.yaml
├── CHROME.yaml
├── REGEX.yaml
├── REMOTE_CHROME.yaml
├── URL.yaml
├── USER.yaml
├── jsonfiles/
│   └── test.json
└── otherfiles/
    ├── test.feature
    ├── testCSV.csv
    └── textTest.txt
```

The corresponding template paths include:

| Resource | Template path begins with |
|---|---|
| `CALENDARS.yaml` | `configs.CALENDARS` |
| `CHROME.yaml` | `configs.CHROME` |
| `jsonfiles/test.json` | `configs.jsonfiles.test` |
| `otherfiles/testCSV.csv` | `configs.otherfiles.testCSV` |
| `otherfiles/textTest.txt` | `configs.otherfiles.textTest` |
| `otherfiles/test.feature` | Not loaded |

If `jsonfiles/test.json` contains:

```json
{
  "prop1": "value1",
  "prop2": {
    "prop3": "2"
  },
  "prop4": 5
}
```

and `otherfiles/testCSV.csv` contains:

```csv
a,b,c
1,2,3
4,5,6
```

then feature files can read values through paths such as:

```text
<configs.jsonfiles.test.prop1>
<configs.jsonfiles.test.prop2.prop3>
<configs.otherfiles.testCSV #1.b>
<configs.otherfiles.textTest>
```

## Reading shared values

If `CALENDARS.yaml` contains:

```yaml
DefaultCalendar:
  TimeZone: America/New_York
  DefaultOutputPattern: yyyy-MM-dd HH:mm:ss VV
  DateTimeFormats:
    - M/d/yyyy
    - uuuu-M-d
    - uuuu-M-d H:m:s
    - uuuu-MM-dd HH:mm:ss VV
```

then:

```text
<configs.CALENDARS.DefaultCalendar.TimeZone>
```

resolves to:

```text
America/New_York
```

It can be used directly in a feature:

```gherkin
* print "Configured time zone: <configs.CALENDARS.DefaultCalendar.TimeZone>"
```

Other examples:

```text
<configs.CHROME.browser>
<configs.CHROME.driver.capabilities.browserName>
<configs.URL.google>
<configs.USER.usera>
<configs.CALENDARS.DefaultCalendar.DateTimeFormats[*] as:LIST>
<configs.CHROME.driver.options.args #1>
```

The same nested-path, wildcard, list, and one-based-position rules described in [Mapping and Templating](mapping-and-templating.md) apply after `configs`.

## Good uses for `configs`

Use the shared directory for values that many scenarios need:

- local and remote browser configurations;
- application and service URLs;
- date formats, time zones, and business calendars;
- regular-expression patterns;
- reusable users or test identities that do not contain secrets;
- named environment settings; and
- common constants used by feature files.

Do not commit passwords, access tokens, private keys, or other secrets. Supply sensitive values through local or CI secret settings.

## Loading another resource with a `/` template

A template key beginning with `/` loads a file or directory relative to:

```text
src/test/resources
```

Assume this file exists:

```text
src/test/resources/files/items.yaml
```

```yaml
- a: 111
  b: 112
  c: 113
- a: 221
  b: 222
  c: 223
```

This template loads the file, selects the first item, and reads `b`:

```text
</files/items #1.b>
```

The result is:

```text
112
```

The file extension may be omitted.

## Loading a directory and then querying it

This alternative also resolves to `112`:

```text
</files.items #1.b>
```

Here `/files` identifies the resource directory. The remaining `.items #1.b` selects `items.yaml`, then its first row, then the `b` value.

Both forms are valid:

```text
</files/items #1.b>
</files.items #1.b>
```

Prefer the direct file path when the target file is known. It avoids loading an entire directory. Load a directory when one query needs to work across several files beneath it.

## Physical paths and data paths

Read a `/` template in two parts:

```text
/<resource path><data query>
```

- The first `/` means “start under `src/test/resources`.”
- Forward slashes identify physical directories and files.
- File extensions may be omitted.
- After the chosen file or directory is loaded, periods, positions, and wildcards navigate its contents.

Examples:

```text
</files/items #2.c>
</files.items #2.c>
</datasets/accounts.records #1.owner.name>
</datasets/accounts.records[*].owner.name as:LIST>
```

## Choosing the right source

| Need | Recommended approach |
|---|---|
| Values used by many scenarios | Put them under `src/test/resources/configs` and use `<configs...>` |
| One known file needed only at that moment | Use a direct `/` template such as `</files/items #1.b>` |
| Several files in one directory must be queried together | Load the directory and continue with a data path |
| Every matching value is needed | Use `[*]` and append `as:LIST` |

---

[Previous: Mapping and Templating](mapping-and-templating.md) · [Documentation home](README.md) · [Next: Nested Steps](nested-steps.md)
