# Linked Tracking Fix-back 1 — Addendum (second independent verification at 0924540c)

These are part of Fix-back 1, with the same guardrails as prompts/build-3.0.0-linked-tracking-fixback1.md. Add them to the Fix-back 1 scoreboard as rows 8–10, and fold 1a and 2a into gaps 1 and 2.

1a. **Auto-hydrate hides the gap 1 failure.** `DaltonHydrator.java:122` runs inside the transaction, and `DaltonRuntime.java:23-27` only prints "Dalton auto-hydrate skipped". So with Dalton on, edited or new `.feature` files silently stop syncing while runs keep reading the stored copies. A new feature added after one tracked run was not stored. A glue edit that moves a definition to another line also triggers it, because the definition id includes the line number. Fix gap 1 so this path works, choose a definition identity that survives line moves (or remap executions), and make an auto-hydrate failure loud: an error with a `fix:` hint that the CLI and Workbench surface. Test: the auto-hydrate path after a tracked run, plus a new feature file being stored.

2a. **Unique run ids.** Model them on `DiagnosticReporter.resolveRunId` (`DiagnosticReporter.java:1106-1111`). Remove the forced `pkb_run_id=dalton-run` in `DaltonRunWorker.java:82-83` and the same fallback in `DaltonExecution.java:286-287`. Where practical, use the SAME run id for the Dalton run and the diagnostic run so the two link.

8. **"Open in Explorer" silently does nothing** when the Dalton run id doesn't match a diagnostic run id (`WorkbenchFrame.java:1300-1314`, `if (selected == null) return;`). After 2a they should match. When there's no match, show a clear status message instead of returning silently. Test: forwarding or behavior test.

9. **Cucumber runs are only recorded under `dalton run`.** The recording plugin is registered only when `pkb_dalton_run_trace=true` (`WorkbenchDirectRun.java:46-49`). A normal Gradle or Maven run with `pkb_engine=cucumber` records nothing, though the Phase 2 plan names the Cucumber adapter. Register the recorder whenever Dalton is on, for both engines, in a way that matches the docs and doesn't slow runs when Dalton is off. Test: a Cucumber-engine run outside `dalton run` records scenario and step executions.

10. **The Workbench timeline loads oldest first and caps at 50** (`DaltonBrowser.java:55-56`). This is part of gap 4. In addition, open on the most recent page by default (or offer jump-to-latest) as well as paging.
