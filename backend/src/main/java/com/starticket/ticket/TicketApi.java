package com.starticket.ticket;

import com.starticket.account.AccountLookup;
import com.starticket.common.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

record TicketView(
        String ticketNo,
        String code,
        String status,
        Long performanceId,
        String performanceName,
        Instant startsAt,
        String seatCode,
        String tierName,
        Instant usedAt
) {
}

record CheckInRequest(@NotBlank String code) {
}

record CheckInResult(String ticketNo, String result, Instant checkedAt) {
}

@Service
class TicketService {

    private final JdbcTemplate jdbc;
    private final AccountLookup accounts;
    private final TicketCodeService codes;
    private final MeterRegistry meters;

    TicketService(JdbcTemplate jdbc, AccountLookup accounts, TicketCodeService codes, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.codes = codes;
        this.meters = meters;
    }

    @Transactional(readOnly = true)
    List<TicketView> list(String username) {
        long userId = accounts.requireUserId(username);
        return jdbc.query("""
                SELECT t.ticket_no, t.status, t.performance_id, p.name, p.starts_at,
                       i.seat_code, i.tier_name, t.used_at
                FROM st_ticket t
                JOIN st_order_item i ON i.id = t.order_item_id
                JOIN st_performance p ON p.id = t.performance_id
                WHERE t.user_id = ? ORDER BY t.created_at DESC
                """, (rs, row) -> new TicketView(rs.getString(1), codes.code(rs.getString(1)), rs.getString(2),
                rs.getLong(3), rs.getString(4), rs.getTimestamp(5).toInstant(), rs.getString(6), rs.getString(7),
                rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant()), userId);
    }

    @Transactional
    CheckInResult redeem(String code, String operator) {
        String hash = codes.hash(code.trim());
        List<TicketState> found = jdbc.query("SELECT id, ticket_no, status FROM st_ticket WHERE code_hash = ? FOR UPDATE",
                (rs, row) -> new TicketState(rs.getLong(1), rs.getString(2), rs.getString(3)), hash);
        if (found.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "电子票不存在");
        TicketState ticket = found.getFirst();
        Instant now = Instant.now();
        int updated = jdbc.update("""
                UPDATE st_ticket SET status = 'USED', used_at = ? WHERE id = ? AND status = 'VALID'
                """, now, ticket.id());
        String result = updated == 1 ? "SUCCESS" : switch (ticket.status()) {
            case "USED" -> "ALREADY_USED";
            case "REFUNDED" -> "REFUNDED";
            default -> "INVALID";
        };
        jdbc.update("""
                INSERT INTO st_check_in_record (ticket_id, operator_name, result, created_at)
                VALUES (?, ?, ?, ?)
                """, ticket.id(), operator, result, now);
        meters.counter("starticket.ticket.redeem", "result", result).increment();
        return new CheckInResult(ticket.ticketNo(), result, now);
    }

    private record TicketState(long id, String ticketNo, String status) {}
}

@RestController
class TicketController {

    private final TicketService tickets;

    TicketController(TicketService tickets) {
        this.tickets = tickets;
    }

    @GetMapping("/api/tickets")
    List<TicketView> list(Authentication authentication) {
        return tickets.list(authentication.getName());
    }

    @PostMapping("/api/check-in/redeem")
    CheckInResult redeem(Authentication authentication, @Valid @RequestBody CheckInRequest request) {
        return tickets.redeem(request.code(), authentication.getName());
    }
}
