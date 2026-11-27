# Store entities

Entities are test data that scenarios can read without putting the values in the Gherkin. Case ids, user ids, and account numbers belong here. Passwords, tokens, and other secrets do not. A secret field holds a reference such as `<password:qaDb>` or a `~MASK;` value, using the same secrets and config approach as the rest of Pickleball.

Store is on unless `pkb_store=false` (or `PKB_STORE`, or `store.properties` `enabled=false`). An explicit false beats a file that turns it on. With Store off, `<entity:...>` is not a special reference and no database is opened.

## Files

One entity is one UTF-8 file:

```
store-entities/<Type>/<id>.entity
```

```
# comments are kept until the database copy is edited
type: Account
id: ada
version: 1
payload: {"name":"Ada","password":"<password:qaDb>"}
```

`type` matches `[A-Za-z][A-Za-z0-9_-]*`. `id` matches `[A-Za-z0-9][A-Za-z0-9_-]*` with optional extra `.segment` pieces (`ae-mgr-all.app`). It does not contain `#`, a slash, or `..`. `version` is an integer starting at 1. `payload` is one JSON object. Keys are not Gherkin.

Commit the files you want Git to remember. Ignore a type directory in `.gitignore` when that type must stay local. Store does not choose for you. The database `.pickleball/store/data` is always gitignored. An entity put with `--sandbox=<name>` stays on that sandbox branch. `main` does not see it.

`store hydrate` reads these files. Unchanged bytes are skipped. A secret field (`password`, `passwd`, `secret`, `token`, `api_key`, `api-key`, `apikey`, `credential`, `private_key`, `private-key`, plus any name in `secret-fields=`) whose value is not a `<...>` reference or a `~MASK;` value is rejected and is not copied into the database. `pkb_store_secret_fields` wins over the file. The database is not encrypted. A value of 32 or more characters with no spaces on a field that is not in that list prints `warn: high-entropy value on <field>` and is stored. The warning does not print the value.

## Reference

The only new reference is:

```
<entity:Type#id.field>
```

`field` is optional. Dots walk nested JSON after the longest stored id. `<entity:Account#ada.name>` is the string `Ada` when the file is `Account/ada.entity`. `<entity:Operator#ae-mgr-all.app>` is the payload of `Operator/ae-mgr-all.app.entity`, and `<entity:Operator#ae-mgr-all.app.name>` is that payload's `name`. `<entity:Account#ada>` is the payload JSON. This does not change `<config:...>`, `<file:...>`, `<data:...>`, or any other existing reference.

A scenario step receives that resolved field only when Store is on. With Store off, the step receives the reference text unchanged. A secret field stays the reference. `<entity:Account#ada.password>` reaches the step as `<password:qaDb>`, not as a password.

With Store on, a missing database row is read from `store-entities/<Type>/<id>.entity` when that file is present. Resolution does not create a database when no tracked step is active. During a tracked step, that read inserts `entity_usages` with operation `read`. `value_ref` is the secret reference when the field holds one (`<password:qaDb>` or `~MASK;`). Otherwise it is `<entity:Type#id.field>`. The resolved cleartext is not stored. `store entity use <type> <id>` records `write` or `mention` (or `read`) on `--step` or the active step. A `--value-ref` that is not a reference is stored as `~MASK;`.

`RECORD ENTITY: "<value>" as Type#id` writes the entity, a mention usage, and a `run_snapshot_entities` row for the tracked step. It does not run object construction, post-actions, or cleanup. A value that fails the secret check is stored as `~MASK;`. The step saves `recordedAccount` as `Type#id` when a scenario map is open. The consumer scenario is `@area-b-record-entity`.

## CLI

Workbench, MCP, and Gradle forward these to the same CLI. There is no separate entity implementation.

```
store entity list
store entity get Account ada
store entity put Account ada --version=2 --payload={"name":"Ada","password":"<password:qaDb>"}
store entity put Account ada --payload-file=account.json
store entity remove Account ada
store entity export
store entity export --force
store entity use Account ada --field=name --operation=mention --step=<step-exec-id>
store entity use Account ada --operation=write --value-ref=<password:qaDb>
```

`store entity export` is `store extract` limited to `store-entities/`. It writes the stored file bytes. Those bytes stay the original file, comments included, until `store entity put`. A put stores the canonical form (`type`, `id`, `version`, and a compact payload with sorted keys) and export writes that. If the file was not edited in the database, the original bytes stay. If both the file and the database changed since the last sync, export prints `conflict: <path>` and does not overwrite. `--force` overwrites the file from the stored bytes. A deleted unedited file is not recreated.

`store entity put` updates the database only. It uses the same write as `store feature put`: `db_edited` is set, `last_synced_hash` stays, and the entity row is replaced in that transaction. Export is what changes the file.

## Pin, deltas, and head

The same source database keeps one commit pin, an append-only delta log, and a flattened head. `store_schema` stays 1. Opening the store writes `schema_meta` keys `universal_entity=1` and `delta_window=100`.

```
store pin show
store delta list [--limit=N]
store delta undo
store delta redo
store head show
store head rebuild
```

`store pin show` prints `commit`, `committedAt`, and `dirty`. The commit sha comes from the same git read as hydrate. An empty git directory stores an empty sha and dirty 0. A dirty tree stores dirty 1. `store status` still warns once when git provenance is empty.

`store delta list` prints `deltas N` and then `seq <seq> <kind> <table> <pk>`. It does not print the payload. Kinds in this phase are `put`, `delete`, `undo`, and `redo`. `store delta undo` appends an inverse of the delta at `delta_cursor` and leaves that row in the log. `store delta redo` appends a copy of the delta that undo inverted. Undo or redo with nothing to apply exits 1.

