package com.capdemo.eu;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class StockController {

    private static final int PRODUCT_ID = 1;

    private final JdbcTemplate jdbcTemplate;

    public StockController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // Read-only: this connects to the standby, so this query always reads
    // whatever has been replayed from WAL so far -- possibly stale during a
    // partition, never written to directly.
    //
    // pg_last_xact_replay_timestamp() is a standby-only function returning
    // when the most recently replayed transaction was committed on the
    // primary. now() minus that timestamp is how "behind" this replica
    // currently is. It's NULL before the standby has replayed anything yet,
    // hence the COALESCE.
    @GetMapping("/stock")
    public Map<String, Object> stock() {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT qty, " +
                "COALESCE(EXTRACT(EPOCH FROM (now() - pg_last_xact_replay_timestamp()))::int, 0) AS last_synced_seconds_ago " +
                "FROM stock WHERE product_id = ?",
                PRODUCT_ID);

        return Map.of(
                "qty", row.get("qty"),
                "lastSyncedSecondsAgo", row.get("last_synced_seconds_ago"));
    }
}
