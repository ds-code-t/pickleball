# Repository Agent Context

This directory supports repository-native AI coding agents. It is not a runtime dependency of Pickleball and does not change framework behavior.

## Canonical files

- `/AGENTS.md` — project contract, workflow, compatibility rules, validation, temporary-work rules, and definition of done
- `/docs/consumer-project.md` — canonical human-readable guide for Maven consumers
- `/docs/consumer-agent-guide.md` — canonical AI-agent contract for Maven consumers; packaged as `AGENT-GUIDE.md` in the dependency guidance bundle
- `/docs/agent/feature-map.md` — living map from capabilities to implementation, tests, consumer examples, and documentation
- `/docs/agent/change-checklist.md` — explicit change-completion checklist
- `/docs/agent/repository-index.md` — generated inventory of relevant files
- `/docs/ai-run-configuration.md` — deterministic test-run configuration for agents, including full-override `pkb_run_profile` usage and protected values
- `/docs/diagnostic-lineage-metadata.md` — canonical semantics for investigation lineage, `pkb_changed_variables`, parent/baseline relationships, evidence-control RunVars, and derived diagnostic metadata
- `/REVIEW.md` — review-time checks for compatibility, tests, consumer examples, and documentation omissions
- `/.agents/skills/pickleball-functionality-change/SKILL.md` — reusable functionality-change workflow for agents supporting Agent Skills

Agent-specific files are intentionally small adapters that point back to the canonical contract. Avoid copying the full project description into every adapter.

The nested `/maven-consumer-project/AGENTS.md` is intentionally the only consumer-side AI bootstrap. It contains only the dependency command that materializes version-matched guidance and then directs the agent to `.pickleball/AGENT-GUIDE.md`. All details after that bootstrap — refresh behavior, version matching, manifest semantics, stale-guidance handling, managed-file cleanup, Git-ignore handling, Pickleball authoring guidance, diagnostics, configuration, and troubleshooting — belong to the dependency-owned exported guidance.

The nested `/maven-consumer-project/README.md` is ordinary sample-project documentation, not an AI-guidance bridge. It must not duplicate `export-guidance`, `.pickleball` lifecycle, manifest, staleness, Git-ignore, or other Pickleball guidance instructions.

`export-guidance .pickleball` is deliberately unconditional: agents should rerun it before Pickleball work rather than trying to infer whether the dependency changed. A successful export writes `.pickleball/GUIDANCE-MANIFEST.json` last with the exporting Pickleball version and managed-file list, removes obsolete previously managed files, and refreshes the current dependency guidance. Git-ignore handling is best effort only: prefer an existing synchronized `.gitignore`, fall back to `.git/info/exclude`, and never let ignore-file problems block the guidance refresh. If export fails, existing `.pickleball` content is potentially stale.

## Temporary agent work

Disposable scripts, patches, generated bundles, migration utilities, investigation output, and intermediate files belong under:

```text
.agent-work/
```

The directory is ignored by Git and excluded from the generated repository index. Delete temporary files after use. Do not force-add files from `.agent-work/`.

The committed `scripts/` directory is reserved for reusable project-maintenance tooling that is reviewed and intended to remain in the repository.

## Expected behavior

With a compatible write-capable coding agent, a prompt such as:

> Add support for template references in XML attributes.

should cause the agent to:

1. Load the repository contract.
2. Locate the capability in the feature map.
3. Inspect related source, tests, Maven consumer scenarios, and guides.
4. Implement the change.
5. Update executable examples and documentation when applicable.
6. Run validation.
7. Remove disposable working files.
8. Report results.

For controlled test reruns, agents should follow `/docs/ai-run-configuration.md` and `/docs/diagnostic-lineage-metadata.md`. When the intended RunVars are already known, prefer an explicit `pkb_run_profile` so local/default/profile RunVar sources cannot silently alter the rerun. `pkb_changed_variables` names intentionally changed RunVars only; source-only fixes should omit it and use `pkb_run_purpose` plus source provenance instead.

This is task-time automation, not a passive background documentation watcher. Manual code edits do not automatically update documentation.

## Maintenance

After adding, moving, or deleting relevant files:

```shell
python scripts/refresh_agent_index.py
python scripts/sync_consumer_guidance.py
```

`docs` is the canonical guidance source. `sync_consumer_guidance.py` refreshes the generated Markdown mirror under `src/main/resources/META-INF/pickleball/guidance` that is packaged into the Maven artifact.

Check the contract and index:

```shell
python scripts/verify_agent_contract.py
python scripts/refresh_agent_index.py --check
python scripts/sync_consumer_guidance.py --check
```

Run the full project validation:

```shell
scripts/agent_validate.sh
```

Windows:

```powershell
.\scripts\agent_validate.ps1
```

## Enforcement levels

By default, `verify_agent_contract.py` treats missing agent files and an invalid temporary-workspace configuration as errors. Change-coverage findings remain warnings.

Use strict mode in CI when the team is comfortable with the heuristics:

```shell
python scripts/verify_agent_contract.py --base-ref origin/master --strict
```

Temporary narrowly scoped overrides are available for false positives:

- `AGENT_CONTRACT_ALLOW_NO_DOCS=true`
- `AGENT_CONTRACT_ALLOW_NO_TESTS=true`

Do not use overrides to bypass genuinely missing documentation or tests.

## Supported adapters included

The repository includes adapters for:

- JetBrains AI Assistant project rules
- JetBrains Junie (`AGENTS.md` plus `.junie/guidelines.md` compatibility adapter)
- GitHub Copilot repository instructions, path rules, Agent Skill, and selectable `pickleball-maintainer` custom agent
- OpenAI Codex
- Claude Code
- Gemini CLI
- Amazon Q Developer
- Cursor
- Continue
- Cline
- Windsurf

Actual support depends on the installed product/version and whether the selected mode can edit files and execute commands.