`schema_meta` key `delta_cursor` is the seq the head currently reflects. A cheap append writes the delta and flattens it into `head_rows` in the same transaction. `expensive=1` sets `heads.stale=1` and waits. `store status` and the end of `store hydrate` call `HeadMaterializer.quiet`, which flattens a stale head. `store head show` rebuilds when the head is stale. `store head rebuild` replays the remaining deltas. An unchanged source row stays in its own table until a delta names it. A tombstone hides the parent row. After a flatten, more than 100 stored deltas are copied to `delta_archive` in `runstate.db` and removed from the source `deltas` table. `store head rebuild --from-archive=<seq>` replays one archived delta and refuses a seq that is still in the live window. A tracked run freezes the head id and the enabled entity hashes in `runstate.db` and does not copy `source.db`. `store history show --json` prints that snapshot under `items`.

`pkb_store_log=pin,delta` logs pin, head, append, undo, redo, and fold lines. The lines are date-stamped, name the sandbox, and do not include the payload. Workbench, Gradle, and MCP only forward these commands.

## Hydration, flags, tags, and run variables

`schema_meta` key `hydration_spine=1` marks the additive columns and tables. `store_schema` stays 1. `files.enabled`, `scenarios.enabled`, `steps.enabled`, and `step_definitions.enabled` default to 1. A file outside the feature and glue roots is stored with `enabled=0`. Call and component files stay enabled 0 so a feature run does not select them. An explicit calls or component path still loads them. `@pkb-ignore` still removes that file or scenario. `files.norm_path` uses backslashes and a lowercase drive letter on Windows. `folder_key` is the parent path. `folders` stores that directory. `scenario_tags` stores one row per scenario tag and per Examples tag, on that scenario's ord. `scenarios.tags` stays. `run_variables` stores the `pkb_*` keys copied from the property and profile files.

A full hydrate compares `schema_meta` `jar_hash` and `heads.jar_hash` with `StoreHashes.jarHash`. A different hash deletes the source rows and hydrates them from files. `runstate.db` is not opened for that wipe. A matching hash skips a file whose `content_hash` is unchanged and counts it as reused. An insert or update counts as rehydrated. `store status` prints `reused=<n> rehydrated=<n> jar=<12 hex chars>`.

```
store enable <feature|scenario|step|definition|folder> <id> --on|--off
store tag add|remove --feature=<path> --scenario=<ord-or-name> --tag=<tag>
store var list
store var set <name> <value>
store var enable <name> --on|--off
```

`store enable` off on a feature, scenario, or folder writes `@pkb-ignore` on that scenario or on the Feature line. On removes only that tag. The write goes through the feature edit. A both-sides edit is refused. Gherkin that does not parse rolls back. A step id is `<feature>#<scenarioOrd>#<stepOrd>` and flips `steps.enabled`. A definition id flips `step_definitions.enabled`. `store query --tag` still matches `scenarios.tags` and also matches `scenario_tags`.

`store var set` on main writes the properties file and the `run_variables` row. A sandbox set updates that database only. A cleartext secret is rejected with `error:` and `fix:`. `pkb_store_log=enable` logs the entity id and the toggle. It does not log variable values. Java source the editor can change stays a stored row. Workbench, Gradle, and MCP only forward these commands.

## Conflict choice

`store conflicts` lists an untracked database edit and a row that changed on both sides. Extract still refuses that row unless `--force` is set.

```
store conflicts show <relative-path>
store conflicts choose <relative-path> --keep=db|file
```

`store conflicts show` prints the file text and the database text. A secret field is shown as the stored reference (`<password:name>`, `<entity:Type#id.field>`, or `~MASK;`). `store conflicts choose --keep=db` writes the database bytes to the file and sets `last_synced_hash` to that hash. `--keep=file` replaces the database row from the file and clears `db_edited`. The side that was not kept is written to `.pickleball/store/discarded/<utc-timestamp>-<path>`, with `/` and `\` turned into `_`. The start id for this JVM is `.pickleball/store/discarded/session`, and each discarded file has a sibling marker of the same name plus `.start`. The next store command deletes a discarded file older than 24 hours and any discarded file whose marker names a different JVM. There is no merge. `store sandbox merge` is unchanged. `pkb_store_log=conflict` logs the path, the choice, and the discarded file name. It does not log the body. Workbench, Gradle, and MCP only forward these commands.

## Copy-on-write sandboxes

`store sandbox create <name>` defaults to `--mode=cow`. The file is still `.pickleball/store/sandboxes/<name>/source.db`. `schema_meta` key `sandbox_mode` is `cow`. `_pkb_branch` key `parent` is the parent sandbox name, or `main` when the sandbox is created from main. The child does not receive a copy of the parent file bytes, and create does not extract. An edit writes a `files` delta in the child. A delete writes a tombstone delta and leaves the working-tree file in place. The child head is the parent head, then those deltas. The parent database is opened on its own connection. It is not attached.

`--mode=copy` is the independent database. It records no `parent` key. `SandboxIndependenceTest` covers that mode.

```
store sandbox promote <name> --to=parent|top --logs=clear|archive
```

`--logs` is required. `--to=parent` writes one expensive delta per changed head row onto the parent, then sets `schema_meta` `head_promoted=1` on the child. `--to=top` walks parents until `main`. Promote onto `main` syncs the changed file rows through `store extract`. A both-sides conflict is reported and that file is left as it is. Promote onto another sandbox does not touch the working tree. `--logs=clear` deletes `run_logs` rows for that sandbox name and leaves the `runs` row. `--logs=archive` leaves the log rows and prefixes `runs.note` with `archived`. `pkb_store_log=sandbox` logs create and promote. The line names the sandbox and does not include payloads. Workbench, Gradle, and MCP only forward these commands.
