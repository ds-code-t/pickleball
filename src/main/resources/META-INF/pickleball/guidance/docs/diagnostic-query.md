# Diagnostic query

Agents read retained diagnostics through one service, `DiagnosticQuery`. JSON files stay the source of truth. The launcher command and the MCP tools call that service. They do not glob `reports/diagnostic-runs` and they do not guess which of the candidate `run-index.json` paths is live.

## Command

```text
query <runs|get|query|search|props|errors> [--run-id=<id>] [--scenario=<id>] [--kind=run|scenario|step|event|property|artifact|error|log] [--text=<words>] [--status=<outcome>] [--fields=<a,b>] [--limit=<n>] [--cursor=<token>] [--id=<id>]
```

Stdout is one compact JSON object. Exit 0 is a page. Exit 2 is usage (`error` and `fix`). A successful query appends one `query` line to `.pickleball/agent-log`. Dense `events.jsonl` bytes are not copied into that log.

| Verb | MCP tool | What it returns |
|---|---|---|
| `query runs` | `wb_runs` | Catalog runs: id, outcome, purpose, failed, and `where` |
| `query get` | `wb_get` | One record. `kind` defaults to `run` |
| `query query` | `wb_query` | Filtered records. `kind` defaults to `event` when `--run-id` is set, otherwise `run` |
| `query search` | `wb_search` | Substring match across run, scenario, event, error, and short-log text |
| `query props` | `wb_props` | Scalar `runProfile` entries and configuration / environment values |
| `query errors` | `wb_errors` | Failures. `stackHead` is the first stack line only |

Default `limit` is 20 (max 100). A short page sets `nextCursor` and `truncated` to `…+N more, use cursor`. Pass `--fields=runId,outcome` to drop every other item field. The default page is at most 4096 UTF-8 bytes; extra items move to the next cursor instead of blowing the context.

`where` is the project-relative file that answered the row. The order matches `AgentCoordination.findRunIndex`: `.pickleball/runs/<id>/diagnostic/<id>/run-index.json`, then `diagnostic/run-index.json`, then `reports/diagnostic-runs/<id>/run-index.json`, then a `record.json` `dataPath`. Screenshot bytes, traces, and absolute paths are not in the default payload. An artifact row carries an id (`frame-1.png`) and a relative `where`.

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

Text fields clip at 160 characters. A missing catalog is an empty `items` list, not a guessed run. Delete a future index database and the files still answer; this reader does not require a database.
