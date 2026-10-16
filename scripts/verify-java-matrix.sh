#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# macOS 本机矩阵；其他平台分别用 JDK 17/21 设置 JAVA_HOME 运行这些命令。
for version in 17 21; do
  task_java_home="$(/usr/libexec/java_home -v "$version")"
  JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" \
    mvn -B -ntp -s .mvn/settings.xml -Dmaven.repo.local=.cache/m2 clean verify
  "$task_java_home/bin/java" -jar examples/target/agt-examples-0.6.0-SNAPSHOT.jar native
  "$task_java_home/bin/java" -jar examples/target/agt-examples-0.6.0-SNAPSHOT.jar all
  "$task_java_home/bin/java" -jar examples/target/agt-examples-0.6.0-SNAPSHOT.jar async all
  "$task_java_home/bin/java" -jar examples/target/agt-examples-0.6.0-SNAPSHOT.jar stream all
  "$task_java_home/bin/java" -jar examples/target/agt-examples-0.6.0-SNAPSHOT.jar allowed examples/policies/default.yaml
  "$task_java_home/bin/java" -jar examples/target/agt-examples-0.6.0-SNAPSHOT.jar data all
  "$task_java_home/bin/java" -jar examples/target/agt-examples-0.6.0-SNAPSHOT.jar data rag-transform examples/policies/data-access.yaml
  "$task_java_home/bin/java" -jar examples/target/agt-examples-0.6.0-SNAPSHOT.jar command all
  "$task_java_home/bin/java" -jar examples/target/agt-examples-0.6.0-SNAPSHOT.jar sandbox all
  "$task_java_home/bin/java" -jar examples/target/agt-examples-0.6.0-SNAPSHOT.jar security all
done
