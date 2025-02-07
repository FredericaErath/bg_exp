#!/bin/bash

# 定义 JanusGraph 相关路径（修改为你的实际路径）
GREMLIN_HOME="$HOME/janusgraph-1.1.0"
GREMLIN_CONSOLE="$GREMLIN_HOME/bin/gremlin.sh"
REMOTE_CONFIG="$GREMLIN_HOME/conf/remote.yaml"

# 记录日志文件
LOG_FILE="experiment_results1.log"

# 清空日志文件
> "$LOG_FILE"

# 定义参数组合
populateDB_variants=("populateDB_1, populateDB_2" "populateDB_3")
ReadOnlyActions_variants=("ReadOnlyActions_1, ReadOnlyActions_2" "ReadOnlyActions_3")
threads_variants=(1 10 100)

# 遍历所有组合
for i in "${!populateDB_variants[@]}"; do
    populateDB="${populateDB_variants[$i]}"
    ReadOnlyActions="${ReadOnlyActions_variants[$i]}"

    # 读取 ReadOnlyActions_i 文件中的 operationcount
    operationcount_file="workloads/$ReadOnlyActions"
    if [ ! -f "$operationcount_file" ]; then
        echo "Error: $operationcount_file not found!" | tee -a "$LOG_FILE"
        continue
    fi

    expected_actions=$(grep "^operationcount=" "$operationcount_file" | cut -d '=' -f2)
    if [[ -z "$expected_actions" ]]; then
        echo "Error: Unable to read operationcount from $operationcount_file" | tee -a "$LOG_FILE"
        continue
    fi

    for threads in "${threads_variants[@]}"; do
        echo "===========================================" | tee -a "$LOG_FILE"
        echo "Running experiment with populateDB=$populateDB, ReadOnlyActions=$ReadOnlyActions, threads=$threads, expected actions=$expected_actions" | tee -a "$LOG_FILE"
        echo "===========================================" | tee -a "$LOG_FILE"

        # 清空数据库
        echo "Clearing JanusGraph database before running this combination..."
        "$GREMLIN_CONSOLE" <<EOF
:remote connect tinkerpop.server $REMOTE_CONFIG
:remote console
:remote config timeout 600000
g.V().drop()
:exit
EOF
        echo "JanusGraph database cleared."

        # 运行 BGMainClass (步骤 2) 并检测 "SHUTDOWN!!!"
        echo "Starting database population..."
        java -cp "build/classes:lib/*" edu.usc.bg.BGMainClass onetime -load edu.usc.bg.workloads.UserWorkLoad -db janusgraph.JanusGraphClient -P "workloads/$populateDB" 2>&1 | tee tmp_output1.log &
        PID=$!

        # 监控日志，检测 "SHUTDOWN!!!"
        while sleep 2; do
            if grep -q "SHUTDOWN!!!" tmp_output1.log; then
                echo "Detected SHUTDOWN!!! - Killing process $PID"
                kill -9 "$PID"
                break
            fi
        done

        # 运行 BGMainClass (步骤 3) 并检测 "X sec: X actions;"
        echo "Starting workload execution with $threads threads..."
        java -cp "build/classes:lib/*" edu.usc.bg.BGMainClass onetime -t edu.usc.bg.workloads.CoreWorkLoad -threads "$threads" -db janusgraph.JanusGraphClient -P "workloads/$ReadOnlyActions" -s true 2>&1 | tee tmp_output1.log &
        PID=$!

        # 监控日志，直到 "X sec: X actions;" 出现，并检查 X actions 是否等于 expected_actions
        while sleep 2; do
            ACTIONS_LINE=$(grep -o "[0-9]\+ sec: [0-9]\+ actions; .*" tmp_output1.log | tail -n 1)
            if [[ -n "$ACTIONS_LINE" ]]; then
                actual_actions=$(echo "$ACTIONS_LINE" | awk '{print $3}')
                if [[ "$actual_actions" -eq "$expected_actions" ]]; then
                    echo "Detected '$ACTIONS_LINE' with expected actions $expected_actions - Killing process $PID"
                    kill -9 "$PID"

                    # 记录日志
                    echo "Result: $ACTIONS_LINE" | tee -a "$LOG_FILE"
                    break
                fi
            fi
        done
    done
done

echo "All experiments completed. Results saved in $LOG_FILE"