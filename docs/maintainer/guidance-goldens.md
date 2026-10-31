# Guidance command goldens

`GuidanceCommandGoldenTest` reads the first-read guide, `docs/workbench-agent-detail.md`, the 2.1.18 section of `docs/release-notes.md`, and the packaged copies of those files. Every documented launcher verb (`discover`, `isolate`, `execute-step`, `query`, and the rest) must be a real command. `help` and `help <verb>` are compared to `src/test/resources/guidance-golden/documented-help.txt`.

The golden is ASCII. A Unicode ellipsis in help text is stored as `...`. Rebuild it with `./gradlew test --tests tools.dscode.launcher.GuidanceCommandGoldenTest -Dpkb.update.golden=true` only when the help text is supposed to change, then commit the golden with the change.

A normal `mvn test` does not read this file.
