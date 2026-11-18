# Evaluation: external consumer field report vs Pickleball 3.0.0

- **Date:** 2026-11-18, about 4:41 PM MST (America/Phoenix)
- **Evaluator:** pickleball · Major release 3 (read-only; no commits, pushes, merges, branches, or Grok chats)
- **Code inspected:** `github.com/ds-code-t/pickleball` `origin/3.0.0` at `f5ccc9f4a0272188ef0c0eca15e0cdcfd64fc92b` (merge of PR #25, consumer-ready), detached worktree `/workspace/pickleball/wt-eval-3.0.0`
- **Report:** 2026-11-18 Workbench field report from a Windows Maven consumer (visible Chrome, iframe app, SSO). Cleaned copy also at `/workspace/pickleball/consumer-reports/field-report-2026-11-18.md`
- **Method:** branch docs first (`AGENTS.md`, `docs/consumer-agent-guide.md`, `docs/pickleball-workbench.md`, `docs/workbench-agent-detail.md`, `docs/dynamic-control-api.md`, `docs/direct-control-handoff.md`, `docs/snapshot.md`, `docs/custom-element-definitions.md`), then code and grep. No full Gradle build or regression. No `./gradlew --stop`. Port 8765 left free. B1 was confirmed by code inspection of the popover/`toggle` cycle (no browser PoC).

Design principles applied to the plan (from Dan):

1. **Bugs first**, then agent-efficiency and responsiveness.
2. **ENGINE tier** = universal, stable, reliable core used by any script or scenario for DOM / desktop interaction, and as automatic fallback when a finer path fails.
3. **AGENT tier** = finer-grained or experimental controls exposed to AI agents (CLI / API / MCP), especially human collaboration and DOM/desktop probing while debugging.
4. **One target-agnostic higher-level API** for browser/DOM and OS/desktop, implemented per target behind the same call. Keep it holistic and encapsulated; avoid extra layers.
5. Keep it streamlined. Inventory existing surfaces before adding verbs.

---

## Verdict summary (B1–B16)

| ID | Verdict | Severity | One-line fix |
|----|---------|----------|--------------|
| **B1** | **Confirmed in code** | critical | Ignore layer-originated `toggle` events and/or guard `raiseLayer()` against re-entry; never hide+show the layer's own popover from a capture `toggle` listener. |
| **B2** | **Confirmed in code** (docs over-promise the CLI path) | high | When a live session/worker exists, CLI agent verbs must POST `/v1/agent/command` (same path as MCP). Fail fast with `error: no live browser` instead of an 8s dead-end or a closed popup. |
| **B3** | **Confirmed in code** / docs mismatch | medium | Either make `start` actually launch a headed browser and navigate (or alias `live-start`), or change the docs so "publishes a live session" is not read as "opens a browser." |
| **B4** | **Confirmed in code** | high | On worker stop/restart, `WebDriver.quit()` and `ProcessTrees.killTree` the worker JVM (Chrome/chromedriver children). Tell the agent `browser_lost`. |
| **B5** | **Confirmed in code** (lease is 120s; poorly documented; renewal can stop) | high | Document the 120s pause lease and 30s renew. Keep the anchor paused while an MCP/controller client is attached, or expose `--hold`. Emit a session-ended / `browser_lost` event. |
| **B6** | **Plausible — likely a symptom of B1** | medium | Fix B1 first and re-test. Injection path (`history` / `pushChat`) looks correct for channel `run`. If messages are still invisible, check panel CSS and `chat()` skip rules after the loop is gone. |
| **B7** | **Confirmed in code** (missing visibility in the reply) | low | Include `visible` / `hidden` / `lost` on placement replies; do not treat a hidden `text:` match as unqualified success. |
| **B8** | **Confirmed in code** | low | Placement layout should avoid other placements, not only the target; return `overlaps:[ids]`. |
| **B9** | **Confirmed** that replies omit the current frame; **plausible** that a failed step can leave the driver in an iframe | medium | Always restore (or report) frame context after a step; include `frame` in step and inspect replies; add `frame --list/--reset/--enter`. |
| **B10** | **Confirmed in code** | medium | Inspector should search the same contexts steps do (or say it does not). Prefer a compact snapshot-backed inspect over a second walk. |
| **B11** | **Plausible-unverified** | medium | ENGINE: wait for a Loading/mask quiet period, or verify the selected tab actually changed, before reporting click SUCCESS. |
| **B12** | **Confirmed in code** | low | Optionally accept `data:`, `file:`, and `fixture:` in `navigate to:` (or a self-test fixture verb). Today only `http://` / `https://` (and config keys) resolve. |
| **B13** | **Confirmed in code** / tool-description mismatch | low | Tool text must say `text=` / `near=` / … need `pkb_composite=on`. Better: snapshot-backed inspect so those selectors work without a surprise flag. |
| **B14** | **Confirmed in code** | medium | Quote `"-Dmdep.includeScope=test"` in the generated PowerShell opener; include `target/classes` and `target/test-classes` on the fallback classpath. |
| **B15** | **Not a code bug; docs mismatch** | low | Align AGENT-GUIDE / isolate wording with `configuration.md`: a new `--run-id` needs `workspace-seed` or a prior discover workspace. Default isolate (no new id) still uses the last Discover snapshot. |
| **B16** | **Confirmed as a real gap** (shadowing works; no warning on execute) | high | Flag empty consumer glue as `noop:true` on `execute-step` and in the run report; `validate --noop` lists them. Decide with Dan whether a no-op is a warning or a failure. |

---

## Bug evidence

### B1 — In-page chat panel endless loop (critical) — Confirmed

`pkb-runtime.js` `raiseLayer()` hides then shows the `<pkb-layer>` popover whenever it is already open:

```129:136:src/main/resources/tools/dscode/browser/surface/pkb-runtime.js
  function raiseLayer() {
    if (!layer || !layer.isConnected || typeof layer.showPopover !== 'function') return;
    try {
      if (!layer.hasAttribute('popover')) layer.setAttribute('popover', 'manual');
      if (layer.matches(':popover-open')) layer.hidePopover();
      layer.showPopover();
    } catch (ignored) { /* z-index fallback */ }
  }
```

A capture-phase document listener on `toggle` calls `raiseLayer()` again with **no** `e.target === layer` filter and **no** re-entry guard:

```480:480:src/main/resources/tools/dscode/browser/surface/pkb-runtime.js
    on(document, 'toggle', function () { raiseLayer(); schedule(); }, true);
```

`panelOpen()` calls `listen()` then `raiseLayer()` (lines 1281, 1295), which starts the cycle. Labels only call `raiseLayer()` when a page `<dialog>` appears (`onMutations`, line 459), which matches the report (labels alone were cheap; a page dialog would start the same loop). The loop explains Chromedriver stalls in `getPageSource` / `CONTROL_TIMEOUT` (60s default at `ControlBridgeCoordinator.DEFAULT_COMMAND_TIMEOUT_SECONDS`, line 87) and the subsequent watchdog restart.

**Fix direction (ENGINE, because the runtime is shared by every live placement):** `if (e.target === layer) return;` and/or skip hide+show when the layer is already the top popover; add a re-entry flag. Add a `selftest ui --cpu` check so this cannot regress.

### B2 — CLI page UI verbs never reach the live isolate browser (high) — Confirmed

Two different CLI paths:

- `inspect-page` / `find-element` use `liveQuery()`, which resolves the live session and POSTs to the worker (`WorkbenchAgentCommands.java` 1689–1728, `source=live-session` at 1759).
- `panel-open`, `label`, `windows`, `tabs`, `chat-send`, `ui-apply`, and the other agent verbs go to `agentControl()` which calls `AgentControl.execute(...)` **in the short-lived CLI JVM** (1865–1951, especially 1944–1950). That JVM has no WebDriver. `PageSurfaces` then reports `no live browser; label was not applied` (PageSurfaces.java 386) or `panel did not open on the page` (AgentControl.java 327). `panel-open` with `target=auto` and no live page falls back to `popup-open` (296–305), which matches `chat-send` reporting `ok` with `target: popup` and `chat-status` `state: closed`.

MCP `workbench_agent_command` forwards to the worker via `ControlBridgeClient.agentCommand` → `POST /v1/agent/command` (`ControlBridgeClient.java` 321–333).

Docs:

- `docs/dynamic-control-api.md:110` is narrowly true: "The CLI calls that same class directly." It does not say that class runs in a JVM **without** the driver.
- `docs/consumer-agent-guide.md:15` and `docs/ai-run-configuration.md:469` say `start` publishes a session "later page commands use" and "An IDE MCP client is not required." That is the over-promise: without MCP (or a forwarder), page UI verbs do not reach an isolate browser.

**Fix (AGENT, with ENGINE-stable forwarding):** reuse the existing `liveQuery` / bridge POST. Do not add a third implementation.

### B3 — `start --url --browser` does not start a browser (medium) — Confirmed / docs mismatch

`ConsumerReadyCommands.start` (lines 39–68) writes `.pickleball/consumer-session.json` with `live=true` and returns. It does not launch a browser, start a worker, or navigate. Docs call this "publishes a live session." The file is real; a browser is not.

### B4 — Worker restart leaves old Chrome running (high) — Confirmed

`WorkbenchWorkerManager.stopActive()` (193–216) resumes the anchor, then `process.destroy()` / `destroyForcibly()` on the **worker JVM only**. It does not call `WebDriver.quit()`, `AgentDriverHooks.quit()`, or `ProcessTrees.killTree`. `ProcessTrees` is used on the isolate/CLI stop path (`WorkbenchSessionCommands.java` 1039, 1046) and Maven watchdog (`ConsumerMavenTestRunner.java` 271), but **not** on the MCP worker manager. On Windows, killing the parent JVM often leaves chromedriver/Chrome. Health-watch restart is `pollHealth` → `restartInteractive()` (238–260) after two failed health checks (`WorkerRestart.FAILURES_BEFORE_RESTART = 2`).

### B5 — Paused live scenario ends while idle (high) — Confirmed

Lease constants: `PAUSE_LEASE_SECONDS = 120`, `LEASE_RENEW_SECONDS = 30` (`WorkbenchWorkerManager.java` 35–36). Renewal is `scheduleLeaseRenewal` (757–770): every 30s it calls `pause(..., PAUSE_LEASE_SECONDS)`. On non-SUCCESS or exception it **shuts the renewal executor down** (765–768). If the bridge is wedged (B1), renewal stops and the 120s lease expires — the report's "about 2 m 16 s after the last agent call" fits 120s plus slack. The same 120s default is `ControlBridgeCoordinator.DEFAULT_PAUSE_LEASE_SECONDS` (line 86). This is not documented in the consumer agent guide. Nothing emits a `browser_lost` event to `ui-poll` / `chat-poll`.

### B6 — Chat panel shows no messages (medium) — Plausible, likely B1

The inject path exists and looks right: default chat channel is `run` (`ChatLog.java` 26); `chat-send` calls `PageSurfaces.pushChat` for that channel (`AgentControl.java` 268–274); `panelOpen` loads last 50 history rows (322–326); `chat()` appends bubbles when `row.text != null` (`pkb-runtime.js` 1050–1067). But `raiseLayer()` continuously `hidePopover()`/`showPopover()` on the layer that holds the panel (B1), which can make the message list look empty (scrollbar without readable content). Re-test after B1. Residual risks: CLI `chat-send` never reaches the page (B2); history mixes earlier runs in the same run-id file (report inefficiency).

### B7 — Hidden targets report success (low) — Confirmed (reply gap)

`PageSurfaces.place()` returns `id`, geometry, `sha256`, etc. (465–497) with **no** `visible` / `hidden` / `lost`. `findTarget('text:…')` (`pkb-runtime.js` 576–588) uses `querySelectorAll` + `innerText`/`value` equality and does **not** check `getClientRects`, `hidden`, or CSS `display`/`visibility`. A narrow-layout hidden control can still match.

### B8 — Placements overlap (low) — Confirmed

`beside()` (240–254) picks a side that fits the **viewport vs the target** only. There is no overlap test against other placements.

### B9 / B10 — Frame context (medium)

- Inspect (`ElementControl.inspect`, 77–79) runs `ElementOperations.describe(driver, By.xpath(...))` → `ElementReads.describe` (104–128), which queries the **current document only**. No `AllContextsScan`. Replies have no `frame` field.
- Step find with `CategoryFlags.ALL_CONTEXTS` walks current doc, top doc, frames, and open shadow roots, then restores in a `finally` (`AllContextsScan.java` 22–26, 30–31, 160–161, 371–396). **Only flagged categories get that walk.** Built-in `Loading` has the flag (`ExecutionDictionary.java` 343–347). Built-in `Link` / `Tab` / `Button` do **not** (`DefinitionContext.java` 588, 673, 709). Docs are explicit: "Only a flagged category searches every context. `Button` does not." (`docs/custom-element-definitions.md:92`).
- Explicit iframe categories call `enterFrame` and leave the driver there (`registerIframe` / `registerTopLevelIframe`, `ExecutionDictionary.java` 1226–1255). A failed click after an enter, or a path that does not restore, matches "frame changed silently."
- The report's claim "steps search iframes, inspect does not" is **partly overstated** if they used plain `Link`/`Tab` without `ALL_CONTEXTS`. A click "inside an iframe" can still happen once the driver is already in that frame (B9). Inspector and steps can then disagree because inspect never leaves the current document and never reports which frame that is.

### B11 — Tab clicks succeed during a loading mask (medium) — Plausible-unverified

`Loading` exists and is `ALL_CONTEXTS` with a built-in locator (spinner-ish XPath in `ExecutionDictionary`). Lean waits use `document.readyState` plus a short MutationObserver quiet period (`LeanWaits.java` 97–145, 214–242), **not** "mask gone / selected tab changed." No evidence of a generic "do not report click SUCCESS if a loading mask is showing or the tab did not change." Not reproduced here (no live app).

### B12 — `navigate to:` rejects `data:` and `file:` (low) — Confirmed

`NavigationSteps.addressAfterConfigLookup` / `isHttpUrl` (43–64) accept only `http://` and `https://` (or a config-key lookup). Anything else, including `data:` and `file:`, throws `URL resolved to 'null'` (line 35).

### B13 — Engine `text=` needs composite (low) — Confirmed / docs mismatch

`ElementOperations.describe(..., EngineQuery)` (288–298) throws `engine query needs the composite runtime` unless `CompositeRuntime.primary()` (`pkb_composite=on`). CSS/XPath can fall back to Selenium. `workbench_element_inspect` describes `text=`, `near=`, `below=`, etc. as supported (`WorkbenchMcpTools.java` 173) without mentioning the flag.

### B14 — PowerShell opener quoting (medium) — Confirmed

Generated script in `PickleballLocalStore.java` line 728:

`& $mvnw ... -Dmdep.includeScope=test "-Dmdep.outputFile=$resolved"`

The includeScope argument is unquoted, so PowerShell splits on `=`. After Maven fails, the script falls back to `java -jar` on the thin jar (739–740), which lacks Jackson (`NoClassDefFoundError` as reported). The cache path also prefers `.pickleball/v/$version/workbench/classpath.cache`; fallback resolved file is `.pickleball/workbench/classpath.resolved` and does not add `target/classes` / `target/test-classes`.

### B15 — `isolate` with a new run id needs a seed (low) — Docs mismatch, not a code bug

Code already errors `no workspace for run id <id>` and tells you to run `workspace-seed` or discover (`WorkbenchSessionCommands.java` 576; also `docs/configuration.md:573`). `docs/workbench-agent-detail.md:39,156` and the agent guide say isolate starts from the **last Discover snapshot**, which is true when you do not pass a fresh `--run-id`. The guide should say the new-id case explicitly. The report's "guide vs consumer bridge disagreed" is real as a consistency issue.

### B16 — No-op consumer glue shadows phrases (high) — Confirmed gap

`workbench_step_resolve` can already return `CONSUMER_GLUE` plus method/source (`ControlBridgeStepResolver.java` 53–68; tool text at `WorkbenchMcpTools.java` 133). Resolution does **not** inspect the method body. `execute-step` has no `noop` flag and the run report does not warn. Empty `@Then("I click the {string} Link")` can therefore report SUCCESS in ~100ms without clicking. Pickleball's own phrase is `*, click the "X" Link` — different text, but a consumer phrase that matches common English will win.

---

## What the report got wrong or overstated

- Commit hash in the report is split with a space (`…e0cdcf d64fc92b`); the real tip is `f5ccc9f4…cdcfd64fc92b`. Content still matches this merge.
- "The docs say the CLI calls that same class directly" is a real quote, but the deeper mismatch is the guide saying MCP is not required for page commands.
- "Steps search iframes, inspect does not" is only generally true for **`ALL_CONTEXTS` categories** (and for a driver already inside a frame). Plain `Link`/`Tab`/`Button` do not get the all-contexts walk. Inspect definitely does not walk frames and does not report the current frame.
- `start` "does not start a browser" is correct; "the docs say it publishes a live session" is also literally correct. The bug is over-promising language, not a crashed command.
- B15 is documented in `configuration.md` already; the gap is the shorter guides.
- Several "proposed" helpers **already exist in some form** (see P7, `ui-apply`, `chat_wait`, mapping snapshot/restore, `session hold`, inspect `--explain`). The cost review still stands: they are hard to discover and not wired into one live-start path.
- `panel-open` ~8s is the CLI-without-driver path (B2), not an inherent page-open cost. Through MCP, the report itself measured 40–276 ms before the B1 loop took over.

---

## Proposals P1–P14

| ID | Worth it? | Size | Duplicates / notes | Tier |
|----|-----------|------|--------------------|------|
| **P1 `live-start`** | **Yes** | M | Do not invent a second start path: extend or replace today's `start`, which only writes a JSON file. | AGENT (uses ENGINE session/worker) |
| **P2 `call`** | **Yes** (or fold into P3) | M | Needed for consumers that cannot register IDE MCP. One loopback controller; `--args-file` / `--args-b64`. Don't build a second tool catalog. | AGENT |
| **P3 CLI verbs forward to the worker** | **Yes — do this with or before P2** | S–M | This *is* the B2 fix. Prefer this over teaching every verb twice. | AGENT façade, ENGINE-stable bridge |
| **P4 compact agent view** | **Yes** | M | `--explain` already exists on live inspect. No compact default; page/screenshot still dump everything. `screenshot --out=` is new. | AGENT default; ENGINE can keep full artifacts on disk |
| **P5 `doctor`** | **Yes** | M | Nothing like it. Huge diagnosis-cost cut. | AGENT |
| **P6 lease and restart control** | **Yes** | S–M | Fixes B4/B5. `session hold` exists but is a different mechanism. | ENGINE lease/restart; AGENT `--hold` / events |
| **P7 chat helpers** | **Maybe** (partially exists) | S | **`chat_wait` / `workbench_chat_wait` already exist** (`ConsumerReadyCommands.chatWait`, MCP tool ~442). Add session-scoped channels, start-at-session cursor, and `chat-watch`. Don't invent a second wait verb. | AGENT |
| **P8 `selftest ui`** | **Yes** | M | Would have caught B1. Fixture pages also address B12. | AGENT (uses ENGINE runtime) |
| **P9 UI verify** | **Yes** (with B7/B8) | S | Natural reply fields; `ui-verify` can be a thin read. | AGENT |
| **P10 frame tools** | **Yes** | M | See B9/B10. Prefer one `inspect`/`frame` API for browser and desktop (desktop: window/process). | ENGINE context; AGENT listing |
| **P11 step resolution guards** | **Yes** | S–M | B16. `step_resolve` already returns kind+method. | ENGINE warning on execute; AGENT `validate --noop` |
| **P12 faster first call** | **Yes** | S | Includes B14. `pkb`/`pkb.cmd` shim + `export-guidance --warm`. | AGENT launcher |
| **P13 guidance and help** | **Yes** | S | One "work with a person in the browser/desktop" recipe; `help <mcp-tool>` with schema+example; consistent isolate/CLI routing text. | AGENT |
| **P14 `live-stop --all`** | **Yes** | S | Pairs with B4. Use `ProcessTrees`, don't add a third killer. | AGENT |

Estimated-effect math in the report (156 → ~40 actions) is plausible **if** B1–B5 and P1–P5 ship; it is not a measurement.

---

## How 3.0.0 handles iframes, shadow DOM, and waits today

This is what the code does. A separate research task covers external options.

### Iframes

| Surface | Behavior today | Evidence |
|---------|----------------|----------|
| Step find (`ALL_CONTEXTS` only) | Walks current document, then top document, then frames (max 20 frames, depth 5), then restores the driver in `finally`. Implicit wait is zero for the scan. | `AllContextsScan.java` 22–26, 30–31, 152–161, 285–325, 371–396 |
| Built-in categories | Only `Loading` is flagged `ALL_CONTEXTS` by default. `Link` / `Tab` / `Button` are not. | `ExecutionDictionary.java` 343–347; `DefinitionContext.java` 588, 673, 709; `docs/custom-element-definitions.md:92` |
| Explicit iframe categories | `registerTopLevelIframe` / `registerIframe` call `defaultContent` (top-level) and `enterFrame`, and **leave the driver in that frame**. | `ExecutionDictionary.java` 1226–1255 |
| Inspect / `workbench_element_inspect` | Current document only. No frame path in the reply. | `ElementControl.java` 77–79; `ElementReads.java` 104–128 |
| Injected runtime `findTarget` | `document.querySelectorAll` / `document.evaluate` in the **host document**. Does not enter iframes. Page UI on iframe *content* will miss unless the script is injected into that frame. | `pkb-runtime.js` 576–615 |
| Composite snapshot | Records `frames[]` (`id`, `parent_id`, `url`, `frame_path`, `offset`) and each node has `frame_id` / `frame_path`. | `docs/snapshot.md` Schema |
| Agent replies | No current-frame field on step or inspect results. | (absence in `ElementControl` / execute-step payloads) |

**Gaps:** inspect ≠ step context; silent frame stickiness; runtime placements are top-document; no `frame` in compact replies.

### Shadow DOM

| Surface | Behavior today | Evidence |
|---------|----------------|----------|
| All-contexts scan | Open shadow roots only (`host.shadowRoot`). Closed roots are skipped (`// closed shadow root, stale host, or cross-origin`). Depth shares the 5-level cap with frames. | `AllContextsScan.java` 61–84, 262–275, 337–348 |
| Explicit shadow categories | `registerTopLevelShadowRoot` / `registerShadowRoot` use `ElementOperations.shadowRoot(host)` and return that `SearchContext`. | `ExecutionDictionary.java` 1258–1285 |
| Injected UI itself | Rings/panel live in an **open** shadow root on `<pkb-layer popover="manual">`. That is why a normal DOM inspector cannot see chat messages (as the report noted). | `pkb-runtime.js` 4, 112, 950; `docs/dynamic-control-api.md:130` |
| `findTarget` / placements | Does not pierce page shadow trees (only skips Pickleball's own `[data-pkb-agent]` / `[data-pkb-layer]` via `ours()`). | `pkb-runtime.js` 138–141, 576–615 |
| Hit-test in LeanWaits | `composedPath()` so a shadow-retargeted center click can still count as hitting the element. | `LeanWaits.java` 244–271 |
| Snapshot | Nodes carry `shadow_path` (host XPath chain joined by ` >> `). | `docs/snapshot.md` |

**Gaps:** closed shadow is invisible to the Selenium walk; page-UI targeting does not pierce consumer shadow trees; inspect does not report shadow path.

### Event-driven vs polling waits

**Injected runtime (event-driven, then one rAF):** scroll (capture, so nested scrollers count), window / `visualViewport` resize and scroll, `popstate` / `hashchange` / `pageshow`, `transitionend` / `animationend`, `toggle`, `visibilitychange`, `ResizeObserver` on targets, `MutationObserver` on `documentElement` (childList, subtree, characterData, attributes `class|style|hidden|open`). Reposition is **once per animation frame**, not a timer poll (`schedule` / `update`, `pkb-runtime.js` 433–436, 466–491). Exceptions: restore after navigation uses `setInterval(..., 500)` (line 1420); `ui-poll --wait` is a server-side wait (report: ~25.5s when idle).

**The B1 bug turns that event-driven design into a tight synchronous loop** because `toggle` → `raiseLayer` → hide/show → `toggle`.

**ENGINE waits are mostly polling, with one MutationObserver assist:**

- Page ready: FluentWait, **500 ms poll**, until `document.readyState === 'complete'`, then a quiet period (`LeanWaits.java` 104–145).
- Quiet period: in-page `MutationObserver` on `document`, plus `setTimeout` checks every `min(quietMs, 250)` until 300 ms with no mutations, capped at ~5s / `6 * quietMs` (214–242). Comment: uses `setTimeout` rather than rAF to avoid background-tab throttling.
- Element visible / present: FluentWait **150 ms poll** (149–193), unless composite `waitOn` is used.
- Composite live wait: poll **150 ms**, matching LeanWaits (`DomCompositeAdapter.java` 699–710).
- Optional start delay: `waitMilliseconds(startWaitMilliseconds)`; window change adds a **2000 ms** sleep (68–76).
- Agent-side: `UiBatch` sleeps 250 ms (`UiBatch.java` 226); some AgentControl polls sleep 200 ms.

**Gaps:** no event-driven "wait until mask gone / tab selected / text present" for steps (B11). Composite wait is still a 150 ms poll. Runtime is event-driven for tracking, but not exposed as a wait API for agents (`wait-for-state` is a proposed macro). Loading masks that do not mutate the observed attributes can sit through the quiet period.

---

## Snapshots in 3.0.0 (what the word means)

Several different "snapshots" exist. Do not conflate them.

| Kind | What it is | Hooked to the control engines? |
|------------------|--------------------------------|
| **Composite snapshot** (`pickleball.composite-snapshot` v1) | The important one for DOM + desktop. Nodes, frames, pre-map indexes, viewport, optional screenshot. Produced by `domengine snapshot --session`, `domengine desktop snapshot`, `domengine premap --file`. Platforms: `chrome`/`chromium`/`edge`/`firefox`, `windows-uia`, `macos-ax`, `linux-atspi`. | **Yes.** `DomCompositeAdapter.snapshot()` requires `capabilities.snapshot_schema` (372–383). Runtime queries use `PreMapLookup` (cache hit skips a DOM reread). Desktop `click`/`type`/`walk` bump `desktop:<sequence>`. Selenium façade path: `FacadeSnapshotProducer`. Schema and behavior: `docs/snapshot.md`. |
| **Last Discover snapshot** | Workspace/config fingerprint used by `isolate` when you do not pass a new run id. | Workbench session only, not a page/desktop image. |
| **Store / sandbox branches** | Entity store + git-like sandbox branches (`docs/store-entities.md`). | Data, not UI. |
| **Mapping snapshot** | `workbench_mapping_snapshot` / `workbench_mapping_restore` already exist. | Mapping memory only. |
| **PlatformSnapshot** | Initiator/environment snapshot in `PlatformSnapshot.java`. | Not a UI tree. |
| **Diagnostic visual fingerprints** (`.pkbf`, `comparisonToPrevious`) | Run reporting / visual diff (AGENTS.md protocol). | After a test run, not a live control primitive. |
| **Guidance export** | Versioned docs under `.pickleball/v/<version>/`. | Docs, not DOM. |

**How the engines connect today:** the Java control engine can capture and query a composite snapshot for **both** a browser session and a desktop accessibility session, with one schema. Premap indexes (`by_role`, `by_text_token`, `interactables`, …) are the compact read path. Workbench inspect / `workbench_browser_page` / page UI **do not use this by default**. Inspect still walks the live document (and needs `pkb_composite=on` for `text=`). Desktop click/type already go through `DomCompositeAdapter` when the element is a desktop source (`click` 528–533, `type` 536–549). Agent page surfaces route desktop sessions to **native highlight** or the **popup**, not the in-page runtime (`AgentSurfaceRouter.java` 262–276).

---

## How 3.0.0 splits browser vs desktop control (duplication)

Today an agent sees **several catalogs**, not one call with two backends:

| Catalog | Browser | Desktop | Evidence |
|---------|---------|---------|----------|
| Pickleball steps / Selenium + optional composite | DOM categories, clicks, LeanWaits | Not this path | Core engine |
| `DomCompositeAdapter` + `domengine` CLI | query, click, type, snapshot (when `pkb_composite=on`) | `desktop list/attach/walk/query/resolve/close/click/type` | Adapter header line 23; desktopAct 528–549; `docs/direct-control-handoff.md` 184–186 |
| MCP `workbench_element_inspect`, `workbench_browser_page`, `workbench_browser_screenshot` | Browser only | No desktop equivalent | `WorkbenchMcpTools.java` 169–173 |
| MCP `domengine` | Raw CLI forwarder (`query`, `desktop`, `session`) | Same raw CLI | `WorkbenchMcpTools.java` 408–413 |
| `workbench_agent_command` + `PageSurfaces` / `pkb-runtime.js` | In-page panel, callouts, labels, … | Not injected into the OS UI | Page-only runtime |
| `AgentSurfaceRouter` | `auto` → page | `auto` → popup or `native` highlight | 262–276; `docs/direct-control-handoff.md` 226–237 |
| `NativeHighlight` | unused in auto-browser | Screen-space box from bounds or desktop query JSON | `NativeHighlight.java` 10–11, 64 |

Duplication / layering to avoid making worse:

- Click/type exist on steps, on `DomCompositeAdapter.act`, and on the raw `domengine` CLI. Fine as one ENGINE backend; do **not** add a fourth.
- Inspect vs `domengine query` vs snapshot premap are three read paths. Plan: **one** `inspect` / `page` call, snapshot-backed when the engine is joined, Selenium walk as ENGINE fallback.
- Human-visible surfaces are split (page runtime vs popup vs native highlight). Plan: **one** `highlight` / `ask` / `chat` / `panel` call; backend is page, popup, or native. No new "desktop-label" verb beside `label`.
- MCP `domengine` as a raw argv tunnel is an escape hatch, not the agent-facing API.

**Rule for new work:** one target-agnostic function (`inspect`, `act`, `wait-for`, `snapshot`, `highlight`, `ask`, `chat-send`, …) with `--target=auto|browser|desktop` (default auto from the live session). Browser backend and desktop backend behind that call. No extra façade layer on top of `AgentControl` + `DomCompositeAdapter`.

---

## Further agent-efficiency gaps (beyond the report)

1. **Discoverability:** five long docs and a raw `domengine` tunnel. Agents grep instead of following one recipe (P13).
2. **Three ways to talk to a live browser** (CLI local `AgentControl`, isolate session POST, MCP worker) that do not behave the same (B2).
3. **No compact default** on the hottest reads (inspect, page, screenshot, execute-step).
4. **Composite/snapshot is off by default**, so the good compact tree is invisible (B13).
5. **No health/doctor**, so agents write CPU samplers and `jcmd` (P5).
6. **Chat history is project/run-scoped**, not session-scoped; cursors are easy to get wrong (P7 residual).
7. **No one-shot "ready browser + panel"** (P1). Scratch features + discover + seed + isolate + worker_start is the real cost.
8. **Maven exec 10–20 s per verb** when the opener/cache is broken (B14, P12). `java -cp` is already ~0.5–2 s.
9. **Guidance vs code drift** (start, isolate, MCP-not-required, inspect selectors).
10. **Human-collaboration verbs are page-only**; desktop users get a popup or a native box, and agents must learn the split.
11. **No snapshot-backed compact view or snapshot diff** in Workbench MCP, despite the engine already having the artifact.
12. **`ui-apply` already batches page UI** (report praised this). Macros should reuse it, not fork it.
13. **Mapping snapshot/restore already exist** and should be mentioned in the agent recipe, not rediscovered.

---

## Latency sources (responsiveness)

Dan's report: startup is sluggish; every step feels slow; worst when injecting prebuilt HTML and collaborating with a human. Measured when things worked: inspect 13–69 ms warm, `ui` 7 ops in 607 ms, label 98 ms, screenshot 59–296 ms. The pain is **first call, session start, CLI-without-worker, B1, and huge replies** — not every warm UI op.

| Source | Evidence | Typical cost |
|--------|----------|--------------|
| Maven exec per Workbench verb | Opener / `WorkbenchOneShotLaunch` / report | 10–20 s vs 0.5–2 s `java -cp` |
| B14 broken opener | `PickleballLocalStore.java:728` | Forces thin-jar or hand-built classpath |
| Isolate materialize + classpath scan | Report ~50 s / 5,400 files / 229 MB; progress in `docs/configuration.md` | Tens of seconds |
| MCP worker start / restart | Report 25–37 s / 54 s; `OUTER_START_TIMEOUT` 300 s; health poll every 5 s (`WorkbenchWorkerManager.java` 39, 235) | 25–60 s + lost browser (B4) |
| B1 CPU loop | `pkb-runtime.js` 129–134, 480 | Stars Chromedriver; 60 s `CONTROL_TIMEOUT`; then restart |
| Scenario-thread hop | `ControlBridgeCoordinator.browserPageOnScenarioThread` (~634); default command timeout 60 s (87) | Fine when healthy; 60 s when stuck |
| CLI agent verbs in the wrong JVM | B2; `panel-open` ~8 s then a misleading error | 8 s + confusion |
| Full page source / inspect / screenshot in the reply | `ElementControl` caps: 8 KiB text, 16 KiB outerHTML, 20 elements default (26–31); `workbench_browser_page` full HTML | 8–25 KiB inspect, ~100 KiB page, ~128 KiB screenshot |
| Re-injecting the full runtime on `panelOpen` | `PageSurfaces.runtimeCall(..., install=true)` 1194–1218; `panelOpen` uses `true` | Extra inject cost each open |
| LeanWait 500 ms ready poll + quiet period | `LeanWaits.java` 110–135, 2000 ms window-change sleep | Adds up on every navigation |
| Composite / element waits | 150 ms poll | Acceptable; not event-driven |
| Runtime restore interval | 500 ms (`pkb-runtime.js` 1420) | Minor |
| Pause lease expiry | 120 s then teardown (B5) | Feels like a hang, then a dead browser |

---

## Brainstorm (inventory first, then new work)

### Already present (do not duplicate)

Page UI via `UiCatalog` / `ui-apply`: overlay (callout), label, border, arrow, step-badge, magnifier, spotlight, panel, panel-close, guide (+ next/back/done), ask (choices → `ui-poll`), chat, remove, clear. Batch + catalog check before apply. Popup chat and native highlight for desktop. `chat_wait`. `session hold/release`. Mapping snapshot/restore. Composite snapshot + desktop snapshot + premap indexes (engine, opt-in). `workbench_step_resolve`. `inspect --explain`. ENGINE click/type on both DOM and desktop through `DomCompositeAdapter` when composite is on.

### New in-page / human-collaboration components (AGENT; desktop backend where noted)

| Idea | What it does | Call shape (same for CLI & MCP) | Why it saves cost | Size | Notes |
|------|--------------|---------------------------------|-------------------|------|-------|
| Confirm chip / "highlight-and-confirm" | Highlight a target and wait for Yes/No (or Cancel) from the human | `confirm --t=… --say=… --timeout=120` | One call instead of highlight + ask + poll | S | Reuse `ask` + highlight; native/popup backend on desktop |
| Wait badge | Persistent "I'm waiting for you / for the page" state | `status --say=… --until=…` | Avoids chat spam and keep-alive hacks | S | |
| Comment pin | Place a comment thread on a control | `pin --t=… --text=…` | Cheaper than overlapping ask cards (B8) | S | Must participate in overlap avoidance |
| Privacy shutter | Redact a region before screenshot | `shutter --t=…` / `--rect=` | Stops huge screenshots of secrets | S | ENGINE-safe default for screenshot? optional |
| Presence / "looking here" | Agent cursor or beacon on the target | `point --t=…` | Replaces screenshot round-trips while explaining | S | Native beacon on desktop |
| Checklist / progress | Multi-step checklist in the panel or a thin overlay | `checklist --steps=…` | Guide already does numbered steps; only add if guide cannot show a static list | S | **Maybe skip** if guide covers it |
| Toast | Transient non-blocking notice | `toast --text=… --ms=4000` | Cheap ack without an ask card | S | |
| Desktop callout | Not a new verb: `overlay`/`label` on a desktop target uses native/popup backend | same `ui` ops | Agents stop learning a second catalog | M | Encapsulation, not a new component name |

Parked (backlog, easy to over-build): page minimap, split compare, keyboard cheatsheet, rich form-coach distinct from `guide`.

### Prebuilt parameterizable macros (one cheap call)

Same call names for browser and desktop; backend differs.

| Idea | What it does | Call shape | Saves | Size | Tier |
|------|--------------|------------|-------|------|------|
| `live-start` | Controller + worker + browser or desktop attach + optional panel/popup | `live-start --url=…` or `--app=… --backend=auto|browser|desktop --hold=30m --panel` | ~12 setup actions | M | AGENT |
| `act` / find-and-act | Resolve + click/type/select in one call | `act --t=… --do=click|type --text=… --wait=ready` | inspect + step + retry | S–M | AGENT over ENGINE act |
| `form-fill` | Fill many fields + optional submit + wait | `form-fill --ops=@file --submit=…` | Many execute-steps | M | AGENT |
| `wait-for` | Wait for text/url/target/mask-gone/quiet | `wait-for --state=… --timeout=30` | Hand-written polls | S–M | ENGINE primitive + AGENT flags |
| `ask-and-wait` | Ask (or confirm) and block until the human answers | `ask --say=… --choices=… --timeout=120` (merge with existing `ask` + `chat_wait` / `ui-poll`) | Watcher scripts | S | AGENT |
| `highlight-and-confirm` | See component above | same | | S | AGENT |
| `snapshot` / `snapshot-diff` | Capture compact tree; diff two ids | `snapshot --out=…` ; `snapshot-diff --from= --to=` | 100 KiB page source; diagnosis | M | ENGINE capture; AGENT compact view |
| `selftest` / `doctor` | P5, P8 | as proposed | | M | AGENT |
| Batched UI | **Already `ui-apply`** | keep | | — | Do not fork |

### Snapshot integration ideas

| Idea | Call | Size | Tier | Note |
|------|------|------|------|------|
| Auto snapshot before/after a composite macro (`act`, `form-fill`), kept on disk, compact id in the reply | implicit under `--snapshot=auto` | S | ENGINE hook, AGENT opt-in (default on for macros?) | Don't put the full tree in the model context |
| Snapshot-backed compact `page` / `inspect` (premap indexes, not HTML) | `page` / `inspect` defaults | M | AGENT default; ENGINE snapshot | Fallback: today's walk if composite is off |
| `snapshot-diff` (nodes added/removed/moved, text change) | `snapshot-diff` | M | AGENT | Reuse diagnostic fingerprint thinking; don't invent a second image codec |
| Replay/restore | **Hard for a live page.** Mapping restore already exists. Desktop restore is not realistic. Offer "re-navigate + re-apply" rather than true DOM rewind | L | backlog | Don't promise time-travel |
| Same snapshot call for browser and desktop | `snapshot` (schema already shared) | S | ENGINE | Workbench should expose it, not only `domengine snapshot` |

---

## Prioritized phase plan (one Grok Build roadmap)

Streamlined, holistic, one target-agnostic agent API, ENGINE fallback, no extra layers. Tags: **E** = engine tier, **A** = agent tier.

### Phase 0 — Stop the bleeding (must ship first)

1. **B1** popover/`toggle` re-entry (**E**, critical). Selftest CPU assertion so it cannot regress (**A** helper).
2. **B4** quit WebDriver + `ProcessTrees.killTree` on worker stop/restart; `browser_lost` event (**E**).
3. **B5** document 120s/30s lease; keep pause while a controller/MCP client is attached, or `--hold`; session-ended event (**E** + **A** docs/flags).
4. **B14** quote the PowerShell includeScope argument; fix fallback classpath (**A** launcher).
5. **B2 / P3** CLI agent verbs forward to the live worker when a session exists; fail fast otherwise (**A** using existing bridge).

Acceptance: `panel-open` on a plain page does not raise Chrome CPU; messages can be re-tested (B6); worker restart does not leave Chrome; CLI `label --run-id=…` reaches the isolate browser.

### Phase 1 — Responsiveness and measurable cost (performance phase)

Build must **add timing instrumentation and a small bench**, and **report numbers**, not just ship features.

Instrumentation (every CLI/MCP reply, compact): `timing={jvmMs,bridgeMs,scenarioThreadMs,injectMs,totalMs}`. `doctor --bench` and `selftest ui --cpu` print a table. No extra telemetry layer — fields on existing replies.

Suggested targets (open question if Dan wants different numbers):

| Metric | Target | Today's field-report ballpark |
|--------|--------|-------------------------------|
| First verb after `export-guidance --warm` / classpath cache (P12) | ≤ 1 s | 0.5–2 s when cache works; 10–20 s Maven / broken opener |
| `live-start` to headed navigated browser (after Phase 2) | track and reduce; stretch < 15 s without a full discover | ~3 min scratch+discover+isolate |
| Warm `inspect` compact | ≤ 100 ms, ≤ 2 KiB | 13–69 ms but 8–25 KiB |
| Warm `page` compact | ≤ 150 ms, ≤ 2 KiB | ~100 KiB HTML |
| Warm single `ui` op (label/overlay) | ≤ 150 ms p95; Chrome CPU back to idle ≤ 0.1 core within 500 ms after panel-open | 98 ms / 87 ms/op when not looping |
| `execute-step` compact reply | not cut mid-JSON; one object | long, truncated |
| Worker restart | no orphan browsers; tell the agent | orphans + silent loss |

Also in this phase: **P4 compact defaults** (inspect/page/screenshot `--out`, execute-step); avoid re-injecting the full runtime when it is already installed; do not put base64 screenshots in the default reply.

### Phase 2 — Agent efficiency (one API, two backends)

1. **P1 / B3** `live-start` (extend `start`, don't add a sibling). `--backend=auto|browser|desktop`.
2. **P2 `call`** or a documented persistent controller; every MCP tool reachable from the CLI without IDE registration.
3. **P5 `doctor`**, **P12** warm + `pkb` shim, **P13** one recipe + `help <tool>`, **P14 `live-stop --all`**.
4. **P7** session-scoped chat + cursor (keep existing `chat_wait` name).
5. One target-agnostic **`inspect` / `page` / `act` / `snapshot`** : snapshot-backed when the engine is joined, Selenium/current walk as **ENGINE fallback**. Desktop uses the same names.

### Phase 3 — Context, honesty, glue

1. **B9/B10 / P10** current frame (or desktop window) on every reply; inspect walks the same contexts or says it doesn't; `frame --list/--reset/--enter`.
2. **B7/B8 / P9** `visible` / `overlaps` on placements.
3. **B16 / P11** `noop` flag + `validate --noop`.
4. **B13** docs + snapshot-backed `text=` so agents are not surprised.
5. **B15** guide wording only.
6. **B11** ENGINE: optional wait-for Loading quiet / verify tab change (if cheap).
7. **B12** `fixture:` (and maybe `data:`/`file:`) or selftest pages (**P8**).

### Phase 4 — Collaboration macros and snapshot hooks (keep thin)

Ship only the high-leverage macros: `wait-for`, `act` (find-and-act), `form-fill`, `ask-and-wait` / `confirm`, auto snapshot before/after macros, `snapshot-diff`. Desktop and browser share the call. Reuse `ui-apply`, `ask`, `chat_wait`, `DomCompositeAdapter`, composite snapshot. **No new layer.**

### Ranked backlog (not in the first Build unless a phase lands early)

- True page replay/restore (likely no).
- Minimap, split compare, form-coach-beyond-guide, cheatsheet.
- Making `pkb_composite=on` the default (product decision).
- Event-driven ENGINE waits (MutationObserver) instead of 150 ms polls — only if a bench shows it matters after Phase 1.
- Closed-shadow support.

---

## Open questions for Dan

1. **No-op consumer glue (B16):** warn and still run, or fail the step? (Failing is safer; it can break a consumer that used empty stubs on purpose.)
2. **`start` vs `live-start`:** replace `start` with a real launch, or keep `start` as a session-file verb and add `live-start` under an honest name?
3. **CLI forwarding (P3) vs `call` (P2):** both are useful; if only one Build slice, P3 first.
4. **Default pause lease:** keep 120s + renew, or "hold while a client is attached / `--hold=forever`"?
5. **Composite on by default** for inspect/snapshot-backed compact views? (Behavior change for consumers.)
6. **`data:` / `file:` navigation:** allow in `navigate to:`, or only `fixture:` via selftest?
7. **Performance targets:** are the Phase 1 numbers right, or do you want stricter first-start goals?
8. **Desktop in the first Build:** same call names from Phase 2, or browser-only first and desktop backends immediately after?
9. **No-op and shadowing:** any known consumer stubs in your other project we should grandfather?

---

## Files touched by this evaluation

- Evaluation: `/workspace/pickleball/eval-consumer-field-report-2026-11-18.md`
- Cleaned report copy: `/workspace/pickleball/consumer-reports/field-report-2026-11-18.md`
- Read-only worktree: `/workspace/pickleball/wt-eval-3.0.0` at `f5ccc9f4` (not modified)
