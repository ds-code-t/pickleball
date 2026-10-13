# Java values

> **Working feature example:** [`release-2-1-16.feature`](../maven-consumer-project/src/test/resources/features/release-2-1-16.feature) saves with ordinary Gherkin, reads the same values from Java, and reads Java saves back from Gherkin.

`tools.dscode.Values` is the consumer-facing value API. One static import is the whole surface:

```java
import static tools.dscode.Values.*;
```

The methods are plain static methods. Thread safety is inside the class. The API does not expose maps, NodeMap, ParsingMap, or enums. That import includes the methods in this guide and `Values.Context`, the context a prefix function receives. `PrefixValue` is a separate type. Framework helpers are not members of `Values`.

## Read and write

`getValue(String query)` reads the whole current value set. It returns the same result as `<query>` in Gherkin. `getValue("orders #2")` is `<orders #2>`.

`saveValue(String name, Object value)` writes where an untargeted Gherkin save writes, the run values.

These pairs read and write one target, the same targets as Gherkin `TO ... MAP`:

| Read | Write | Target |
|---|---|---|
| `getRunValue` | `saveRunValue` | run |
| `getScenarioValue` | `saveScenarioValue` | scenario |
| `getRootScenarioValue` | `saveRootScenarioValue` | root scenario |
| `getStepValue` | `saveStepValue` | step |
| `getDefaultValue` | `saveDefaultValue` | default |
| `getOverrideValue` | `saveOverrideValue` | override |
| `getSingletonValue` | `saveSingletonValue` | singleton |

A map-specific read applies directives on that value. It does not walk the other targets. Scenario, step, and root-scenario reads throw when no step is running. A step value belongs to the running step. A later step has its own step values. Scenario, root-scenario, run, default, override, and singleton values stay visible to later steps in that scope. Passed, example, phrase, global, datatable, and docstring values are read only through `getValue`.

Queries use the Gherkin rules: base-1 `#n`, `[]` history, and JSONata.

## Typed reads

Every read has a `Class<T>` overload. It converts the stored value with Jackson. `getValue("total", Integer.class)` turns `"42"` into `42`. `getValue("orders #2", Order.class)` maps a row to a POJO. A conversion failure throws `IllegalArgumentException` and the message starts with `Cannot convert`. It does not return null.

`String.class` returns the plain string. An untyped `getValue` of a masked value returns the masked holder, whose `toString` is `******`. `getValue(query, String.class)` returns the plain string.

## softFail

`softFail(String message)` records a soft failure the way a soft assert is meant to finish the scenario. The calling step continues. The scenario fails when it ends. `RUN IF SCENARIO SOFT FAILED` sees the soft failure and runs. This does not change `SOFT ASSERT` or `SOFT FAIL SCENARIO`.

Outside a scenario, `softFail` throws `IllegalStateException`.

## throttle

`throttle(String key, int seconds)` blocks until at least `seconds` have passed since the previous `throttle` call with the same key in this JVM. Parallel scenarios share the gate. The first call does not wait. `0` records the call and does not wait. A blank key or a negative second count throws. There is no Gherkin syntax.

## Custom reference prefixes

`registerPrefix(String prefix, function)` adds a reference prefix. Gherkin `<password:testAccount>` calls the function. The argument is the text after the first colon.

The function receives a context with the same read and write methods as this class, and it returns a `PrefixValue`. `PrefixValue.of(value)` prints the value. `secret(value)` returns a `PrefixValue` whose `toString` is `******`. The real value is still what gets typed, sent, or converted.

Registering a built-in prefix fails. Built-in prefixes include `file`, `classpath`, `wd-file`, `data`, `config`, `configs`, `value`, `run`, `step`, `scenario`, `scenario root`, `root scenario`, `default`, `override`, `singleton`, `parent`, `global`, `phrase`, `passed`, `example`, `datatable`, and `docstring`. Registering a prefix twice fails. An unknown `prefix:argument` is left as an ordinary missing reference.

`cacheGet(key)` and `cachePut(key, value)` are a JVM-wide cache for prefix functions. The cache is thread safe. A null value can be stored and read back.

## Masked values

`~MASK;` stores a masked value. `* , save "hunter2" as "pw~MASK;"` keeps the real characters and prints `******`. `<pw>` stays masked inside other text. The real value is used and sent. Logs, the terminal, the run panel, Workbench, HTML reports, ReportPortal, and diagnostics show `******`. `<pw~UNMASK;>` returns the plain value.

Every known masked value is replaced with `******` in log text, including a secret embedded in a longer line such as `Bearer <pw>`.
