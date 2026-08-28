package com.starticket.infrastructure;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.infrastructure.enabled", havingValue = "true")
class InfrastructureMetrics {

    InfrastructureMetrics(MeterRegistry registry, JdbcTemplate jdbc, RabbitTemplate rabbit) {
        Gauge.builder("starticket.outbox.pending", () -> count(jdbc, "PENDING"))
                .description("待发布 Outbox 消息数").register(registry);
        Gauge.builder("starticket.outbox.dead", () -> count(jdbc, "DEAD"))
                .description("死亡 Outbox 消息数").register(registry);
        Gauge.builder("starticket.outbox.processing", () -> count(jdbc, "PROCESSING"))
                .description("正在发布的 Outbox 消息数").register(registry);
        Gauge.builder("starticket.outbox.retrying", () -> retrying(jdbc))
                .description("已失败但仍在重试的 Outbox 消息数").register(registry);
        Gauge.builder("starticket.rabbit.close.queue", () -> queueDepth(rabbit, MessagingNames.CLOSE_QUEUE))
                .description("待处理关单消息数").register(registry);
        Gauge.builder("starticket.rabbit.failed.queue", () -> queueDepth(rabbit, MessagingNames.FAILED_QUEUE))
                .description("RabbitMQ 失败队列消息数").register(registry);
        Gauge.builder("starticket.rabbit.dead.persisted", () -> persistedDead(jdbc))
                .description("已持久化且待重放的消费死信数").register(registry);
    }

    private static double count(JdbcTemplate jdbc, String status) {
        try {
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM st_outbox_event WHERE status = ?", Long.class, status);
            return count == null ? 0 : count;
        } catch (RuntimeException unavailable) {
            return Double.NaN;
        }
    }

    private static double queueDepth(RabbitTemplate rabbit, String queue) {
        try {
            Long count = rabbit.execute(channel -> channel.messageCount(queue));
            return count == null ? 0 : count;
        } catch (RuntimeException unavailable) {
            return Double.NaN;
        }
    }

    private static double retrying(JdbcTemplate jdbc) {
        try {
            Long count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM st_outbox_event WHERE status = 'PENDING' AND retry_count > 0
                    """, Long.class);
            return count == null ? 0 : count;
        } catch (RuntimeException unavailable) {
            return Double.NaN;
        }
    }

    private static double persistedDead(JdbcTemplate jdbc) {
        try {
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM st_failed_message WHERE status = 'DEAD'", Long.class);
            return count == null ? 0 : count;
        } catch (RuntimeException unavailable) {
            return Double.NaN;
        }
    }
}
