#!/bin/bash

GREMLIN_HOME="$HOME/janusgraph-1.1.0"
GREMLIN_CONSOLE="$GREMLIN_HOME/bin/gremlin.sh"
REMOTE_CONFIG="$GREMLIN_HOME/conf/remote.yaml"

LOG_FILE="validation_results_1.log"
TMP_OUTPUT1="tmp_output1.log"
TMP_OUTPUT2="tmp_output2.log"

> "$LOG_FILE"

PopulateAction="populateDB_1000"
ValidationAction="SymmetricMixDelegateAction"

threads_variants=(1 10 100)

echo "Starting database population..." | tee -a "$LOG_FILE"

echo "Clearing JanusGraph database before running this combination..." | tee -a "$LOG_FILE"
"$GREMLIN_CONSOLE" <<EOF
:remote connect tinkerpop.server $REMOTE_CONFIG
:remote console
:remote config timeout 600000
g.E().drop()
g.V().drop()
:exit
EOF

java -cp "build/classes:lib/*" edu.usc.bg.BGMainClass onetime -load edu.usc.bg.workloads.UserWorkLoad -db janusgraph.JanusGraphClient -P "workloads/$PopulateAction" 2>&1 | tee "$TMP_OUTPUT1" &
PID=$!

# 监测 SHUTDOWN 关键字
while sleep 2; do
    if grep -q "SHUTDOWN" "$TMP_OUTPUT1"; then
        echo "Detected SHUTDOWN - Waiting for process $PID to exit..." | tee -a "$LOG_FILE"
        wait "$PID"
        echo "Database population complete." | tee -a "$LOG_FILE"
        break
    fi
done

for threads in "${threads_variants[@]}"; do
    echo "Starting validation with $threads threads..." | tee -a "$LOG_FILE"

    java -cp "build/classes:lib/*" edu.usc.bg.validator.ValidationMainClass onetime -t edu.usc.bg.workloads.CoreWorkLoad -db janusgraph.JanusGraphClient -P "workloads/$ValidationAction" -threads "$threads" 2>&1 | tee "$TMP_OUTPUT2" &
    PID=$!

    # 运行 10 分钟后终止
    sleep 600
    echo "Stopping validation process $PID after 10 minutes..." | tee -a "$LOG_FILE"
    kill -9 "$PID"

    # 提取日志中 "X% of reads observed the value of ..." 及其后两行
    echo "Extracting validation results for $threads threads..." | tee -a "$LOG_FILE"
    awk '/[0-9]+% of reads observed the value of /{print; getline; print; getline; print}' "$TMP_OUTPUT2" >> "$LOG_FILE"

    echo "Validation with $threads threads completed. Results logged." | tee -a "$LOG_FILE"
done

echo "All validations completed. Results saved in $LOG_FILE."







