package com.starticket.payment;

import com.starticket.account.AccountLookup;
import com.starticket.common.ApiException;
import com.starticket.ticket.TicketCodeService;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

record CreatePaymentRequest(@NotBlank String orderNo) {
}

record PaymentCallbackRequest(
        @NotBlank String paymentNo,
        @NotBlank String channelTxnNo,
        @NotNull Boolean success,
        @NotBlank String signature
) {
}

record PaymentView(String paymentNo, String orderNo, BigDecimal amount, String status, String channelTxnNo,
                   Instant createdAt, Instant paidAt) {
}

@Service
class PaymentService {

    private final JdbcTemplate jdbc;
    private final AccountLookup accounts;
    private final TicketCodeService ticketCodes;
    private final MeterRegistry meters;
    private final byte[] callbackSecret;

    PaymentService(JdbcTemplate jdbc, AccountLookup accounts, TicketCodeService ticketCodes, MeterRegistry meters,
                   @Value("${app.payment.callback-secret:local-payment-callback-secret-32b}") String callbackSecret) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.ticketCodes = ticketCodes;
        this.meters = meters;
        this.callbackSecret = callbackSecret.getBytes(StandardCharsets.UTF_8);
    }

    @Transactional
    PaymentView create(String username, String orderNo) {
        long userId = accounts.requireUserId(username);
        List<OrderPaymentData> orders = jdbc.query("""
                SELECT id, total_amount, status, expires_at FROM st_order
                WHERE order_no = ? AND user_id = ? FOR UPDATE
                """, (rs, row) -> new OrderPaymentData(rs.getLong(1), rs.getBigDecimal(2), rs.getString(3),
                rs.getTimestamp(4).toInstant()), orderNo, userId);
        if (orders.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "订单不存在");
        List<PaymentView> existing = findByOrder(orderNo, userId);
        if (!existing.isEmpty()) return existing.getFirst();
        OrderPaymentData order = orders.getFirst();
        if (!"PENDING_PAYMENT".equals(order.status()) || !order.expiresAt().isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.CONFLICT, "订单当前不能支付");
        }
        String paymentNo = no("PAY");
        jdbc.update("""
                INSERT INTO st_payment (payment_no, order_id, amount, status, created_at)
                VALUES (?, ?, ?, 'PENDING', ?)
                """, paymentNo, order.id(), order.amount(), Instant.now());
        return requirePayment(paymentNo);
    }

    @Transactional
    PaymentView callback(PaymentCallbackRequest request) {
        String expected = signature(request.paymentNo(), request.channelTxnNo(), request.success());
        if (!MessageDigestSafe.equals(expected, request.signature())) {
            meters.counter("starticket.payment.callback", "result", "invalid-signature").increment();
            throw new ApiException(HttpStatus.UNAUTHORIZED, "支付回调签名无效");
        }
        PaymentView payment = requirePaymentForUpdate(request.paymentNo());
        if (!"PENDING".equals(payment.status())) {
            meters.counter("starticket.payment.callback", "result", "duplicate").increment();
            return payment;
        }
        if (!request.success()) {
            jdbc.update("UPDATE st_payment SET status = 'FAILED', channel_txn_no = ? WHERE payment_no = ? AND status = 'PENDING'",
                    request.channelTxnNo(), request.paymentNo());
            meters.counter("starticket.payment.callback", "result", "failed").increment();
            return requirePayment(request.paymentNo());
        }
        int orderUpdated = jdbc.update("""
                UPDATE st_order SET status = 'PAID', paid_at = ?, updated_at = ?
                WHERE id = (SELECT p.order_id FROM st_payment p WHERE p.payment_no = ?)
                  AND status = 'PENDING_PAYMENT' AND expires_at > ?
                """, Instant.now(), Instant.now(), request.paymentNo(), Instant.now());
        if (orderUpdated == 0) throw new ApiException(HttpStatus.CONFLICT, "订单已过期或状态已变化");
        jdbc.update("""
                UPDATE st_payment SET status = 'SUCCESS', channel_txn_no = ?, paid_at = ?
                WHERE payment_no = ? AND status = 'PENDING'
                """, request.channelTxnNo(), Instant.now(), request.paymentNo());
        jdbc.update("""
                UPDATE st_performance_seat SET status = 'SOLD', lock_expires_at = NULL, version = version + 1
                WHERE locked_order_no = (SELECT o.order_no FROM st_order o JOIN st_payment p ON p.order_id = o.id
                                         WHERE p.payment_no = ?)
                  AND status = 'LOCKED'
                """, request.paymentNo());
        issueTickets(request.paymentNo());
        meters.counter("starticket.payment.success").increment();
        meters.counter("starticket.payment.callback", "result", "success").increment();
        return requirePayment(request.paymentNo());
    }

    @Transactional
    PaymentView simulate(String username, String paymentNo) {
        long userId = accounts.requireUserId(username);
        Integer owned = jdbc.queryForObject("""
                SELECT COUNT(*) FROM st_payment p JOIN st_order o ON o.id = p.order_id
                WHERE p.payment_no = ? AND o.user_id = ?
                """, Integer.class, paymentNo, userId);
        if (owned == null || owned == 0) throw new ApiException(HttpStatus.NOT_FOUND, "支付单不存在");
        String channelTxnNo = no("MOCK");
        return callback(new PaymentCallbackRequest(paymentNo, channelTxnNo, true,
                signature(paymentNo, channelTxnNo, true)));
    }

    private void issueTickets(String paymentNo) {
        List<TicketSeed> items = jdbc.query("""
                SELECT i.id, o.user_id, o.performance_id
                FROM st_order_item i JOIN st_order o ON o.id = i.order_id
                JOIN st_payment p ON p.order_id = o.id
                WHERE p.payment_no = ?
                """, (rs, row) -> new TicketSeed(rs.getLong(1), rs.getLong(2), rs.getLong(3)), paymentNo);
        for (TicketSeed item : items) {
            String ticketNo = no("TKT");
            String codeHash = ticketCodes.hash(ticketCodes.code(ticketNo));
            jdbc.update("""
                    INSERT INTO st_ticket
                        (ticket_no, order_item_id, user_id, performance_id, code_hash, status, created_at)
                    SELECT ?, ?, ?, ?, ?, 'VALID', ?
                    WHERE NOT EXISTS (SELECT 1 FROM st_ticket WHERE order_item_id = ?)
                    """, ticketNo, item.orderItemId(), item.userId(), item.performanceId(), codeHash,
                    Instant.now(), item.orderItemId());
        }
    }

    private List<PaymentView> findByOrder(String orderNo, long userId) {
        return jdbc.query("""
                SELECT p.payment_no, o.order_no, p.amount, p.status, p.channel_txn_no, p.created_at, p.paid_at
                FROM st_payment p JOIN st_order o ON o.id = p.order_id
                WHERE o.order_no = ? AND o.user_id = ?
                FOR UPDATE
                """, PaymentService::map, orderNo, userId);
    }

    private PaymentView requirePayment(String paymentNo) {
        List<PaymentView> result = jdbc.query("""
                SELECT p.payment_no, o.order_no, p.amount, p.status, p.channel_txn_no, p.created_at, p.paid_at
                FROM st_payment p JOIN st_order o ON o.id = p.order_id WHERE p.payment_no = ?
                """, PaymentService::map, paymentNo);
        if (result.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "支付单不存在");
        return result.getFirst();
    }

    private PaymentView requirePaymentForUpdate(String paymentNo) {
        List<PaymentView> result = jdbc.query("""
                SELECT p.payment_no, o.order_no, p.amount, p.status, p.channel_txn_no, p.created_at, p.paid_at
                FROM st_payment p JOIN st_order o ON o.id = p.order_id
                WHERE p.payment_no = ? FOR UPDATE
                """, PaymentService::map, paymentNo);
        if (result.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "支付单不存在");
        return result.getFirst();
    }

    private static PaymentView map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new PaymentView(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getString(4),
                rs.getString(5), rs.getTimestamp(6).toInstant(),
                rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant());
    }

    private String signature(String paymentNo, String channelTxnNo, boolean success) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(callbackSecret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(
                    (paymentNo + "|" + channelTxnNo + "|" + success).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String no(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    private record OrderPaymentData(long id, BigDecimal amount, String status, Instant expiresAt) {}
    private record TicketSeed(long orderItemId, long userId, long performanceId) {}

    private static final class MessageDigestSafe {
        static boolean equals(String left, String right) {
            return java.security.MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),
                    right.getBytes(StandardCharsets.UTF_8));
        }
    }
}

@RestController
@RequestMapping("/api/payments")
class PaymentController {

    private final PaymentService payments;

    PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @PostMapping
    PaymentView create(Authentication authentication, @Valid @RequestBody CreatePaymentRequest request) {
        return payments.create(authentication.getName(), request.orderNo());
    }

    @PostMapping("/callback")
    PaymentView callback(@Valid @RequestBody PaymentCallbackRequest request) {
        return payments.callback(request);
    }

    @PostMapping("/{paymentNo}/simulate-success")
    PaymentView simulate(Authentication authentication, @org.springframework.web.bind.annotation.PathVariable String paymentNo) {
        return payments.simulate(authentication.getName(), paymentNo);
    }
}
