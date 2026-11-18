# Dashboard

`dashboard` writes one static HTML file from the embedded store. It does not start a server and it does not create a second database.

Each run row can carry `git_commit` and `feature_path`. A blank commit is filled from the project git commit and then kept. Feature paths come from the scenario key. Later scenarios in the same run are appended.

The page is a rewindable timeline with markers for runs, scenarios, and case ids. Filters cover status, feature, entity, text, and a time range. Panels list business entities, case ids, common errors, and case types. A run detail links the feature file, data files, the commit, and artifacts. Long lists are drawn in a window as you scroll. The script is in the page. Nothing is downloaded at runtime.

Case ids are entities whose type is `Case`. The case type is the `type` or `caseType` field on that entity. Otherwise it is `unspecified`.

Open it from the Workbench Tracking tab (HTML dashboard) or from the CLI. The Tracking timeline, query, and Explorer actions stay.

```text
dashboard --project=<dir> [--out=<file>] [--from=<iso>] [--to=<iso>] [--status=<status>] [--feature=<path>] [--run=<id>] [--entity=<Type#id>] [--text=<words>]
```

The default file is `.pickleball/dashboard/index.html`. A commit link is present only when the store already recorded a GitHub repository. Secret values are not on the page.

Deferred: central hosting, Java source views, and case opened or closed events.
