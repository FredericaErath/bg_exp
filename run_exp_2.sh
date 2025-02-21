#!/bin/bash

# 定义 JanusGraph 相关路径（修改为你的实际路径）
GREMLIN_HOME="$HOME/janusgraph-1.1.0"
GREMLIN_CONSOLE="$GREMLIN_HOME/bin/gremlin.sh"
REMOTE_CONFIG="$GREMLIN_HOME/conf/remote.yaml"

# 记录日志文件
LOG_FILE="experiment_results_4.log"

# 清空日志文件
> "$LOG_FILE"

# 定义参数组合
ReadOnlyActions_variants=("ReadOnlyActions_1" "ReadOnlyActions_2")
threads_variants=(1 10 100)

for i in "${!ReadOnlyActions_variants[@]}"; do
    ReadOnlyActions="${ReadOnlyActions_variants[$i]}"

    # 读取对应 workload 文件中的 operationcount（可选）
    operationcount_file="workloads/$ReadOnlyActions"
    if [ ! -f "$operationcount_file" ]; then
        echo "Error: $operationcount_file not found!" | tee -a "$LOG_FILE"
        continue
    fi

    expected_actions=$(grep "^operationcount=" "$operationcount_file" | cut -d '=' -f2)
    if [[ -z "$expected_actions" ]]; then
        echo "Warning: Unable to read operationcount from $operationcount_file. (Will continue anyway)" | tee -a "$LOG_FILE"
        expected_actions="N/A"
    fi

    for threads in "${threads_variants[@]}"; do
        echo "===========================================" | tee -a "$LOG_FILE"
        echo "Running experiment with ReadOnlyActions=$ReadOnlyActions, threads=$threads, expected actions=$expected_actions" | tee -a "$LOG_FILE"
        echo "===========================================" | tee -a "$LOG_FILE"

        # 临时输出文件，用于解析 "X sec: X actions;"
        TMP_OUTPUT="tmp_output_4.log"
        > "$TMP_OUTPUT"

        # 启动 BGMainClass 负载
        echo "Starting workload execution with $threads threads for 5 minutes..."
        java -cp "build/classes:lib/*" \
             edu.usc.bg.BGMainClass \
             onetime \
             -t edu.usc.bg.workloads.CoreWorkLoad \
             -threads "$threads" \
             -db janusgraph.JanusGraphClient \
             -P "workloads/$ReadOnlyActions" \
             -s true \
             2>&1 | tee "$TMP_OUTPUT" &
        PID=$!

        if [ "$threads" -eq 100 ]; then
            # 如果是100线程，则记录CPU/内存使用
            CPU_MEM_LOG="cpu_mem_${ReadOnlyActions}_${threads}.log"
            > "$CPU_MEM_LOG"
            echo "Recording CPU/MEM usage to $CPU_MEM_LOG..."

            # 每隔5秒记录一次，共5分钟
            end_time=$((SECONDS+300))  # 300秒 = 5分钟
            while [ $SECONDS -lt $end_time ]; do
                if kill -0 "$PID" 2>/dev/null; then
                    timestamp=$(date '+%Y-%m-%d %H:%M:%S')
                    usage=$(ps -p $PID -o %cpu,%mem --no-headers)
                    echo "$timestamp CPU/MEM: $usage" >> "$CPU_MEM_LOG"
                    sleep 5
                else
                    echo "Process $PID ended early." >> "$CPU_MEM_LOG"
                    break
                fi
            done

            echo "5 minutes reached. Killing process $PID..."
            kill -9 "$PID"

        else
            # 否则，直接等待5分钟后结束进程
            sleep 300
            echo "5 minutes reached. Killing process $PID..."
            kill -9 "$PID"
        fi

        # 提取最后一条 "X sec: X actions;" 记录
        LAST_ACTIONS_LINE=$(grep -o "[0-9]\+ sec: [0-9]\+ actions; .*" "$TMP_OUTPUT" | tail -n 1)
        if [[ -n "$LAST_ACTIONS_LINE" ]]; then
            echo "Final record before kill: $LAST_ACTIONS_LINE" | tee -a "$LOG_FILE"
        else
            echo "No 'X sec: X actions;' found in $TMP_OUTPUT" | tee -a "$LOG_FILE"
        fi
    done
done

echo "All experiments completed. Results saved in $LOG_FILE"
