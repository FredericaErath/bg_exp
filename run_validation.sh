#!/bin/bash

GREMLIN_HOME="$HOME/janusgraph-1.1.0"
GREMLIN_CONSOLE="$GREMLIN_HOME/bin/gremlin.sh"
REMOTE_CONFIG="$GREMLIN_HOME/conf/remote.yaml"

LOG_FILE="validation_results_1.log"
TMP_OUTPUT1="tmp_output1.log"
TMP_OUTPUT2="tmp_output2.log"
TMP_OUTPUT3="tmp_output3.log"

> "$LOG_FILE"

PopulateAction="populateDB_1000"
ValidationAction="SymmetricMixDelegateAction"

threads_variants=1

echo "Removing old read and update files..." | tee -a "$LOG_FILE"
sudo rm -f read*.txt
sudo rm -f update*.txt

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

java -cp "build/classes:lib/*" edu.usc.bg.BGMainClass onetime -load edu.usc.bg.workloads.UserWorkLoad -threads 1 -db janusgraph.JanusGraphClient -P "workloads/$PopulateAction" 2>&1 | tee "$TMP_OUTPUT1" &
PID1=$!

# 监测 SHUTDOWN 关键字
while sleep 2; do
    if grep -q "SHUTDOWN" "$TMP_OUTPUT1"; then
        echo "Detected SHUTDOWN - Waiting for process $PID1 to exit..." | tee -a "$LOG_FILE"
        wait "$PID1"
        echo "Database population complete." | tee -a "$LOG_FILE"
        break
    fi
done
kill -9 "$PID1"

java -cp "build/classes:lib/*" edu.usc.bg.BGMainClass onetime -t edu.usc.bg.workloads.CoreWorkLoad -threads "$threads_variants" -db janusgraph.JanusGraphClient -P "workloads/$ValidationAction" -s true 2>&1 | tee $TMP_OUTPUT2 &
PID2=$!

# 运行 10 分钟后终止
sleep 600
echo "Stopping validation process $PID2 after 10 minutes..." | tee -a "$LOG_FILE"
kill -9 "$PID2"


echo "Starting validation with $threads_variants threads..." | tee -a "$LOG_FILE"

java -cp "build/classes:lib/*" edu.usc.bg.validator.ValidationMainClass onetime -t edu.usc.bg.workloads.CoreWorkLoad -db janusgraph.JanusGraphClient -P "workloads/$ValidationAction" -threads "$threads_variants" 2>&1 | tee "$TMP_OUTPUT3" &
PID3=$!

# 提取日志中 "X% of reads observed the value of ..." 及其后两行
echo "Extracting validation results for $threads_variants threads..." | tee -a "$LOG_FILE"
awk '/[0-9]+% of reads observed the value of /{print; getline; print; getline; print}' "$TMP_OUTPUT3" >> "$LOG_FILE"

echo "Validation with $threads_variants threads completed. Results logged." | tee -a "$LOG_FILE"


echo "All validations completed. Results saved in $LOG_FILE."
kill -9 "$PID3"







