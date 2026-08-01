# Repository Agent Context

This directory supports repository-native AI coding agents. It is not a runtime dependency of Pickleball and is not included to change framework behavior.


## One-command setup

After extracting the drop-in ZIP into the Pickleball repository root, run:

```powershell
.\setup-agent.ps1
```

This merges the supplied ignore entry into `.gitignore`, removes the temporary example file, generates the repository index, verifies the agent contract, and runs full framework and Maven consumer validation.

The Maven consumer uses its committed Maven Wrapper, so a separate Maven installation and IntelliJ bundled-Maven path are not required. The first wrapper run downloads the pinned Maven version into the user Maven cache.

Optional modes:

```powershell
.\setup-agent.ps1 -Quick       # Contract checks plus Gradle framework tests
.\setup-agent.ps1 -SkipTests   # Configure and verify only
.\setup-agent.ps1 -StageGit    # Full setup, then stage the shared files
```

The script never commits or pushes changes.

## Canonical files

- `/AGENTS.md` — project contract, workflow, compatibility rules, validation, and definition of done
- `/docs/agent/feature-map.md` — living map from capabilities to implementation, tests, consumer examples, and documentation
- `/docs/agent/change-checklist.md` — explicit change-completion checklist
- `/docs/agent/repository-index.md` — generated inventory of relevant files
- `/REVIEW.md` — review-time checks for compatibility, test, consumer-example, and documentation omissions
- `/.agents/skills/pickleball-functionality-change/SKILL.md` — reusable functionality-change workflow for agents supporting Agent Skills

Agent-specific files are intentionally small adapters that point back to the canonical contract. Avoid copying the full project description into every adapter.

## Expected behavior

With a compatible write-capable coding agent, a prompt such as:

> Add support for template references in XML attributes.

should cause the agent to:

1. Load the repository contract.
2. Locate the capability in the feature map.
3. Inspect related source, tests, consumer scenarios, and guides.
4. Implement the change.
5. Update executable examples and documentation when applicable.
6. Run validation.
7. Report results.

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

By default, `verify_agent_contract.py` treats missing setup files as errors and change-coverage findings as warnings.

Use strict mode in CI when the team is comfortable with the heuristics:

```shell
python scripts/verify_agent_contract.py --base-ref origin/master --strict
```

Temporary narrowly scoped overrides are available for false positives:

- `AGENT_CONTRACT_ALLOW_NO_DOCS=true`
- `AGENT_CONTRACT_ALLOW_NO_TESTS=true`

Do not use overrides to bypass genuinely missing documentation or tests.

## Supported adapters included

The bundle includes adapters for:

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
