Before any Pickleball work, run `mvn -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=tools.dscode.launcher.PickleballWorkbenchLauncher" "-Dexec.classpathScope=test" "-Dexec.args=export-guidance .pickleball"` and, after a successful export, follow `.pickleball/AGENT-GUIDE.md`.

Use Workbench `hint`, `discover`, and `confirm` (narrow `--tags` / `--name`) to find failures. Same launcher; only change `-Dexec.args`. Do not start the GUI.

For live debug, Maven-exec `isolate` (starts a headless CLI session), then `execute-step`, `status`, `events`, and `stop`. If `workbench_*` tools are already attached they are the same session, not a setup step.
