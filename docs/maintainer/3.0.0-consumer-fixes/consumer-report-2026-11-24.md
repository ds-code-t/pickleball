> Copy of a consumer project agent report (2026-11-24), redacted: app name, host, case and account numbers replaced with placeholders.

# Pickleball 3.0.0 — Live Collaboration Test: Issues, Inefficiencies, and Ideas

**Date:** 2026-11-24  
**Pickleball:** 3.0.0 (commit b65a716)  
**App under test:** ConsumerApp DEV (<consumer app DEV URL>)  
**Browsers:** Chrome 157 (headed, start host and Selenium worker), Edge 158 (foreign window)  
**Playwright:** 1.65.1  
**OS:** Windows 11, native UI Automation

## 1. What was tested

One AI agent played two roles on one live start session:

- **Agent (control tier):** chat, question cards, checklist, pins, toasts, highlights, badges, borders, arrows, guides, network, protocol, and desktop verbs.
- **Human-tier helper** (locked with `session-mode --human=on`): only `snap`, `find`, `act`, `input`, `chat-read`, `screenshot`, `doctor`, and the panel composer.

The pair created and submitted ConsumerApp case **<case-id>** (Money Manager Relations, account **<account-no>**, process type "AS AE Addendum") using only injected chat, cards, a checklist, and overlays. Later runs covered CDP vs Playwright vs Selenium routes, monitoring, network mocking and recording, foreign-window attach, and Windows desktop control.

Raw per-step results are in the session report `PICKLEBALL_LIVE_TEST_REPORT.md`. This document groups them by what needs fixing and adds proposals.

### Severity key

| Level  | Meaning |
|--------|---------|
| High   | Blocks a core workflow or gives a wrong answer with no warning. |
| Medium | Workflow possible only through a workaround the docs do not mention. |
| Low    | Cosmetic, misleading output, or extra cost. |

## 2. Defects

### 2.1 Launch and session

| #  | Sev    | Area                  | Repro                                      | Expected                          | Actual                                                                 | Workaround                  |
|----|--------|-----------------------|--------------------------------------------|-----------------------------------|------------------------------------------------------------------------|-----------------------------|
| D1 | High   | `start --headed`      | `start --url=... --browser=chrome --headed --panel` | Visible Chrome window             | Chrome starts with `--headless=new`; reply says `headed=false`. `ConsumerReadyCommands.headed()` reads `--panel` as the value of `--headed`, and any value other than `true` / `1` means "not headed". | `--headed=true` or put `--headed` last. |
| D2 | Medium | Stray positional argument | A broken quote produced the token `Case\ menu` at the top left on a `chat-send` line. | `error:` for an unknown positional | The token contained a backslash, so it was taken as a project directory. Pickleball created `Case\ menu` at the top left `.pickleball\agent\chat\run.jsonl` inside the repo and reported success. | None; delete the folder by hand. |
| D3 | Low    | `doctor --protocols` with a worker | Run with an `isolate` worker alive         | Report the worker browser's backends | Still reports the `start` host backends (`bidi=no classic=no`). The Selenium worker's protocol set is never shown. | None. |

### 2.2 Human lock and tiers

| #  | Sev    | Area                  | Repro                                      | Expected                          | Actual                                                                 |
|----|--------|-----------------------|--------------------------------------------|-----------------------------------|------------------------------------------------------------------------|
| D4 | High   | `act` under the lock  | Session locked; agent runs `act ... --human-override=agent` | Help says the owner may pass `--human-override=<reason>` on one call | `error: act does not use --human-override`. While the lock is on, the agent cannot act on the page at all. |
| D5 | Medium | `call` under the lock | `call chat-send --args-file=... --human-override=agent` | Override forwarded to the inner verb | `call` is a control-tier verb and this session is human-locked. JSON args cannot carry the override either. |
| D6 | Medium | Lock scope            | Recipe says the helper runs "under its own run id" | Lock applies per caller or run id | The lock is session-wide. The agent must add `--human-override` to every control call, and some verbs refuse it (D4, D5). There is no way to give two callers different tiers on one session. |

### 2.3 Finding and acting on elements

