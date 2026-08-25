# Pickleball 2.1.9 Workbench Player Drop-in

Extract this ZIP at the root of the `2.1.9` branch and allow the included files to replace matching paths.

No build, publishing, dependency, Shadow JAR, launcher, Maven Central, or Workbench isolation configuration is changed.

## What changes

- Global Play always starts a fresh scenario run from the first executable step.
- Step Editor provides distinct **Step** and **From Here** play actions.
- **From Here** restarts into a fresh scenario context and runs from the selected step onward.
- Successful steps no longer retain checkmarks or gray executed styling; only the active execution line is marked.
- End-of-buffer remains `WAITING_FOR_STEP`.
- Enter inserts after the selected line and resumes a waiting player when the new step extends the active run.
- Step-only execution pauses automatic playback and uses the current live context.
- A working three-step consumer smoke scenario is preloaded.
- Full Gherkin keyword lines are parsed worker-side.
- Mapping becomes a current-ParsingMap NodeMap dropdown plus auto-saving JSON object editor.
- Focused `@control-bridge` acceptance coverage is extended.

## Suggested validation after extraction

```powershell
.\scripts\agent_validate.ps1
.\gradlew.bat verifyStrictControllerIsolation :pickleball-workbench:test
.\gradlew.bat publishToMavenLocal
.\maven-consumer-project\mvnw.cmd -f maven-consumer-project\pom.xml -U test -Dpkb_runvars.pkb_browser=CHROME_HEADLESS -Dpkb_runvars.pkb_parallel=80 -Dpkb_runvars.pkb_tags=@control-bridge
```

Run the repository index refresh after extraction if the agent contract check reports the index is stale:

```powershell
python scripts\refresh_agent_index.py
```

Then launch the UI from the consumer project. Global Play and From Here intentionally restart the interactive worker to create fresh scenario state; Step Only reuses the current paused live context.
