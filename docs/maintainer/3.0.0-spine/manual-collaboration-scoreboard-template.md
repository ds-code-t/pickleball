# Manual collaboration regression — results template

Copy this file to `docs/maintainer/3.0.0-spine/manual-collaboration-<yyyy-mm-dd>.md` for each run. The catalog is `docs/roadmaps/3.0.0-spine/manual-collaboration-regression.md`. Times are MST (America/Phoenix).

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
| M-HL-01 | Control verbs are refused in the human lock |  |  |  |  |
| M-HL-02 | Override is for the owner only |  |  |  |  |
| M-HL-03 | Human input forms |  |  |  |  |
| M-HL-04 | start --human locks a new session |  |  |  |  |
| M-HV-01 | A covered or hidden control is not human input (A→H) |  |  |  |  |
| M-HV-02 | Collaboration fields in the human view (A→H) |  |  |  |  |
| M-HV-03 | Aria view and aria refs (H) |  |  |  |  |
| M-HV-04 | Screenshot and capture (H) |  |  |  |  |
| M-HV-05 | Name ranking and pkb surfaces (A→H) |  |  |  |  |
| M-HV-06 | Snapshot deltas (A→H) |  |  |  |  |
| M-CH-01 | Agent asks, person answers in the panel (A→H→A) |  |  |  |  |
| M-CH-02 | Person writes first; listed once (H→A) |  |  |  |  |
| M-CH-03 | Chat pop-out keeps read marks (A, H check) |  |  |  |  |
| M-CA-01 | Choose card (A→H) |  |  |  |  |
| M-CA-02 | Timeout and retry under the same id (A→H) |  |  |  |  |
| M-CA-03 | Text, confirm, and sensitive cards (A→H) |  |  |  |  |
| M-CA-04 | wait-any watch (A→H) |  |  |  |  |
| M-CA-05 | Agent answers a card (A, H check) |  |  |  |  |
| M-CA-06 | Quoted value chip (A→H) |  |  |  |  |
| M-GD-01 | Person follows the guide (A→H→A) |  |  |  |  |
| M-GD-02 | Agent moves the guide (A, H check) |  |  |  |  |
| M-GD-03 | Guide survives reload (A, H check) |  |  |  |  |
| M-GD-04 | Missing steps file (A) |  |  |  |  |
| M-VS-01 | Highlight (A→H) |  |  |  |  |
| M-VS-02 | Visual components (A→H) |  |  |  |  |
| M-VS-03 | Overlay and blocking (A→H→A) |  |  |  |  |
| M-VS-04 | Pin, toast, checklist, progress (A→H) |  |  |  |  |
| M-VS-05 | Batch UI and TTL (A, H check) |  |  |  |  |
| M-VS-06 | Placements follow layout changes (A, H check) |  |  |  |  |
| M-HI-01 | HTML write, replace, insert, remove, cleanup (A→H) |  |  |  |  |
| M-HI-02 | Injected script is inert (A) |  |  |  |  |
| M-HI-03 | Consumer direct DOM and JS (A, H check) |  |  |  |  |
| M-PT-01 | Panel tabs, collapse, reopen (A→H→A) |  |  |  |  |
| M-PT-02 | Close asks first (H→A) |  |  |  |  |
| M-PT-03 | Hidden composer is not clickable (H) |  |  |  |  |
| M-RC-01 | Recording with the person (A→H→A) |  |  |  |  |
| M-RC-02 | Step editor edits (A, H check) |  |  |  |  |
| M-RC-03 | Editor pop-out (A) |  |  |  |  |
| M-RC-04 | Capture to steps (A) |  |  |  |  |
| M-UP-01 | Person asks the agent to attach (H→A→H) |  |  |  |  |
| M-UP-02 | Upload forms and errors (A) |  |  |  |  |
| M-UP-03 | Drag and drop (A→H) |  |  |  |  |
| M-NV-01 | Navigate keeps the session (A, H check) |  |  |  |  |
| M-LS-01 | DOM push stream (H→A) |  |  |  |  |
| M-LS-02 | Agent waits that wake on push (H→A) |  |  |  |  |
| M-LS-03 | Person-side waits (A→H) |  |  |  |  |
| M-LS-04 | Collaboration waits use push (A) |  |  |  |  |
| M-LS-05 | Event streams (A) |  |  |  |  |
| M-LS-06 | Page errors and logpoints (H→A) |  |  |  |  |
| M-DT-01 | Raw CDP (A→H) |  |  |  |  |
| M-DT-02 | Playwright over CDP (A→H) |  |  |  |  |
| M-DT-03 | Selenium BiDi verbs (A) |  |  |  |  |
| M-DT-04 | Network capture (H→A) |  |  |  |  |
| M-DT-05 | Network control (A→H) |  |  |  |  |
| M-DT-06 | WebSocket routing (A) |  |  |  |  |
| M-DT-07 | Emulation (A→H) |  |  |  |  |
| M-DT-08 | Clock (A→H) |  |  |  |  |
| M-DT-09 | Permissions and geolocation (H→A→H) |  |  |  |  |
| M-DT-10 | Contexts, tabs, history, print (A, H check) |  |  |  |  |
| M-DT-11 | Storage (H→A→H) |  |  |  |  |
| M-DT-12 | Downloads (H→A) |  |  |  |  |
| M-DT-13 | JavaScript dialogs (H→A) |  |  |  |  |
| M-DT-14 | Input chains and text (A, H) |  |  |  |  |
| M-DT-15 | WebAuthn, extensions, WebMCP (A) |  |  |  |  |
| M-DT-16 | Performance, memory, screencast (A) |  |  |  |  |
| M-DT-17 | Audits, service workers, security, CSP, auth (A) |  |  |  |  |
| M-DT-18 | Optional add-ons (A, needs Dan's OK) |  |  |  |  |
| M-DT-19 | Attach routes and sharing (A) |  |  |  |  |
| M-DT-20 | Frames and shadow roots (A→H) |  |  |  |  |
| M-PP-01 | Agent popup window (A) |  |  |  |  |
| M-PP-02 | Native highlight (A) |  |  |  |  |
| M-PP-03 | Desktop surface (A→H, Windows) |  |  |  |  |
| M-LK-01 | Spine session surfaces (A) |  |  |  |  |
| M-LK-02 | Human lock is not the Workbench lease (A) |  |  |  |  |
| M-MC-01 | call forwards tools with tiers (A, H) |  |  |  |  |

## Failures (one batch)

List every failure before fixing any. Then record the fix batch: the root cause, the reproducing automated test, the commit SHA, and the targeted tests run together for the batch. Re-run only the failed manual tests after the batch passes.

| ID | Symptom | Root cause | Fix SHA | Reproducing test | Re-run result |
|---|---|---|---|---|---|

## Gaps and unclear capabilities

| ID | Capability | What happened | Proposed follow-up |
|---|---|---|---|

## Timing

Per-verb wall times for each side. A verb over 2 s while the other side waits is listed under Failures.
