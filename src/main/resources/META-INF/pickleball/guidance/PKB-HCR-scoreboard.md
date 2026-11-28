# PKB-HCR — results template

PKB-HCR (pickleball Human Collaboration Regression) results template. In this repository, copy this file to a dated `pkb-hcr-<yyyy-mm-dd>.md` file for each run. The catalog is `PKB-HCR.md`. In a consumer project, this file is exported as `.pickleball/PKB-HCR-scoreboard.md`; copy it to a dated file the owner names, and load the catalog from `.pickleball/PKB-HCR.md`. Record stable ids HCR-01 through HCR-75 (older M-* aliases in parentheses). Times are MST (America/Phoenix).

## Run

| Field | Value |
|---|---|
| Date and start time (MST) |  |
| Branch and SHA |  |
| Scope | whole catalog (Dan asked: quote the request) / targeted (list the tests and the implementation that may have impacted them) |
| Machine, OS, Chrome version |  |
| Launcher form (S0 step 3) | Maven exec / `java -cp` |
| H played by | a separate agent / the same agent with separate notes |
| Notes files | `C:\pkb-manual\agent-notes.md`, `C:\pkb-manual\helper-notes.md` |

## Results

Result is `pass`, `fail`, `gap` (an expected human-mode limitation from the catalog), `unclear` (recorded behavior for a capability marked unclear), `not run` (with the reason, for example no approval for an external download), or `env` (environment only, for example WebMCP unsupported on the page).

