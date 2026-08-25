#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

mode="full"
if [[ "${1:-}" == "--quick" ]]; then
  mode="quick"
elif [[ "${1:-}" == "--workbench" ]]; then
  mode="workbench"
elif [[ $# -gt 0 ]]; then
  echo "Usage: scripts/agent_validate.sh [--quick|--workbench]" >&2
  exit 2
fi

python3 scripts/verify_agent_contract.py
python3 scripts/refresh_agent_index.py --check
python3 scripts/sync_consumer_guidance.py --check

if [[ "$mode" == "workbench" ]]; then
  ./gradlew verifyStrictControllerIsolation :pickleball-workbench:test publishToMavenLocal
  bash ./maven-consumer-project/mvnw -f maven-consumer-project/pom.xml -U test \
    -Dpkb_runvars.pkb_browser=CHROME_HEADLESS \
    -Dpkb_runvars.pkb_parallel=80 \
    -Dpkb_runvars.pkb_tags=@control-bridge
  bash ./maven-consumer-project/mvnw -f maven-consumer-project/pom.xml -U test \
    -Dpkb_runvars.pkb_browser=CHROME_HEADLESS \
    -Dpkb_runvars.pkb_parallel=80 \
    -Dpkb_runvars.pkb_tags=@step-override-bridge
else
  ./gradlew test
fi

if [[ "$mode" == "full" ]]; then
  ./gradlew publishToMavenLocal
  bash ./maven-consumer-project/mvnw -f maven-consumer-project/pom.xml -U test \
    -Dpkb_runvars.pkb_browser=CHROME_HEADLESS \
    -Dpkb_runvars.pkb_tags=@all
fi

echo "Pickleball validation completed ($mode mode)."
