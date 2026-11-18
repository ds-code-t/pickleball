# Browser-control research for pickleball 3.0.0

Written Wed Nov 18 2026, about 4:45 PM MST, by the "pickleball · Major release 3" research subagent.
Basis: a read-only review of `origin/3.0.0` at f5ccc9f4 (merge of PR #25, committed 2026-11-18 12:11 MST), plus web research. Nothing was built, committed or pushed.

---

## 0. TL;DR

* **pickleball already has the right foundation.** Selenium handles browser lifecycle and the default element path. An in-repo, Java-only **raw CDP** client (`dom-engine`, plus `CdpAdapter`) and a **raw BiDi** client (`BidiAdapter`) take care of snapshots (`DOMSnapshot.captureSnapshot` and `Accessibility.getFullAXTree`), isolated worlds, preload scripts, bindings and Overlay. An optional `PlaywrightAdapter` is `compileOnly`. One query language and one verb set already serve browser and desktop (`Query.run`).
* **Recommendation: keep Selenium + raw CDP/BiDi as the engine. Don't adopt a Node MCP server or Playwright as a dependency.** Borrow ideas instead:
  * Playwright MCP / CLI's **ref-based compact text snapshot** with a `find` view.
  * Chrome DevTools MCP's `includeSnapshot` and `wait_for` patterns.
  * Playwright's **actionability auto-wait**.
* **Biggest responsiveness wins:**
  1. Replace polling waits (`LeanWaits`: FluentWait at 150/500/800 ms) with an event-driven, injected **page watcher**: MutationObserver, network-inflight, busy masks and route hooks that **push** through a binding.
  2. Fix the binding push path. `pkbSurface` is registered but the page JS never calls it, so events are still drained by polling (see §2.4).
  3. Use per-target **auto-attach with `waitForDebuggerOnStart`** so init scripts reach every frame, including OOPIFs, before page scripts run.
* **One target-agnostic API (§6).** It is the existing `domengine` verb set, extended with `snap`, `find`, `act`, `wait`, `watch`, `ui`. Browser and desktop backends sit behind it, with no new layer.
* **Flags:**
  * Everything evaluated is Apache-2.0 or MIT, apart from Azure Playwright Workspaces (a paid cloud service).
  * Playwright-Java bundles a Node driver and downloads browsers (about 150–400 MB) unless you set it up otherwise.
  * Chrome DevTools MCP has **usage statistics on by default** and sends trace URLs to Google CrUX.
  * Chrome ≥136 refuses `--remote-debugging-port` on the default profile.

---

## 1. How pickleball 3.0.0 drives browsers today (from code)

| Area | Current implementation (origin/3.0.0) |
|---|---|
| Lifecycle | Selenium `[4.44,5.0)`, plus Selenium Manager with a Chrome-for-Testing/Edge driver fallback. Remote WebDriver is supported. |
| Default element path | `SeleniumAdapter` when `pkb_composite` is unset/auto/off. `ElementOperations` does find/click/type/… |
| Control Engine | `dev.domengine` (`pkb_composite=on`). Java 21, Jackson only, over a raw CDP websocket (`CdpConnection`). `ChromeProcess` launches with `--remote-debugging-port=0` and reads `DevToolsActivePort`. `session join --id/--cdp` attaches to an existing browser by CDP URL. Live desktop runs UIA / AX / AT-SPI through the same `Query.run`. |
| Agent protocol path | `AgentProtocolController` → `CdpAdapter` (Chromium) / `BidiAdapter` (Firefox) / Selenium `executeScript` (logged last resort). It uses Selenium only to get the tab's target id, then `Target.attachToTarget{flatten}`. **Selenium `HasDevTools`/`se:cdp` and Selenium's BiDi classes are deliberately not used** (`DictionaryLayerBoundaryTest` bans `org.openqa.selenium.bidi` in the dictionary layer). |
| Snapshot | `CdpSnapshotProducer`: one `DOMSnapshot.captureSnapshot` plus a per-frame AX tree plus layout metrics. The schema is `pickleball.composite-snapshot` v1. It has a flat `nodes[]` (handle, role, name, text ≤200, xpath, shadow_path, frame_path, boxes, interactable, section_path, paint) and a **pre-map** that always runs (sections, labels, indexes, viewport map). Firefox uses BiDi or a Selenium fallback. Desktop has its own producer. |
| Iframes | Same-origin frames come through `contentDocument`. OOPIFs: `LiveTree.graftOutOfProcess` runs `Target.setAutoAttach{autoAttach, flatten, waitForDebuggerOnStart:false}` + `Target.getTargets`, matches by URL, then `DOM.getDocument{pierce, depth:-1}` per child session. This is done at read time, not continuously. Offline `HtmlTree` handles `srcdoc`. |
| Shadow DOM | `DOM.getDocument pierce` sees open **and closed** roots. The query grammar has `shadow`/`pierce` with `pierce=open` skipping closed roots. Conformance flags `closed_shadow_hit` and `skip:scripted-shadow`. |
| Injection | `Page.addScriptToEvaluateOnNewDocument{worldName:"pkb-surface"}` (needs `Page.enable`, handled) and `Page.createIsolatedWorld`. BiDi uses `script.addPreloadScript`. `pkb-runtime.js` (1,488 lines, `window.__pkbUi`) runs in the isolated world: shadow-root host, `adoptedStyleSheets` (CSP-safe), `popover=manual` + `showPopover()` raised again on `toggle` (top layer), and ResizeObserver, MutationObserver, scroll/resize/visualViewport/popstate/hashchange/pageshow listeners for placement tracking. `UiState` re-injects after reload or SPA route change. |
| Page → agent events | Guide/ask/chat events queue inside the isolated world. `PageSurfaces.drainIsolated` pulls them, and agents long-poll `ui-poll`/`overlay-poll` (≤25 s). A CDP binding `pkbSurface` is registered (`Runtime.addBinding`, **no `executionContextName`**), but **no shipped JS calls it** (grep finds no `pkbSurface` in `src/main/resources/**/*.js`). |
| Waits | `LeanWaits`: `readyState` polled every 500 ms, then a MutationObserver "quiet period" polled every 800 ms (cap 5 s), and element waits at 150 ms. The engine has `query --wait-for attached/visible/hidden/detached` (re-reads the live tree). `SessionEvents` tracks Network/Page/console events. `LiveConformance` already has an in-page network-inflight / aria-busy / MO probe. |
| Playwright | `PlaywrightAdapter` (compileOnly `[1.40,2.0)`): evaluate in a world, addInitScript, exposeBinding, screenshot, send. It is never chosen by `ProtocolSelector`. |
| Attach to user's browser | `pkb_debug_browser` port, Selenium `debuggerAddress`, and `domengine session join --cdp <url>`. |

---

## 2. Option comparison

Each option is rated on four dimensions: **Snap** (compact DOM mapping), **Attach** (take over any browser, including a running user browser), **Speed** (interaction latency and reliability) and **Inject** (init scripts, worlds, bindings, CSP, top layer).

| Option | Snap | Attach | Speed / reliability | Inject | Java? | License / deps | Fit |
|---|---|---|---|---|---|---|---|
| **Playwright MCP** (microsoft/playwright-mcp) | ★★★ AX "aria snapshot" YAML with `[ref=eN]`. `browser_snapshot{depth,boxes,target}`, `browser_find{text,regex}` returns snippets only. `--snapshot-mode full\|none`. | `--cdp-endpoint`, `--extension` (Chrome/Edge extension attaches to the live profile, tab groups), `--user-data-dir`, `--isolated`, `--storage-state` | ★★★ Playwright auto-wait. One MCP round trip per action. | `--init-script` (every page), `--init-page`. `browser_evaluate`, `browser_run_code_unsafe`. Caps are opt-in: `vision, pdf, devtools, network, storage, testing, config`. | No (Node 20+) | Apache-2.0. Node + Playwright browsers. | **Borrow the format, don't depend on it.** It's a Node server, against the Java-only design. |
| **Playwright CLI + Skills** (`@playwright/cli`, `playwright-cli install --skills`) | Same refs. The CLI keeps snapshots out of context unless asked. Microsoft itself now says CLI+skills is more token-efficient than MCP for coding agents. | `open`/`attach`, named sessions | Same as MCP. One process call per verb (a daemon keeps state). | `run-code`, mocking, tracing, video, recording → codegen | No (Node 20+) | Apache-2.0. Node. | **Copy the pattern:** a terse CLI plus a skill file. pickleball already has `domengine`, so ship a skill for it. |
| **Chrome DevTools MCP** (ChromeDevTools/chrome-devtools-mcp) | ★★★ `take_snapshot` (AX tree with `uid`). Every input tool has an `includeSnapshot` flag, which saves a round trip. | `--browserUrl`, `--wsEndpoint`, **`--autoConnect`** (Chrome ≥144: the user enables `chrome://inspect/#remote-debugging`, and it attaches to the default profile with no restart). `--isolated`, `--slim`. | ★★★ Puppeteer, waits for the action result. `wait_for` text. | `evaluate_script`, network mocks, perf traces, memory, extensions, WebMCP, WebAuthn | No (Node LTS) | Apache-2.0. **Usage stats on by default (`--no-usage-statistics`). CrUX calls (`--no-performance-crux`).** Officially Chrome only. | Use as an **external companion** for perf/debug, documented only. Borrow `includeSnapshot` and the autoConnect UX. |
| **Playwright Workspaces remote MCP** (Azure) | `browser_snapshot` / `browser_find`. 22 tools. | Cloud browsers only (`create_browser_session`, 15 min idle, connection loss is terminal). VNet for intranet. | Network latency to Azure. No concurrency per session. | Limited (no init-script control documented) | Any MCP client | **Paid Azure service, preview, no SLA. Page data leaves the machine.** Entra ID or an x-api-key. | Out of scope for the core. At most a documented optional remote target. |
| **Playwright Java API** (+ `connectOverCDP`) | `locator.ariaSnapshot()` (YAML, no refs in the public API), `page.accessibility` removed. A good actionability model. | `launch`, `launchPersistentContext`, `connectOverCDP(url)` (Chromium only, "significantly lower fidelity", and expects Playwright's launch args) | ★★★ Auto-wait, strict locators, frame locators, retries | `addInitScript` (all frames, survives navigation), `exposeBinding`, `route`, isolated worlds via CDPSession | **Yes** | Apache-2.0. **Ships a Node driver inside the jar (tens of MB per OS) and downloads browsers to `ms-playwright` on first use** unless `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` + `channel=chrome/msedge` | Keep as the **optional backend** (already compileOnly). Don't make it the default. |
| **WebDriver BiDi** (Selenium 4 Java, or raw) | `browsingContext.getTree`, `script.evaluate`. No AX tree in the spec. Locate nodes with `browsingContext.locateNodes` (css/xpath/innerText/accessibility). | Standard. Chrome via chromium-bidi in ChromeDriver, Firefox native (Firefox CDP deprecated/removed). | Good. Events are push-based (`log.entryAdded`, `network.*`, `browsingContext.*`). | `script.addPreloadScript{sandbox, contexts, userContexts}` covers **all contexts including iframes**. `script.message` **channel** gives page→client callbacks in a sandbox. `network.addIntercept`. | Yes (Selenium ≥4.15 preload, 4.18 intercept, 4.50 current) | Apache-2.0, already a dependency | **Firefox path, cross-browser future.** pickleball's raw `BidiAdapter` is fine. Use the channel argument for push. |
| **Raw CDP** (what dom-engine does) | `DOMSnapshot.captureSnapshot` (layout, paint order, styles, shadow, same-process frames in one call), `Accessibility.getFullAXTree`/`queryAXTree` per frame, `DOM.getDocument{pierce}` (closed shadow included) | Any Chromium with a debug port or pipe. Chrome ≥136 needs a non-default `--user-data-dir` (Chrome for Testing is exempt). Chrome ≥144 has user-enabled remote debugging. | ★★★ Lowest latency (one websocket, no driver hop). The auto-wait has to be built by you. | `Page.addScriptToEvaluateOnNewDocument{worldName, runImmediately}`, `Page.createIsolatedWorld`, `Runtime.addBinding{executionContextName}` → `Runtime.bindingCalled`, `Target.setAutoAttach{waitForDebuggerOnStart, flatten}` + `Runtime.runIfWaitingForDebugger`, `Page.setBypassCSP`, `Input.*`, `Overlay.*`, `DOM.resolveNode` (even closed shadow roots) | Yes (in-repo) | Protocol only. No deps. | **Core engine. Keep it and deepen it.** |
| **Selenium** (current) | `getPageSource` / JS only | `debuggerAddress`, Remote/Grid, all browsers incl. Safari | ★★ An extra HTTP hop per command. Implicit/explicit waits. Stale elements. | `executeScript` (main world, CSP-exposed, dies on navigation). `HasDevTools` exists but is version-pinned. | Yes | Apache-2.0 | **Keep for lifecycle, Grid/remote, Safari, and as the final fallback.** |

Sources are in §9.

---

## 3. Difficult DOM areas: what pickleball does now, the recommendation, and the tier

E = ENGINE tier (universal, used by scripts and as automatic fallback). A = AGENT tier (fine-grained or experimental, exposed through CLI/API/MCP).

### 3.1 Event-driven listeners: push instead of poll

| Technique | Today | Recommendation | Tier | Size |
|---|---|---|---|---|
| **MutationObserver** | Placement tracking in `pkb-runtime.js`. `LeanWaits` quiet-period polling. `DocVersion` bump. | One **injected page watcher** (`pkb-watch`, isolated world, preload) keeps `docVersion`, `lastMutationTs`, and a dirty-subtree set (coalesced per rAF, capped). The engine waits on it with **one `Runtime.evaluate{awaitPromise}` / BiDi `script.callFunction{awaitPromise}`** that resolves on a condition instead of FluentWait polling. | E | M |
| **Network inflight / idle** | `SessionEvents` (CDP Network) and the `LiveConformance` probe | Make "network idle ≤N inflight for X ms" a standard settle signal in the same awaitPromise: CDP Network events on Chromium, BiDi `network.*` on Firefox, injected fetch/XHR counters as fallback. | E | S |
| **Busy / load-mask detection** | `ExecutionDictionary` XPath (`aria-busy`, progressbar). `LiveConformance` busy list. | Watcher computes `busy`: `aria-busy=true`, a visible `[role=progressbar]`, a top-most full-viewport overlay above the target (via `elementsFromPoint`), `inert` / `pointer-events:none` on the body. Actionability refuses clicks while the target is covered (Playwright-style "receives events" check). | E | M |
| **Navigation / history hooks** | Runtime listens to popstate/hashchange/pageshow. `Page.navigatedWithinDocument` in SessionEvents. | Engine: use `Page.frameNavigated` / `navigatedWithinDocument` / BiDi `browsingContext.navigationStarted/historyUpdated` as **the** route-change signal. Patching `history.pushState` in the main world only as fallback. These events bump the snapshot doc version and invalidate refs. | E | S |
| **ResizeObserver / IntersectionObserver** | RO for overlay placement. IO not used. | IO for "visible in viewport" waits and virtualized lists (§3.4). RO stays a UI concern. | E (IO wait), A (UI) | S |
| **Event delegation** | Capture-phase click/input/change listeners for guide steps | Keep one capture-phase delegate on `document` (plus each open shadow root, through the attachShadow hook) that emits `user-action` events (selector-free: ref plus role/name, **never values of password/sensitive fields**). This powers "wait for the human to do X" and recording. | A | M |
| **Popover / dialog / top-layer** | `showPopover` re-raise on `toggle` | Also observe `dialog[open]` / `:modal` changes and `beforetoggle`. Emit `top-layer-changed` and re-raise the pkb layer. The engine's covered-target check accounts for modal `inert`. | E (detect), A (raise UI) | S |
| **Push channel** | `Runtime.addBinding("pkbSurface")` without `executionContextName`, so it exists only in the **main** world while the runtime is in `pkb-surface`. No JS calls it. Events are drained by polling. | Register `Runtime.addBinding{name, executionContextName:"pkb-surface"}` (CDP) / a BiDi preload `arguments:[{type:"channel"}]` → `script.message`. Have the runtime and watcher call it. `ui-poll` keeps its long-poll API but wakes instantly from the pushed queue. Keep `drainIsolated` as the fallback. **Verify with a live test before trusting this.** | E | S |

### 3.2 Iframes

| Case | Today | Recommendation | Tier | Size |
|---|---|---|---|---|
| Same-origin, nested | `contentDocument` in `DOM.getDocument{pierce}` and DOMSnapshot | OK. Snapshot already carries `frame_path`. | E | – |
| Cross-origin (OOPIF) | Grafted at read time (auto-attach with `waitForDebuggerOnStart:false`, matched by URL) | Make it **continuous**: on the page session, `Target.setAutoAttach{autoAttach:true, flatten:true, waitForDebuggerOnStart:true, filter:[{type:"iframe"}]}`. On `Target.attachedToTarget`, apply preloads, binding and watcher to that child session, then `Runtime.runIfWaitingForDebugger`. Keep a frameId→sessionId map. Match frames by `frameId` (from `Page.frameAttached` / `DOM` `frameId`), not URL. | E | M |
| Dynamically added frames | Missed until the next read | Covered by auto-attach plus `Page.frameAttached`. The watcher bumps the version. | E | (incl.) |
| about:blank / srcdoc / `document.write` frames | Offline srcdoc only | They're same-origin with the parent and often get no navigation, so init scripts may not run. On `Runtime.executionContextCreated` for a frame with no pkb world, call `Page.createIsolatedWorld{frameId}` and evaluate the runtime. Access from the parent works too. | E | S |
| Firefox / BiDi | `browsingContext.getTree` | Preload with `contexts` omitted covers child contexts. Subscribe to `browsingContext.contextCreated`. | E | S |
| Selenium fallback | `switchTo().frame` | Keep it as the last fallback. Cross-origin JS access is impossible here. | E | – |

### 3.3 Shadow DOM

| Case | Today | Recommendation | Tier | Size |
|---|---|---|---|---|
| Open roots | Pierced (CDP and query `shadow`/`pierce`) | OK. Include `shadow_path` in refs. | E | – |
| Closed roots | CDP `pierce` sees them. In-page JS can't. | **No main-world patching by default.** CDP `DOM.resolveNode{backendNodeId, executionContextId:<pkb world>}` gives a JS handle even inside closed roots, so actions and evaluation work. Optional agent-tier `--patch-attach-shadow` (main-world init script that records closed roots in a WeakMap) for Firefox/Selenium. It's detectable by the page, so off by default. | E (CDP), A (patch) | S / S |
| Declarative shadow DOM, slots | Offline handles DSD | Snapshot: use the AX tree for slotted content order. Make sure `DOMSnapshot` + `includeShadowTree` paths are used. | E | S |
| Listeners inside shadow | Not delegated | The capture listener on `document` sees composed events. Use `composedPath()[0]` for the real target. Non-composed events need a per-root delegate added through the attachShadow hook (agent tier). | A | S |

### 3.4 Other hard areas

| Area | Today | Recommendation | Tier | Size |
|---|---|---|---|---|
| SPAs (route changes, rehydration) | `UiState` re-inject, popstate listener | Route signal (§3.1) + watcher settle + ref invalidation. Wait for "hydrated" via `readyState` + no mutation burst + network idle. | E | S |
| Virtualized lists | Detected (`virtualized_unmounted`, `PlatformSnapshot.detectVirtualization`) | `find --scroll-search`: scroll the nearest scroll container in viewport steps, IO/MO-driven, until the query matches or the end is reached. Report `virtualized:true` in the snapshot. | E | M |
| Canvas / WebGL | Flagged `canvas_bitmap` | No DOM. Fall back to a screenshot crop, then OCR/vision (agent tier), and coordinate `act --xy` via CDP `Input` / desktop input. Expose an app hook if the page has one (WebMCP tools list, as in Playwright CLI / DevTools MCP). | A | M |
| SVG | Normal DOM | Ensure `role`/`name` from the AX tree and `<title>`. Boxes from DOMSnapshot. | E | S |
| CSP | Isolated world + `adoptedStyleSheets` (CSP-safe), noted in the overlay audit | Keep. Add `Page.setBypassCSP(true)` only as an agent-tier opt-in for main-world injection. Watch **Trusted Types** if any main-world `innerHTML` is used (use DOM APIs). | E / A | S |
| Cross-origin restrictions | Per-frame sessions | Everything goes through per-target CDP sessions, never cross-frame JS. | E | (incl.) |
| Dialogs (alert/confirm/beforeunload) | Selenium alerts | `Page.javascriptDialogOpening` / BiDi `browsingContext.userPromptOpened` push events. Policy: engine auto-dismiss per step config; the agent tier can ask the human. | E | S |

---

## 4. Recommendation: two tiers

### 4.1 ENGINE tier (core, universal, used by scenarios and as automatic fallback)

| # | Item | Size |
|---|---|---|
| E1 | **Page watcher + event-driven waits** (MO / network / busy / route / top-layer in one `awaitPromise` call). Replaces FluentWait polling in `LeanWaits` and engine `--wait-for`. | M |
| E2 | **Fix push channel** (`addBinding{executionContextName}` / BiDi channel). `ui-poll` wakes on push. | S |
| E3 | **Continuous frame coverage**: auto-attach with `waitForDebuggerOnStart`, a per-frame preload/world/binding, frameId-based mapping, about:blank/srcdoc injection. | M |
| E4 | **Actionability check** before every act (attached, visible, stable box over 2 rAFs, enabled, not covered via `elementsFromPoint` incl. shadow, receives events). Auto-scroll and retry. | M |
| E5 | **Compact ref snapshot view** generated from the existing composite snapshot: `- button "Save" [ref=e12]` style with section headers, interactables first, a token budget, `--depth`, `--within`, and `find --text/--regex` snippets. Refs = `docVersion:backendNodeId` (or desktop handle), invalidated by the watcher. No new snapshot producer. | M |
| E6 | **Snapshot diff** (`since=<docVersion>`), driven by the watcher's dirty set. Returned optionally after each act (`--snap=diff\|none\|full`, like DevTools MCP `includeSnapshot`). | M |
| E7 | **Closed shadow access via `DOM.resolveNode`** in the pkb world. | S |
| E8 | **Virtualized-list scroll search**. | M |
| E9 | **Dialog and navigation events** as engine signals. | S |
| E10 | **Attach UX**: `attach --cdp <url>` (exists) + `attach --auto` (read `DevToolsActivePort` from known profile dirs, which supports Chrome ≥144 user-enabled remote debugging) + a clear error for the Chrome ≥136 default-profile refusal. | S |

**Engine fallback chain** (automatic, logged once per run and reason, the same pattern as today's `composite.fallback`):

1. **Locate**: pre-map hit → live query (CDP `DOM` pierce / BiDi `locateNodes`) → AX-name match (`Accessibility.queryAXTree`) → Selenium `findElement` (default frame, then frame-switch) → desktop UIA/AX/AT-SPI when the window isn't a web view.
2. **Act**: CDP `Input.dispatch*` at the actionable point (after E4) → BiDi `input.performActions` → Selenium Actions / WebElement → JS `el.click()` / `dispatchEvent` in the isolated world (marked as `synthetic` in the result) → desktop OS input at screen coordinates.
3. **Wait**: watcher `awaitPromise` → protocol events (Network / Page / BiDi) → the bounded polling in today's `LeanWaits`.
4. **Inject**: CDP isolated-world preload + binding → BiDi sandbox preload + channel → Selenium `executeScript` main world (re-injected on navigation, logged).
5. **Snapshot**: CDP producer → BiDi producer → Selenium page-source fallback → desktop producer. Skipped fields go in `warnings[]`, not failures (as today).

### 4.2 AGENT tier (direct, finer-grained or experimental, behind CLI/API/MCP)

| # | Item | Size |
|---|---|---|
| A1 | **Predefined parameterizable functions** (one call each, §5). | M |
| A2 | **Human-collaboration primitives**: `ask` and `wait-for-human-action` (delegated capture events, no sensitive values), `guide` steps that auto-advance when the watched condition is met, a `point-at` overlay on a ref. | M |
| A3 | **Probe/debug verbs**: `watch` stream (mutations / network / console / route for a scope, time-boxed), `explain` (why a locate or act failed: covered by X, hidden, ambiguous candidates), `highlight ref`, `eval --world pkb\|main`, `dom-at x,y`. | M |
| A4 | Opt-in **experimental switches**: `--patch-attach-shadow`, `--bypass-csp`, `--main-world`, vision/OCR fallback for canvas, `record` → Gherkin (pickleball steps, keep Gherkin syntax). | M |
| A5 | **Ship a `domengine` agent skill file** (Playwright CLI pattern: terse commands, snapshots to file unless asked). | S |
| A6 | Optional docs: how to pair with Chrome DevTools MCP (perf traces) or Playwright MCP. No dependency. | S |
| A7 | **Optional Playwright-Java backend** stays compileOnly. Only if a consumer wants WebKit or Playwright tracing. Never default (download/Node implications). | L (if pursued) |

---

## 5. Predefined parameterizable functions (agent tier, one call each)

All take `target` (§6) and return `{ok, result, snap:diff|none, events[], fallback?}`.

| Function | Parameters | Notes |
|---|---|---|
| `find_and_act` | `query`, `action` (click/type/select/check/hover/press), `value?`, `strict?`, `scrollSearch?` | E4 actionability + fallback chain |
| `fill_form` | `fields:[{query|ref, value}]`, `submit?` | Batched. Values come from vault refs, never logged. |
| `wait_until` | `condition` (text/visible/hidden/url/route/networkIdle/notBusy/count>=n/expr), `timeout` | Watcher-driven |
| `act_and_wait` | `action…`, `then:condition` | Removes a round trip |
| `ask_human` | `question`, `choices?`, `anchor?`, `timeout` | Returns the answer via push |
| `guide_human` | `steps:[{anchor, text, until:condition}]` | Auto-advance |
| `wait_for_human` | `until:condition` or `action:{query, kind}` | Delegated events |
| `ui_batch` | list of overlay ops (exists as `ui-apply`) | Keep |
| `snapshot_view` | `mode:compact|find|diff|full`, `within?`, `budget?`, `boxes?` | E5/E6 |
| `extract` | `query`, `fields`, `as:table|list|json` | For tables/lists (incl. virtualized) |
| `dismiss_blockers` | `kinds:[cookie, modal, dialog, toast]` | Heuristics. Agent tier only. |
| `select_option` / `upload_file` / `drag_to` | standard | `DOM.setFileInputFiles` on CDP |
| `watch` | `scope`, `kinds`, `duration` | Debug probe |
| `explain_failure` | `query|ref` | Debug probe |

---

## 6. One target-agnostic API (no new layer)

**Principle:** extend the existing `domengine` verb set (`Query.run`, `capabilities`). Workbench MCP and agent ops stay thin forwarders, as now. Layers: **MCP/CLI/API → `domengine` verb dispatcher → backend (browser: CDP|BiDi|Selenium, desktop: UIA|AX|AT-SPI)**. That's three layers, the same as today.

**Target selector** (one string or object):
```
target = browser:<session-id>[/tab:<id|title~|url~>][/frame:<path|name>]
       | browser:cdp=<ws/http url> | browser:auto
       | desktop:<pid|app~name>[/window:<title~>]
       | ref:<docVersion:handle>          # from a previous snap
```

**Verbs** (exist ✓ / new ✚):

| Verb | Key params | Browser backend (chain) | Desktop backend |
|---|---|---|---|
| `attach` ✓ | `target`, `--auto` | CDP join / BiDi / Selenium `debuggerAddress` | `desktop attach --live` |
| `snap` ✓(`snapshot`) ✚modes | `mode compact|find|diff|full`, `within`, `budget`, `boxes` | CDP producer → BiDi → Selenium | UIA/AX/AT-SPI producer |
| `find` ✓(`query`/`resolve`) | `query`, `strict`, `rank`, `scrollSearch` | pre-map → CDP DOM/AX → BiDi locateNodes → Selenium | same query on desktop tree (`frame`/`shadow` rejected) |
| `act` ✚ (wraps click/type/hover/drag/scroll/key/submit ✓) | `kind`, `query|ref|xy`, `value`, `snap` | CDP Input → BiDi input → Selenium → JS synthetic | platform invoke/pattern → OS input at coords |
| `wait` ✚ (extends `--wait-for`) | `condition`, `timeout` | watcher awaitPromise → protocol events → poll | UIA/AX event subscriptions → tree re-read poll |
| `watch` ✚ | `kinds`, `scope`, `duration` | watcher + CDP/BiDi events (push) | UIA StructureChanged/FocusChanged, AX notifications, AT-SPI signals |
| `eval` ✓ | `expr`, `world pkb|main` | CDP Runtime / BiDi script | n/a (rejected) |
| `ui` ✓(overlay/guide/ask/panel/ui-apply) | op list, `anchor ref` | `pkb-runtime.js` in the isolated world | agent popup + native highlight rectangles (exist) |
| `explain` ✚ | `query|ref` | actionability report | pattern availability / offscreen / disabled |
| `capture` ✓ | `screenshot`, `clip ref` | `Page.captureScreenshot` | desktop screenshot |

**Snapshot integration:** every `act`/`wait` can return `snap=diff` keyed on `docVersion`. Refs from `snap` are accepted by `act`/`find`/`ui` on both target types. Desktop refs use the existing desktop handle. Diff on desktop comes from tree re-read hash comparison (S/M later).

---

## 7. Licensing, distribution, download, Windows

| Item | License | Distribution / download implications | Windows |
|---|---|---|---|
| Selenium 4.x (existing) | Apache-2.0 | Selenium Manager downloads drivers/CfT at runtime (existing, logged) | Yes |
| Raw CDP / BiDi (in-repo) | Protocols, no deps | None | Yes |
| Playwright Java (optional) | Apache-2.0 | **Bundled Node driver per OS (tens of MB) plus browser downloads (~150–400 MB) to `%USERPROFILE%\AppData\Local\ms-playwright`** unless `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` + `channel("chrome"|"msedge")`. Corporate proxies/AV may block. | Yes |
| Playwright MCP / CLI | Apache-2.0 | **Node 20+ required**, npm install, browser downloads; `--extension` needs a Chrome Web Store extension | Yes |
| Chrome DevTools MCP | Apache-2.0 | **Node LTS; usage statistics ON by default; CrUX calls (send trace URLs to Google); update checks**. Officially Chrome only. | Yes |
| Playwright Workspaces remote MCP | **Commercial Azure service (preview, no SLA)** | Page content and credentials go to Azure. Needs Entra ID or an access token. | n/a (cloud) |
| Security | – | Any debug port exposes cookies and sessions to local processes. Chrome ≥136 blocks it on the default profile. Keep loopback-only and `--remote-allow-origins` scoped where possible (today `*` for engine-launched browsers, acceptable for throwaway profiles only). Never attach to a user's real profile without explicit consent (`--autoConnect` style, user-enabled). | – |

No GPL/LGPL/AGPL or other copyleft found.

---

## 8. Risks

* **Auto-attach with `waitForDebuggerOnStart`**: a missed `runIfWaitingForDebugger` hangs frames. Needs a timeout watchdog and live OOPIF tests (Windows CI included).
* **Watcher overhead** on mutation-heavy pages: coalesce per rAF, cap dirty sets, disconnect when idle. Benchmark against the responsiveness targets.
* **Refs going stale** across SPA updates: the watcher-driven invalidation must be conservative, then re-resolve by role/name/xpath before failing.
* **Main-world patches** (attachShadow, history) are detectable and can break apps. That's why they're agent tier and opt-in.
* **BiDi parity**: Chrome BiDi runs via the chromium-bidi mapper, so some commands lag behind CDP. Keep CDP primary on Chromium.
* **Chrome policy changes** (remote debugging restrictions) can break attach-to-user-browser flows. Fail with a clear `fix:` message.
* **Playwright backend creep**: if adopted, it adds a second actionability model and downloads. Keep it compileOnly and separate.
* **Sensitive values** in delegated events and recordings: mask by input type, autocomplete, and `pkb` sensitivity rules.

---

## 9. Sources

* Playwright MCP README (tools, `--caps`, `--cdp-endpoint`, `--extension`, `--init-script`, `--snapshot-mode`, `browser_find`): https://github.com/microsoft/playwright-mcp (raw README fetched 2026-11-18)
* Playwright CLI + skills: https://github.com/microsoft/playwright-cli, https://playwright.dev/agent-cli/skills, https://playwright.dev/docs/getting-started-cli, https://github.com/microsoft/playwright-cli/blob/HEAD/skills/playwright-cli/SKILL.md
* Chrome DevTools MCP: https://github.com/ChromeDevTools/chrome-devtools-mcp, tool reference https://github.com/ChromeDevTools/chrome-devtools-mcp/blob/main/docs/tool-reference.md, advanced usage (autoConnect Chrome 144, browserUrl) https://github.com/ChromeDevTools/chrome-devtools-mcp/blob/main/docs/advanced-usage.md
* Playwright Workspaces remote MCP: https://learn.microsoft.com/en-us/azure/app-testing/playwright-cloud-browsers/how-to-playwright-workspaces-remote-mcp, https://techcommunity.microsoft.com/blog/azure-ai-foundry-blog/announcing-the-playwright-workspaces-remote-mcp-server-for-agentic-browser-autom/4555698
* Playwright Java `connectOverCDP`: https://playwright.dev/java/docs/api/class-browsertype#browser-type-connect-over-cdp
* Chrome 136 remote-debugging change: https://developer.chrome.com/blog/remote-debugging-port
* Selenium BiDi (Java preload 4.15, intercept 4.18): https://www.selenium.dev/documentation/webdriver/bidi/w3c/script/, https://www.selenium.dev/documentation/webdriver/bidi/w3c/network/, Selenium 4.50 https://www.selenium.dev/blog/2026/selenium-4-50-released/
* CDP domains: https://chromedevtools.github.io/devtools-protocol/ (DOMSnapshot, Accessibility, Page.addScriptToEvaluateOnNewDocument, Runtime.addBinding, Target.setAutoAttach, DOM.resolveNode, Input)
* WebDriver BiDi spec (script.addPreloadScript, channel, browsingContext.locateNodes): https://w3c.github.io/webdriver-bidi/
* pickleball code: origin/3.0.0 f5ccc9f4. `AGENTS.md`, `docs/snapshot.md`, `CdpAdapter.java`, `BidiAdapter.java`, `PlaywrightAdapter.java`, `PageSurfaces.java` (installSurface ~L1303, drainIsolated ~L754), `LiveTree.java` (graftOutOfProcess ~L180), `LeanWaits.java`, `pkb-runtime.js`, `build.gradle` L186–201.
