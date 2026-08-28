package com.starticket.inventory;

import com.starticket.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

record PerformanceSeatView(
        Long inventoryId,
        Long seatId,
        Long areaId,
        String areaName,
        String seatCode,
        String rowLabel,
        int seatNumber,
        Long ticketTierId,
        String tierName,
        BigDecimal price,
        String status
) {
}

record SeatMapView(Long performanceId, String performanceName, List<PerformanceSeatView> seats) {
}

@RestController
@RequestMapping("/api/performances")
class InventoryController {

    private final JdbcTemplate jdbc;

    InventoryController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/{performanceId}/seats")
    SeatMapView seats(@PathVariable Long performanceId) {
        List<String> names = jdbc.query("""
                SELECT p.name FROM st_performance p
                JOIN st_event e ON e.id = p.event_id
                WHERE p.id = ? AND p.status = 'SCHEDULED' AND e.status IN ('APPROVED', 'ON_SALE')
                """, (rs, row) -> rs.getString(1), performanceId);
        if (names.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "场次不存在");
        }
        List<PerformanceSeatView> seats = jdbc.query("""
                SELECT ps.id, s.id, a.id, a.name, s.code, s.row_label, s.seat_number,
                       t.id, t.name, ps.price, ps.status
                FROM st_performance_seat ps
                JOIN st_seat s ON s.id = ps.seat_id
                JOIN st_venue_area a ON a.id = s.area_id
                JOIN st_ticket_tier t ON t.id = ps.ticket_tier_id
                WHERE ps.performance_id = ?
                ORDER BY a.sort_order, a.id, s.row_label, s.seat_number
                """, (rs, row) -> new PerformanceSeatView(
                rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getInt(7), rs.getLong(8), rs.getString(9),
                rs.getBigDecimal(10), rs.getString(11)), performanceId);
        return new SeatMapView(performanceId, names.getFirst(), seats);
    }
}
