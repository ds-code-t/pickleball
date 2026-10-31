# Diagnostic query

Agents read retained diagnostics through one service, `DiagnosticQuery`. JSON files stay the source of truth. The launcher command and the MCP tools call that service. They do not glob `reports/diagnostic-runs` and they do not guess which of the candidate `run-index.json` paths is live.

## Command

```text
query <runs|get|query|search|props|errors|tail> [--run-id=<id>] [--scenario=<id>] [--kind=run|scenario|step|event|property|artifact|error|log] [--text=<words>] [--status=<outcome>] [--fields=<a,b>] [--limit=<n>] [--cursor=<token>] [--id=<id>]
```

Stdout is one compact JSON object. Exit 0 is a page. Exit 2 is usage (`error` and `fix`). A successful query appends one `query` line to `.pickleball/agent-log` through `AgentLog`. The MCP tools, including the deprecated `workbench_diagnostic_*` aliases, append that same line. Dense `events.jsonl` bytes are not copied into that log.

| Verb | MCP tool | What it returns |
|---|---|---|
| `query runs` | `wb_runs` | Catalog runs: id, outcome, purpose, failed, and `where` |
| `query get` | `wb_get` | One record. `kind` defaults to `run` |
| `query query` | `wb_query` | Filtered records. `kind` defaults to `event` when `--run-id` is set, otherwise `run` |
| `query search` | `wb_search` | Substring match across run, scenario, event, error, and short-log text |
| `query props` | `wb_props` | Scalar `runProfile` entries and configuration / environment values |
| `query errors` | `wb_errors` | Failures. `stackHead` is the first stack line, copied from the failure event onto a failed step that has none. An empty `scenario_end` is not an error |
| `query tail` | (launcher only) | Newest lines of `.pickleball/agent-log` for that run, then `*.log` under `.pickleball/runs/<id>`. Requires `--run-id` |

Default `limit` is 20 (max 100). A short page sets `nextCursor` and `truncated` to `...+N more, use cursor`. Pass `--fields=runId,outcome` to drop every other item field. The default page is at most 4096 UTF-8 bytes; extra items move to the next cursor instead of blowing the context.

`where` is the project-relative file that answered the row. `DiagnosticQuery` and `AgentCoordination.findRunIndex` both call `RunIndexFinder`: `.pickleball/runs/<id>/diagnostic/<id>/run-index.json`, then `diagnostic/run-index.json`, then `reports/diagnostic-runs/<id>/run-index.json`, then a `record.json` `dataPath`, then a depth-6 walk. The parent folder name matches before the JSON `runId`. `browser-profile`, `config`, and `scratch` are not walked. A JSON body over 2 MB is not read. When no index exists and `reports/diagnostic-runs/<id>` is a directory, the query still reads scenario files there. Screenshot bytes, traces, and absolute paths are not in the default payload. An artifact row carries an id (`frame-1.png`) and a relative `where`.

## Record fields

| kind | Compact fields |
|---|---|
| run | `runId`, `outcome`, `completion`, `purpose`, `scenarioCount`, `failed`, `where` |
| scenario | `scenarioId`, `name`, `outcome`, `where` |
| step / event | `seq`, `type`, `status`, `text` (clipped), `where` |
| error | `message`, `stackHead` (one line), `where` |
| property | `key`, `value` (clipped), `where` |
| artifact | `id`, `artifact` (`screenshot` or `trace`), `where` |
| log | `source` (`agent-log`), `text`, `where` |

Text fields clip at 160 characters and drop a BOM and zero-width characters. A missing catalog is an empty `items` list, not a guessed run. Delete a future index database and the files still answer; this reader does not require a database. One scenario is one row: an index `scenarioExecutionId` and the summary directory UUID are merged, and `name` comes from the index identity or the summary. `query search` decodes each file as UTF-8. A bad file is replaced, named in `decodeWarnings`, and does not fail the search. Worker logs are UTF-8 because the worker JVM is started with `-Dfile.encoding=UTF-8` and matching stdout and stderr encodings.

## Derived SQLite index

`reports/diagnostic-runs/diagnostics.db` is a disposable FTS5 copy of the same rows `DiagnosticQuery` reads from the JSON files. `DiagnosticIndexRebuilder.rebuildRunIndex` and `rebuildRunCatalog` refresh it when `org.sqlite.JDBC` is visible. The driver is not a normal consumer dependency and it is not in the published Pickleball POM or the shadow jar. The Workbench controller resolves `org.xerial:sqlite-jdbc:3.47.2.0` as a runtime library (not shaded). A normal `mvn test` does not get that jar. `query search` uses the index when the driver and the file are both present, and reads the JSON files otherwise. sqlite-jdbc carries the Windows, macOS, and Linux natives. The parity tests run on those three hosts (`.github/workflows/sqlite-index.yml`).

The database is not a second source of truth. Delete it and the next catalog rebuild recreates it. `DiagnosticSqliteIndex.rebuild(project)` does the same from the files. `query search` uses the index when the file is present and falls back to the JSON files when it is missing or the driver is absent. Before that search, a present index is rebuilt when a source is strictly newer than the database: JSON or JSONL under `reports/diagnostic-runs` (other than the database itself), `.pickleball/agent-log`, diagnostic JSON/JSONL under `.pickleball/runs`, or a `worker-*.log`. A run directory with a `run-index.json` that the catalog has not listed yet is included in that rebuild, so a search sees the run without a separate rebuild command. Other query verbs keep reading the files. MCP tools call the same `DiagnosticQuery`, so they follow the same refresh and the same fallback. A hit still returns ids, a relative `where`, and a one-line stack head. `where` names `reports/diagnostic-runs/diagnostics.db` when the index answered.

