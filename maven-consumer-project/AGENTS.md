Before any Pickleball work, run `mvn -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=tools.dscode.common.reporting.diagnostic.DiagnosticCli" "-Dexec.classpathScope=test" "-Dexec.args=export-guidance .pickleball"` and, after a successful export, follow `.pickleball/AGENT-GUIDE.md`.

To find which scenarios fail, run one diagnostic `mvn test` with `pkb_runvars` (parallel is OK when the project supports it). That discovery run is not a skip of Workbench. Do not skip Workbench.

To isolate a known failure, use live Workbench only if `workbench_*` tools are already available, or follow AGENT-GUIDE's CLI/Workbench steps. Do not register MCP with the IDE or start an IDE-owned stdio server. If `workbench_*` tools are already in this session, use them for isolate.

Do not start the GUI. Do not start a worker to run the whole suite.
