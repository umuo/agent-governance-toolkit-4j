#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -B -ntp -s .mvn/settings.xml -Dmaven.repo.local=.cache/m2 verify
java -jar examples/target/agt-examples-0.6.0-SNAPSHOT.jar all
java -jar examples/target/agt-examples-0.6.0-SNAPSHOT.jar async all
java -jar examples/target/agt-examples-0.6.0-SNAPSHOT.jar stream all
java -jar examples/target/agt-examples-0.6.0-SNAPSHOT.jar allowed examples/policies/default.yaml
java -jar examples/target/agt-examples-0.6.0-SNAPSHOT.jar data all
java -jar examples/target/agt-examples-0.6.0-SNAPSHOT.jar data rag-transform examples/policies/data-access.yaml
java -jar examples/target/agt-examples-0.6.0-SNAPSHOT.jar command all
java -jar examples/target/agt-examples-0.6.0-SNAPSHOT.jar sandbox all
java -jar examples/target/agt-examples-0.6.0-SNAPSHOT.jar security all
java -jar examples/target/agt-examples-0.6.0-SNAPSHOT.jar native
