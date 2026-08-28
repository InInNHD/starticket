package com.starticket.infrastructure;

import com.rabbitmq.client.Channel;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

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

    private final OutboxClaimService outbox;
    private final RabbitTemplate rabbit;
    private final String workerId = UUID.randomUUID().toString();

    OutboxPublisher(OutboxClaimService outbox, RabbitTemplate rabbit) {
        this.outbox = outbox;
        this.rabbit = rabbit;
    }

    @Scheduled(fixedDelay = 1000)
    void publish() {
        outbox.claim(workerId, Instant.now(), 20).forEach(this::publishOne);
    }

    private void publishOne(OutboxClaimService.OutboxMessage row) {
        try {
            Message message = MessageBuilder.withBody(row.payload().getBytes(StandardCharsets.UTF_8))
                    .setContentType("text/plain").setExpiration("600000").setMessageId(String.valueOf(row.id()))
                    .setHeader("eventType", row.eventType()).setHeader("aggregateId", row.aggregateId()).build();
            Boolean confirmed = rabbit.invoke(operations -> {
                operations.send(MessagingNames.DELAY_EXCHANGE, "delay", message);
                return operations.waitForConfirms(5000);
            });
            if (!Boolean.TRUE.equals(confirmed)) throw new IllegalStateException("RabbitMQ 未确认消息");
            outbox.published(workerId, row.id(), Instant.now());
        } catch (Exception exception) {
            outbox.failed(workerId, row, exception, Instant.now());
        }
    }
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
@ConditionalOnProperty(name = "app.infrastructure.enabled", havingValue = "true")
class FailedMessageCollector {

    private final JdbcTemplate jdbc;

    FailedMessageCollector(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @RabbitListener(queues = MessagingNames.FAILED_QUEUE, ackMode = "MANUAL")
    void collect(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            String payload = new String(message.getBody(), StandardCharsets.UTF_8);
            String messageId = message.getMessageProperties().getMessageId();
            if (messageId == null || messageId.isBlank()) messageId = UUID.randomUUID().toString();
            String eventType = header(message, "eventType", "ORDER_EXPIRY");
            String aggregateId = header(message, "aggregateId", payload);
            jdbc.update("""
                    INSERT IGNORE INTO st_failed_message
                        (message_id, event_type, aggregate_id, payload, failure_reason, status, failed_at)
                    VALUES (?, ?, ?, ?, ?, 'DEAD', ?)
                    """, messageId, eventType, aggregateId, payload, failureReason(message), Instant.now());
            channel.basicAck(deliveryTag, false);
        } catch (RuntimeException exception) {
            channel.basicNack(deliveryTag, false, true);
        }
    }

    private static String header(Message message, String name, String fallback) {
        Object value = message.getMessageProperties().getHeaders().get(name);
        return value == null ? fallback : value.toString();
    }

    private static String failureReason(Message message) {
        Object death = message.getMessageProperties().getHeaders().get("x-first-death-reason");
        return death == null ? "RabbitMQ 消费重试耗尽" : death.toString();
    }
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