| #  | Sev    | Area                  | Repro                                      | Expected                          | Actual                                                                 |
|----|--------|-----------------------|--------------------------------------------|-----------------------------------|------------------------------------------------------------------------|
| D7 | High   | Own UI in page finds  | `find --query=Create-New-Case`, `act --query=Process-Type`, `find --query=Money-Manager-Relations` | Docs: "Page finds still skip that UI" | Checklist rows (`data-pkb-purpose="placement,callout"`) came back as candidates three times. In one case this made `act` ambiguous and it acted on nothing. |
| D8 | High   | Typing into iframe inputs | `act --kind=type --query=s1.h165 --value=<account-no>` (Pega iframe) | Value typed                       | `not applied`; the field stayed empty. The same happened by name (`--query=Account-Number --pick=textbox`) and through the agent's form-fill. Only `input --actions=@chain.json` (coordinates plus key events) worked. |
| D9 | Medium | `act --kind=select` result | `act --kind=select --query=s1.h451 --value=...` | `applied:true`                    | Reports `not applied`, but `eval` showed the right option selected. A false negative makes an agent retry or give up. |
| D10| Medium | `act --query` misses an obvious combobox | `act --kind=select --query=Process-Type --pick=combobox` | Picks combobox "Process Type Select..." | `ambiguous` after 21 s. Candidates were a link "Select Process Type" and our own checklist row; the combobox was missing. |
| D11| Medium | `visible=true` on hidden nodes | `find --query=Money-Manager-Relations` with the menu closed | Hidden submenu items `visible=false` | Both submenu copies returned `visible=true`. Hidden Pega fields (`pzHarnessID`, `PEGAGADGET`, `Declare_*`) were also listed as visible textboxes on every `snap`. |
| D12| Low    | Short id goes stale after a re-render | `act --query=s1.h165` after Pega re-rendered on a validation error | Refind by identity chain          | `stale_ref`. `--refind-chain` exists but is not the default and the reply does not suggest it. |

### 2.4 Placements, overlays, guides

| #  | Sev    | Area                  | Repro                                      | Expected                          | Actual                                                                 |
|----|--------|-----------------------|--------------------------------------------|-----------------------------------|------------------------------------------------------------------------|
| D13| High   | Handles inside iframes | `highlight/label/spotlight --handle=s1.h165` (iframe field) | Placement on that field           | `unknown handle: cf64283a`. The short id resolves to an element id that is looked up in the top document only. |
| D14| Medium | `highlight --query=<short id>` | `highlight --query=s1.h229`               | Help: short ids are accepted      | `not found` after 5.7 s. `highlight --query=<name>` did reach into the iframe (2 s). |
| D15| Medium | `step-badge` result   | `step-badge --number=1 --handle=s1.h23`   | `visible:true`                    | Reply said `visible:false`, `hidden:true`, `lost:true`; the badge was drawn and visible. |
| D16| Medium | `unstep-badge`        | `unstep-badge --handle=s1.h23`            | Badge removed                     | `ok:false`; the badge stayed until `overlay-remove --handle=pkb-2`. |
| D17| Medium | Overlap reporting     | `checklist --items=...` (no position)     | Placed clear of page controls, or `overlaps` lists the clash | Placed over the ConsumerApp header and user menu; `overlaps:[]`. `ui-verify` gave rects but no overlaps. |
| D18| Medium | Coordinate overlays do not follow the page | `border --x --y ...` around a field, then a validation message moved the field | Border moves with the field       | Border stayed put and covered the field's caption area; its text also covered the typed value. |
| D19| Low    | `arrow --handle --direction` | `arrow --handle=s1.h165 --direction=left` | Arrow at the element              | `arrow` needs `--from` and `--to`, or a target and `--direction` (same root cause as D13). |

### 2.5 Verbs that fail outright

| #  | Sev    | Verb                  | Actual                                                                 |
|----|--------|-----------------------|------------------------------------------------------------------------|
| D20| High   | `pw`                  | Every form fails with `pw needs an area`: positional (`pw page title`), flags (`--area= --action=`), `pw run --steps=file`, and `call pw --args-file`. The Playwright passthrough cannot be used from the CLI. |
| D21| Medium | `frame --list`        | `no live browser` while `doctor` reports `chrome=present health=ok page=attached`. |
| D22| Medium | `tab-new`             | Silently makes the new blank tab the protocol target. Later `storage`, `print`, and `screencast` acted on `about:blank` with no warning (PDF was 850 bytes, 1 frame captured). `tabs` still listed one tab. |
| D23| Low    | `tab-activate --page=`| `tab-activate` needs `--id`; other verbs in the same family take `--page=<url substring>`. |
| D24| Low    | `chat-read --fields=source,text,read` | Items came back as `[{}]`. Same with `find --format=json --fields=id,role,name,frame,ref`. Field names are not checked or listed. |

