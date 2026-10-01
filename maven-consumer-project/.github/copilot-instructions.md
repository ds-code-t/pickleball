Before any Pickleball work, run the project wrapper (`mvnw.cmd` on Windows, otherwise `mvnw`, or `java -jar` on `.mvn/wrapper/maven-wrapper.jar`) as `org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=tools.dscode.launcher.PickleballWorkbenchLauncher" "-Dexec.classpathScope=test" "-Dexec.args=export-guidance .pickleball"` and, after a successful export, follow `.pickleball/AGENT-GUIDE.md`. Do not require a machine-wide Maven or Gradle install.

Use Workbench `hint`, `discover`, and `confirm` (narrow `--tags` / `--name` / `--example`) to find failures. Same launcher; only change `-Dexec.args`. Do not start the GUI. Do not click the JavaFX or WebView UI.

When a Workbench window is already open, drive that session with `open-scenario`, `example`, `play`, `execute-step`, `stop`, and `diagnostic-run` so the person sees it. When nobody is watching, `isolate` starts a headless session, then `execute-step`, `status`, `events`, and `stop`.

Read the short log `.pickleball/agent-log` before a run, after a run, and during a long session. Use your own run id. When a Workbench window is already open, only one agent drives it; other agents stay headless on their own run ids. Write start and stop lines. Put purpose and findings on the run record. Use the inbox only for a short note to a named agent or to any, and delete an inbox note after reading it.

Gradle projects use `gradlew.bat` or `gradlew` the same way. Pick the script from the OS.
