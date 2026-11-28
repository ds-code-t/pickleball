# Build prompt: 3.0.0-spine doc and help gaps from the manual collaboration catalog

This prompt stands alone. You are a fresh headless Grok Build session on Dan's Windows PC. You have not seen the planning chat. Your job is to close the documentation and help gaps found while writing the manual human-collaboration catalog, with targeted tests only. Then STOP and report the head.

- **Repo:** https://github.com/ds-code-t/pickleball
- **Branch:** `3.0.0-spine-doc-gaps` only. Never `master`, never `3.0.0`, never `3.0.0-spine`, never `3.0.0-spine-parallel-tests`, never a 2.x branch. No tags, releases, publishing to a remote repository, pull requests, or force-push.
- **Start head:** `cf3da2bfe` on `origin/3.0.0-spine` (merge of the regression-tier docs plus the three verify-gap fixes). Create and stay on `3.0.0-spine-doc-gaps` from that head if you are not already on it.
- **Worktree:** `C:\Users\Admin\IdeaProjects\pkb-spine-doc-gaps` (Dan's Windows PC, DESKTOP-TUUEDJG). Do not use `pkb-spine`, `pkb-spine-r7`, `pkb-spine-r8`, `pkb-spine-parallel-tests`, or any other worktree.
- **Toolchain:** JDK 21. Windows commands. `.\gradlew.bat`. Never run `.\gradlew.bat --stop` or `./gradlew --stop`. Use `python`, not `python3`.
- **Scoreboard (you create it):** `docs/maintainer/3.0.0-spine/doc-gaps-scoreboard.md`

## Read first

Read these in full before you change anything. The branch docs win over this prompt if they disagree; if they do, follow the docs and say so on the scoreboard.

1. `AGENTS.md` (test policy, functionality-change rules, human-tier wording, help and inventory rules).
2. `docs/roadmaps/3.0.0-spine/final-regression.md` (three tiers, batching, when a short or full regression may run).
3. `docs/roadmaps/3.0.0-spine/manual-collaboration-regression.md` (Roles, Known gaps, every test marked **Unclear**, and Setup S0 step 3 on the Maven `start` classpath).
4. `docs/tech-debt.md`, including `Final regression findings (open, 2026-11-27)`.
5. `docs/dynamic-control-api.md` (tiers and human-locked sessions), `docs/collaborative-self-test.md` (Two-actor recipe human-tier list), `docs/verb-inventory.md`, `docs/direct-control-handoff.md` (popup verbs).
6. `pickleball-control-protocol/src/main/java/tools/dscode/control/protocol/VerbTiers.java`, `src/main/java/tools/dscode/common/agentcontrol/HumanLock.java`, `src/main/java/tools/dscode/launcher/WorkbenchCliHelp.java`, `src/main/java/tools/dscode/launcher/ConsumerReadyCommands.java` (how `start` launches `LiveBrowserHost`).
7. Existing tests you will update or add: `HumanTierTest`, help and inventory goldens (`WorkbenchAgentUsabilityTest`, `GuidanceCommandGoldenTest`, `VerbCatalogTest` or the files they read).

## Hard rules

- Windows PC only. No Linux or macOS desktop runs. No GitHub Actions or workflow runs. No pull request. No push to `3.0.0` or any other CI-triggering branch.
- Do not run the **full** regression. Do not run the **short** (Chrome + Windows) regression. Those tiers run only under Dan's rules, and this Build is not that time. When every item below is done and its targeted tests are green, STOP and report the head.
- **Batching (Dan, 2026-11-27):** if targeted tests fail, let the run finish and collect every failure. Fix them all at once where practical, each at the root with a reproducing test that fails before and passes after. Run all of those targeted tests together. Do not start a new broad run until that whole batch passes. Never weaken, skip, or delete a test. Never loosen an assertion to make a change pass. Keep Gherkin syntax; step annotations stay single string literals. No secrets in cleartext.
- **Parallelism (Dan, 2026-11-27):** any test run on this PC must be as fast as the suite allows. If `-Ppkb.test.forks` already exists, pass a high value (24 to 80) that fits in 64 GB. If it does not exist yet, do not add it (that is the parallel-tests Build). Just run the targeted classes; they are small.
- **File ownership, to avoid overlap with `3.0.0-spine-parallel-tests`:** do not edit `build.gradle`, any module `build.gradle`, `gradle.properties`, `settings.gradle`, or consumer `pom.xml`. Do not change test fork, heap, or Gradle parallel settings. You may edit help, verb docs, `AGENTS.md`, the manual catalog's unclear notes only as needed to mark a point resolved, `docs/tech-debt.md` only to add or close lines about these doc and help gaps, and one release-notes paragraph titled so it is clearly yours (start it with `Docs and help.`). Do not rewrite the three-tier lead or any parallelism paragraph.
- After every green change: run `python scripts/sync_consumer_guidance.py` and `python scripts/refresh_agent_index.py` when docs or guidance changed, confirm `python scripts/verify_agent_contract.py` exits 0, commit, `git fetch origin`, rebase onto `origin/3.0.0-spine` (and onto `origin/3.0.0-spine-doc-gaps` if that remote branch already has your earlier commits), then `git push origin HEAD:refs/heads/3.0.0-spine-doc-gaps`. Never push to `3.0.0-spine`. Never force-push.
- Production-caller standard: a help or docs claim that is true only in a test hook, or a test that does not go through the real help or lock path, is a fail. Prefer updating `WorkbenchCliHelp` and `VerbTiers` so `help`, the inventory, and `HumanLock` all read the same source.
- Never touch grok pids 48332 or 41064, or java pids 48240 or 50896, or the other IdeaProjects worktrees.

## Work items

Close every gap below. Use the real command and flag names from this branch. If the code cannot honestly support a form, document why in the help or the relevant doc and on the scoreboard; do not invent a flag.

1. **Human-tier verb list.** `VerbTiers.HUMAN_VERBS` is 13 verbs: `act`, `aria-snap`, `capture`, `chat-read`, `doctor`, `explain`, `find`, `help`, `input`, `screenshot`, `snap`, `wait`, `wait-for`. `HumanLock` allows `act --kind=scroll` (`HUMAN_ACT_KINDS` includes `scroll`) and human `input` chains that are only key, pointer, wheel, and pause actions at viewport coordinates. Update `AGENTS.md`, `docs/dynamic-control-api.md`, `docs/collaborative-self-test.md`, the `HumanLock` refusal `fix:` line, and any help text that still lists 11 human verbs or only click/type/press/hover/select. `help act` must list `scroll` if the lock accepts it. `HumanTierTest` and any other list of human verbs must match `VerbTiers`.

2. **Popup verb usage.** `popup-open`, `popup-status`, and `popup-close` have no `Usage:` line in `WorkbenchCliHelp`. Add accurate usage from `AgentSurfaceRouter` / `docs/direct-control-handoff.md` (including `--target=popup` if that is a real flag on those verbs or only on surface ops). `help popup-open`, `help popup-status`, and `help popup-close` must print it. Update the help golden.

3. **`ws-route --rules` schema.** `help ws-route` and `docs/browser-events.md` must describe the rules file shape with a short real example, taken from the code that parses it. If the parser is underspecified, say what is actually accepted.

4. **`@file` forms, or document why not.** For `ask --say`, `html-insert --text`, and `cdp --params`, either accept `@file` (or an existing equivalent such as `--params-file` / `--say-file`) the same way other verbs already do, or keep the current form and document in `help` how to pass spaces, quotes, or JSON on Windows without a file. Prefer adding `@file` when that is already the project convention and it is a small, safe change. Add or extend a targeted test for each form you add.

5. **`--agent` as the human-lock owner.** `session-mode --human=on` and `start --human` record an owner from the coordination agent id (`--agent`). `help session-mode` and the dynamic-control-api tiers paragraph must say that the owner is `--agent`, and that only that owner may pass `--human-override=<reason>`. `HumanTierTest` already covers the override; keep it passing.

6. **Catalog unclear behaviors.** For each of these, read the code, then make the help or the relevant doc state the actual behavior, and tick the catalog test's **Unclear** note to a short "resolved: ..." line or leave it unclear with evidence on the scoreboard:
   - whether `capture` accepts `--query` (or only `--target` and `--out`);
   - the `domengine desktop type` flags (check `help` / `domengine help desktop`; do not invent them);
   - how a text question card is submitted (the control a person clicks);
   - whether a person can tick a checklist row and whether that posts an event;
   - whether `dialogs --policy=dismiss` closes a dialog that is already open;
   - how a `spine session` relates to a `start` host page (whether H's `snap` shows `progress` and `point` on that page).

7. **`start` under Maven `exec:java`.** `ConsumerReadyCommands` launches `LiveBrowserHost` with this JVM's `java.class.path`. Under `exec:java` that path can be the Maven classworlds launcher, and a 2026-11-23 run failed with `ClassNotFoundException: tools.dscode.common.agentfast.LiveBrowserHost`. Confirm whether that is still true. If it is, fix the production launcher so `start` from the documented consumer wrapper works (for example, prefer a resolved test classpath or `classpath.cache` over a classworlds-only path), and add a targeted test that fails on a classworlds-only `java.class.path` before the fix. If a production fix is out of scope without touching files you do not own, document the working command and the failure mode in `docs/collaborative-self-test.md` and Setup S0 of the manual catalog, and record the remaining gap in `docs/tech-debt.md`. Do not leave S0 saying only "unclear."

## Tests

Run only targeted tests for what you changed, for example:

```
.\gradlew.bat :test --tests tools.dscode.launcher.HumanTierTest --tests tools.dscode.launcher.WorkbenchAgentUsabilityTest --tests tools.dscode.launcher.GuidanceCommandGoldenTest --tests tools.dscode.launcher.VerbCatalogTest --console=plain
```

Add any other class you change. Update goldens only when help or inventory text actually changed, and only to the new correct output. After docs or guidance changes, run the three Python checks and confirm `verify_agent_contract.py` exits 0. Do not run `:test` as a whole, do not run `mvnw @all`, and do not run either regression tier.

## Docs and scoreboard

- Functionality or help changes also update `docs/release-notes.md` (one `Docs and help.` paragraph under 3.0.0), any user-facing doc you corrected, and consumer guidance via the sync script.
- Record each item: code SHA, tests run (counts), and whether it is closed or still unclear with evidence.
- Flakes go in `docs/tech-debt.md` with evidence, not as passes.

## Stop

Stop when every item is done or honestly recorded, the targeted tests for the last batch are green, the Python checks exit 0, and `3.0.0-spine-doc-gaps` is pushed. Do not start the short or full regression. End with a scoreboard: branch, head SHA, items closed, tests, remaining gaps, blocker if any.
