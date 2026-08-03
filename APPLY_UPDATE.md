# Apply the 2.1.2 data-reference update

1. Extract this archive at the Pickleball repository root.
2. Run:

```shell
python apply-update.py
python scripts/refresh_agent_index.py
```

3. Publish and run the consumer validation:

```shell
./gradlew test publishToMavenLocal
./maven-consumer-project/mvnw -f maven-consumer-project/pom.xml -U test -Dpkb_browser=CHROME_HEADLESS
python scripts/verify_agent_contract.py
python scripts/refresh_agent_index.py --check
```

On Windows, use `gradlew.bat` and `mvnw.cmd`.

`apply-update.py` patches the large existing source/document files and removes the
root Java test classes that were relocated into `maven-consumer-project`.
