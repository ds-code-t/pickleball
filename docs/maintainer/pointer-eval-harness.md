## Maintainer-only: pointer-eval harness

This note is for Pickleball maintainers. It is not the product suite, it is not first-read for consumer agents, and it is not copied into the consumer guidance export.

Pickleball's example Maven consumer includes an opt-in mixed pass/fail suite tagged only `@agent-pointer-eval` (`maven-consumer-project/src/test/resources/features/agent-pointer-eval.feature`). It is not part of `@all`, `@regression`, or the other Maven suite-profile tags. Do not add `@all` to that feature. The failures are intentional canned fixtures for scoring whether a consumer AI agent follows the short `AGENTS.md` pointer into the consumer agent guide and then uses Workbench discover-then-confirm (and isolate/execute-step when live debug is needed). Do not treat those failures as product bugs, and do not "fix" the feature unless a human asked to change the harness. During an eval, still follow Discover / Confirm / live debug. Do not ignore canned fails.

Run it explicitly:

```text
mvnw -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=tools.dscode.launcher.PickleballWorkbenchLauncher" "-Dexec.classpathScope=test" "-Dexec.args=discover --tags=@agent-pointer-eval"
```

or `-Dpkb_runvars="pkb_tags=@agent-pointer-eval, pkb_browser=CHROME_HEADLESS"`. Do not add this tag to consumer `AGENTS.md` or Copilot pointer files.
