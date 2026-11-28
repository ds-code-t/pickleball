# Doc and help gaps — scoreboard

Branch: `3.0.0-spine-doc-gaps`. Worktree: `C:\Users\Admin\IdeaProjects\pkb-spine-doc-gaps`. Start head: `cf3da2bfe` (via `d8fa23480`, the prompt commit). The branch docs matched this build. No disagreement to record.

Head is the commit on `3.0.0-spine-doc-gaps` that adds this scoreboard, after rebase onto `origin/3.0.0-spine`. The session report names that SHA. Targeted tests only. The short and full regression tiers were not run.

| Item | Result | Evidence |
|---|---|---|
| 1. Human-tier verb list | closed | `VerbTiers` human set is 13 names. `HumanLock` refusals and `help act` read that set. `scroll` is listed. The act body still reports `unknown kind`. |
| 2. Popup verb usage | closed | `help popup-open`, `help popup-status`, and `help popup-close` print `Usage:`. `--target=popup` is a surface-op route, not a flag of those verbs. `--on-top` and `--headless` are not `popup-open` flags. |
| 3. `ws-route --rules` schema | closed | Help and `docs/browser-events.md` match `RouteCommands`: `on`, `reply`, `close`, `closeCode`. Example `[{"on":"ping","reply":"pong"}]`. |
| 4. `@file` forms | closed | `ask --say` and `--prompt`, `html-insert --text`, and `cdp --params` accept `@file`. `DocGapsTest` goes through `WorkbenchAgentCommands` and `PwCommand.run`. |
| 5. `--agent` lock owner | closed | `session-mode --human=on` and `start --human` record `agentIdOf` (`--agent`, same as `--agent-id`). Help and `docs/dynamic-control-api.md` say only that owner may pass `--human-override=<reason>`. Existing `HumanTierTest` override cases stay. |
| 6. Catalog unclear notes | closed | Capture ignores `--query`. Desktop type flags are on `domengine help` (`--id`, not `--query`). Text cards submit with Send. A checklist tick posts `type=check` `source=human`. `dialogs --policy=dismiss` does not close a dialog that is already open. `spine session` is not the `start` page. `progress` does not paint that page. |
| 7. `start` under Maven `exec:java` | closed | `ConsumerReadyCommands.hostClasspath` skips a classworlds-only `java.class.path`. Next is `classpath.cache`, then the loader classes. `LiveHostClasspathTest` fails if the classworlds string is returned. |

## Remaining gap

`act --kind=scroll` is accepted by the lock and named in help. `TargetVerb` reports `unknown kind` and does not scroll. Recorded in `docs/tech-debt.md`. Not implemented here.

## Tests

`.\gradlew.bat :test --tests tools.dscode.launcher.HumanTierTest --tests tools.dscode.launcher.WorkbenchAgentUsabilityTest --tests tools.dscode.launcher.GuidanceCommandGoldenTest --tests tools.dscode.launcher.VerbCatalogTest --tests tools.dscode.launcher.DocGapsTest --tests tools.dscode.launcher.LiveHostClasspathTest --console=plain`

The first run completed 33 tests and failed 1: `GuidanceCommandGoldenTest.documentedCommandsMatchTheHelpGolden`. Help pages for `act`, `ask`, `cdp`, `html-insert`, `popup-open`, `popup-status`, `popup-close`, `progress`, and `start` had changed. `src/test/resources/guidance-golden/documented-help.txt` was updated to that help text and the diff was reviewed. The same command was run again without `-Dpkb.update.golden=true`.

That second run passed, 33 tests, 0 failed. XML timestamps 2026-11-28T05:36:20Z through 2026-11-28T05:36:25Z: `DocGapsTest` 6, `GuidanceCommandGoldenTest` 1, `HumanTierTest` 6, `LiveHostClasspathTest` 3, `VerbCatalogTest` 7, `WorkbenchAgentUsabilityTest` 10.

Python checks before the commit, each exit 0: `sync_consumer_guidance.py`, `refresh_agent_index.py`, `verify_agent_contract.py`, and `refresh_agent_index.py --check`.
