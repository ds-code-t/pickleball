---
applyTo: "src/main/java/**/*.java,src/main/aspectj/**/*,src/test/**/*.java,build.gradle"
---

Follow `/AGENTS.md`.

Before changing Java or AspectJ behavior, search for corresponding tests, Gherkin scenarios, documentation, and consumer dependencies. Preserve Java 21 compatibility and public consumer contracts. Add focused tests and run the applicable Gradle and Maven consumer validation.
