package com.starticket.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class AuditLogService {

    private final JdbcTemplate jdbc;

    AuditLogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String actor, String action, String targetType, Object targetId, String detail) {
        jdbc.update("""
                INSERT INTO st_audit_log (actor, action, target_type, target_id, detail, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, actor, action, targetType, String.valueOf(targetId), trim(detail), Instant.now());
    }

    private static String trim(String detail) {
        if (detail == null || detail.isBlank()) return null;
        String clean = detail.trim();
        return clean.substring(0, Math.min(500, clean.length()));
    }
}
