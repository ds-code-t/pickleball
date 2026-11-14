# Dalton entities

Entities are test data that scenarios can read without putting the values in the Gherkin. Case ids, user ids, and account numbers belong here. Passwords, tokens, and other secrets do not. A secret field holds a reference such as `<password:qaDb>` or a `~MASK;` value, using the same secrets and config approach as the rest of Pickleball.

Dalton is on unless `pkb_dalton=false` (or `PKB_DALTON`, or `dalton.properties` `enabled=false`). An explicit false beats a file that turns it on. With Dalton off, `<entity:...>` is not a special reference and no database is opened.

## Files

One entity is one UTF-8 file:

```
dalton-entities/<Type>/<id>.entity
```

```
# comments are kept until the database copy is edited
type: Account
id: ada
version: 1
payload: {"name":"Ada","password":"<password:qaDb>"}
```

`type` matches `[A-Za-z][A-Za-z0-9_-]*`. `id` matches `[A-Za-z0-9][A-Za-z0-9_-]*` and does not contain `.` or `#`. `version` is an integer starting at 1. `payload` is one JSON object. Keys are not Gherkin.

Commit the files you want Git to remember. Ignore a type directory in `.gitignore` when that type must stay local. Dalton does not choose for you. The database `.pickleball/dalton/dalton.db` is always gitignored.

`dalton hydrate` reads these files. Unchanged bytes are skipped. A secret field (`password`, `passwd`, `secret`, `token`, `api_key`, `api-key`, `apikey`, `credential`, `private_key`, `private-key`, plus any name in `secret-fields=`) whose value is not a `<...>` reference or a `~MASK;` value is rejected and is not copied into the database. `pkb_dalton_secret_fields` wins over the file. The database is not encrypted. A value of 32 or more characters with no spaces on a field that is not in that list prints `warn: high-entropy value on <field>` and is stored. The warning does not print the value.

## Reference

The only new reference is:

```
<entity:Type#id.field>
```

`field` is optional. Dots walk nested JSON. `<entity:Account#ada.name>` is the string `Ada`. `<entity:Account#ada>` is the payload JSON. This does not change `<config:...>`, `<file:...>`, `<data:...>`, or any other existing reference.

A scenario step receives that resolved field only when Dalton is on. With Dalton off, the step receives the reference text unchanged. A secret field stays the reference. `<entity:Account#ada.password>` reaches the step as `<password:qaDb>`, not as a password.

With Dalton on, a missing database row is read from `dalton-entities/<Type>/<id>.entity` when that file is present. Resolution does not create a database.

## CLI

Workbench, MCP, and Gradle forward these to the same CLI. There is no separate entity implementation.

```
dalton entity list
dalton entity get Account ada
dalton entity put Account ada --version=2 --payload={"name":"Ada","password":"<password:qaDb>"}
dalton entity put Account ada --payload-file=account.json
dalton entity remove Account ada
dalton entity export
dalton entity export --force
```

`dalton entity export` is `dalton extract` limited to `dalton-entities/`. It writes the stored file bytes. Those bytes stay the original file, comments included, until `dalton entity put`. A put stores the canonical form (`type`, `id`, `version`, and a compact payload with sorted keys) and export writes that. If the file was not edited in the database, the original bytes stay. If both the file and the database changed since the last sync, export prints `conflict: <path>` and does not overwrite. `--force` overwrites the file from the stored bytes. A deleted unedited file is not recreated.

`dalton entity put` updates the database only. It uses the same write as `dalton feature put`: `db_edited` is set, `last_synced_hash` stays, and the entity row is replaced in that transaction. Export is what changes the file.
