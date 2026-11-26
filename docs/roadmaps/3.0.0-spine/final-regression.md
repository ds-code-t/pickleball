# Final regression — 3.0.0-spine

Branch `3.0.0-spine` only. Never `master` or any 2.x branch. No tags, releases, publishing, or force-push.

The final full regression runs once, after the R5 fix-back, the R8 fix-back, the merge of `3.0.0-spine-r8` into `3.0.0-spine`, and R6 are all complete and verified. It is the full regression, the R1 through R8 Windows-only sweep, and the cross-roadmap docs audit described at the end of R8 P4.

## Platform (Dan, 2026-11-26)

- Run the final regression only on Dan's Windows PC (DESKTOP-TUUEDJG), locally in the `3.0.0-spine` worktree.
- No Linux or macOS desktop runs for this pass, and no GitHub Actions or workflow runs. Do not use `workflow_dispatch`, and do not open or update a pull request or push to a CI-triggering branch to get CI results. This conserves Grok Bot and GitHub resources.
- Informal spot checks on another platform are allowed only if they are easy. They are not part of the pass and are not recorded as results.
- Heavier Linux and macOS desktop testing is deferred to a later cycle. It stays in the Linux and macOS section of `docs/tech-debt.md`.

## CI jobs that must not be triggered for this pass

`.github/workflows/agent-contract.yml` runs on any `pull_request` and on `push` to `master`, `2.2.0`, and `3.0.0`. It has a `macos` job (`macos-latest`), a `dom-engine-live` job (`ubuntu-latest`), and a `gate` job that needs both. `.github/workflows/sqlite-index.yml` runs on any `pull_request` and on `push` to `master`, `2.1.18`, and `3.0.0` (Windows only). Pushes to `3.0.0-spine` trigger neither workflow. A pull request from `3.0.0-spine`, or a push to `3.0.0`, would trigger both, including the macOS and Linux jobs. Neither happens during this pass.

## Build prompt

The Build prompt for the final regression quotes the Platform and CI sections above word for word.
