# Iframe-heavy apps

Working recipe for pages built from iframes (Pega, Salesforce, Oracle, and the same shape). Read this after `docs/consumer-agent-guide.md`. The two-actor lock is in [Collaborative self-test](collaborative-self-test.md).

## Working recipe

1. `start` the page. A bare `--headed` is true when the next token is another flag or the project path (`--headed --panel`, or `--headed` last). `--headed=false` and `--headed=0` stay false.
2. `frame --list` while you still need the frame id, name, url, and `sameOrigin`. The top frame is same-origin. An `about:blank` child is not. This list is served by the start host even when an isolate worker is alive.
3. Look up inside the frame. Prefer `snap --within=<iframe id> --where=role=<role>`, or `find --role=<role> --name=<accessible name>`. On a start host, `find --role` searches every frame unless `--frame=top` or `--frame=<id>`. A CDP or BiDi target stays on the protocol host and still pierces content documents. The published id on `browser:auto` is a short id. A hit inside a frame is `frame:backend`.
4. Click with `act --query=<short id>`. Do not search the whole page by visible text when the name is ambiguous.
5. Type with `act --kind=type`. The reply reports `via` (`cdp-insertText`, `cdp-keys`, or `input-chain`), `value`, and `verified`. `applied` follows that read-back. A cross-origin iframe uses that frame's session. A hidden or collapsed type or select is refused before the write. That check does not refuse a click.
6. When the control is not a textbox, type with `input --text=<text> --at=<short id>`. That sends one `Input.insertText`, including at a frame short id. It is the fallback, not the first choice.
7. Paint `highlight`, `label`, `arrow`, `step-badge`, `unstep-badge`, and `guide` with that short id. A `frame:backend` handle paints on the top document at the stored viewport box. It does not inject the overlay into the iframe document. An unresolved guide still fails.
8. The accessible name is often not the visible label. Match role plus that name (`label for=` and `aria-label` count). A snap of a select still appends the selected option. The role-find row reports the label.

`-` and `_` in a page query are spaces when nothing matches exactly. The query's whole words must appear in order in the accessible name, aria-label, placeholder, or text. `Upload-File` matches "Upload File". `Uplo` does not. `find`, `act`, `upload`, and `highlight --query` use that rule. Desktop `act` does not. A desktop match is the window title and the document or edit inside it.

## Shell quoting

Pass one argument per flag. An unknown positional is an error and is not created as a directory. An absolute path is the project even when that directory does not exist yet. `record`, `selftest`, `pw`, and `cdp` also accept a relative slash or backslash path that does not exist yet. A bare `pw` area word, such as `page` or `run`, stays the area even when a directory of that name exists. `.` and `..` are project paths. `@<file>.json` and `--args-file=<file>` are a JSON object of flags on every verb. An explicit flag wins. An unknown key fails, and the error does not print the value. `call <verb> --args-file=<file>` still forwards that file to the named tool. The quoting table in this section stays.

| Shell | Commas | Embedded quotes | `&` in a URL |
|---|---|---|---|
| PowerShell | Quote the flag: `--fields="id,role"`. An unquoted comma becomes an array, so the verb sees one broken name. | A single-quoted argument keeps inner double quotes: `'--text=Click "Save"'`. A backslash-escaped quote inside double quotes does not. | Quote the URL. An unquoted `&` starts another statement. |
| cmd | Quote the flag: `--fields="id,role"`. | Prefer a form that does not nest quotes, or `\"` inside double quotes. | Quote the URL. An unquoted `&` separates commands. |
| bash | Quote the flag: `--fields='id,role'`. | `--text='Click "Save"'`. | Quote the URL. An unquoted `&` backgrounds the command. |

`--fields` on `snap`, `find`, and `chat-read` rejects an unknown name: `error: unknown field "x"; known: ...`. A token that contains `=` is a form-fill pair, not a projection name. Names that are always known: `ref`, `id`, `role`, `type`, `name`, `handle`, `visible`, `enabled`, `interactive`, `frame`, `via`, `shadow`, `shadow_path`, `collapsed`, `source`, `value`, `checked`, `purpose`, `badge`, `highlight`, `guide`, `card`, `msg`, `at`, `agent`, `channel`, `text`, `pageId`, `read`, `event`, `sessionId`, `priority`. A key that is already on a returned row is also accepted.

