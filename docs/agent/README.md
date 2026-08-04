# Repository Agent Context

This directory supports repository-native AI coding agents. It is not a runtime dependency of Pickleball and does not change framework behavior.

## Canonical files

- `/AGENTS.md` — project contract, workflow, compatibility rules, validation, temporary-work rules, and definition of done
- `/docs/agent/feature-map.md` — living map from capabilities to implementation, tests, consumer examples, and documentation
- `/docs/agent/change-checklist.md` — explicit change-completion checklist
- `/docs/agent/repository-index.md` — generated inventory of relevant files
- `/REVIEW.md` — review-time checks for compatibility, tests, consumer examples, and documentation omissions
- `/.agents/skills/pickleball-functionality-change/SKILL.md` — reusable functionality-change workflow for agents supporting Agent Skills

Agent-specific files are intentionally small adapters that point back to the canonical contract. Avoid copying the full project description into every adapter.

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

This is task-time automation, not a passive background documentation watcher. Manual code edits do not automatically update documentation.

## Maintenance

After adding, moving, or deleting relevant files:

```shell
python scripts/refresh_agent_index.py
```

Check the contract and index:

```shell
python scripts/verify_agent_contract.py
python scripts/refresh_agent_index.py --check
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
