package com.starticket.order;

import com.starticket.account.AccountLookup;
import com.starticket.common.ApiException;
import com.starticket.common.PageResult;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

record SalesBreakdownView(long performanceId, String performanceName, long tierId, String tierName,
                          long capacity, long soldTickets, long refundedTickets, BigDecimal netRevenue) {
}

record SalesSummaryView(long eventId, String eventTitle, long totalOrders, long pendingOrders, long paidOrders,
                        long refundedOrders, long soldTickets, long refundedTickets, BigDecimal grossRevenue,
                        BigDecimal refundAmount, BigDecimal netRevenue, List<SalesBreakdownView> breakdown) {
}

record OrderSummaryView(String orderNo, String username, long eventId, String eventTitle, long performanceId,
                        String performanceName, BigDecimal totalAmount, String status, long itemCount,
                        Instant expiresAt, Instant paidAt, Instant createdAt) {
}

@Service
class OrderOperationsService {

    private final JdbcTemplate jdbc;
    private final AccountLookup accounts;

    OrderOperationsService(JdbcTemplate jdbc, AccountLookup accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    SalesSummaryView sales(String username, long eventId) {
        long organizerId = accounts.requireUserId(username);
        String eventTitle = requireOwnedEvent(organizerId, eventId);
        OrderAggregate orders = jdbc.queryForObject("""
                SELECT COUNT(o.id),
                       COALESCE(SUM(CASE WHEN o.status = 'PENDING_PAYMENT' THEN 1 ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN o.status = 'PAID' THEN 1 ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN o.status = 'REFUNDED' THEN 1 ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN o.status IN ('PAID', 'REFUNDING', 'REFUNDED')
                                         THEN o.total_amount ELSE 0 END), 0)
                FROM st_order o JOIN st_performance p ON p.id = o.performance_id
                WHERE p.event_id = ?
                """, (rs, row) -> new OrderAggregate(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                rs.getLong(4), rs.getBigDecimal(5)), eventId);
        TicketAggregate tickets = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN o.status IN ('PAID', 'REFUNDING') THEN 1 ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN o.status = 'REFUNDED' THEN 1 ELSE 0 END), 0)
                FROM st_order_item i JOIN st_order o ON o.id = i.order_id
                JOIN st_performance p ON p.id = o.performance_id WHERE p.event_id = ?
                """, (rs, row) -> new TicketAggregate(rs.getLong(1), rs.getLong(2)), eventId);
        BigDecimal refundAmount = jdbc.queryForObject("""
                SELECT COALESCE(SUM(r.amount), 0) FROM st_refund r
                JOIN st_order o ON o.id = r.order_id JOIN st_performance p ON p.id = o.performance_id
                WHERE p.event_id = ? AND r.status = 'SUCCESS'
                """, BigDecimal.class, eventId);
        List<SalesBreakdownView> breakdown = jdbc.query("""
                SELECT p.id, p.name, t.id, t.name,
                       (SELECT COUNT(*) FROM st_seat s WHERE s.area_id = t.area_id AND s.enabled = TRUE),
                       (SELECT COUNT(*) FROM st_order_item i JOIN st_order o ON o.id = i.order_id
                         WHERE i.ticket_tier_id = t.id AND o.status IN ('PAID', 'REFUNDING')),
                       (SELECT COUNT(*) FROM st_order_item i JOIN st_order o ON o.id = i.order_id
                         WHERE i.ticket_tier_id = t.id AND o.status = 'REFUNDED'),
                       COALESCE((SELECT SUM(i.price) FROM st_order_item i JOIN st_order o ON o.id = i.order_id
                         WHERE i.ticket_tier_id = t.id AND o.status IN ('PAID', 'REFUNDING')), 0)
                FROM st_performance p JOIN st_ticket_tier t ON t.performance_id = p.id
                WHERE p.event_id = ? ORDER BY p.starts_at, t.price
                """, (rs, row) -> new SalesBreakdownView(rs.getLong(1), rs.getString(2), rs.getLong(3),
                rs.getString(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getBigDecimal(8)), eventId);
        BigDecimal refunds = refundAmount == null ? BigDecimal.ZERO : refundAmount;
        return new SalesSummaryView(eventId, eventTitle, orders.total(), orders.pending(), orders.paid(),
                orders.refunded(), tickets.sold(), tickets.refunded(), orders.gross(), refunds,
                orders.gross().subtract(refunds), breakdown);
    }

    @Transactional(readOnly = true)
    PageResult<OrderSummaryView> organizerOrders(String username, long eventId, String keyword, String status,
                                                  int page, int size) {
        long organizerId = accounts.requireUserId(username);
        requireOwnedEvent(organizerId, eventId);
        return orderPage(organizerId, eventId, keyword, status, page, size);
    }

    @Transactional(readOnly = true)
    PageResult<OrderSummaryView> adminOrders(Long eventId, String keyword, String status, int page, int size) {
        return orderPage(null, eventId, keyword, status, page, size);
    }

    private PageResult<OrderSummaryView> orderPage(Long organizerId, Long eventId, String keyword, String status,
                                                    int page, int size) {
        String normalizedStatus = OrderService.normalizeStatus(status);
        StringBuilder where = new StringBuilder("""
                FROM st_order o JOIN st_user u ON u.id = o.user_id
                JOIN st_performance p ON p.id = o.performance_id
                JOIN st_event e ON e.id = p.event_id WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (organizerId != null) {
            where.append(" AND e.organizer_id = ?");
            args.add(organizerId);
        }
        if (eventId != null) {
            where.append(" AND e.id = ?");
            args.add(eventId);
        }
        if (normalizedStatus != null) {
            where.append(" AND o.status = ?");
            args.add(normalizedStatus);
        }
        String cleanKeyword = keyword == null ? "" : keyword.trim().toLowerCase();
        if (!cleanKeyword.isEmpty()) {
            where.append(" AND (LOWER(o.order_no) LIKE ? OR LOWER(u.username) LIKE ? OR LOWER(e.title) LIKE ?)");
            String like = "%" + cleanKeyword + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) " + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(page * size);
        List<OrderSummaryView> content = jdbc.query("""
                        SELECT o.order_no, u.username, e.id, e.title, p.id, p.name, o.total_amount, o.status,
                               (SELECT COUNT(*) FROM st_order_item i WHERE i.order_id = o.id),
                               o.expires_at, o.paid_at, o.created_at
                        """ + where + " ORDER BY o.created_at DESC LIMIT ? OFFSET ?",
                (rs, row) -> new OrderSummaryView(rs.getString(1), rs.getString(2), rs.getLong(3),
                        rs.getString(4), rs.getLong(5), rs.getString(6), rs.getBigDecimal(7), rs.getString(8),
                        rs.getLong(9), rs.getTimestamp(10).toInstant(),
                        rs.getTimestamp(11) == null ? null : rs.getTimestamp(11).toInstant(),
                        rs.getTimestamp(12).toInstant()), pageArgs.toArray());
        return PageResult.of(content, page, size, total == null ? 0 : total);
    }

    private String requireOwnedEvent(long organizerId, long eventId) {
        List<String> titles = jdbc.query("SELECT title FROM st_event WHERE id = ? AND organizer_id = ?",
                (rs, row) -> rs.getString(1), eventId, organizerId);
        if (titles.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "活动不存在");
        return titles.getFirst();
    }

    private record OrderAggregate(long total, long pending, long paid, long refunded, BigDecimal gross) {
    }

    private record TicketAggregate(long sold, long refunded) {
    }
}

