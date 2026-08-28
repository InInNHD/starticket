package com.starticket.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

record DeadOutboxView(long id, String eventType, String aggregateId, int retryCount,
                      String lastError, Instant createdAt) {
}

@RestController
@RequestMapping("/api/admin/outbox")
class OperationsController {

    private final JdbcTemplate jdbc;

    OperationsController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/dead")
    List<DeadOutboxView> dead() {
        return jdbc.query("""
                SELECT id, event_type, aggregate_id, retry_count, last_error, created_at
                FROM st_outbox_event WHERE status = 'DEAD' ORDER BY created_at DESC
                """, (rs, row) -> new DeadOutboxView(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getInt(4), rs.getString(5), rs.getTimestamp(6).toInstant()));
    }

    @PostMapping("/{id}/retry")
    void retry(@PathVariable long id) {
        jdbc.update("""
                UPDATE st_outbox_event
                SET status = 'PENDING', retry_count = 0, next_retry_at = ?, last_error = NULL
                WHERE id = ? AND status = 'DEAD'
                """, Instant.now(), id);
    }
}
