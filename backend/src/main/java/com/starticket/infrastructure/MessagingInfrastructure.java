package com.starticket.infrastructure;

import com.starticket.order.OrderService;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

final class MessagingNames {
    static final String DELAY_EXCHANGE = "starticket.order.delay.exchange";
    static final String DELAY_QUEUE = "starticket.order.delay.queue";
    static final String CLOSE_EXCHANGE = "starticket.order.close.exchange";
    static final String CLOSE_QUEUE = "starticket.order.close.queue";
    static final String FAILED_EXCHANGE = "starticket.order.failed.exchange";
    static final String FAILED_QUEUE = "starticket.order.failed.queue";
    private MessagingNames() {}
}

@org.springframework.context.annotation.Configuration
@ConditionalOnProperty(name = "app.infrastructure.enabled", havingValue = "true")
class MessagingConfiguration {

    @org.springframework.context.annotation.Bean
    DirectExchange delayExchange() { return new DirectExchange(MessagingNames.DELAY_EXCHANGE, true, false); }

    @org.springframework.context.annotation.Bean
    DirectExchange closeExchange() { return new DirectExchange(MessagingNames.CLOSE_EXCHANGE, true, false); }

    @org.springframework.context.annotation.Bean
    DirectExchange failedExchange() { return new DirectExchange(MessagingNames.FAILED_EXCHANGE, true, false); }

    @org.springframework.context.annotation.Bean
    Queue delayQueue() {
        return QueueBuilder.durable(MessagingNames.DELAY_QUEUE)
                .deadLetterExchange(MessagingNames.CLOSE_EXCHANGE).deadLetterRoutingKey("close").build();
    }

    @org.springframework.context.annotation.Bean
    Queue closeQueue() {
        return QueueBuilder.durable(MessagingNames.CLOSE_QUEUE)
                .deadLetterExchange(MessagingNames.FAILED_EXCHANGE).deadLetterRoutingKey("failed").build();
    }

    @org.springframework.context.annotation.Bean
    Queue failedQueue() { return QueueBuilder.durable(MessagingNames.FAILED_QUEUE).build(); }

    @org.springframework.context.annotation.Bean
    Binding delayBinding(Queue delayQueue, DirectExchange delayExchange) {
        return BindingBuilder.bind(delayQueue).to(delayExchange).with("delay");
    }

    @org.springframework.context.annotation.Bean
    Binding closeBinding(Queue closeQueue, DirectExchange closeExchange) {
        return BindingBuilder.bind(closeQueue).to(closeExchange).with("close");
    }

    @org.springframework.context.annotation.Bean
    Binding failedBinding(Queue failedQueue, DirectExchange failedExchange) {
        return BindingBuilder.bind(failedQueue).to(failedExchange).with("failed");
    }
}

@Component
@ConditionalOnProperty(name = "app.infrastructure.enabled", havingValue = "true")
class OutboxPublisher {

    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;

    OutboxPublisher(JdbcTemplate jdbc, RabbitTemplate rabbit) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
    }

    @Scheduled(fixedDelay = 1000)
    void publish() {
        List<OutboxRow> rows = jdbc.query("""
                SELECT id, aggregate_id, payload, retry_count FROM st_outbox_event
                WHERE status = 'PENDING' AND next_retry_at <= ? ORDER BY id LIMIT 20
                """, (rs, row) -> new OutboxRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4)),
                Instant.now());
        rows.forEach(this::publishOne);
    }

    private void publishOne(OutboxRow row) {
        try {
            Message message = MessageBuilder.withBody(row.payload().getBytes(StandardCharsets.UTF_8))
                    .setContentType("text/plain").setExpiration("600000").setMessageId(String.valueOf(row.id())).build();
            Boolean confirmed = rabbit.invoke(operations -> {
                operations.send(MessagingNames.DELAY_EXCHANGE, "delay", message);
                return operations.waitForConfirms(5000);
            });
            if (!Boolean.TRUE.equals(confirmed)) throw new IllegalStateException("RabbitMQ 未确认消息");
            jdbc.update("""
                    UPDATE st_outbox_event SET status = 'PUBLISHED', published_at = ?, last_error = NULL
                    WHERE id = ? AND status = 'PENDING'
                    """, Instant.now(), row.id());
        } catch (Exception exception) {
            int retries = row.retryCount() + 1;
            jdbc.update("""
                    UPDATE st_outbox_event SET status = ?, retry_count = ?, next_retry_at = ?, last_error = ?
                    WHERE id = ?
                    """, retries >= 5 ? "DEAD" : "PENDING", retries,
                    Instant.now().plus(Math.min(1L << retries, 60), ChronoUnit.SECONDS),
                    exception.getMessage() == null ? exception.getClass().getSimpleName()
                            : exception.getMessage().substring(0, Math.min(500, exception.getMessage().length())),
                    row.id());
        }
    }

    private record OutboxRow(long id, String aggregateId, String payload, int retryCount) {}
}

@Component
@ConditionalOnProperty(name = "app.infrastructure.enabled", havingValue = "true")
class OrderExpiryListener {

    private final OrderService orders;

    OrderExpiryListener(OrderService orders) { this.orders = orders; }

    @RabbitListener(queues = MessagingNames.CLOSE_QUEUE)
    void close(String orderNo) { orders.expire(orderNo); }
}

@Component
class OrderExpiryCompensator {

    private final JdbcTemplate jdbc;
    private final OrderService orders;

    OrderExpiryCompensator(JdbcTemplate jdbc, OrderService orders) {
        this.jdbc = jdbc;
        this.orders = orders;
    }

    @Scheduled(fixedDelay = 60000)
    void compensate() {
        jdbc.query("""
                SELECT order_no FROM st_order WHERE status = 'PENDING_PAYMENT' AND expires_at <= ? LIMIT 100
                """, (rs, row) -> rs.getString(1), Instant.now()).forEach(orders::expire);
    }
}