@RestController
@RequestMapping("/api/organizer/events/{eventId}")
@Validated
class OrganizerOrderController {

    private final OrderOperationsService operations;

    OrganizerOrderController(OrderOperationsService operations) {
        this.operations = operations;
    }

    @GetMapping("/sales-summary")
    SalesSummaryView sales(@PathVariable long eventId, Authentication authentication) {
        return operations.sales(authentication.getName(), eventId);
    }

    @GetMapping("/orders")
    PageResult<OrderSummaryView> orders(@PathVariable long eventId, Authentication authentication,
                                        @RequestParam(defaultValue = "") @Size(max = 100) String keyword,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(defaultValue = "0") @Min(0) int page,
                                        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return operations.organizerOrders(authentication.getName(), eventId, keyword, status, page, size);
    }
}

@RestController
@RequestMapping("/api/admin/orders")
@Validated
class AdminOrderController {

    private final OrderOperationsService operations;

    AdminOrderController(OrderOperationsService operations) {
        this.operations = operations;
    }

    @GetMapping
    PageResult<OrderSummaryView> orders(@RequestParam(required = false) Long eventId,
                                        @RequestParam(defaultValue = "") @Size(max = 100) String keyword,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(defaultValue = "0") @Min(0) int page,
                                        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return operations.adminOrders(eventId, keyword, status, page, size);
    }
}
