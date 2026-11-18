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
