#!/bin/bash

# 定义 JanusGraph 相关路径（修改为你的实际路径）
GREMLIN_HOME="$HOME/janusgraph-1.1.0"
GREMLIN_CONSOLE="$GREMLIN_HOME/bin/gremlin.sh"
REMOTE_CONFIG="$GREMLIN_HOME/conf/remote.yaml"

# 记录日志文件
LOG_FILE="experiment_results.log"

# 清空日志文件
> "$LOG_FILE"

# 定义参数组合
populateDB_variants=("populateDB_1" "populateDB_2" "populateDB_3")
ReadOnlyActions_variants=("ReadOnlyActions_1" "ReadOnlyActions_2" "ReadOnlyActions_3")
threads_variants=(1 2 3)

# 遍历所有组合
for i in "${!populateDB_variants[@]}"; do
    populateDB="${populateDB_variants[$i]}"
    ReadOnlyActions="${ReadOnlyActions_variants[$i]}"

    for threads in "${threads_variants[@]}"; do
        echo "===========================================" | tee -a "$LOG_FILE"
        echo "Running experiment with populateDB=$populateDB, ReadOnlyActions=$ReadOnlyActions and threads=$threads" | tee -a "$LOG_FILE"
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
        java -cp "build/classes:lib/*" edu.usc.bg.BGMainClass onetime -load edu.usc.bg.workloads.UserWorkLoad -db janusgraph.JanusGraphClient -P "workloads/$populateDB" 2>&1 | tee tmp_output.log &
        PID=$!

        # 监控日志，检测 "SHUTDOWN!!!"
        while sleep 2; do
            if grep -q "SHUTDOWN!!!" tmp_output.log; then
                echo "Detected SHUTDOWN!!! - Killing process $PID"
                kill -9 "$PID"
                break
            fi
        done

        # 运行 BGMainClass (步骤 3) 并检测 "Visualization thread has Stopped..." 或 "NullPointerException"
        echo "Starting workload execution with $threads threads..."
        java -cp "build/classes:lib/*" edu.usc.bg.BGMainClass onetime -t edu.usc.bg.workloads.CoreWorkLoad -threads "$threads" -db janusgraph.JanusGraphClient -P "workloads/$ReadOnlyActions" -s true 2>&1 | tee tmp_output.log &
        PID=$!

        # 监控日志，检测 "Visualization thread has Stopped..." 或 "NullPointerException"
        while sleep 2; do
            if grep -q "Visualization thread has Stopped" tmp_output.log; then
                echo "Detected Visualization thread has Stopped - Killing process $PID"
                kill -9 "$PID"

                # 记录倒数第三行
                LAST_THREE_LINES=$(tail -n 3 tmp_output.log)
                THIRD_LAST_LINE=$(echo "$LAST_THREE_LINES" | head -n 1)
                echo "Result: $THIRD_LAST_LINE" | tee -a "$LOG_FILE"
                break

            elif grep -q "Exception in thread .* java.lang.NullPointerException" tmp_output.log; then
                echo "Detected NullPointerException - Searching for PROFILE AverageResponseTime"
                kill -9 "$PID"

                # 记录最后一行含有 [PROFILE AverageResponseTime(us)=...] 的内容
                PROFILE_LINE=$(grep "\[PROFILE AverageResponseTime(us)=" tmp_output.log | tail -n 1)
                if [ -n "$PROFILE_LINE" ]; then
                    echo "Result: $PROFILE_LINE" | tee -a "$LOG_FILE"
                else
                    echo "Result: No PROFILE AverageResponseTime found" | tee -a "$LOG_FILE"
                fi
                break
            fi
        done
    done
done

echo "All experiments completed. Results saved in $LOG_FILE"
