# Pickleball Studio Agent Contract

This directory contains the standalone Pickleball Studio application. Root `AGENTS.md` still applies.

## Architectural boundary

Studio is a generic development application distributed inside the normal Pickleball artifact but executed in a separate JVM.

Keep these rules:

- Do not make Studio part of the consumer test JVM classpath or runtime lifecycle.
- Do not add Studio dependencies to the public Pickleball Maven dependency graph.
- Do not depend on Pickleball Core from generic Studio infrastructure unless a later Pickleball-specific adapter explicitly requires it.
- Put project files, search, builds, processes, output, activity, MCP hosting, and GUI infrastructure in Studio.
- Keep live scenario/browser/mapping execution inside Pickleball Core and connect it later through an explicit bridge.
- GUI, CLI, MCP, and future Java integrations should call the same Studio services rather than reimplementing behavior.
- AI policy belongs in the AI client. Studio exposes deterministic capabilities and evidence.

## Build and packaging

- Use Java 21.
- The repository wrapper currently targets Gradle 9.7.0.
- The root artifact uses `com.gradleup.shadow` 9.6.1.
- Keep `pickleball-studio.jar` opaque at `META-INF/pickleball/studio/pickleball-studio.jar`; never unpack Studio implementation classes into the outer consumer runtime.
- Keep ordinary Shadow duplicate handling first-entry-wins, while allowing `META-INF/services/**` duplicates through to `mergeServiceFiles()`.
- Do not mix AspectJ version changes into Studio packaging changes unless the task explicitly requires both.

## Current phase

Phase 2A establishes only the isolated application/package boundary, launcher, generic workspace service, and modernized Gradle/Shadow packaging.

Do not claim that MCP, GUI, Maven/Gradle execution, terminal support, or live Pickleball control is implemented until those later slices are added.

See `docs/pickleball-studio.md`.
