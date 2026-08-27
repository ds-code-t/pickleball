Before any Pickleball work, run `mvn -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=tools.dscode.launcher.PickleballWorkbenchLauncher" "-Dexec.classpathScope=test" "-Dexec.args=export-guidance .pickleball"` and, after a successful export, follow `.pickleball/AGENT-GUIDE.md`.

Use Workbench `hint`, `discover`, and `confirm` (narrow `--tags` / `--name`). Same launcher; only change `-Dexec.args`. Do not start the GUI. Do not register IDE MCP.

Live isolate only if `workbench_*` tools are already in this session. Do not Maven-exec `isolate` as a one-shot.