| ID | Test | Result | Evidence (verb, exit, `ms` or `workerMs`, key reply fields) | Time (MST) | Notes |
|---|---|---|---|---|---|
| HCR-01 (M-HL-01) | Control verbs are refused in the human lock |  |  |  |  |
| HCR-02 (M-HL-02) | Override is for the owner only |  |  |  |  |
| HCR-03 (M-HL-03) | Human input forms |  |  |  |  |
| HCR-04 (M-HL-04) | start --human locks a new session |  |  |  |  |
| HCR-05 (M-HV-01) | A covered or hidden control is not human input (A→H) |  |  |  |  |
| HCR-06 (M-HV-02) | Collaboration fields in the human view (A→H) |  |  |  |  |
| HCR-07 (M-HV-03) | Aria view and aria refs (H) |  |  |  |  |
| HCR-08 (M-HV-04) | Screenshot and capture (H) |  |  |  |  |
| HCR-09 (M-HV-05) | Name ranking and pkb surfaces (A→H) |  |  |  |  |
| HCR-10 (M-HV-06) | Snapshot deltas (A→H) |  |  |  |  |
| HCR-11 (M-CH-01) | Agent asks, person answers in the panel (A→H→A) |  |  |  |  |
| HCR-12 (M-CH-02) | Person writes first; listed once (H→A) |  |  |  |  |
| HCR-13 (M-CH-03) | Chat pop-out keeps read marks (A, H check) |  |  |  |  |
| HCR-14 (M-CA-01) | Choose card (A→H) |  |  |  |  |
| HCR-15 (M-CA-02) | Timeout and retry under the same id (A→H) |  |  |  |  |
| HCR-16 (M-CA-03) | Text, confirm, and sensitive cards (A→H) |  |  |  |  |
| HCR-17 (M-CA-04) | wait-any watch (A→H) |  |  |  |  |
| HCR-18 (M-CA-05) | Agent answers a card (A, H check) |  |  |  |  |
| HCR-19 (M-CA-06) | Quoted value chip (A→H) |  |  |  |  |
| HCR-20 (M-GD-01) | Person follows the guide (A→H→A) |  |  |  |  |
| HCR-21 (M-GD-02) | Agent moves the guide (A, H check) |  |  |  |  |
| HCR-22 (M-GD-03) | Guide survives reload (A, H check) |  |  |  |  |
| HCR-23 (M-GD-04) | Missing steps file (A) |  |  |  |  |
| HCR-24 (M-VS-01) | Highlight (A→H) |  |  |  |  |
| HCR-25 (M-VS-02) | Visual components (A→H) |  |  |  |  |
| HCR-26 (M-VS-03) | Overlay and blocking (A→H→A) |  |  |  |  |
| HCR-27 (M-VS-04) | Pin, toast, checklist, progress (A→H) |  |  |  |  |
| HCR-28 (M-VS-05) | Batch UI and TTL (A, H check) |  |  |  |  |
| HCR-29 (M-VS-06) | Placements follow layout changes (A, H check) |  |  |  |  |
| HCR-30 (M-HI-01) | HTML write, replace, insert, remove, cleanup (A→H) |  |  |  |  |
| HCR-31 (M-HI-02) | Injected script is inert (A) |  |  |  |  |
| HCR-32 (M-HI-03) | Consumer direct DOM and JS (A, H check) |  |  |  |  |
| HCR-33 (M-PT-01) | Panel tabs, collapse, reopen (A→H→A) |  |  |  |  |
| HCR-34 (M-PT-02) | Close asks first (H→A) |  |  |  |  |
| HCR-35 (M-PT-03) | Hidden composer is not clickable (H) |  |  |  |  |
| HCR-36 (M-RC-01) | Recording with the person (A→H→A) |  |  |  |  |
| HCR-37 (M-RC-02) | Step editor edits (A, H check) |  |  |  |  |
| HCR-38 (M-RC-03) | Editor pop-out (A) |  |  |  |  |
| HCR-39 (M-RC-04) | Capture to steps (A) |  |  |  |  |
| HCR-40 (M-UP-01) | Person asks the agent to attach (H→A→H) |  |  |  |  |
| HCR-41 (M-UP-02) | Upload forms and errors (A) |  |  |  |  |
| HCR-42 (M-UP-03) | Drag and drop (A→H) |  |  |  |  |
| HCR-43 (M-NV-01) | Navigate keeps the session (A, H check) |  |  |  |  |
| HCR-44 (M-LS-01) | DOM push stream (H→A) |  |  |  |  |
| HCR-45 (M-LS-02) | Agent waits that wake on push (H→A) |  |  |  |  |
| HCR-46 (M-LS-03) | Person-side waits (A→H) |  |  |  |  |
| HCR-47 (M-LS-04) | Collaboration waits use push (A) |  |  |  |  |
| HCR-48 (M-LS-05) | Event streams (A) |  |  |  |  |
| HCR-49 (M-LS-06) | Page errors and logpoints (H→A) |  |  |  |  |
| HCR-50 (M-DT-01) | Raw CDP (A→H) |  |  |  |  |
| HCR-51 (M-DT-02) | Playwright over CDP (A→H) |  |  |  |  |
| HCR-52 (M-DT-03) | Selenium BiDi verbs (A) |  |  |  |  |
| HCR-53 (M-DT-04) | Network capture (H→A) |  |  |  |  |
| HCR-54 (M-DT-05) | Network control (A→H) |  |  |  |  |
| HCR-55 (M-DT-06) | WebSocket routing (A) |  |  |  |  |
| HCR-56 (M-DT-07) | Emulation (A→H) |  |  |  |  |
| HCR-57 (M-DT-08) | Clock (A→H) |  |  |  |  |
| HCR-58 (M-DT-09) | Permissions and geolocation (H→A→H) |  |  |  |  |
| HCR-59 (M-DT-10) | Contexts, tabs, history, print (A, H check) |  |  |  |  |
| HCR-60 (M-DT-11) | Storage (H→A→H) |  |  |  |  |
| HCR-61 (M-DT-12) | Downloads (H→A) |  |  |  |  |
| HCR-62 (M-DT-13) | JavaScript dialogs (H→A) |  |  |  |  |
| HCR-63 (M-DT-14) | Input chains and text (A, H) |  |  |  |  |
| HCR-64 (M-DT-15) | WebAuthn, extensions, WebMCP (A) |  |  |  |  |
| HCR-65 (M-DT-16) | Performance, memory, screencast (A) |  |  |  |  |
| HCR-66 (M-DT-17) | Audits, service workers, security, CSP, auth (A) |  |  |  |  |
| HCR-67 (M-DT-18) | Optional add-ons (A, needs Dan's OK) |  |  |  |  |
| HCR-68 (M-DT-19) | Attach routes and sharing (A) |  |  |  |  |
| HCR-69 (M-DT-20) | Frames and shadow roots (A→H) |  |  |  |  |
| HCR-70 (M-PP-01) | Agent popup window (A) |  |  |  |  |
| HCR-71 (M-PP-02) | Native highlight (A) |  |  |  |  |
| HCR-72 (M-PP-03) | Desktop surface (A→H, Windows) |  |  |  |  |
| HCR-73 (M-LK-01) | Spine session surfaces (A) |  |  |  |  |
| HCR-74 (M-LK-02) | Human lock is not the Workbench lease (A) |  |  |  |  |
| HCR-75 (M-MC-01) | call forwards tools with tiers (A, H) |  |  |  |  |

## Failures (one batch)

List every failure before fixing any. Then record the fix batch: the root cause, the reproducing automated test, the commit SHA, and the targeted tests run together for the batch. Re-run only the failed manual tests after the batch passes.

| ID | Symptom | Root cause | Fix SHA | Reproducing test | Re-run result |
|---|---|---|---|---|---|

## Gaps and unclear capabilities

| ID | Capability | What happened | Proposed follow-up |
|---|---|---|---|

## Timing

Per-verb wall times for each side. A verb over 2 s while the other side waits is listed under Failures.