### 2.6 Foreign windows

| #  | Sev    | Area                  | Repro                                      | Actual                                                                 |
|----|--------|-----------------------|--------------------------------------------|------------------------------------------------------------------------|
| D25| Medium | Collaboration UI on an attached window | `overlay ... --target=browser:cdp=http://127.0.0.1:9333`, `chat-send ... --target=...` | Both succeeded but drew on the ConsumerApp `start` page. `--target` is silently ignored; no error says the verb only serves the start host. |

### 2.7 Desktop

| #  | Sev    | Area                  | Repro                                      | Actual                                                                 |
|----|--------|-----------------------|--------------------------------------------|------------------------------------------------------------------------|
| D26| High   | Typing into Notepad   | `act --target=desktop:app=Notepad --kind=type` with `--query=Text-editor`, `--query="Text editor"`, `--query=editor`, `--ref=ref:desktop:0:4`, `--query=s1.h836`, `css=`, `xpath=` | Either `query requires --css, --xpath, --text, --pipeline, or --selector` or `not found`. `find` returned the same node (`document "Text editor"`, `interactive=false`). `act` seems to skip non-interactive roles, and a UIA `Document` is non-interactive. |
| D27| Medium | Misleading error      | as D26                                     | The error names flags (`--text`, `--pipeline`, `--selector`) that `act` does not list in its usage. `--text=...` returned nothing at all. |
| D28| Medium | Same target, two parsers | `--target=desktop:app=pickleball-desktop-test` worked for `snap` and `find`, then `act` with the same target returned in 17 ms with the browser frame id. | The hyphenated app name or the warm hop sent `act` to a different path. |
| D29| Low    | `state.frame` in desktop replies | Every desktop `act` reply carried the ConsumerApp browser frame id | Makes a correct desktop call look misrouted. |

## 3. Limitations (by design or not yet built)

| #   | Area                  | Limitation                                                                 | Effect on this test |
|-----|-----------------------|----------------------------------------------------------------------------|---------------------|
| L1  | `guide`               | Targets resolve in the top document only (`#id`, `[id=...]`, short id all refused). | Unusable on ConsumerApp: every form is inside `PegaGadgetNIfr`. The guide feature, built for this exact hand-off, could not be used once. |
| L2  | `find --role`         | CDP `Accessibility.queryAXTree` and Playwright `getByRole` searched the top document only. | Pega's Submit button (`button "Complete this assignment"`) returned 0 hits. |
| L3  | `eval`                | Does not await a Promise; returns `{}`.                                    | Two `eval` calls per async check (store on `window`, read back). |
| L4  | `inspect-page`, `find-element` | Need an `isolate` worker; on a `start` host they say `start a session first`. | The message is wrong for a session that is clearly started. |
| L5  | Foreign windows       | Collaboration UI (panel, chat, cards, overlays) exists only on the `start` host or worker. | An attached window gets `eval`, `act`, `snap`, `cdp`, `browser-events` only. |
| L6  | User's own browsers   | Attach needs the `chrome://inspect` toggle, the Playwright extension, or `relaunch-with-debug --confirm` (closes the browser). | Not attempted on the user's Chrome/Edge. |
| L7  | Desktop launch        | No verb starts an app.                                                     | Used `Start-Process`. |
| L8  | Desktop text entry    | No working path to type into a document control (D26).                     | Used `java.awt.Robot` outside Pickleball. |
| L9  | Network mocks of Pega AJAX | `net-route` hit is logged, but Pega drops a swapped or edited body because it is tied to `pzTransactionId`. | Mocking real Pega responses needs a body built for the live transaction. |
| L10 | WebMCP                | Not available on Chrome 157 without `navigator.modelContext`.              | Not tested. |
| L11 | BiDi and Selenium classic on the start host | `doctor --protocols` reports `bidi=no classic=no`.                         | Fallback order could only compare CDP and Playwright. |
| L12 | Chat channel          | One `run` channel is shared across the `start` host and the worker.       | The worker page replayed the earlier session's whole conversation. |
| L13 | Gherkin worker default context | The project registers the Pega iframe as the default context; the header links in the top document were not found by `execute-step`. | Mixed live-verb/Gherkin sessions start in different frames. |

