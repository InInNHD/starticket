package com.starticket.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class OutboxClaimService {

    private final JdbcTemplate jdbc;

    OutboxClaimService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public List<OutboxMessage> claim(String workerId, Instant now, int limit) {
        jdbc.update("""
                UPDATE st_outbox_event
                SET status = 'PENDING', locked_by = NULL, locked_at = NULL, next_retry_at = ?,
                    last_error = '发布抢占超时，已自动恢复'
                WHERE status = 'PROCESSING' AND locked_at < ?
                """, now, now.minus(1, ChronoUnit.MINUTES));
        List<OutboxMessage> rows = jdbc.query("""
                SELECT id, event_type, aggregate_id, payload, retry_count
                FROM st_outbox_event
                WHERE status = 'PENDING' AND next_retry_at <= ?
                ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new OutboxMessage(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getInt(5)), now, limit);
        rows.forEach(row -> jdbc.update("""
                UPDATE st_outbox_event SET status = 'PROCESSING', locked_by = ?, locked_at = ?
                WHERE id = ? AND status = 'PENDING'
                """, workerId, now, row.id()));
        return rows;
    }

    public void published(String workerId, long id, Instant now) {
        jdbc.update("""
                UPDATE st_outbox_event
                SET status = 'PUBLISHED', published_at = ?, last_error = NULL, locked_by = NULL, locked_at = NULL
                WHERE id = ? AND status = 'PROCESSING' AND locked_by = ?
                """, now, id, workerId);
    }

    public void failed(String workerId, OutboxMessage row, Exception exception, Instant now) {
        int retries = row.retryCount() + 1;
        jdbc.update("""
                UPDATE st_outbox_event
                SET status = ?, retry_count = ?, next_retry_at = ?, last_error = ?, locked_by = NULL, locked_at = NULL
                WHERE id = ? AND status = 'PROCESSING' AND locked_by = ?
                """, retries >= 5 ? "DEAD" : "PENDING", retries,
                now.plus(Math.min(1L << retries, 60), ChronoUnit.SECONDS), message(exception), row.id(), workerId);
    }

    private static String message(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) message = exception.getClass().getSimpleName();
        return message.substring(0, Math.min(500, message.length()));
    }

    public record OutboxMessage(long id, String eventType, String aggregateId, String payload, int retryCount) {
    }
}
