# Grok Build prompt: finish and verify 3.0.0 agent-fast (standalone)

Paste this whole file into a **fresh** Grok Build session started in a pickleball checkout.

## Context
- Repo: github.com/ds-code-t/pickleball. Work branch: `3.0.0-agent-fast` (draft PR #26). Integration branch: `3.0.0`.
- As of 2026-11-19 10:15 PM MST, `3.0.0` was fast-forwarded to the agent-fast tip. The previous Build session marked roadmap Phases 1–7 "done" (commit 3683812f) and CI is green. **The bot's independent verification of the Phase 6 and 7 fixes did not happen.** Several earlier rounds marked "done" turned out partial on review, so treat every item below as unverified.
- First read, in full: `AGENTS.md`, `docs/maintainer/3.0.0-agent-fast-design.md`, `docs/maintainer/3.0.0-agent-fast/` (README, evaluation, consumer field report), `prompts/build-3.0.0-agent-fast.md` (the original 7-phase roadmap) and the agent-fast scoreboard/roadmap status. The branch docs win over this file if they disagree.

## Rules (non-negotiable)
- Never commit, push or merge to `master`. No tags, releases, Maven publishing or force-push.
- Work and push only on `3.0.0-agent-fast`. Fetch and rebase before every push. Small, focused commits.
- Never weaken, skip or delete tests. Keep Gherkin syntax. Step annotations stay single string literals.
- Never run `./gradlew --stop`. No secrets in cleartext.
- Flag any distribution, licensing, security or external-download implication before building it.
- Production-path test standard: every fix needs a test that drives the real production path (real Chrome / real step / real CLI verb) and **fails when the fix is reverted**. Check that by temporarily reverting (mutation check) before committing.

## Work, in order
1. **Verify, then fix.** For every item in the fix list below (fixes5 items 1–10, P6-1 to P6-8, P7-1 to P7-12, F-1), read the current code and tests, and decide whether it is fully done to the stated spec. Record the verdict per item (done / partial / missing, with evidence: file, test name, mutation result) in `docs/maintainer/3.0.0-agent-fast/verification-final.md`. Fix every partial or missing item.
2. **Double click first.** Item 1 and P6-8 (a click must fire exactly once on every path, including async handlers) are the highest-priority consumer bug. Prove it with a real-Chrome test that counts handler calls.
3. **CI coverage.** Add the agent-fast named tests (including `FrameActTest`, `FrameRestoreTest`, `WorkbenchCliSurfaceCoverageTest` and every Phase 1–7 test class) to the GitHub Actions workflows under `.github/workflows` so CI actually runs them on Linux, Windows and macOS. Keep the existing jobs. Get CI green; for a known flake (Windows consumer-sim 8-restart-paths cold start), rerun the job rather than loosening the budget.
4. **Held items (do last, only if time remains, each its own commit):** `validate --all`, Workbench first-start timing, Maven `@all` run, the audit items F1–F12 in `docs/maintainer/3.0.0-locked-groups-plan.md` / `3.0.0-engine-defects-design.md`, and a full regression run. Report results honestly; don't mark anything done that you didn't run.
5. Update the scoreboard and docs as you go. At the end, print a summary: per item verdict, commits, CI run links, and anything still open.

When the work is verified and CI is green, Dan (or his bot) can fast-forward `3.0.0` to the branch tip. Do not do that yourself.

---

# Fix list (verbatim from the bot's last verification rounds)

# Agent-fast Phases 1–5: fix list 5 (from verify-agent-fast-P12345-91234f21.md)

Base: implementation 91234f21 (scoreboard 992d5991). Phases 1–5 stay reopened until every item below is done and meets the test standard.

## Test standard (applies to every item)

**Production path.** Every named test drives the real production path:
- a fresh CLI (`PickleballWorkbenchLauncher` in a new JVM), or
- `WorkbenchAgentCommands.run` / `AgentControl.execute`, against a live start host or worker with real Chrome.

**Assertions.**
- Each test must fail when the behaviour breaks. Name the mutant it kills.
- No filler, already-true, or loose `contains` assertions.
- `applied:true` must be backed by reading the effect back from the page. Use a counter or value read through `eval`, or through CDP on that frame's own target.

**Forbidden shortcuts.**
- No stubs.
- No timing of a cached getter.
- No setting internal state the production path cannot reach.

**Unrunnable tests.** A test that cannot run here skips with a reason. It must not pass vacuously.

**Housekeeping.** Never weaken, skip, or delete an existing test. Gherkin and single-string step annotations stay as they are.

## 1. A click must fire exactly once on every path

**Problem.**
- `TargetVerb.perform` (CDP path, main frame) dispatches the coordinate click.
- If `pageMark` (title plus MutationObserver count) did not change, it calls `clickByResolve`, which runs `this.click()`: a second click.
- A button whose handler only changes JS state (a counter, analytics, a fetch) fires twice per `act`. The same happens to open-shadow buttons and plain `role=button` divs.
- Live at 91234f21, after one `act --query=Count` each:
  - `window.cnt` = 2;
  - `OpenOne` (open shadow) = 2;
  - `PlainDiv` = 2.

**Second problem.** That act, and every frame act whose click lands without a DOM or title change, returns `ok:false`, `error:"not applied"`, exit 1. An agent retries and clicks again. Affected:
- OOPIF `Inner`;
- nested OOPIF `Grand`;
- same-origin about:blank `BlankBtn`;
- closed-shadow `Hidden`.

**Required.**
- Never dispatch a second click after one has been dispatched.
- Remove the re-click fallback, or use it only when the first dispatch provably did not reach the element (for example, a hit test at the click point shows another element). Never use it because "nothing visibly changed".
- In the reply, separate "dispatched, no visible change" from "could not land" (disabled, inert, covered, detached). Document the fields and exit codes in docs/dynamic-control-api.md.
- `applied:true` still requires a read-back.
- A dispatched click must not be reported as the same error as a refused one.

**Tests (real Chrome, fresh CLI).**
- A page with JS-only counter buttons:
  - in the main frame;
  - in an open shadow root;
  - on a `role=button` div;
  - in a cross-site OOPIF;
  - in a nested cross-site OOPIF;
  - in a same-origin about:blank frame;
  - in a closed shadow root.
- One `act` each. Assert the counter is **exactly 1**, read through `eval` or CDP on the frame's own target.
- Assert the reply fields chosen above.
- Kills:
  - "re-click when pageMark unchanged";
  - "always clickByResolve after CDP click".

## 2. JS fallback must be honest

**Problem 1.** The mutant "`jsAct` click returns applied:true always" survives the whole suite. The only JS-path no-effect cases (inert and disabled) return before `jsAct`.

**Problem 2.** With `-Dpkb_cdp_input=off`, `select` reads the value back inside the same script, before the page's handlers settle.
- A select whose change handler resets the value in a microtask (`Promise.resolve().then(()=>this.value='S')`, React-18-style) reports `applied:true`, but the final value is `S`.
- A `setTimeout(0)` reset also reports `applied:true`.
- The CDP path handles both correctly.

**Required.**
- The JS path reads click and select effects back after a settle, the same way the CDP path does.
- The JS path must never count its own writes. That includes a write it removes again: the MutationObserver count must not include the fallback's own mutations.

**Tests (start host with `-Dpkb_cdp_input=off`).**
- Click a visible, enabled button with no handler. Assert `applied:false`, `used:"js"`, and the title and DOM unchanged.
- Run a select with a microtask reset and a select with a `setTimeout(0)` reset. Assert `applied:false` and the read-back value equals the reset value.
- Kills: "jsAct applied always true" and "JS select read-back taken before settle".

## 3. `browser_lost` must reach polls that pass `--run-id`

**Problem.**
- After Chrome dies, `ui-poll`, `ui-catalog` and `chat-poll` without `--run-id` return `agentEvents:[browser_lost]` once.
- With `--run-id=<the run the start host was started with>` they return `agentEvents:[]`, and the event is never delivered.
- The event line in `.pickleball/agent-events.jsonl` has no run, and run-scoped slices filter it out. Agents normally pass `--run-id`.

**Required.** A `browser_lost` (and any host-level event) is delivered exactly once per agent cursor, whatever `--run-id` the poll carries. At minimum it reaches the start run and polls with no run.

**Test.**
- `start --run-id=R`, then kill chromePid.
- `ui-poll --run-id=R` → exactly one `browser_lost`; a second poll → zero.
- Repeat for `ui-catalog` and `chat-poll`.
- Kill: "events filtered by run".

## 4. Scroll-search must find the virtualized container, not the first tall box

**Problem.**
- `scrollOnce` picks the first element with `overflow:auto|scroll`, `scrollHeight>800` and fewer than 30 children.
- A plain scroll panel with one tall wrapper child (very common) placed before a virtualized list is chosen. `find --scroll-search "Item 30"` then returns `not found`.
- Only one container is ever tried.
- The 40-step cap makes rows past roughly 40×0.8 viewports unreachable, with no message saying so.

**Required.**
- Prefer the container whose rendered rows change when it scrolls, or one that contains rows like the query.
- Try the other scroll candidates (and finally the document) before giving up.
- Restore every container on a miss.
- Make the cap explicit: report `scrollSearch:{container, steps, capped:true}` on a capped miss, and document it.

**Tests (real Chrome).**
- A page with a static scroll box that has one 2000 px child and sits **before** a virtualized list. Find a row that needs ≥ 10 steps. Assert it is found, and that the static box's `scrollTop` is unchanged after a hit and after a miss.
- A row beyond the cap: assert a capped miss, and assert restore.
- Kills:
  - "first tall box wins";
  - "single container only";
  - "no restore".

## 5. Entered-frame and target-correct `frame` in every reply; `shadow_path` on act replies

**What docs/dynamic-control-api.md line 35 promises:** after `frame --enter`, replies carry the entered frame's CDP id.

**Problem.** At 91234f21, after `frame --enter=cross`:
- **Replies stamped with the main frame id:** `explain` (which *is* scoped to the child: it finds `Inner` and misses `Save`), `wait`, `snap`, `inspect-page`, `eval` and `highlight`.
- **Not scoped at all:** `snap` and `eval`. `eval location.href` returns main.html.
- **Wrong hit frame:** `find Grand` labels the nested grand-child hit with the child id. Without `--enter` it correctly gets the grand id.
- **Token instead of CDP id:** the `frame --enter` reply reports `frame:"cross"`, the token.
- **Missed act after enter (about:blank frame):** an `act` that misses after `--enter=blank` reports the main id.

**Problem (other browser target).** A failed `highlight --target=browser:cdp=<another Chrome>` reports the **host's** main frame id. `AgentControl.timed` stamps the host frame onto any reply without one. Stamp only from the browser the verb targeted.

**Problem (shadow hits).** `act` replies on open- and closed-shadow hits carry no `shadow_path`. Hit rows do.

**Required.**
- Every browser reply's `frame`, on success and failure, is the CDP frame id of the frame the verb actually ran in (the entered frame, the hit's own frame, or the targeted browser's main frame).
- Either scope `snap`/`eval`/`wait`/`inspect-page`/`highlight` to the entered frame, or document per verb that they ignore it. The `frame` they report must match what they did.

**Tests (real Chrome, fresh CLI).**
- After `--enter` on a cross-site OOPIF, assert the exact child CDP id in the reply `frame` of:
  - explain (hit and miss);
  - wait (timeout);
  - snap;
  - inspect-page;
  - eval;
  - highlight (miss);
  - act (miss).
- A nested hit after enter carries the grand-child id.
- The `frame --enter` reply carries the CDP id.
- A failed `highlight --target=browser:cdp=<2nd Chrome>` carries the 2nd Chrome's main frame id.
- `act` on an open- and a closed-shadow button returns `shadow_path`.
- Kills:
  - "stamp main when entered";
  - "stamp host frame for cdp targets";
  - "drop shadow_path on act".

## 6. No test-page convention in production surface detection

**Problem.**
- `FrameCoverage.BOOT_TRAILER` sets `__pkbBoot` to `'late'` only when `document.documentElement.dataset.ran==='page'`. That attribute is set only by test pages.
- On every real page `surface:"before-page"` is therefore reported unconditionally and proves nothing. This is the same shape as the removed `data-boot` hook.

**Required.**
- Determine "runtime ran before page script" from production-observable facts only. Examples:
  - the preload records `performance.now()` in the pkb world;
  - the first page script's execution is observed through a `Runtime.executionContextCreated` / `Debugger.scriptParsed` ordering on the paused target;
  - or drop the claim and report only `surface:"ready"` when `__pkbUi` exists in the pkb world.
- No production code may read test-page attributes.

**Test.** An OOPIF page that never sets any marker attribute. Assert the reported surface state is derived from the pkb world: `__pkbUi` is an object, read through that frame's session. Kill: "surface hard-coded before-page".

## 7. Unmatched or redirected cross-site iframes must not stall every verb

**Problem.**
- `LiveTree.coverageSession` polls up to 1500 ms per empty frame owner and matches the child frame by the iframe's `src` URL.
- A cross-site iframe whose document URL differs from `src` (redirect, `location.replace`, auth or ad hops) never matches, so every find, act and snap waits 1.5 s per such frame.
- Live: 4 redirecting iframes made `find Save` take 6275 ms and `act Save` take 12345 ms (two tree builds). The same page with non-redirecting iframes took 253–267 ms.

**Required.**
- Match the child by the owner's frame id: `DOM.getFrameOwner` or `Page.getFrameTree` parent/child plus `backendNodeId`, not by URL.
- Bound the total wait per call, not per frame.
- Never wait for a frame that FrameCoverage already knows about.

**Test (real Chrome).**
- A page with 4 cross-site iframes that `location.replace` to another URL, each with a button.
- Warm `find` of a main-frame button: `elapsedMs < 1000` on two consecutive calls.
- `find` of a button inside a redirected iframe returns it with that frame's id.
- Kills: "URL-only match" and "per-frame 1500 ms wait".

## 8. Test gaps

- **Same-origin in-place branch (mutant survives).** "skip `clickInPlace` for same-origin frame nodes" survives. Either:
  - remove the branch, if the coordinate path is always correct; or
  - add a real-Chrome test where coordinates from the frame document miss: a scrolled, transformed or offset same-origin frame where the parent viewport point is wrong. The test asserts exactly one click lands.
- **Warm-verb test.** `TimingFieldsTest.realChromeWarmVerbPrintsElapsed` asserts only `< 4000 ms` and that `elapsedMs` is present.
  - Tighten the bound to what a real warm highlight measures. Live: 784–821 ms wall, about 240 ms `elapsedMs`. Use two samples and their minimum, with a documented bound (≤ 1500 ms).
  - Read the paint back from the page (`data-pkb-style` or the computed style).

## 9. Docs

`docs/dynamic-control-api.md` and the packaged copy must state:
- per verb, whether `frame --enter` scopes it, and what `frame` the reply carries (item 5);
- the scroll-search container choice, the step cap and the capped-miss fields (item 4);
- the act reply fields that separate "dispatched, no visible change" from "could not land" (item 1);
- one form of the `watch` flags, with docs and `help watch` agreeing. Today docs say `--kinds --duration` and help says `--condition --timeout`; both work.

Run `scripts/refresh_agent_index.py --check`, `verify_agent_contract.py` and `sync_consumer_guidance.py --check`.

## 10. Minor

`FallbackLog.BY_RUN` keeps one set per run id for the life of the start host. Each set is capped at 64 keys; the number of runs is not capped. Cap the number of runs remembered (oldest run first). Unit-level test through `FallbackLog.note`: after N+1 runs, the oldest run's key logs again.

## Phase 6 gaps (from verify-agent-fast-P6-8931adc1.md)

Base: 8931adc1. Same test standard as above. Phase 6 stays reopened until these are done.

### P6-1. Refs must survive unrelated DOM changes and go stale only when their target does

**Problem.**
- A browser ref is stale when `hit.docVersion != url:mutationCount`. Any mutation anywhere on the page invalidates every ref. Live at 8931adc1, a Save ref returned `stale_ref` after each of these:
  - appending one `<li>`;
  - the page changing a `<span>` clock;
  - **our own `highlight`** (attribute write).
- On real pages with clocks, spinners or ads, refs are single-use or unusable.
- The stale reply's `fix:` line is the generic act usage.

**Required.**
- Validate a ref against its own node:
  - the backend node is still attached in the same document (same URL and loader);
  - its role and name still match.
- Invalidate when the node is detached or replaced, or the document navigated.
- pkb's own writes (highlight, surface, `__pkbDoc*`) must never stale a ref.
- The stale reply says `fix: run snap (or find) again for a fresh ref`.

**Tests (real Chrome, fresh CLI).**
- snap → page appends a row and changes an unrelated span → `highlight` → `act --target=<Save ref>`. Expect `applied:true`, title read back.
- Remove the Save button (`outerHTML` replaced) → the same ref gives `stale_ref` with the snap hint, and nothing is clicked (counter stays).
- Navigate → `stale_ref`.
- Kills:
  - "never stale" (N4 survives today);
  - "any mutation stales".

### P6-2. Refs in wait

**Problem.** `wait --target=<ref>` ignores the ref. `--condition=visible` is treated as the text "visible" and times out.

**Required.** wait accepts a ref (as `--target=<ref>` or `--ref`) with conditions:
- `visible`;
- `hidden`;
- `enabled`;
- `detached`;
- `text=…` on that node.

Document them.

**Test.** A ref to a button that becomes visible, then enabled, after timers. wait on each condition succeeds. Each condition on a never-met state times out. Kill: "wait ignores ref".

### P6-3. `--snap=diff` must report the real change

**Problem.** The diff is `{title, docVersion:"url:n -> url:m", changed}`, where `n` is a page-world MutationObserver count:
- An act that appends a row says only `:9 -> :10`.
- A successful `type` reports `applied:true` with `changed:false`, because a value property is not a mutation.
- A failed act ("not applied") carries no diff at all, even when the page did change (async handlers).
- The standalone `snap --mode=diff` already produces line-level diffs; act/wait don't use them.

**Required.**
- act/wait `--snap=diff` returns element-level changes (added, removed and changed compact lines with refs), including control value or checked/selected state, against the pre-act snapshot.
- The same on failed acts.
- `changed` is true only when that list is non-empty or the title or URL changed.
- pkb's own writes are excluded.
- Keep the page-visible `window.__pkbDocWatch/__pkbDocCount` out of the main world (use the pkb world).

**Tests (real Chrome).**
| Act | Expected diff |
|---|---|
| Append row | Exactly one `added` line, `- listitem "Row 0"` |
| Type | A `changed` line showing the value |
| No-op click on a handler-less button | `changed:false`, empty list |
| Async handler | Diff on the reply after the settle |

Kills:
- "changed always true" (N3 survives today);
- "docVersion before -> before" (passes the browser test today because of a loose `contains`).

### P6-4. Desktop diff and baseline honesty

**Problem.**
- The desktop act `--snap=diff` reports `changed:true` (`desktop:0 -> desktop:1`) on a fixture act that is `applied:false`. The bump comes from the re-read, and `desktopActSnapDiffReportsTheVersionBump` asserts this as correct.
- The desktop compact `snap` never stores a diff baseline, so `snap --mode=diff` right after an identical snap reports the whole tree as `added`.
- The desktop `wait --snap=diff` has no `snap` field.

**Required.**
- Desktop diffs come from comparing the tree before and after (the hash or the compact lines). A re-read with no change gives `changed:false`.
- The compact desktop snap stores the baseline.
- Desktop wait supports `--snap=diff`.

**Tests (fixture).**
- An identical snap then `snap --mode=diff` → empty diff.
- A non-applied fixture act → `changed:false`.
- A fixture whose second read adds a button (two-file fixture or a sequence) → one `added` line.
- Desktop `wait --snap=diff` gives a `snap` field.
- Replace the current assertion that a no-op is `changed:true`. Do not delete the test; change its expected value to the honest one.

### P6-5. Default snap must be budgeted

**Problem.** On a page with 1500 buttons the default snap reply was 319 KB and took 7.5 s. It had 1500 `hits` *plus* 1500 duplicate `lines`, with no budget, no `--depth/--within`, and no spill to a file the way other compact replies spill. Each snap also builds a full `CompositeSnapshots.browser` snapshot and a second tree walk.

**Required (design E5).**
- Interactables first, with section headers.
- A default line or token budget (for example ≤ 200 lines, or ≤ 16 KB). Anything over it goes to the `.pickleball/agent-full/` file, with `truncated:true` and the path in the reply.
- `--within=<ref>` and `--depth`.
- Do not send both `hits` and `lines` for the same rows by default.
- Build one tree per snap.

**Test (real Chrome).** A 1500-button page. The default snap reply is under the budget, has `truncated:true` and a `full` file that contains all 1500 lines, and has warm `elapsedMs < 2000`. `--within` scoped to a container returns only its rows.

### P6-6. Auto-snapshot

**Problem.** The roadmap says "auto-snapshot where practical". Nothing is implemented, and no document says where it applies or that it is deferred to the Phase 7 macros (evaluation.md: `--snapshot=auto` on composite macros).

**Required.** Either:
- implement it: for example, `act`/`wait` keep a pre/post compact snapshot on disk and return its id when `--snap=auto`, with a test; or
- state in the roadmap Phase 6 row and in docs/dynamic-control-api.md that auto-snapshot ships with the Phase 7 macros, and name the macro test that will cover it.

### P6-7. Docs: a snapshot section

`docs/dynamic-control-api.md` (and the packaged copy) needs a snapshot section that covers:
- the compact line format;
- ref lifetime and staleness rules (P6-1);
- verbs that accept refs, including wait (P6-2);
- the `--snap=diff` fields and what counts as a change (P6-3/P6-4);
- the budget and spill (P6-5);
- auto-snapshot (P6-6).

Run `refresh_agent_index.py --check`, `verify_agent_contract.py` and `sync_consumer_guidance.py --check`.

### P6-8. The double click also hits async handlers (raises fixes5 item 1)

**Problem.**
- A button whose handler changes the DOM after a `setTimeout`/fetch (the common SPA case) also fires twice per `act`. The coordinate click shows no immediate change, so `clickByResolve` clicks again.
- Live: `AsyncSave` (updates a span after 150 ms) gave `as=2`, and the reply was `ok:false "not applied"`, both with and without `--snap=diff`.

**Required.** Covered by item 1, plus:
- after one dispatch, wait a short settle window (for example up to 500 ms, using the watcher) before deciding "no visible change";
- never re-dispatch.

**Test (real Chrome).** An async-handler button (150 ms `setTimeout` DOM update). One `act` gives a counter of exactly 1, and the reply reports the change; with `--snap=diff`, the diff contains the updated span line.

## Phase 7 gaps (from verify-agent-fast-P7-3c35e000.md)

Base: 3c35e000. Same test standard as above. Phase 7 stays reopened until these are done. Acceptance is "all listed bugs closed".

### P7-1. `ask-and-wait` must only return an answer to *this* question

**Problem.**
- Every ask defaults to id `q1`.
- The answer search scans the whole run's `overlay-events.jsonl` history.
- Live: the first ask was answered `Yes`. A second, unanswered ask ("Delete everything?") returned `value:"Yes"`, `applied:true`, in 226 ms.

**Required.**
- Give each ask a unique id (or require `--id`).
- Accept only answers that arrive after this ask was shown, with this id.
- On timeout: exit 1 with `error:"timeout"`, and remove the question.

**Test (real Chrome, fresh CLI).** Two asks in one run:
- the first is answered `Yes` by a page click;
- the second is unanswered with `--timeout=1500`.

Assert that the second returns `timeout`, takes ≥ 1500 ms, and has no `value`. Then answer the second with `No` and assert `No`.

Kill: "ask-and-wait ignores id".

### P7-2. `form-fill` must never write into the wrong field, and must report partial fills

**Problem.**
- A `<select>` pair (`plan=Pro`) was typed into the previously focused email input, giving `a@b.cPro`.
- A checkbox pair toggled the box but reported `applied:false`.
- A field named by its `<label for>` text gives `not found`.
- When a later field is missing, the earlier fields have already been typed, but the reply is only `error:"not found"` with no `fields` list.

**Required.**
- Per field kind:
  - `select` uses the select path (option by value or label, with read-back);
  - checkbox/radio set `checked` from `true`/`false`, with read-back;
  - text types only after confirming focus is on that node.
- Resolve keys by id, name, label text or accessible name.
- Validate that every key resolves **before** writing anything. Otherwise return `not found` with the missing keys and write nothing.
- The reply always lists each field's read-back.

**Test (real Chrome).**
- One form with a text input, a select, a checkbox, a label-only field and an upper-casing input.
- Fill all of them. Assert each value through `eval`, and assert the email is unchanged by the select.
- A run with a missing key writes nothing.
- Kill: "form-fill trusts every field" (Q3 survives today).

### P7-3. One-call `act --condition` must judge success by the condition and click once

**Problem.**
- With `--condition`, a click whose handler updates the page after 150 ms (or sets the title after 50 ms) fired **twice**: `as=2`, `cnt=2`.
- It returned `ok:false "not applied"`, and the condition was never evaluated, although it became true.
- This is fixes5 item 1 / P6-8, inherited by the prebuilt.

**Required.**
- When `--condition` is given, dispatch once.
- Then wait for the condition. Success means the condition was met: `applied:true`, with the condition read back.
- Never re-dispatch.

**Test.** An async handler with `--condition=title=Done1`:
- counter exactly 1;
- `applied:true`;
- title `Done1`.

Kill: "re-click when the immediate page mark is unchanged".

### P7-4. Never click through a covering element

**Problem.**
- When the coordinate click lands on a covering element (a modal backdrop or full-page overlay), the `clickByResolve` fallback runs `this.click()` on the target behind it.
- Live: a button under a full-page `div` was clicked (`as=1`).

**Required.**
- Before or after dispatch, hit-test the click point (`elementsFromPoint`, including shadow roots).
- If another element covers the target, return `applied:false`, `error:"covered"`, `coveredBy:<role/name or selector>`, and do not click.

**Test.**
- A button under a full-page overlay: counter 0, `error:"covered"`.
- After the overlay is removed: counter 1.

### P7-5. `visible` must mean a user can see it

**Problem.** These all report `visible:true`:
- `left:-9999px`;
- `opacity:0`;
- a 0×0 box;
- a target fully covered by another element.

**Required.**
- `visible` requires all of: a non-zero box intersecting the viewport (or scrollable into it), effective opacity > 0, and the centre point not covered.
- Report the reason (`offscreen`, `transparent`, `zero-size`, `covered`) in `hiddenReason`.

**Test (real Chrome).** One handle per case. Assert `visible:false` with the exact reason, and `visible:true` for a normal button.

Kills:
- "visible ignores the viewport";
- "visible ignores coverage".

### P7-6. `overlaps` must reflect what is on the page now

**Problem.** `SEEN` is an in-memory list that is never pruned.
- After `ui remove` of `pkb-12`, the next overlay reports `overlaps:["pkb-12"]`.
- After `ui clear`, a new overlay reports `["pkb-2","pkb-4","pkb-5"]`.
- Handle labels, pin, toast and checklist are not included.

**Required.**
- Compute overlaps from the placements currently on the page (or prune on remove, clear and toast expiry).
- Include every placement kind.
- Placement layout avoids existing placements (B8: "should avoid other placements"), or the reply says it could not.

**Tests.**
- Overlay A → remove A → overlay B on the same spot: `overlaps:[]`.
- Clear → `[]`.
- Label on a handle under an existing overlay: its id appears in `overlaps`.

### P7-7. pin / toast / checklist / status are real, distinct cards

**Problem.**
- All of them place a plain label at a fixed `x=24,y=24`. Two pins and a checklist stack on the same spot.
- `checklist` is the items joined by spaces, with no checkboxes or state.
- `status` is routed in `AgentControl` but documented as "not a separate command".

**Required.**
- Cards stack without overlapping (or use the `overlaps` layout from P7-6).
- `checklist` renders one row per item with a check state, and a way to tick an item (`checklist --id --check=<n>`), read back.
- Decide whether `status` is a verb or only a `ui-apply` component, and make the code and docs agree.

**Test.**
- Two pins and a checklist: their boxes do not intersect (read rects in the pkb layer).
- Checklist items appear as separate rows; ticking item 2 marks only item 2.

### P7-8. Mask quiet must not silently stall on unrelated progress bars

**Problem.**
- Any visible `role=progressbar` or `aria-busy` element anywhere delays every click by the full timeout: 3 s by default, 30 s with `--timeout=30000`.
- The click then proceeds anyway, and the reply does not say so.
- A non-ARIA mask is not detected (see P7-4).
- The engine step click (the B11 subject) is unchanged.
- MaskQuietTest hung until its 180 s timeout once in 8 runs.

**Required.**
- Wait only for a mask that covers the target's click point (hit-test), or one that is a modal/backdrop ancestor of what's at that point.
- Report `waitedMs` and `maskCleared:true/false`.
- Apply the same quiet-wait to the engine click step (B11) or document why not.
- Find and fix the hang.

**Tests.**
- A page with a small permanent upload progressbar: a click completes in `elapsedMs < 1000`, with `waitedMs:0`.
- A full-page busy mask removed after 900 ms: `waitedMs ≥ 800`, `maskCleared:true`.
- Engine step: a Gherkin click step on the same mask page waits as well.
- Repeat MaskQuietTest 10×: no hang.

### P7-9. B9: the step dictionary must not leave the driver inside an iframe

**Problem.**
- `ExecutionDictionary.registerIframe` / `registerTopLevelIframe` call `ElementOperations.enterFrame`, and nothing restores `defaultContent` after the step. The phase 7 scoreboard note admits this.
- Phase 7 acceptance requires all listed bugs to be closed.

**Required.** After every step, success or failure, restore the frame context the step started in, or report the frame it left the driver in. Add `frame` to step replies.

**Test (real Chrome, a Gherkin run through the production step path).**
- A step clicks a button inside an iframe.
- The next step clicks a main-page button: it succeeds without any frame step.
- A failing in-frame step also restores the frame.

### P7-10. `text=`/`role=` with composite off must not invent visibility or enablement

**Problem.** `ElementOperations.snapshotQuery` builds every `ElementView` with `displayed=true`, `enabled=true` and a 0,0,0,0 box. Hidden or disabled matches are reported as visible and enabled. There is no shadow DOM or iframe coverage.

**Required.** Either:
- resolve the matches against the live page (box, displayed, enabled); or
- mark these views `state:"unknown"` and make callers treat them that way.

Document the coverage limits.

**Test.** `text=Save` on a page with a hidden Save and a disabled Save: each view's displayed/enabled matches the page.

### P7-11. Tests that drive the real paths

- **NavigateDataFileTest** must run the Gherkin `navigate to:` step itself (production step path) for `file:`, `data:` and `about:blank`, and read the page back. Today it calls the static helper and then `start --url=`.
- **ConfirmTest**: remove the already-true `assertFalse(... "Other note")` / `"Gamma"` filler, or replace it with assertions that can fail (for example, a checklist read back as distinct rows).
- **Warm verb (Phase 2 target ≤ 1 s)**: `TimingFieldsTest.realChromeWarmVerbPrintsElapsed` asserts `< 4000 ms`, so the 1201 ms in the phase 7 scoreboard didn't fail anything. Enforce ≤ 1000 ms on the minimum of 3 samples, or justify a documented looser bound. Probe today: 767–854 ms idle.

### P7-12. Docs: prebuilt section

Add a "Prebuilt one-call functions" section to `docs/dynamic-control-api.md` (and the packaged copy). It must cover:
- act `--condition` semantics (P7-3);
- form-fill field kinds and partial-fill reporting (P7-2);
- ask-and-wait ids and timeout (P7-1);
- that `confirm` is the scenario command and `ask-and-wait` is the agent confirm;
- pin, toast, checklist and status (P7-7);
- `visible` reasons (P7-5);
- overlaps (P7-6);
- the mask-quiet rule (P7-8).

Release notes must list the remaining B-items honestly. Run the three doc check scripts.

## CI flake (added 2026-11-19 7:24 PM MST, low priority, do after the listed gaps)
- F-1: On Windows CI, EmbeddedMultiProcessTest.sixteenProcessesWriteWithoutLosingARow failed once on 0ec2f3fa (run 44658537706): workers 9 and 14 exited with SQLITE_BUSY "database file is locked". Make the multi-process write path handle contention correctly (busy timeout / retry in production code, not by weakening the test), and keep the test's no-lost-row assertion.
