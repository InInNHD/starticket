package com.starticket.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class InventoryInitializer {

    private final JdbcTemplate jdbc;

    public InventoryInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int initializeEvent(Long eventId) {
        return jdbc.update("""
                INSERT INTO st_performance_seat
                    (performance_id, seat_id, ticket_tier_id, price, status, version)
                SELECT p.id, s.id, t.id, t.price, 'AVAILABLE', 0
                FROM st_performance p
                JOIN st_ticket_tier t ON t.performance_id = p.id AND t.enabled = TRUE
                JOIN st_seat s ON s.area_id = t.area_id AND s.enabled = TRUE
                WHERE p.event_id = ?
                  AND p.status = 'SCHEDULED'
                  AND NOT EXISTS (
                      SELECT 1 FROM st_performance_seat ps
                      WHERE ps.performance_id = p.id AND ps.seat_id = s.id
                  )
                """, eventId);
    }
}
