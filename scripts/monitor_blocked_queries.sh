#!/bin/bash
# Detailed blocked query monitoring script
# Captures which queries are blocked and what they're waiting on
# Usage: ./monitor_blocked_queries.sh [duration_seconds] [output_file]

DURATION=${1:-60}
OUTPUT_FILE=${2:-"blocked_$(date +%Y%m%d_%H%M%S)"}
LOG_FILE="${OUTPUT_FILE}.log"

CONTAINER="java-commandbus-postgres"
DB_USER="postgres"
DB_NAME="commandbus_test"

echo "=== Blocked Query Monitor ===" | tee "$LOG_FILE"
echo "Started: $(date)" | tee -a "$LOG_FILE"
echo "Duration: ${DURATION}s" | tee -a "$LOG_FILE"
echo "==============================" | tee -a "$LOG_FILE"

END_TIME=$((SECONDS + DURATION))

while [ $SECONDS -lt $END_TIME ]; do
    TIMESTAMP=$(date +%H:%M:%S)

    # Count blocked queries
    BLOCKED_COUNT=$(docker exec $CONTAINER psql -U $DB_USER -d $DB_NAME -t -A -c "
        SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'
    " 2>/dev/null)

    echo "" | tee -a "$LOG_FILE"
    echo "$TIMESTAMP - $BLOCKED_COUNT blocked queries detected" | tee -a "$LOG_FILE"

    if [ "$BLOCKED_COUNT" -gt 0 ]; then
        # Show details of blocked queries
        docker exec $CONTAINER psql -U $DB_USER -d $DB_NAME -c "
            SELECT
                pid,
                wait_event_type || ':' || coalesce(wait_event, '') as wait_on,
                left(query, 50) as query_preview,
                now() - query_start as duration
            FROM pg_stat_activity
            WHERE wait_event_type IS NOT NULL
              AND state = 'active'
            ORDER BY query_start
            LIMIT 10;
        " 2>/dev/null | tee -a "$LOG_FILE"
    fi

    sleep 2
done

echo "" | tee -a "$LOG_FILE"
echo "=== Analysis Summary ===" | tee -a "$LOG_FILE"

# Table update counts
echo "" | tee -a "$LOG_FILE"
echo "Table Statistics (n_tup_upd = updates since last vacuum):" | tee -a "$LOG_FILE"
docker exec $CONTAINER psql -U $DB_USER -d $DB_NAME -c "
    SELECT
        schemaname || '.' || relname as table_name,
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
echo "Log saved to: $LOG_FILE"