`eval` without `--await` does not return a Promise's value. `eval --await` sets CDP `awaitPromise` and returns that value. `eval --frame=<id>` runs in that frame. A timeout stays the text `timeout` and has no stable `code`.

## Capability matrix

`yes` means the verb runs on that surface. A blank cell means the verb is not that surface's path. "Attached" is a CDP URL, another browser target, or `desktop:` passed to a page collaboration verb.

| Verb | Top document | Same-origin iframe | Cross-origin iframe | Attached browser | Isolate worker | Desktop |
|---|---|---|---|---|---|---|
| `frame --list` | rows include the top frame | `sameOrigin=true` when scheme, host, and port match | `sameOrigin=false` | not a CDP attach list | still the start host | |
| `snap` | yes | `--within` includes that frame | use the frame id from `frame --list` | `snap --target` can read it | `--session` selects the process | `desktop:app=` is the same as `desktop:app~`. `state.frame` is `desktop:<title>` |
| `find` / `find --role` | yes, every frame unless `--frame=top` or `--frame=<id>` | included in the default scan | short id is `frame:backend` | protocol host pierces content documents | page verb posts to the live worker when one is up | title match, not hyphen-as-space |
| `act` click | yes | yes | yes with the frame short id | page verb, not a collaboration refusal | posts to the live worker | refused unless that window is foreground and the title matches |
| `act` type / select | verified read-back | frame session | frame session | page verb | posts to the live worker | ValuePattern, then `WM_SETTEXT`, then TextPattern plus SendInput, then SendInput. Foreground guard. |
| `input --text --at` | one insertText | frame short id | frame short id | | | |
| `highlight` | yes | paints on the top document at the stored box | same | still paints the selected browser | | `--target=native` draws the rectangle |
| `guide` | yes | top-document box for a frame short id | same | | | |
| `label`, `border`, `arrow`, `step-badge`, `magnifier`, `spotlight`, `overlay`, `chat-send`, `panel-open`, `pin`, `toast`, `checklist` | yes on the start host | anchored box follows scroll | top-document box for a frame handle | `unsupported on attached target` | the live session | `unsupported on attached target` |
| `eval` | yes | `--frame=<id>` | `--frame=<id>` | CDP | | |
| `session-mode` | the lock is per run id | | | | | |
| `wait-any` | stored events, no browser required | | | | does not hold the command lane | |

`page`, `popup`, `auto`, `native`, and `browser:auto` are not attached targets. A ref target is not an attached target.

## Expected timings

These are hung-call ceilings from the 2026-11-24 consumer run and the Windows proofs on this branch. A call under the ceiling is slow, not stuck. A fresh JVM adds about 0.6 to 1.2 s before the verb runs. A test that starts Chrome adds several seconds before the verb itself.

| Verb | Expect | Treat as hung |
|---|---|---|
| `help`, `session-mode`, a usage error | under 1 s plus JVM start | more than 5 s |
| `snap --within` or `act --query=<short id>` on a live page | under 1 s | more than 5 s with no `--budget-ms` progress |
| `find` or `act` by text across several iframes | can be 2 to 30 s | no stderr budget line for 15 s. Pass `--budget-ms` |
| `highlight --handle` on the top document | tens of milliseconds | more than 5 s |
| `highlight --query` | about 1.5 to 2 s | more than 10 s |
| First `aria-snap` or raw CDP `DOM.getDocument` | about 6 to 7 s (attach). The next call is under 1 s | more than 20 s. These sessions are not warmed at `start` |
| `eval --await` | the promise plus the call | the verb `--timeout` |
| `input --text --at` | one round trip | more than 5 s. A per-key chain is the old path |
| `frame --list` on a live page | under 1 s | more than 5 s |
| Desktop `snap` or `act` | several seconds (about 3 to 9 s on this PC) | more than 20 s |
| `answer-wait`, `chat-turn`, `wait-any` | open until `--timeout` (default 30 s for `wait-any`, up to 120 s) | past the timeout plus about 15 s |
| `isolate` cold start | about 150 s | more than 3 minutes with no progress |

`find` and `act` print a stderr line every 2 s while a `--budget-ms` search is still running. A spent budget keeps the best hits and sets `budgetExceeded:true`.
