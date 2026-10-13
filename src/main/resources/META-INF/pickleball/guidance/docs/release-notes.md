# Release notes

## 2.1.16

### Breaking change

Element text matching is a full match. `matching` used to succeed when the pattern was found anywhere in the element text (`Pattern.find`). It now uses `Matcher.matches`, the same full-match rule phrase `matches` already used, and it honors quote types. A single quote on either side is case-insensitive. A backtick on either side is raw text. There is no regex warning.

On a page with buttons Save, Save All, and Autosave:

- `click the Button matching "Save"` clicks only Save
- `click the Button matching "Save.*"` clicks Save or Save All
- `click the Button matching 'save all'` clicks Save All

`containing` is still a literal partial match. For a partial regular expression, wrap the pattern in `.*`, for example `".*Sa.e.*"`. For exact text that contains `(` `)` `.` or `$`, use `equals` or escape the pattern. Quote types change case and whitespace only. There is no new syntax.

### New features

- Java value API on `tools.dscode.Values`: `getValue` / `saveValue` and the run, scenario, root-scenario, step, default, override, and singleton pairs, plus Jackson `Class<T>` overloads. See [Java values](java-values.md).
- `softFail(String message)` records a soft failure. The step continues, the scenario fails at the end, and `RUN IF SCENARIO SOFT FAILED` runs.
- `throttle(String key, int seconds)` waits until `seconds` have passed since the last call with that key in the JVM. There is no Gherkin syntax.
- `~XML-ESCAPE;` escapes `& < > " '`. It works everywhere conversion directives work, and it is rejected on a `MAP "key" TEXT/OBJECT VALUE` destination key.
- Custom reference prefixes. `registerPrefix` adds `<password:testAccount>`. `secret(value)` prints `******`. `~MASK;` stores a masked value that stays masked in logs, reports, the terminal, and Workbench. `<pw~UNMASK;>` returns the plain value.
- Experimental JDBC service calls. A `REQUEST.endpoint` that starts with `jdbc:` runs a JDBC call. HTTP calls are unchanged. Drivers for PostgreSQL, Microsoft SQL Server, Oracle, MariaDB, and H2 are published with Pickleball. See [Experimental features](experimental-features.md).

### Other

- XML readers reject `DOCTYPE` and `ENTITY` declarations. Normal XML is unchanged. `~XML;` uses Jackson's `XmlMapper` conversion, documented with captured output in [Mapping and templating](mapping-and-templating.md).
- A `file:/...` or unprefixed `/...` path that is not on the classpath fails with a hint to use `wd-file:/...` for a filesystem path.
- Literal key escapes and registered-object cleanup are documented. The existing `cleanup` property runs at scenario end after a pass, a soft failure, or a hard-failed step.
- A static import of `Values` includes the documented methods and `Values.Context`. Framework helpers are not part of that import.
- Sample `pkb_callpath` and `pkb_componentpath` values use the resources-folder names `calls` and `component`. When those properties are unset, the defaults remain `src/test/resources/calls` and `src/test/resources/component`.
