# Registered objects

A scenario can register an object with a configuration that names how to build it, what to do after construction, and what to run at the end.

The configuration is a JSON object:

| Property | Role |
|---|---|
| `constructor` | Step text that builds the object. Required for Gherkin construction. |
| `postActions` | String or array of strings run after construction. |
| `cleanup` | String or array of strings run at scenario end. |
| `_pathKey` | Key used to find the object again. |

Browser configuration already uses this. `CHROME_HEADLESS` sets `constructor` to `CREATE_LOCAL_DRIVER` and `cleanup` to `QUIT_LOCAL_DRIVER`.

## Cleanup at scenario end

The `cleanup` property runs when the scenario ends. It runs after a passing scenario and after a failure. Each object's cleanup is isolated, so one failure does not skip the remaining objects. Cleanup is skipped when `pkb_debugbrowser` is true, because that mode keeps the browser open.

There is no separate cleanup step to add for ordinary scenario teardown. Use one of these:

- `ALWAYS RUN` for a Gherkin child that must run after the scenario is complete. See [Nested steps](nested-steps.md) and [Log and assert steps](log-and-assert-steps.md).
- A Cucumber `@After` hook on the consumer runner or glue for Java teardown.

The executable check is [`release-2-1-16.feature`](../maven-consumer-project/src/test/resources/features/release-2-1-16.feature). It registers an object whose `cleanup` action records a token, including on a soft failure.
