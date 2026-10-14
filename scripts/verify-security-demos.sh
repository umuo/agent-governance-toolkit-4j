#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -B -ntp -s .mvn/settings.xml -Dmaven.repo.local=.cache/m2 verify
java -jar examples/target/agt-examples-0.6.0-SNAPSHOT.jar security asi02
java -jar examples/target/agt-examples-0.6.0-SNAPSHOT.jar security asi05
