#!/bin/bash
# Lock monitoring script for PostgreSQL performance analysis
# Usage: ./monitor_locks.sh [duration_seconds] [output_file]
#
# Examples:
#   ./monitor_locks.sh              # Run for 60s, output to locks_TIMESTAMP.log
#   ./monitor_locks.sh 120          # Run for 120s
#   ./monitor_locks.sh 60 mytest    # Run for 60s, output to mytest.log

DURATION=${1:-60}
OUTPUT_FILE=${2:-"locks_$(date +%Y%m%d_%H%M%S)"}
LOG_FILE="${OUTPUT_FILE}.log"

CONTAINER="java-commandbus-postgres"
DB_USER="postgres"
DB_NAME="commandbus_test"

echo "=== PostgreSQL Lock Monitor ===" | tee "$LOG_FILE"
echo "Started: $(date)" | tee -a "$LOG_FILE"
echo "Duration: ${DURATION}s" | tee -a "$LOG_FILE"
echo "Output: $LOG_FILE" | tee -a "$LOG_FILE"
echo "===============================" | tee -a "$LOG_FILE"

END_TIME=$((SECONDS + DURATION))

while [ $SECONDS -lt $END_TIME ]; do
    TIMESTAMP=$(date +%H:%M:%S)

    echo "" | tee -a "$LOG_FILE"
    echo "=== $TIMESTAMP ===" | tee -a "$LOG_FILE"

    # Summary counts
    SUMMARY=$(docker exec $CONTAINER psql -U $DB_USER -d $DB_NAME -t -c "
        SELECT
            'Locks: ' || (SELECT count(*) FROM pg_locks WHERE locktype = 'relation') ||
            ' | Waiting: ' || (SELECT count(*) FROM pg_locks WHERE granted = false) ||
            ' | Blocked: ' || (SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock') ||
            ' | Active: ' || (SELECT count(*) FROM pg_stat_activity WHERE state = 'active')
    " 2>&1)

    if [ $? -eq 0 ]; then
        echo "$SUMMARY" | tee -a "$LOG_FILE"
    else
        echo "DB connection error" | tee -a "$LOG_FILE"
        sleep 2
        continue
    fi

    # Locks by table
    docker exec $CONTAINER psql -U $DB_USER -d $DB_NAME -c "
        SELECT
            c.relname as table_name,
            l.mode as lock_mode,
            count(*) as lock_count,
            sum(case when l.granted then 0 else 1 end) as waiting
        FROM pg_locks l
        JOIN pg_class c ON l.relation = c.oid
        WHERE c.relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'commandbus')
        GROUP BY c.relname, l.mode
        HAVING count(*) > 1
        ORDER BY count(*) DESC
        LIMIT 10;
    " 2>/dev/null | tee -a "$LOG_FILE"

    # Blocked queries with table info
    BLOCKED=$(docker exec $CONTAINER psql -U $DB_USER -d $DB_NAME -t -A -c "
        SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'
    " 2>/dev/null)

    # Default to 0 if empty or not a number
    BLOCKED=${BLOCKED:-0}
    BLOCKED=$(echo "$BLOCKED" | tr -d '[:space:]')
    if ! [[ "$BLOCKED" =~ ^[0-9]+$ ]]; then
        BLOCKED=0
    fi

    if [ "$BLOCKED" -gt 0 ]; then
        echo "" | tee -a "$LOG_FILE"
        echo "BLOCKED QUERIES:" | tee -a "$LOG_FILE"
        docker exec $CONTAINER psql -U $DB_USER -d $DB_NAME -c "
            SELECT
                a.pid,
                a.wait_event,
                left(a.query, 60) as query,
                round(extract(epoch from (now() - a.query_start))::numeric, 3) as duration_sec
            FROM pg_stat_activity a
            WHERE a.wait_event_type = 'Lock'
            ORDER BY a.query_start
            LIMIT 5;
        " 2>/dev/null | tee -a "$LOG_FILE"
    fi

    sleep 2
done

echo "" | tee -a "$LOG_FILE"
echo "=== Final Table Statistics ===" | tee -a "$LOG_FILE"
docker exec $CONTAINER psql -U $DB_USER -d $DB_NAME -c "
    SELECT
        relname as table_name,
        n_tup_ins as inserts,
        n_tup_upd as updates,
        n_tup_del as deletes,
        n_live_tup as live_rows
    FROM pg_stat_user_tables
    WHERE schemaname = 'commandbus'
    ORDER BY n_tup_upd DESC;
" 2>/dev/null | tee -a "$LOG_FILE"

echo "" | tee -a "$LOG_FILE"
echo "=== Monitor Complete ===" | tee -a "$LOG_FILE"
echo "Ended: $(date)" | tee -a "$LOG_FILE"
echo "Log saved to: $LOG_FILE"