## 4. Inefficiencies and timings

| #   | Operation                              | Observed                          | Faster path seen                                      | Ratio          |
|-----|----------------------------------------|-----------------------------------|-------------------------------------------------------|----------------|
| E1  | `find --query=<text>` (engine) with 1–3 Pega iframes | 1.8 s → 20.6 s → 27.8 s          | `snap --within=<iframe> --where=role=...` 0.24 s     | ~100×          |
| E2  | `act --query=<text>` with an ambiguous name | 21 s, then `ambiguous`           | `snap` + `act --query=<short id>` 0.3–0.6 s          | ~40×           |
| E3  | `highlight --query=<name>`             | 1.5–2.0 s                        | `highlight --handle` 15–50 ms (top document only)    | ~40×           |
| E4  | First Playwright call (`aria-snap`)    | 5.9 s                            | Second call 0.8 s                                    | attach cost    |
| E5  | First raw `cdp DOM.getDocument`        | 6.8 s                            | `Runtime.evaluate` 0.65 s wall                       | attach cost    |
| E6  | Desktop `snap` / `act` (UIA, PowerShell) | 6.5 s snap; 7.5–9.3 s per click | —                                                    | One full tree walk per action |
| E7  | `isolate` cold start                   | 148.6 s                          | —                                                    | Expected per docs, but no warm reuse across run ids |
| E8  | CLI process cost per verb              | 0.6–1.0 s wall for 10–200 ms of work | —                                                 | JVM start dominates every call |
| E9  | `input` key chain (8 chars)            | 3.6 s wall                       | —                                                    | One round trip per key |
| E10 | Reply size                             | `checklist` echoes the full injected HTML (`guidance`, `summary`, `sha256`) | `--fields`                                       | Wasted agent context on every placement |

## 5. Documentation and instruction gaps

| #  | Gap                                                                 | Effect |
|----|---------------------------------------------------------------------|--------|
| G1 | No statement that `guide`, `--handle` placements, `find --role`, and `act --kind=type` behave differently inside iframes. | Hours of trial and error on any Pega page. |
| G2 | `input` is not mentioned as the typing fallback for iframes or custom controls. | Found by reading help for unrelated verbs. |
| G3 | `--headed` value parsing (D1) is not documented; examples show bare `--headed` before `--panel`. | The documented first command opens a headless browser. |
| G4 | No PowerShell quoting guidance for commas (`a,b` becomes an array) or embedded quotes (`'--text=Click "Save"'` works; `\"` does not). | Two false "defects" and one stray directory (D2). |
| G5 | The two-actor recipe says the helper runs "under its own run id", but `session-mode` locks the whole session (D6). | The recipe cannot be followed literally. |
| G6 | Hyphen-as-space (`--query=Create-New-Case`) is documented for `find`, not for `act`, `highlight`, or desktop targets, and it does not work for desktop `act`. | Inconsistent selector rules per verb. |
| G7 | `--fields` key names are not listed per verb (D24).                 | Silent empty items. |
| G8 | No note that `eval` returns `{}` for a Promise.                     | Wrong conclusions about network mocks until the pattern was found. |
| G9 | Accessible names differ from visible labels (Pega "Submit" is `button "Complete this assignment"`). No guidance to search by role within a frame first. | Missed the button on the first two tries. |

## 6. Ideas and proposed features

### 6.1 Iframes as first-class targets (fixes D8, D13, D14, D19, L1, L2, G1)

1. Frame-aware short ids. A short id already carries its frame (`frame=D5BC...`). Every verb that takes `--handle`, `--query=<short id>`, or a guide `t` should resolve it in that frame, not the top document.
2. Guide steps by short id or role/name. Accept `{"t":"s1.h165"}` and `{"role":"textbox","name":"Account Number","frame":"PegaGadget1Ifr"}`.
3. Placements anchored to an element, not to coordinates. Keep an overlay's anchor as a frame-qualified ref, and reposition it on scroll, resize, and DOM mutation (a `ResizeObserver` plus `MutationObserver` per anchored placement). Report `lost` only when the anchor is detached.
4. `find --role` across frames by default, with `--frame=top|all|<id>`.
5. Default frame policy flag (`pkb_frame_policy=auto|top|<id>`) shared by live verbs and Gherkin steps, so both start in the same frame (L13).

