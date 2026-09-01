# Repository Agent Context

This directory supports repository-native AI coding agents. It is not a runtime dependency of Pickleball and does not itself change framework behavior.

## Canonical files

- `/AGENTS.md` — project contract, workflow, compatibility rules, validation, core technical-debt notes, temporary-work rules, and definition of done
- `/docs/consumer-project.md` — canonical human-readable guide for Maven consumers
- `/docs/consumer-agent-guide.md` — canonical AI-agent contract for Maven consumers; packaged as `AGENT-GUIDE.md`
- `/docs/agent/feature-map.md` — living map from capabilities to implementation, tests, consumer examples, and documentation
- `/docs/agent/change-checklist.md` — explicit change-completion checklist
- `/docs/agent/repository-index.md` — generated inventory of relevant files
- `/pickleball-workbench/AGENTS.md` — strict controller/core dependency, artifact, process, worker-classpath, protocol, and focused-test invariants
- `/docs/ai-run-configuration.md` — controlled execution through `pkb_runvars`, canonical `pkb_run_profile`, inherited execution context, `pkb_configpath`, replay, and protected values
- `/docs/diagnostic-lineage-metadata.md` — investigation lineage and derived diagnostic metadata
- `/REVIEW.md` — review-time checks for compatibility, tests, consumer examples, and documentation omissions
- `/.agents/skills/pickleball-functionality-change/SKILL.md` — reusable functionality-change workflow for Agent Skills

Agent adapters should remain small and point back to the canonical contract rather than copying the full project description.

The nested `/maven-consumer-project/AGENTS.md` is a dependency-owned Workbench bootstrap plus a short Discover/Confirm/live-debug pointer. It materializes version-matched guidance through `PickleballWorkbenchLauncher export-guidance`, directs the consumer agent to `.pickleball/AGENT-GUIDE.md`, and tells agents to use Workbench `hint` / `discover` / `confirm`, then `isolate` / `execute-step` / `status` / `events` / `stop` for live debug. Do not start the GUI. Refresh/version/manifest semantics, authoring rules, configuration, diagnostics, and troubleshooting belong in the exported dependency guidance.

The nested `/maven-consumer-project/.github/copilot-instructions.md` is identical to `AGENTS.md` so Copilot Chat sees the same Workbench pointer.

The nested `/maven-consumer-project/README.md` is ordinary sample-project documentation. It may point humans and agents at `AGENTS.md` for guidance export, but should not duplicate the AI guidance lifecycle.

`export-guidance .pickleball` is deliberately unconditional before Pickleball work. A successful export writes `.pickleball/GUIDANCE-MANIFEST.json` last, removes obsolete previously managed files, and refreshes current dependency guidance. Git-ignore handling is best effort. If export fails, existing `.pickleball` content is potentially stale.

## Configuration-development context

Core agents working on execution configuration must distinguish:

```text
default_profile  = normal resolved project RunVar reference snapshot
pkb_runvars      = authoritative AI controlled-run input
pkb_run_profile  = canonical resolved RunVar output; never external input
```

Controlled/named-profile execution inherits only missing `pkb_glue`, `pkb_features`, `pkb_datapath`, `pkb_callpath`, `pkb_componentpath`, and `pkb_configpath`. Explicit blanks suppress inheritance and remain replayable blank tombstones in the final canonical run profile so established subsystem fallback behavior is preserved.

Runtime configuration mappings are loaded only after final run configuration; they must not participate in resolving profiles/RunVars or `pkb_configpath`. New syntax should prefer `<config:...>` while legacy `<configs...>` remains compatible.

Resource-path grammar normalization across feature/data/call/component/config paths is intentionally deferred technical debt documented in root `AGENTS.md`. Do not normalize those paths opportunistically during unrelated configuration work.

## Temporary agent work

Disposable scripts, patches, generated bundles, migration utilities, investigation output, and intermediate files belong under `.agent-work/`. The directory is ignored by Git and excluded from the generated repository index. Delete temporary files after use and never force-add them.

The committed `scripts/` directory is reserved for reusable maintained project tooling.

## Expected behavior

A functionality-change agent should:

1. Load the repository contract.
2. Locate the capability in the feature map.
3. Inspect source, tests, Maven consumer scenarios, and guides.
4. Implement the change.
5. Update executable examples/documentation when applicable.
6. Run validation.
7. Remove disposable working files.
8. Report results.

Workbench work has an additional hard boundary: `pickleball-workbench` may share only the JDK-only `pickleball-control-protocol`; all execution remains in the consumer worker. The outer Pickleball JAR may carry the completed Workbench as opaque bytes, but Workbench must never contain or load Pickleball. Future agents must not restore the removed root/published-equivalent dependency. Use `verifyStrictControllerIsolation` and focused `@control-bridge` / `@step-override-bridge` scenarios with `pkb_parallel=80`, never `@all`, for this boundary.

For AI-launched tests with known settings, default to `pkb_runvars`. Use ordinary JVM RunVars or named profiles instead only when intentionally exercising those resolution paths. For controlled reruns, follow `/docs/ai-run-configuration.md` and `/docs/diagnostic-lineage-metadata.md`: replay retained `runProfile` through `pkb_runvars`, change only intentional RunVars, keep lineage separate, and verify `runProfileFingerprint`. `pkb_changed_variables` names RunVars only, not source changes or profile controls.

This is task-time automation, not a passive background documentation watcher.

## Maintenance

After adding, moving, or deleting relevant files:

```shell
python scripts/refresh_agent_index.py
python scripts/sync_consumer_guidance.py
```

`docs` is the canonical guidance source. `sync_consumer_guidance.py` refreshes the Markdown mirror under `src/main/resources/META-INF/pickleball/guidance` packaged in the Maven artifact.

Checks:

```shell
python scripts/verify_agent_contract.py
python scripts/refresh_agent_index.py --check
python scripts/sync_consumer_guidance.py --check
```

Full validation:

```shell
scripts/agent_validate.sh
```

Windows:

```powershell
.\scripts\agent_validate.ps1
```

Workbench/controller isolation uses the dedicated focused mode, which runs strict artifact/dependency checks and then runs `@control-bridge` and `@step-override-bridge` sequentially, each with `pkb_parallel=80`:

```shell
scripts/agent_validate.sh --workbench
```

```powershell
.\scripts\agent_validate.ps1 -Workbench
```

## Enforcement levels

By default, `verify_agent_contract.py` treats missing agent files and invalid temporary-workspace configuration as errors; change-coverage findings remain warnings. Strict mode may be used in CI when appropriate.

Temporary narrowly scoped overrides exist for documented false positives; never use them to bypass genuinely missing tests or documentation.

## Supported adapters included

The repository includes adapters for JetBrains AI Assistant/Junie, GitHub Copilot, OpenAI Codex, Claude Code, Gemini CLI, Amazon Q Developer, Cursor, Continue, Cline, and Windsurf. Actual support depends on installed product/version and permissions.
