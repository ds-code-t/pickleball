Before any Pickleball work, run `mvn -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=tools.dscode.launcher.PickleballWorkbenchLauncher" "-Dexec.classpathScope=test" "-Dexec.args=export-guidance .pickleball"` and, after a successful export, follow `.pickleball/AGENT-GUIDE.md`.

Use Workbench `hint`, `discover`, `isolate`, and `confirm` for Discover / Isolate / Confirm. Do not start the GUI. Do not register IDE MCP.

If `workbench_*` tools are already in this session, they are an alias for the same Isolate loop.