### 6.2 Typing and form entry (fixes D8, D9, D26, E9)

1. `act --kind=type` fallback ladder with the rung reported: `Input.insertText` → key events → `input` chain at the element's centre. Reply: `"via":"cdp-insertText"`.
2. Verify-after-write. After `type`, `select`, and `form-fill`, read the value back and report `"value":"<account-no>","verified":true`. This removes false negatives such as D9.
3. Batch key events. `input --text="<account-no>" --at=<short id>` sends one CDP `Input.insertText` instead of 16 round trips.
4. Desktop type through UIA `ValuePattern` / `TextPattern`, falling back to `SendInput` after a foreground check — the same check used manually in this test.

### 6.3 Selector and lookup speed (fixes E1–E3, D7, D10, D11)

1. Make `snap` the backbone of `find`. Build a short-lived index from the last `snap` (per document version) and answer `find --query` from it. Fall back to the engine only on a miss.
2. Exclude `data-pkb-meta="true"` from every page `find`, `act`, and `highlight` unless `--purpose=` names a pkb token (D7).
3. Visibility from layout, not attributes. Use the same visibility check `act` uses (`checked.visible`) in `snap` and `find` rows, so hidden Pega fields and closed menus drop out (D11).
4. Ranking for `act --query`. When one candidate's role matches `--pick` exactly, choose it instead of returning `ambiguous`; list why each other candidate lost.
5. Time budget and progress. `--budget-ms` on `find` / `act`, and a `progress` line every 2 s so a 20 s search is visible as it runs.
6. Warm Playwright and CDP sessions at `start` so the first `aria-snap` or raw `cdp` call does not pay 6 s.

### 6.4 Human-lock and tier model (fixes D4–D6, G5)

1. Per-caller tiers. `session-mode --human=on --caller=helper` applies the lock to calls that carry `--caller=helper` (or a token), leaving the agent at control tier. This matches the two-actor recipe.
2. Accept `--human-override` on every control verb, including `act` and `call`, or reject it uniformly with one clear message.
3. Tier in every reply. `"tier":"human","caller":"helper"`, so logs show who did what.

### 6.5 Bidirectional communication (chat, cards, events)

1. Structured chat messages. Let `chat-send` carry `{text, refs:[s1.h165], actions:[{label,verb,args}]}`. The panel renders refs as clickable chips that highlight the element, and actions as buttons. The helper could click "Show me" instead of searching.
2. Human replies that point at the page. A "point" button in the composer lets the person click an element; the message arrives with its short id and frame. Today the person can only describe an element in words.
3. Typed answers on cards. `ask --input=text|number|select --validate=<regex>` for values such as account numbers, with the answer returned as a typed JSON value.
4. Per-session chat channels by default, with an explicit `--channel=shared` to replay history (L12).
5. One wait for several conditions. `wait-any --answer=q-1 --chat=human --watch=s1.h458:click --timeout=60s` returns whichever comes first. The agent ran separate waits for each kind in this test.
6. Progress card. A built-in card that shows the checklist, the current step, and the agent's status ("searching…", "waiting for you") in one place instead of a checklist, a toast, and chat messages spread across the page.
7. Read receipts for overlays. Push an event when the person sees (scrolls to) or dismisses an overlay, the same way chat has sending/delivered/read.

### 6.6 DOM and browser interfaces

1. `eval --await` (and `--frame=<id>`) so one call returns a Promise's result and can run inside a given iframe (L3, G8).
2. `tab-new --activate=false` default, and a warning in every reply when the protocol target differs from the visible tab (D22).
3. `frame --list` served by the start host (D21), including iframe id, name, URL, and same-origin flag.
4. Collaboration UI on attached windows: when `--target=browser:cdp=...` is passed to `overlay`, `chat-send`, `panel-open`, inject the page runtime there, or fail with a clear unsupported on attached target (D25, L5).
5. Mock builder for transaction-bound apps. `net-route --action=modify --replace-body='<regex><text>'` changes the live response in flight instead of replacing it, so Pega's transaction id stays valid (L9).
6. Fix `pw` (D20) and add `pw` to `execute-steps` so a Playwright locator step can sit beside live verbs.

### 6.7 OS and desktop

