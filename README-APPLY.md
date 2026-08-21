# Pickleball 2.1.9 strict Workbench controller isolation

This drop-in targets branch `2.1.9` at commit `9c255431f23a4fa48a3615b387610b3367f476af`.

Copy the archive contents over the project root, preserving paths. Every included project file is a complete replacement or new file. The bundle intentionally requires no file deletion; the obsolete `gradle/pickleball-published-variant.gradle` is replaced by a migration tombstone so a drag-and-drop overlay cannot retain its old build logic.

## Result

- `pickleball-control-protocol` is a JDK-only module containing protocol versions, capabilities, transport constants, request/response envelopes, and immutable wire records.
- `pickleball-workbench` depends only on that protocol plus controller libraries. It no longer resolves, imports, shades, loads, or executes Pickleball core or the behavioral control API.
- Worker-side bridge server/coordinator/bootstrap and all execution behavior remain in Pickleball and run from the consumer project's captured test runtime in a separate JVM.
- Protocol connection checks require compatible versions/capabilities, distinct controller/worker PIDs, consumer-classpath runtime origin, synchronized Pickleball version, and no Workbench controller on the worker classpath.
- The root Pickleball JAR embeds one byte-identical controller-only Workbench JAR as opaque bytes at `META-INF/pickleball/workbench/pickleball-workbench.jar`; Workbench/MCP entries are not flattened into the outer runtime.
- `PickleballWorkbenchLauncher` extracts the embedded payload atomically by SHA-256 beneath `.pickleball/workbench/controller/` and always launches it with `java -jar` in a separate JVM.
- Artifact, dependency, POM, nested-JAR/service, controller-classpath, worker-origin, launcher, protocol-client, and focused consumer checks enforce the boundary.
- Canonical human documentation, repository agent guidance, review rules, generated indexes, packaged consumer guidance, and validation scripts describe the same architecture.

The permanent ownership rule is: **Pickleball may contain Workbench; Workbench must not contain Pickleball.**

## Validation performed for this handoff

Completed in the bundle workspace:

- `python3 scripts/verify_agent_contract.py`;
- `python3 scripts/refresh_agent_index.py --check`;
- `python3 scripts/sync_consumer_guidance.py --check`;
- `git diff --check` and `bash -n scripts/agent_validate.sh`;
- JDK compiler probes for the dependency-free protocol, launcher, controller runtime guard, protocol client, worker lifecycle/live-session seam, shared controller service, UI controller, and focused client/launcher tests (using narrow temporary type stubs where third-party libraries were unavailable);
- a launcher harness covering content-addressed extraction, corrupted-cache repair, and the separate `java -jar` command; and
- a controller-boundary harness proving the isolated classpath cannot see Pickleball core.

The workspace provided Java 17 only and could not resolve the Gradle 9.7 distribution or Maven dependencies through its restricted network. Therefore the Java 21 Gradle build, publication, executable/nested-JAR inspection tasks, Workbench unit suite, and Cucumber scenarios were **not executed here and are not claimed as passing**. Run the focused Java 21 commands below after applying the bundle.

## Focused validation

Use Java 21 and an environment that can resolve the existing Gradle/Maven dependencies:

```bash
scripts/agent_validate.sh --workbench
```

Equivalent explicit commands:

```bash
python3 scripts/verify_agent_contract.py
python3 scripts/refresh_agent_index.py --check
python3 scripts/sync_consumer_guidance.py --check
./gradlew verifyStrictControllerIsolation :pickleball-workbench:test publishToMavenLocal
./maven-consumer-project/mvnw -f maven-consumer-project/pom.xml -U test \
  -Dpkb_runvars.pkb_browser=CHROME_HEADLESS \
  -Dpkb_runvars.pkb_parallel=80 \
  -Dpkb_runvars.pkb_tags=@control-bridge
./maven-consumer-project/mvnw -f maven-consumer-project/pom.xml -U test \
  -Dpkb_runvars.pkb_browser=CHROME_HEADLESS \
  -Dpkb_runvars.pkb_parallel=80 \
  -Dpkb_runvars.pkb_tags=@step-override-bridge
```

Run the two Maven commands sequentially because both scenarios exercise the process-global bridge bootstrap.

Do not use `@all` for this migration.

## Consumer launch

A Maven consumer can launch the matching embedded controller without locating a cache entry or declaring a second version:

```bash
mvn -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.mainClass=tools.dscode.launcher.PickleballWorkbenchLauncher \
  -Dexec.classpathScope=test \
  "-Dexec.args=ui ."
```

See `docs/pickleball-workbench.md` for architecture, commands, lifecycle, MCP stdout rules, and verification details.
