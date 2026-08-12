## Summary

Describe the requested behavior and the resulting implementation.

## Functionality-change coverage

- [ ] I reviewed `AGENTS.md` and the relevant feature-map entry.
- [ ] Framework implementation is complete.
- [ ] Focused framework tests were added or updated.
- [ ] Maven consumer scenarios and supporting resources were added or updated when applicable.
- [ ] README or canonical documentation was updated when behavior changed.
- [ ] `docs/agent/feature-map.md` remains accurate.
- [ ] `docs/agent/repository-index.md` is current.
- [ ] Backward compatibility was preserved, or the breaking change is documented.

## Validation

- [ ] `python scripts/verify_agent_contract.py`
- [ ] `python scripts/refresh_agent_index.py --check`
- [ ] `./gradlew test`
- [ ] `./gradlew publishToMavenLocal`
- [ ] `./maven-consumer-project/mvnw -f maven-consumer-project/pom.xml -U test -Dpkb_browser=CHROME_HEADLESS`

List any command not run and explain why.