1. `desktop launch --app=notepad [--args=...] --wait-window=<title>` returning the window target (L7).
2. Foreground guard on every desktop input. Refuse to type unless the target window is foreground and its title matches; report the title in the reply. This protects the user's other documents, which mattered here because Notepad already held unsaved tabs.
3. Cache the UIA tree per window with change events (`StructureChanged`, `PropertyChanged`) so each click does not walk the tree again (E6).
4. `desktop screenshot` of a window or the screen, so the agent need not use `.NET CopyFromScreen`.
5. Correct `state` for desktop replies (`frame=desktop:<window title>`) (D29).

### 6.8 Data format, normalisation, and sharing

1. One reply schema for all verbs. Some verbs reply with the envelope (`v, ok, op, ms, state, sum, items`), others with bare objects (`pw` errors, `browser-events` without `v` / `state`, `doctor` as key=value lines). One schema would let an agent parse every reply with one rule.
2. Stable error codes. Use enums (`E_NOT_FOUND`, `E_AMBIGUOUS`, `E_STALE_REF`, `E_TIER`, `E_UNSUPPORTED_TARGET`, `E_NOT_APPLIED_UNVERIFIED`) next to the human message, plus `retryable:true|false`.
3. `--fields` validation. Unknown field names return `error: unknown field "text"; known: id, at, source, ...` (D24).
4. Lean replies by default for placements. Return `{id, kind, visible, rect, overlaps}`; move `guidance`, `summary`, `sha256` behind `--verbose` (E10).
5. Normalised element record shared by `snap`, `find`, `act`, placements, and desktop: `{sid, ref, frame, role, name, label, value, visible, enabled, interactive, rect, purpose}`. Today the same element has different keys in different replies.
6. Argument files for every verb. `--args-file` works only through `call`. Accept `@args.json` on every verb so quoting never depends on the shell (G4, D2).
7. Reject unknown positionals unless the verb declares a directory argument (D2).
8. Session journal for agents. One NDJSON file per session that records each verb, its args, tier, caller, reply code, and timing. An agent could resume or hand off from it, and the human could audit it.

### 6.9 Instructions for agents

1. An "iframe-heavy apps" page (Pega, Salesforce, Oracle) with the working recipe from this test:
   - `snap --within=<iframe id> --where=role=<role>` for lookup.
   - `act --query=<short id>` for clicks.
   - `input` for typing until 6.2 lands.
   - Coordinates from `eval` rects for overlays until 6.1 lands.
2. A shell quoting table for PowerShell, cmd, and bash: commas, double quotes, `&` in URLs, and `@file` args.
3. A verb capability matrix (top document / same-origin iframe / attached window / worker / desktop) for each verb.
4. Two-actor recipe update to match the real lock model, or the lock model updated to match the recipe (6.4).
5. Expected timings per verb so an agent can tell a slow call from a hung one.

## 7. Suggested priority

| Priority | Items                          | Why |
|----------|--------------------------------|-----|
| 1        | D1, D4, D7, D8, D13, L1 (6.1, 6.2, 6.3.2) | Core hand-off on any iframe app fails or needs hidden workarounds. |
| 2        | D20, D22, D26, E1, E2 (6.3.1, 6.6.2, 6.7.2) | Broken verb, silent retarget, unsafe desktop input, 20–28 s lookups. |
| 3        | D2, D9, D15–D18, D24, D25 (6.8.1–6.8.7) | Wrong or misleading replies that cost agent retries. |
| 4        | 6.5 and 6.4 ideas              | Better two-way collaboration between the person and the agent. |
| 5        | Documentation gaps G1–G9       | Cheap to fix and saves the most agent time right away. |

## 8. What worked well (keep as is)

- Question cards with push wake (60–115 ms) and `Answer sent:` confirmation.
- Chat with sending/delivered/read states that survive reload and pop-out.
- `.pkb-value` quote chips in chat, cards, and pins.
- `snap` speed (150–235 ms) and short ids with role, name, frame, and card state.
- Human lock refuses control verbs with a clear `fix:` line.
- `browser-events`, `errors`, `perf`, `audits`, `emulate`, `print`, `screencast`, `storage-state` over CDP, each under 1 s.
- `net-route` through CDP and Playwright, `net-block`, `net-headers`, `har`, `net-body`, and `capture-scenario --har` producing a `SERVICE_CALL` feature from real traffic.
- `attach --discover` on a window Pickleball did not open.
- The warm controller (`PkbMain`) cutting per-verb cost to about 0.7 s.
