package com.capdemo.us;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.SQLWarningException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.util.Map;

@RestController
public class StockController {

    private static final int PRODUCT_ID = 1;

    // Upper bound on an explicit reset target, to bound a typo.
    private static final int MAX_STOCK = 1000;

    // Postgres SQLSTATE for "query_canceled" -- what a driver-issued cancel
    // request comes back as.
    private static final String QUERY_CANCELED_SQLSTATE = "57014";

    private final JdbcTemplate jdbcTemplate;
    private final int startingStock;

    public StockController(JdbcTemplate jdbcTemplate,
                            @Value("${app.sell-timeout-seconds}") int sellTimeoutSeconds,
                            @Value("${app.starting-stock}") int startingStock) {
        this.jdbcTemplate = jdbcTemplate;
        this.startingStock = startingStock;
        // JDBC Statement.setQueryTimeout(): pgjdbc enforces this client-side
        // by sending a real Postgres cancel request on a separate connection
        // once the timeout elapses. That's what bounds the CP-mode hang.
        this.jdbcTemplate.setQueryTimeout(sellTimeoutSeconds);

        // Canceling a backend that is parked waiting for a synchronous
        // standby does NOT fail the statement -- Postgres commits locally,
        // emits a WARNING ("canceling wait for synchronous replication"),
        // and returns success. So without this, a CP-mode partition would
        // silently return 200 for a write that was never replicated.
        // Surfacing warnings as SQLWarningException lets us report that
        // unconfirmed write honestly, as a 503.
        this.jdbcTemplate.setIgnoreWarnings(false);
    }

    @GetMapping("/stock")
    public Map<String, Object> stock() {
        Integer qty = jdbcTemplate.queryForObject(
                "SELECT qty FROM stock WHERE product_id = ?", Integer.class, PRODUCT_ID);
        return Map.of("qty", qty);
    }

    @PostMapping("/admin/sell")
    public ResponseEntity<Map<String, Object>> sell() {
        try {
            Integer qty = jdbcTemplate.queryForObject(
                    "UPDATE stock SET qty = qty - 1 WHERE product_id = ? AND qty > 0 RETURNING qty",
                    Integer.class, PRODUCT_ID);
            return ResponseEntity.ok(Map.of("qty", qty));
        } catch (EmptyResultDataAccessException ex) {
            return ResponseEntity.status(409).body(Map.of("error", "out_of_stock"));
        } catch (SQLWarningException ex) {
            // The commit was not confirmed by the standby. It DID apply
            // locally on the primary -- that's the "in doubt" state
            // synchronous replication puts you in during a partition.
            return ResponseEntity.status(503).body(Map.of(
                    "error", "not_replicated",
                    "message", "Write was not confirmed by the standby (committed locally, replication unconfirmed)"));
        } catch (DataAccessException ex) {
            if (isQueryCanceled(ex)) {
                return ResponseEntity.status(503).body(Map.of(
                        "error", "timeout",
                        "message", "Timed out waiting on a row lock held by an unconfirmed write"));
            }
            throw ex;
        }
    }

    /**
     * Resets the counter. Pass ?qty=N to set an explicit value instead of the
     * configured starting stock.
     */
    @PostMapping("/admin/reset")
    public ResponseEntity<Map<String, Object>> reset(@RequestParam(required = false) Integer qty) {
        int target = qty != null ? qty : startingStock;
        if (target < 0 || target > MAX_STOCK) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "invalid_qty",
                    "message", "qty must be between 0 and " + MAX_STOCK));
        }
        try {
            jdbcTemplate.update("UPDATE stock SET qty = ? WHERE product_id = ?", target, PRODUCT_ID);
            return ResponseEntity.ok(Map.of("qty", target));
        } catch (SQLWarningException ex) {
            // Same unconfirmed-commit case as /admin/sell: the reset applied
            // locally but the standby never acknowledged it.
            return ResponseEntity.status(503).body(Map.of(
                    "error", "not_replicated",
                    "message", "Reset applied locally but not confirmed by the standby"));
        }
    }

    private boolean isQueryCanceled(Throwable ex) {
        while (ex != null) {
            if (ex instanceof SQLException sqlEx && QUERY_CANCELED_SQLSTATE.equals(sqlEx.getSQLState())) {
                return true;
            }
            ex = ex.getCause();
        }
        return false;
    }
}
