package com.starticket.order;

import com.starticket.account.AccountLookup;
import com.starticket.common.ApiException;
import com.starticket.infrastructure.RedisSeatGuard;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

record RefundView(String refundNo, String orderNo, BigDecimal amount, String status,
                  Instant createdAt, Instant completedAt) {
}

@Service
class RefundService {

    private final JdbcTemplate jdbc;
    private final AccountLookup accounts;
    private final ObjectProvider<RedisSeatGuard> redisGuards;
    private final MeterRegistry meters;

    RefundService(JdbcTemplate jdbc, AccountLookup accounts, ObjectProvider<RedisSeatGuard> redisGuards,
                  MeterRegistry meters) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.redisGuards = redisGuards;
        this.meters = meters;
    }

    @Transactional
    RefundView refund(String orderNo, String username) {
        long userId = accounts.requireUserId(username);
        List<OrderRefundData> orders = jdbc.query("""
                SELECT o.id, o.total_amount, o.status, p.starts_at
                FROM st_order o JOIN st_performance p ON p.id = o.performance_id
                WHERE o.order_no = ? AND o.user_id = ? FOR UPDATE
                """, (rs, row) -> new OrderRefundData(rs.getLong(1), rs.getBigDecimal(2), rs.getString(3),
                rs.getTimestamp(4).toInstant()), orderNo, userId);
        if (orders.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "订单不存在");
        List<RefundView> existing = find(orderNo, userId);
        if (!existing.isEmpty()) return existing.getFirst();
        OrderRefundData order = orders.getFirst();
        if (!"PAID".equals(order.status()) || !order.startsAt().isAfter(Instant.now().plus(24, ChronoUnit.HOURS))) {
            throw new ApiException(HttpStatus.CONFLICT, "仅支持演出开始24小时前的已支付订单退款");
        }
        Integer usedTickets = jdbc.queryForObject("""
                SELECT COUNT(*) FROM st_ticket t JOIN st_order_item i ON i.id = t.order_item_id
                WHERE i.order_id = ? AND t.status = 'USED'
                """, Integer.class, order.id());
        if (usedTickets != null && usedTickets > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "订单包含已核销电子票，不能退款");
        }
        Instant now = Instant.now();
        jdbc.update("UPDATE st_order SET status = 'REFUNDING', updated_at = ? WHERE id = ? AND status = 'PAID'",
                now, order.id());
        String refundNo = "REF" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        jdbc.update("""
                INSERT INTO st_refund (refund_no, order_id, amount, status, created_at, completed_at)
                VALUES (?, ?, ?, 'SUCCESS', ?, ?)
                """, refundNo, order.id(), order.amount(), now, now);
        jdbc.update("""
                UPDATE st_ticket SET status = 'REFUNDED'
                WHERE order_item_id IN (SELECT id FROM st_order_item WHERE order_id = ?)
                  AND status = 'VALID'
                """, order.id());
        jdbc.update("""
                UPDATE st_performance_seat
                SET status = 'AVAILABLE', locked_order_no = NULL, version = version + 1
                WHERE id IN (SELECT performance_seat_id FROM st_order_item WHERE order_id = ?)
                  AND status = 'SOLD'
                """, order.id());
        jdbc.update("UPDATE st_order SET status = 'REFUNDED', updated_at = ? WHERE id = ?", now, order.id());
        releaseRedis(orderNo, order.id());
        meters.counter("starticket.refund.success").increment();
        return find(orderNo, userId).getFirst();
    }

    private void releaseRedis(String orderNo, long orderId) {
        RedisSeatGuard guard = redisGuards.getIfAvailable();
        if (guard == null) return;
        Long performanceId = jdbc.queryForObject("SELECT performance_id FROM st_order WHERE id = ?", Long.class, orderId);
        List<Long> seatIds = jdbc.query("SELECT seat_id FROM st_order_item WHERE order_id = ?",
                (rs, row) -> rs.getLong(1), orderId);
        guard.release(performanceId, seatIds, orderNo);
    }

    private List<RefundView> find(String orderNo, long userId) {
        return jdbc.query("""
                SELECT r.refund_no, o.order_no, r.amount, r.status, r.created_at, r.completed_at
                FROM st_refund r JOIN st_order o ON o.id = r.order_id
                WHERE o.order_no = ? AND o.user_id = ?
                FOR UPDATE
                """, (rs, row) -> new RefundView(rs.getString(1), rs.getString(2), rs.getBigDecimal(3),
                rs.getString(4), rs.getTimestamp(5).toInstant(), rs.getTimestamp(6).toInstant()), orderNo, userId);
    }

    private record OrderRefundData(long id, BigDecimal amount, String status, Instant startsAt) {}
}

@RestController
@RequestMapping("/api/orders")
class RefundController {

    private final RefundService refunds;

    RefundController(RefundService refunds) {
        this.refunds = refunds;
    }

    @PostMapping("/{orderNo}/refunds")
    RefundView refund(@PathVariable String orderNo, Authentication authentication) {
        return refunds.refund(orderNo, authentication.getName());
    }
}
