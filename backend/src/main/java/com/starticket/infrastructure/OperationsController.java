package com.starticket.infrastructure;

import com.starticket.common.ApiException;
import com.starticket.common.PageResult;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

record DeadOutboxView(long id, String eventType, String aggregateId, int retryCount,
                      String lastError, Instant createdAt) {
}

record DeadMessageView(long id, String messageId, String eventType, String aggregateId,
                       String failureReason, String status, Instant failedAt, Instant replayedAt) {
}

record AuditLogView(long id, String actor, String action, String targetType, String targetId,
                    String detail, Instant createdAt) {
}

@RestController
@RequestMapping("/api/admin/outbox")
class OperationsController {

    private final JdbcTemplate jdbc;
    private final AuditLogService audits;
    private final boolean publicDemo;

    OperationsController(JdbcTemplate jdbc, AuditLogService audits,
                         @Value("${app.demo.public:false}") boolean publicDemo) {
        this.jdbc = jdbc;
        this.audits = audits;
        this.publicDemo = publicDemo;
    }

    @GetMapping("/dead")
    List<DeadOutboxView> dead() {
        return jdbc.query("""
                SELECT id, event_type, aggregate_id, retry_count, last_error, created_at
                FROM st_outbox_event WHERE status = 'DEAD' ORDER BY created_at DESC
                """, (rs, row) -> new DeadOutboxView(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getInt(4), rs.getString(5), rs.getTimestamp(6).toInstant()));
    }

    @PostMapping("/{id}/retry")
    void retry(@PathVariable long id, Authentication authentication) {
        if (publicDemo) throw new ApiException(HttpStatus.FORBIDDEN, "公开演示环境禁止重试 Outbox");
        int updated = jdbc.update("""
                UPDATE st_outbox_event
                SET status = 'PENDING', retry_count = 0, next_retry_at = ?, last_error = NULL,
                    locked_by = NULL, locked_at = NULL
                WHERE id = ? AND status = 'DEAD'
                """, Instant.now(), id);
        if (updated == 0) throw new ApiException(HttpStatus.CONFLICT, "该 Outbox 事件当前不能重试");
        audits.record(authentication.getName(), "OUTBOX_RETRY", "OUTBOX", id, "发布失败事件重新入队");
    }
}

@Service
class DeadMessageService {

    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final AuditLogService audits;

    DeadMessageService(JdbcTemplate jdbc, RabbitTemplate rabbit, AuditLogService audits) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        this.audits = audits;
    }

    List<DeadMessageView> dead() {
        jdbc.update("""
                UPDATE st_failed_message SET status = 'DEAD', replayed_at = NULL
                WHERE status = 'PROCESSING' AND replayed_at < ?
                """, Instant.now().minus(1, ChronoUnit.MINUTES));
        return jdbc.query("""
                SELECT id, message_id, event_type, aggregate_id, failure_reason, status, failed_at, replayed_at
                FROM st_failed_message WHERE status = 'DEAD' ORDER BY failed_at DESC
                """, (rs, row) -> new DeadMessageView(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getTimestamp(7).toInstant(),
                rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant()));
    }

    void replay(long id, String actor) {
        List<FailedPayload> rows = jdbc.query("""
                SELECT event_type, aggregate_id, payload FROM st_failed_message
                WHERE id = ? AND status = 'DEAD'
                """, (rs, row) -> new FailedPayload(rs.getString(1), rs.getString(2), rs.getString(3)), id);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.CONFLICT, "该消费死信当前不能重放");
        FailedPayload row = rows.getFirst();
        int claimed = jdbc.update("""
                UPDATE st_failed_message SET status = 'PROCESSING', replayed_at = ?
                WHERE id = ? AND status = 'DEAD'
                """, Instant.now(), id);
        if (claimed == 0) throw new ApiException(HttpStatus.CONFLICT, "该消费死信正在处理");
        try {
            var message = MessageBuilder.withBody(row.payload().getBytes(StandardCharsets.UTF_8))
                    .setContentType("text/plain").setMessageId("replay-" + id + "-" + Instant.now().toEpochMilli())
                    .setHeader("eventType", row.eventType()).setHeader("aggregateId", row.aggregateId()).build();
            Boolean confirmed = rabbit.invoke(operations -> {
                operations.send(MessagingNames.CLOSE_EXCHANGE, "close", message);
                return operations.waitForConfirms(5000);
            });
            if (!Boolean.TRUE.equals(confirmed)) throw new IllegalStateException("RabbitMQ 未确认重放消息");
            jdbc.update("UPDATE st_failed_message SET status = 'REPLAYED', replayed_at = ? WHERE id = ?",
                    Instant.now(), id);
            audits.record(actor, "RABBIT_DEAD_REPLAY", "FAILED_MESSAGE", id,
                    row.eventType() + ":" + row.aggregateId());
        } catch (RuntimeException exception) {
            jdbc.update("UPDATE st_failed_message SET status = 'DEAD', replayed_at = NULL WHERE id = ?", id);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "RabbitMQ 重放失败，请稍后重试");
        }
    }

    private record FailedPayload(String eventType, String aggregateId, String payload) {
    }
}

@RestController
@RequestMapping("/api/admin/messages")
class DeadMessageController {

    private final DeadMessageService messages;
    private final boolean publicDemo;

    DeadMessageController(DeadMessageService messages,
                          @Value("${app.demo.public:false}") boolean publicDemo) {
        this.messages = messages;
        this.publicDemo = publicDemo;
    }

    @GetMapping("/dead")
    List<DeadMessageView> dead() {
        return messages.dead();
    }

    @PostMapping("/dead/{id}/retry")
    void replay(@PathVariable long id, Authentication authentication) {
        if (publicDemo) throw new ApiException(HttpStatus.FORBIDDEN, "公开演示环境禁止重放死信");
        messages.replay(id, authentication.getName());
    }
}

@RestController
@RequestMapping("/api/admin/audits")
@Validated
class AuditLogController {

    private final JdbcTemplate jdbc;

    AuditLogController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    PageResult<AuditLogView> list(@RequestParam(defaultValue = "0") @Min(0) int page,
                                  @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM st_audit_log", Long.class);
        List<AuditLogView> content = jdbc.query("""
                SELECT id, actor, action, target_type, target_id, detail, created_at
                FROM st_audit_log ORDER BY created_at DESC LIMIT ? OFFSET ?
                """, (rs, row) -> new AuditLogView(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getTimestamp(7).toInstant()),
                size, page * size);
        return PageResult.of(content, page, size, total == null ? 0 : total);
    }
}
