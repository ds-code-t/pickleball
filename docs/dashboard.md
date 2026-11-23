# Dashboard

`dashboard` writes one static HTML file from the embedded store. It does not start a server and it does not create a second database.

Each run row can carry `git_commit` and `feature_path`. The commit is recorded when that run is written. It is not copied onto older runs. An older row with a blank commit stays blank. A feature path is taken from the scenario key when the run is written. Later scenarios in the same run are appended.

`dashboard` only reads the store. It does not add columns, fill commits, or change rows. A database from before those columns still renders. The columns are added when the store itself opens or writes a run.

The page is a rewindable timeline. Markers are colored: run, scenario, case, entity, and error. Filters cover status, feature, entity or case, text, and a rewind range. Clear resets them. The filter bar stays at the top while the lists scroll. A count says how many runs are showing. The text box matches the note, the scenario name, scenario keys, feature paths, and marker text, including a failed step. Clicking a business entity or a case id fills the entity filter. Clicking a common error fills the text filter. Panels list business entities, case ids, common errors, and case types. A run detail shows the status, the passed and failed counts, and the commit when one was stored. Links are grouped under Features, Data, Artifacts, and Commit, and an empty group is omitted. Long lists are drawn in a scrolling window. The page is one file, readable at a typical desktop width, and the columns stack on a narrow window. The script is in the page. Nothing is downloaded at runtime.

Case ids are entities whose type is `Case`. The case type is the `type` or `caseType` field on that entity. Otherwise it is `unspecified`.

Open it from the Workbench Tracking tab (HTML dashboard) or from the CLI. The Workbench button writes the page off the UI thread and opens it in the desktop browser when one is available. The Tracking timeline, query, and Explorer actions stay.

```text
dashboard --project=<dir> [--out=<file>] [--from=<iso>] [--to=<iso>] [--status=<status>] [--feature=<path>] [--run=<id>] [--entity=<Type#id>] [--text=<words>]
```

The default file is `.pickleball/dashboard/index.html`. Feature, data, and artifact links are written relative to the page's folder, so they open from the file. A commit link is present only when the store already recorded a GitHub repository. Secret values are not on the page.

Deferred: central hosting, Java source views, and case opened or closed events.
