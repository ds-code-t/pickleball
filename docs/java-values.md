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

`softFail(String message)` records a soft failure the way a soft assert is meant to finish the scenario. The calling step continues. The scenario fails when it ends. `RUN IF SCENARIO SOFT FAILED` sees the soft failure and runs. This does not change `SOFT ASSERT` or `SOFT FAIL SCENARIO`. There is no API to discard or absorb that failure. The Cucumber result stays failed, matching the workbook status. The JUnit failure is a `RuntimeException` whose cause is a `SoftRuntimeException`. That cause's message is the text you passed. It is not null.

Outside a scenario, `softFail` throws `IllegalStateException`. A Cucumber `@Before` or `@After` is not a running scenario step, so `softFail` throws there too.

## isBlank

`isBlank(Object value)` is true for null, `""`, and a character sequence that is only whitespace. A `PrefixValue` is judged by the stored value, not by the printed `******`, so a masked blank is blank and a masked word is not. A number, a boolean, or any other object is not blank. `isBlank(0)` and `isBlank(false)` are false.

## throttle

`throttle(String key, int seconds)` blocks until at least `seconds` have passed since the previous `throttle` call with the same key in this JVM. It returns how many milliseconds this call waited. The first call returns `0` and does not wait. `0` records the call and returns `0`. A blank key or a negative second count throws. Callers that ignore the result are unchanged. There is no Gherkin syntax.

One key has one lock. The thread that holds it parks until the gap has elapsed. Another thread with the same key waits for the lock, then for whatever part of the gap is still left. Parallel scenarios share that gate. An interrupt sets the interrupt flag again and throws `IllegalStateException` with the message `throttle was interrupted.` The gate is not moved forward when the wait is interrupted.

## Hooks

A Cucumber `@Before` or `@After` is not a running step. `getScenarioValue`, `saveScenarioValue`, `getRootScenarioValue`, `saveRootScenarioValue`, `getStepValue`, `saveStepValue`, and `softFail` throw there. `getRunValue`, `saveRunValue`, `getValue`, `saveValue`, `throttle`, and `isBlank` do not need a running step. `getValue` and `saveValue` use the run values when no scenario step is current.

## Custom reference prefixes

`registerPrefix(String prefix, function)` adds a reference prefix. Gherkin `<password:testAccount>` calls the function. The argument is the text after the first colon.

The function receives a context with the same read and write methods as this class, and it returns a `PrefixValue`. `PrefixValue.of(value)` prints the value. `secret(value)` returns a `PrefixValue` whose `toString` is `******`. The real value is still what gets typed, sent, or converted.

Registering a built-in prefix fails. Built-in prefixes include `file`, `classpath`, `wd-file`, `data`, `config`, `configs`, `value`, `run`, `step`, `scenario`, `scenario root`, `root scenario`, `default`, `override`, `singleton`, `parent`, `global`, `phrase`, `passed`, `example`, `datatable`, and `docstring`. Registering a prefix twice fails. An unknown `prefix:argument` is left as an ordinary missing reference.

`cacheGet(key)` and `cachePut(key, value)` are a JVM-wide cache for prefix functions. The cache is thread safe. A null value can be stored and read back.

## Masked values

`~MASK;` stores a masked value. `* , save "hunter2" as "pw~MASK;"` keeps the real characters and prints `******`. `<pw>` stays masked inside other text. The real value is used and sent. Logs, the terminal, the run panel, Workbench, HTML reports, ReportPortal, and diagnostics show `******`. `<pw~UNMASK;>` returns the plain value.

Every known masked value is replaced with `******` in log text, including a secret embedded in a longer line such as `Bearer <pw>`.
