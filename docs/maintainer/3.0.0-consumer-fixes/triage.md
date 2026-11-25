# Triage: consumer report 2026-11-24 (ConsumerApp, Windows 11, pickleball 3.0.0 b65a716)

Triaged 11-25 (MST), read-only, against origin/3.0.0 = b65a716b. No code changes, builds or pushes.
Round-3 open items (state.md): a = page controls must outrank own panel text in find/act; b = doctor orphans false positive; c = wait new-message --from=agent; d = guide --steps=@file missing file message; e = minors (collapsed panel visibility, applied=false on Upload, timeout fix hint).

Classes: 1 = fixed on origin/3.0.0 · 2 = overlaps round-3 a–e · 3 = still open (code cited) · 4 = needs a live run (W = Windows PC, B = box is enough) · ENV = test environment or usage, not a pickleball bug · DES = by design or not built.

Note: the report was produced on b65a716, which already contains R1–R8. So no item comes back as class 1. R7 (named-word fallback) and R8 are in that build, and their known gaps are round-3 a and b.

## Defects

| # | Sev | Class | Evidence / note |
|---|-----|-------|-----------------|
| D1 `--headed --panel` gives headless | High | 3 | ConsumerReadyCommands.flag() line 1161 returns the next token (`--panel`) as the value of `--headed`. headed() line 218 then says false. Same bug pattern for any bare boolean flag that is followed by a flag. |
| D2 Stray positional becomes a project dir | Med | 3 + ENV | WorkbenchCommandLine.looksLikeProject() line 1025 treats any token containing `/` or `\` as a project, even when no such directory exists. Triggered by PowerShell quoting (G4). |
| D3 `doctor --protocols` ignores the worker | Low | 4B | Not traced. Needs an isolate worker alive. |
| D4 `act` refuses `--human-override` | High | 3 | TargetVerb ACT_KEYS (line ~75–78) has no `human-override`, so act fails with "act does not use --human-override" (line ~717). HumanLock.check (lines 75–86) accepts the override but does not strip the flag. |
| D5 `call` under lock: override not forwarded | Med | 3 (likely) | The CLI gate (WorkbenchAgentCommands.humanLockRefusal lines 62–70) passes `call`. The inner verb from `--args-file` is re-checked without the override, and JSON args cannot carry it. Confirm in the fix. |
| D6 Lock is session-wide | Med | ENV + 3 (UX) | The lock is per run id (HumanLock key, default `session`). The recipe (collaborative-self-test.md:276–277) already says to give the helper `--run-id=<helper>`. The consumer ran both roles on one run id. Gap: no warning when `session-mode --human=on` is run without `--run-id`. |
| D7 Own checklist rows are find/act candidates | High | 2 (a) | This is exactly round-3 a. Uncommitted S1 (pkbOwned/preferPage) targets `data-pkb-*` rows. Add a test for checklist rows (`data-pkb-purpose`). |
| D8 `act --kind=type` in a Pega iframe: not applied | High | 4W | Likely a cross-origin iframe focus/insert path. Repro on DESKTOP-TUUEDJG or a cross-origin fixture on the box. |
| D9 `select` reports not applied, but the option was selected | Med | 2 (e) | Same false-negative family as e (Upload applied=false). Fix with verify-after-write (6.2.2). |
| D10 Combobox missing, own row candidate, ambiguous | Med | 2 (a) | Own row part is a. The missing combobox is probably the D11/iframe ranking. Retest after a. |
| D11 Hidden nodes report `visible=true` | Med | 3 (part e) | dom-engine Query.inputRole() maps `type=hidden` to `textbox`. TargetVerb.hiddenByAncestor (line 4109) checks only `displayed` and the `hidden` attribute. |
| D12 stale_ref does not suggest refind | Low | 3 | TargetVerb.staleRef() line 3054: the fix line says "run snap again" and does not mention `--refind`. |
| D13 `--handle=<short id>` in an iframe: unknown handle | High | 3 | TargetVerb.highlight calls `document.getElementById(handle)` in the top document only. The short id's frame is ignored. |
| D14 `highlight --query=<short id>` not found | Med | 3 | highlightQuery (R7) does not expand short ids (no SessionIds.expand). |
| D15 step-badge says lost/hidden while visible | Med | 4W | Probably the iframe anchor (D13 root). Needs a repro. |
| D16 `unstep-badge --handle` ok:false | Med | 4W | AgentControl line 260 groups it with unlabel and others. Removal is keyed by handle, while the overlay id is pkb-2. Likely D13 root. |
| D17 checklist over the page header, `overlaps:[]` | Med | 4B | Overlap check vs page controls. Repro with a fixed header page on the box. |
| D18 Coordinate overlays do not follow the page | Med | DES/3 | Coordinate placements are static by design. Fix is the anchored placements in 6.1.3. |
| D19 `arrow --handle --direction` | Low | 3 | Same root as D13. |
| D20 `pw` always "needs an area" | High | 3 | PwCommand.positional() line 692 starts at index 2 and assumes args[1] is the project dir. Without an explicit project, `pw page title` makes the area "title". flags() never reads `--area`/`--action`. Error is at line 130. |
| D21 `frame --list`: no live browser | Med | 4W | Reached failFastWithoutBrowser (WorkbenchAgentCommands:2465) because livePageTarget found no bridge. Probably routing while an isolate worker was alive. |
| D22 `tab-new` silently retargets | Med | 4B | ContextCommands.tabNew (CDP Target.createTarget / PW newPage). How it becomes the protocol target was not traced. Repro on the box. |
| D23 `tab-activate` needs `--id` | Low | 3 | ContextCommands.tabActivate requires `--id`. No `--page=` support. |
| D24 `--fields` gives `[{}]` | Low | 3 + ENV | SnapView.fields() line 101 does no validation. PowerShell also turns `a,b` into an array (G4), so the joined name likely matched nothing. |
| D25 `--target` ignored by overlay/chat-send | Med | 3 (likely) | Collaboration verbs serve only the start host. There is no unsupported-target error. |
| D26 Desktop type into Notepad | High | 4W | UIA Document role is non-interactive. Windows only. |
| D27 Error names flags act does not list | Med | 3 | dom-engine Query.java:88 message (`--text`, `--pipeline`, `--selector`). |
| D28 Hyphenated desktop app name: act misroutes | Med | 4W | |
| D29 Desktop replies carry the browser frame id | Low | 4W | |

## Limitations

| # | Class | Note |
|---|-------|------|
| L1 guide top document only | 3 | Same root as D13 (top-document id lookup). Priority 1 in the report. |
| L2 `find --role` top document only | 3 (likely) | AX query and getByRole are top-frame. |
| L3 eval does not await a Promise | 3 | TargetVerb eval (lines ~2040–2065) Runtime.evaluate has no awaitPromise. Other paths (lines 2477, 2898) do set it. |
| L4 inspect-page says "start a session first" on a start host | 3 | WorkbenchAgentCommands:1940. Wording should say an isolate worker is needed. |
| L5 No collab UI on attached windows | DES | See D25 / 6.6.4. |
| L6 Attaching user browsers | DES/ENV | Security model. Keep. |
| L7 No desktop launch verb | DES | 6.7.1 |
| L8 Desktop text entry | 4W | Same as D26. |
| L9 Pega drops mocked bodies | ENV/DES | App-specific transaction binding. |
| L10 WebMCP | ENV | Chrome 157 flag. |
| L11 bidi/classic=no on start host | DES | Start host is CDP. |
| L12 Shared run chat channel | 3/DES | One `run` channel by design. 6.5.4 |
| L13 Gherkin default context is the iframe | ENV | Consumer project config. |

## Inefficiencies

| # | Class | Note |
|---|-------|------|
| E1 find 20–28 s with iframes | 3 | Engine text search per frame. 6.3.1 |
| E2 act ambiguous after 21 s | 2 (a) + 3 | Ranking is a. Speed is E1. |
| E3 highlight --query 1.5–2 s | 3 | Minor. |
| E4/E5 first PW/CDP attach 6 s | DES | 6.3.6 (warm at start) |
| E6 Desktop UIA 6–9 s | 4W | |
| E7 isolate 148 s cold | DES | Documented. |
| E8 JVM per verb | DES | Warm controller exists. |
| E9 input one round trip per key | 3 | 6.2.3 |
| E10 Placement replies too large | 3 | 6.8.4 |

## Doc gaps (all class 3, still open)

G1 iframe behaviour · G2 `input` as typing fallback · G3 `--headed` example (gone once D1 is fixed) · G4 PowerShell quoting (explains D2/D24, ENV) · G5 recipe is correct, needs a "use distinct --run-id" warning (D6) · G6 hyphen-as-space across verbs · G7 field names per verb · G8 eval Promise · G9 search by role in frame.

## Proposals (keep / defer)

| # | Rec | Why |
|---|-----|-----|
| 6.1.1 frame-aware short ids | Keep (P1) | Fixes D13, D14, D19, L1. |
| 6.1.2 guide by short id / role | Keep (P1) | L1 |
| 6.1.3 anchored placements | Keep (P3) | D18, D15. Medium size. |
| 6.1.4 find --role across frames | Keep (P1/P2) | L2 |
| 6.1.5 shared frame policy | Defer | L13 is consumer config. |
| 6.2.1 type fallback ladder | Keep (P1) | D8 |
| 6.2.2 verify-after-write | Keep (P1) | D9, e |
| 6.2.3 batched input text | Keep (P3) | E9 |
| 6.2.4 UIA ValuePattern type | Keep (P2, Windows) | D26 |
| 6.3.1 snap-backed find index | Defer (design) | Large. Do budget/progress first. |
| 6.3.2 exclude pkb meta from page finds | Keep (P1) | Is round-3 a. |
| 6.3.3 layout visibility in snap/find | Keep (P3) | D11 |
| 6.3.4 pick-role tie-break | Keep (P1 with a) | D10 |
| 6.3.5 --budget-ms + progress | Keep (P2) | E1, E2 |
| 6.3.6 warm PW/CDP at start | Defer | Attach cost, optional. |
| 6.4.1 per-caller tiers | Decision | Run-id lock already exists. Maybe a doc fix only. |
| 6.4.2 override on every control verb | Keep (P1) | D4, D5 |
| 6.4.3 tier in replies | Keep (P4) | Cheap. |
| 6.5.1 structured chat refs/actions | Defer | Feature. |
| 6.5.2 "point" button | Defer | Feature. |
| 6.5.3 typed card answers | Defer | Feature. |
| 6.5.4 per-session channels | Defer | L12, behaviour change. |
| 6.5.5 wait-any | Keep (P4) | Pairs with c. |
| 6.5.6 progress card | Defer | |
| 6.5.7 overlay read receipts | Defer | |
| 6.6.1 eval --await / --frame | Keep (P3) | L3, G8. Small. |
| 6.6.2 tab-new no retarget + warning | Keep (P2) | D22 |
| 6.6.3 frame --list on start host | Keep (P2) | D21 |
| 6.6.4 collab UI on attached targets | Keep: fail-clearly part only (P3) | D25. Defer injection. |
| 6.6.5 net-route modify body | Defer | L9 |
| 6.6.6 fix pw + pw in execute-steps | Keep fix (P2). Defer execute-steps. | D20 |
| 6.7.1 desktop launch | Defer | |
| 6.7.2 desktop foreground guard | Keep (P2) | Safety. |
| 6.7.3 UIA tree cache | Defer | E6 |
| 6.7.4 desktop screenshot | Defer | |
| 6.7.5 desktop state frame | Keep (P3) | D29 |
| 6.8.1 one reply schema | Defer | Large, breaking. |
| 6.8.2 stable error codes | Defer | Large. |
| 6.8.3 --fields validation | Keep (P3) | D24 |
| 6.8.4 lean placement replies | Keep (P3) | E10 |
| 6.8.5 normalised element record | Defer | |
| 6.8.6 @args.json on every verb | Defer (P5 docs first) | G4 |
| 6.8.7 reject unknown positionals | Keep (P3) | D2 |
| 6.8.8 session journal | Defer | |
| 6.9.1 iframe-heavy apps page | Keep (P5) | |
| 6.9.2 shell quoting table | Keep (P5) | |
| 6.9.3 capability matrix | Keep (P5) | |
| 6.9.4 two-actor recipe vs lock | Keep (P5) | |
| 6.9.5 expected timings | Defer | |

## Proposed merged fix list (round-3 a–e + still-open report items)

- **Phase 1 (core hand-off):** round-3 a plus D7, D10, 6.3.4 (finish uncommitted S1, add a checklist-row test). D1 + G3. D4, D5 (6.4.2). D13, D14, D19, L1 (6.1.1–2). L2 (6.1.4). D8 + D9 with e-applied (6.2.1–2), verified on the PC.
- **Phase 2 (broken verbs and safety):** D20 (pw). D22 (6.6.2). D21 (6.6.3). E1/E2 budget and progress (6.3.5). D26 + 6.7.2 (Windows). Round-3 b (orphans), c (new-message --from=agent), d (guide file not found).
- **Phase 3 (misleading replies):** D2 (6.8.7). D11 (6.3.3) + e-visibility minors. D12. D15–D18 (6.1.3 as stretch). D24 (6.8.3). D25 (fail clearly). D27. D29. L3 (6.6.1). L4. E10 (6.8.4). Rest of e (timeout fix hint).
- **Phase 4 (collaboration ideas):** 6.4.3 tier in replies. 6.5.5 wait-any. Rest deferred. 6.4.1 per Dan's decision.
- **Phase 5 (docs):** G1–G9, 6.9.1–4, plus a `session-mode` warning when there is no `--run-id` (D6/G5).
- **Tech debt:** Linux/macOS testing (already logged on the r3 branch). Deferred proposals listed above.

## Needs Dan's Windows PC (DESKTOP-TUUEDJG)

- Repro and verify: D8, D9, D15, D16, D21, D26, D27 (desktop act), D28, D29, E6.
- After the fix, re-check on Windows: D1, D2/D24 (PowerShell quoting), D4/D5, D13/D14 (Pega-like cross-origin iframe), D20.
- Box is enough: D3, D17, D22.
