package com.starticket.order;

import com.starticket.account.AccountLookup;
import com.starticket.common.ApiException;
import com.starticket.infrastructure.RedisSeatGuard;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class OrderService {

    private final JdbcTemplate jdbc;
    private final AccountLookup accounts;
    private final MeterRegistry meters;
    private final ObjectProvider<RedisSeatGuard> redisGuards;
    private final Object[] idempotencyLocks = new Object[256];

    OrderService(JdbcTemplate jdbc, AccountLookup accounts, MeterRegistry meters,
                 ObjectProvider<RedisSeatGuard> redisGuards) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.meters = meters;
        this.redisGuards = redisGuards;
        for (int i = 0; i < idempotencyLocks.length; i++) idempotencyLocks[i] = new Object();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderView create(String username, String idempotencyKey, CreateOrderRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Idempotency-Key 必填且不能超过100个字符");
        }
        List<Long> seatIds = request.seatIds().stream().distinct().sorted().toList();
        if (seatIds.size() != request.seatIds().size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "座位不能重复选择");
        }
        long userId = accounts.requireUserId(username);
        RedisSeatGuard guard = redisGuards.getIfAvailable();
        if (guard != null && !guard.allowOrder(userId)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "下单过于频繁，请稍后再试");
        }
        String lockKey = userId + ":" + idempotencyKey;
        String proposedOrderNo = "ST" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        long started = System.nanoTime();
        // ponytail: 固定分片锁避免每个幂等键常驻内存；多实例由用户行锁和数据库唯一键兜底。
        try {
            Object idempotencyLock = idempotencyLocks[Math.floorMod(lockKey.hashCode(), idempotencyLocks.length)];
            synchronized (idempotencyLock) {
                try {
                    return createLocked(userId, idempotencyKey.trim(), request.performanceId(), seatIds,
                            proposedOrderNo, guard);
                } catch (RuntimeException exception) {
                    if (guard != null) guard.release(request.performanceId(), seatIds, proposedOrderNo);
                    throw exception;
                }
            }
        } catch (RuntimeException exception) {
            meters.counter("starticket.order.failed", "exception", exception.getClass().getSimpleName()).increment();
            throw exception;
        } finally {
            meters.timer("starticket.order.duration").record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private OrderView createLocked(long userId, String key, long performanceId, List<Long> seatIds,
                                   String proposedOrderNo, RedisSeatGuard guard) {
        // 同一用户的下单事务串行化，防止并发拆单绕过场次和票档限购。
        jdbc.queryForObject("SELECT id FROM st_user WHERE id = ? FOR UPDATE", Long.class, userId);
        String requestHash = sha256(performanceId + ":" + seatIds);
        List<Map<String, Object>> existing = jdbc.queryForList("""
                SELECT request_hash, order_no FROM st_idempotency_record
                WHERE user_id = ? AND idempotency_key = ?
                """, userId, key);
        if (!existing.isEmpty()) {
            if (!requestHash.equals(existing.getFirst().get("request_hash"))) {
                throw new ApiException(HttpStatus.CONFLICT, "相同幂等键不能用于不同请求");
            }
            return requireOrder(existing.getFirst().get("order_no").toString(), userId, true);
        }

        validateSale(performanceId);
        validatePurchaseLimits(userId, performanceId, seatIds);
        String orderNo = proposedOrderNo;
        Instant expiresAt = Instant.now().plus(10, ChronoUnit.MINUTES);
        if (guard != null && !guard.acquire(performanceId, seatIds, orderNo, Duration.ofMinutes(10))) {
            throw new ApiException(HttpStatus.CONFLICT, "所选座位正在被其他用户处理");
        }
        jdbc.update("""
                INSERT INTO st_idempotency_record
                    (user_id, idempotency_key, request_hash, order_no, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, userId, key, requestHash, orderNo, Instant.now());

        for (Long seatId : seatIds) {
            int updated = jdbc.update("""
                    UPDATE st_performance_seat
                    SET status = 'LOCKED', locked_order_no = ?, lock_expires_at = ?, version = version + 1
                    WHERE performance_id = ? AND seat_id = ? AND status = 'AVAILABLE'
                    """, orderNo, expiresAt, performanceId, seatId);
            if (updated != 1) {
                meters.counter("starticket.order.lock.failed").increment();
                throw new ApiException(HttpStatus.CONFLICT, "所选座位已被占用，请刷新座位图");
            }
        }

        List<LockedSeat> locked = jdbc.query("""
                SELECT ps.id, ps.seat_id, s.code, ps.ticket_tier_id, t.name, ps.price
                FROM st_performance_seat ps
                JOIN st_seat s ON s.id = ps.seat_id
                JOIN st_ticket_tier t ON t.id = ps.ticket_tier_id
                WHERE ps.locked_order_no = ? ORDER BY ps.seat_id
                """, (rs, row) -> new LockedSeat(rs.getLong(1), rs.getLong(2), rs.getString(3),
                rs.getLong(4), rs.getString(5), rs.getBigDecimal(6)), orderNo);
        if (locked.size() != seatIds.size()) {
            throw new ApiException(HttpStatus.CONFLICT, "锁座结果不完整");
        }
        BigDecimal total = locked.stream().map(LockedSeat::price).reduce(BigDecimal.ZERO, BigDecimal::add);
        Instant now = Instant.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO st_order
                        (order_no, user_id, performance_id, total_amount, status, expires_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'PENDING_PAYMENT', ?, ?, ?)
                    """, new String[]{"id"});
            statement.setString(1, orderNo);
            statement.setLong(2, userId);
            statement.setLong(3, performanceId);
            statement.setBigDecimal(4, total);
            statement.setObject(5, expiresAt);
            statement.setObject(6, now);
            statement.setObject(7, now);
            return statement;
        }, keyHolder);
        long orderId = keyHolder.getKey().longValue();
        locked.forEach(seat -> jdbc.update("""
                INSERT INTO st_order_item
                    (order_id, performance_seat_id, seat_id, seat_code, ticket_tier_id, tier_name, price)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, orderId, seat.inventoryId(), seat.seatId(), seat.seatCode(), seat.tierId(), seat.tierName(), seat.price()));
        jdbc.update("""
                INSERT INTO st_outbox_event
                    (event_type, aggregate_id, payload, status, retry_count, next_retry_at, created_at)
                VALUES ('ORDER_CREATED', ?, ?, 'PENDING', 0, ?, ?)
                """, orderNo, orderNo, now, now);
        meters.counter("starticket.order.created").increment();
        return requireOrder(orderNo, userId);
    }

    @Transactional(readOnly = true)
    public List<OrderView> list(String username) {
        long userId = accounts.requireUserId(username);
        return jdbc.query("SELECT order_no FROM st_order WHERE user_id = ? ORDER BY created_at DESC",
                (rs, row) -> rs.getString(1), userId).stream().map(orderNo -> requireOrder(orderNo, userId)).toList();
    }

    @Transactional(readOnly = true)
    public OrderView get(String orderNo, String username) {
        return requireOrder(orderNo, accounts.requireUserId(username));
    }

    @Transactional
    public OrderView cancel(String orderNo, String username) {
        long userId = accounts.requireUserId(username);
        requireOrder(orderNo, userId);
        int updated = jdbc.update("""
                UPDATE st_order SET status = 'CANCELLED', updated_at = ?
                WHERE order_no = ? AND user_id = ? AND status = 'PENDING_PAYMENT'
                """, Instant.now(), orderNo, userId);
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "当前订单不能取消");
        }
        releaseSeats(orderNo);
        releaseRedis(orderNo);
        return requireOrder(orderNo, userId);
    }

    @Transactional
    public boolean expire(String orderNo) {
        int updated = jdbc.update("""
                UPDATE st_order SET status = 'EXPIRED', updated_at = ?
                WHERE order_no = ? AND status = 'PENDING_PAYMENT' AND expires_at <= ?
                """, Instant.now(), orderNo, Instant.now());
        if (updated == 1) {
            releaseSeats(orderNo);
            releaseRedis(orderNo);
            meters.counter("starticket.order.expired").increment();
            return true;
        }
        return false;
    }

    private void releaseSeats(String orderNo) {
        jdbc.update("""
                UPDATE st_performance_seat
                SET status = 'AVAILABLE', locked_order_no = NULL, lock_expires_at = NULL, version = version + 1
                WHERE locked_order_no = ? AND status = 'LOCKED'
                """, orderNo);
    }

    private void releaseRedis(String orderNo) {
        RedisSeatGuard guard = redisGuards.getIfAvailable();
        if (guard == null) return;
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT o.performance_id, i.seat_id FROM st_order o
                JOIN st_order_item i ON i.order_id = o.id WHERE o.order_no = ?
                """, orderNo);
        if (rows.isEmpty()) return;
        long performanceId = ((Number) rows.getFirst().get("performance_id")).longValue();
        List<Long> seatIds = rows.stream().map(row -> ((Number) row.get("seat_id")).longValue()).toList();
        guard.release(performanceId, seatIds, orderNo);
    }

    private void validateSale(long performanceId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM st_performance p
                JOIN st_event e ON e.id = p.event_id
                WHERE p.id = ? AND p.status = 'SCHEDULED'
                  AND e.status IN ('APPROVED', 'ON_SALE')
                  AND p.sales_start_at <= ? AND p.sales_end_at > ? AND p.starts_at > ?
                """, Integer.class, performanceId, Instant.now(), Instant.now(), Instant.now());
        if (count == null || count == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "当前场次不在售票时间内");
        }
    }

    private void validatePurchaseLimits(long userId, long performanceId, List<Long> seatIds) {
        String placeholders = String.join(",", java.util.Collections.nCopies(seatIds.size(), "?"));
        Object[] selectedArgs = new Object[seatIds.size() + 1];
        selectedArgs[0] = performanceId;
        for (int i = 0; i < seatIds.size(); i++) selectedArgs[i + 1] = seatIds.get(i);
        List<TierSelection> selections = jdbc.query("""
                SELECT ps.ticket_tier_id, t.name, t.purchase_limit, COUNT(*)
                FROM st_performance_seat ps JOIN st_ticket_tier t ON t.id = ps.ticket_tier_id
                WHERE ps.performance_id = ? AND ps.seat_id IN (%s)
                GROUP BY ps.ticket_tier_id, t.name, t.purchase_limit
                """.formatted(placeholders), (rs, row) -> new TierSelection(
                rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getInt(4)), selectedArgs);
        if (selections.stream().mapToInt(TierSelection::selected).sum() != seatIds.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "所选座位不属于当前场次");
        }

        Integer activeInPerformance = jdbc.queryForObject("""
                SELECT COUNT(*) FROM st_order_item i JOIN st_order o ON o.id = i.order_id
                WHERE o.user_id = ? AND o.performance_id = ?
                  AND o.status IN ('PENDING_PAYMENT', 'PAID', 'REFUNDING')
                """, Integer.class, userId, performanceId);
        if ((activeInPerformance == null ? 0 : activeInPerformance) + seatIds.size() > 6) {
            throw new ApiException(HttpStatus.CONFLICT, "同一用户每场最多持有6张有效票");
        }

        for (TierSelection selection : selections) {
            Integer activeInTier = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM st_order_item i JOIN st_order o ON o.id = i.order_id
                    WHERE o.user_id = ? AND o.performance_id = ? AND i.ticket_tier_id = ?
                      AND o.status IN ('PENDING_PAYMENT', 'PAID', 'REFUNDING')
                    """, Integer.class, userId, performanceId, selection.tierId());
            if ((activeInTier == null ? 0 : activeInTier) + selection.selected() > selection.limit()) {
                throw new ApiException(HttpStatus.CONFLICT,
                        selection.name() + "每个账号限购" + selection.limit() + "张");
            }
        }
    }

    private OrderView requireOrder(String orderNo, long userId) {
        return requireOrder(orderNo, userId, false);
    }

    private OrderView requireOrder(String orderNo, long userId, boolean lockingRead) {
        List<OrderView> result = jdbc.query("""
                SELECT order_no, performance_id, total_amount, status, expires_at, paid_at, created_at
                FROM st_order WHERE order_no = ? AND user_id = ?
                """ + (lockingRead ? " FOR UPDATE" : ""), (rs, row) -> new OrderView(rs.getString(1), rs.getLong(2), rs.getBigDecimal(3),
                rs.getString(4), rs.getTimestamp(5).toInstant(),
                rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(),
                rs.getTimestamp(7).toInstant(), orderItems(orderNo, lockingRead)), orderNo, userId);
        if (result.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "订单不存在");
        }
        return result.getFirst();
    }

    private List<OrderItemView> orderItems(String orderNo) {
        return orderItems(orderNo, false);
    }

    private List<OrderItemView> orderItems(String orderNo, boolean lockingRead) {
        return jdbc.query("""
                SELECT i.id, i.seat_id, i.seat_code, i.tier_name, i.price
                FROM st_order_item i JOIN st_order o ON o.id = i.order_id
                WHERE o.order_no = ? ORDER BY i.id
                """ + (lockingRead ? " FOR UPDATE" : ""), (rs, row) -> new OrderItemView(rs.getLong(1), rs.getLong(2), rs.getString(3),
                rs.getString(4), rs.getBigDecimal(5)), orderNo);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record LockedSeat(long inventoryId, long seatId, String seatCode, long tierId,
                              String tierName, BigDecimal price) {
    }

    private record TierSelection(long tierId, String name, int limit, int selected) {
    }
}
