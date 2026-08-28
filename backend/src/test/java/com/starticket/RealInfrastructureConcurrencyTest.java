package com.starticket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starticket.infrastructure.OutboxClaimService;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.lifecycle.Startables;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealInfrastructureConcurrencyTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("starticket").withUsername("starticket").withPassword("starticket_test");
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management");

    static {
        Startables.deepStart(Stream.of(MYSQL, REDIS, RABBIT)).join();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        registry.add("app.jwt.secret", () -> "testcontainers-jwt-secret-at-least-32-bytes");
        registry.add("app.infrastructure.enabled", () -> true);
        registry.add("app.demo.enabled", () -> false);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxClaimService outboxClaims;
    @Autowired RabbitTemplate rabbit;

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private String admin;
    private String organizer;
    private String checker;

    @BeforeAll
    void roles() throws Exception {
        admin = account("real_admin", "real-admin@example.com", "ADMIN");
        organizer = account("real_organizer", "real-organizer@example.com", "ORGANIZER");
        checker = account("real_checker", "real-checker@example.com", "CHECKER");
    }

    @Test
    @Order(1)
    void tierAndPerformanceLimitsCannotBeBypassedWithSplitOrders() throws Exception {
        Fixture fixture = fixture(7, 2);
        String buyer = account("limit_buyer", "limit-buyer@example.com", null);
        String threeSeats = orderBody(fixture, fixture.seats().subList(0, 3));
        assertThat(perform(orderRequest(buyer, "limit-too-many", threeSeats)).status()).isEqualTo(409);

        assertThat(perform(orderRequest(buyer, "limit-first", orderBody(fixture, fixture.seats().subList(0, 2)))).status())
                .isEqualTo(201);
        assertThat(perform(orderRequest(buyer, "limit-split", orderBody(fixture, List.of(fixture.seats().get(2))))).status())
                .isEqualTo(409);
    }

    @Test
    @Order(2)
    void oneHundredRequestsCannotOversellOneSeat() throws Exception {
        Fixture fixture = fixture(1, 6);
        String body = orderBody(fixture, fixture.seats());
        List<String> buyers = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            buyers.add(account("race_" + i, "race-" + i + "@example.com", null));
        }
        List<HttpResult> results = concurrent(100, index ->
                orderRequest(buyers.get(index % buyers.size()), "race-" + index, body));
        assertThat(results.stream().filter(result -> result.status() == 201).count()).isEqualTo(1);
        assertThat(results).allMatch(result -> result.status() == 201 || result.status() == 409);
        Integer sold = jdbc.queryForObject("""
                SELECT COUNT(*) FROM st_order_item i JOIN st_order o ON o.id = i.order_id
                WHERE o.performance_id = ? AND i.seat_id = ?
                """, Integer.class, fixture.performanceId(), fixture.seats().getFirst());
        assertThat(sold).isEqualTo(1);
    }

    @Test
    @Order(3)
    void concurrentSameIdempotencyKeyReturnsOneOrder() throws Exception {
        Fixture fixture = fixture(1, 6);
        String buyer = account("idem_buyer", "idem-buyer@example.com", null);
        String body = orderBody(fixture, fixture.seats());
        List<HttpResult> results = concurrent(10, ignored -> orderRequest(buyer, "same-idempotency-key", body));
        assertThat(results).allMatch(result -> result.status() == 201);
        HashSet<String> orderNumbers = new HashSet<>();
        for (HttpResult result : results) orderNumbers.add(json.readTree(result.body()).get("orderNo").asText());
        assertThat(orderNumbers).hasSize(1);
    }

    @Test
    @Order(4)
    void paymentAndRefundAreConcurrentAndIdempotent() throws Exception {
        Fixture fixture = fixture(1, 6);
        String buyer = account("pay_buyer", "pay-buyer@example.com", null);
        String orderNo = json.readTree(perform(orderRequest(buyer, "pay-order", orderBody(fixture, fixture.seats()))).body())
                .get("orderNo").asText();

        List<HttpResult> payments = concurrent(10, ignored -> post("/api/payments")
                .header("Authorization", bearer(buyer)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderNo\":\"" + orderNo + "\"}"));
        assertThat(payments).allMatch(result -> result.status() == 200);
        HashSet<String> paymentNumbers = new HashSet<>();
        for (HttpResult result : payments) paymentNumbers.add(json.readTree(result.body()).get("paymentNo").asText());
        assertThat(paymentNumbers).hasSize(1);
        String paymentNo = paymentNumbers.iterator().next();

        List<HttpResult> callbacks = concurrent(20, ignored -> post("/api/payments/{paymentNo}/simulate-success", paymentNo)
                .header("Authorization", bearer(buyer)));
        assertThat(callbacks).allMatch(result -> result.status() == 200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM st_ticket t JOIN st_order_item i ON i.id=t.order_item_id " +
                "JOIN st_order o ON o.id=i.order_id WHERE o.order_no=?", Integer.class, orderNo)).isEqualTo(1);

        List<HttpResult> refunds = concurrent(10, ignored -> post("/api/orders/{orderNo}/refunds", orderNo)
                .header("Authorization", bearer(buyer)));
        assertThat(refunds).allMatch(result -> result.status() == 200);
        HashSet<String> refundNumbers = new HashSet<>();
        for (HttpResult result : refunds) refundNumbers.add(json.readTree(result.body()).get("refundNo").asText());
        assertThat(refundNumbers).hasSize(1);
    }

    @Test
    @Order(5)
    void concurrentTicketRedeemHasOneSuccessAndTheRestAlreadyUsed() throws Exception {
        Fixture fixture = fixture(1, 6);
        String buyer = account("ticket_buyer", "ticket-buyer@example.com", null);
        String orderNo = json.readTree(perform(orderRequest(buyer, "ticket-order", orderBody(fixture, fixture.seats()))).body())
                .get("orderNo").asText();
        String paymentNo = json.readTree(postJson("/api/payments", buyer, "{\"orderNo\":\"" + orderNo + "\"}"))
                .get("paymentNo").asText();
        postJson("/api/payments/" + paymentNo + "/simulate-success", buyer, null);
        String code = json.readTree(perform(get("/api/tickets").header("Authorization", bearer(buyer))).body())
                .get(0).get("code").asText();

        List<HttpResult> results = concurrent(20, ignored -> post("/api/check-in/redeem")
                .header("Authorization", bearer(checker)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}"));
        long success = results.stream().filter(result -> result.body().contains("SUCCESS")).count();
        long alreadyUsed = results.stream().filter(result -> result.body().contains("ALREADY_USED")).count();
        assertThat(success).isEqualTo(1);
        assertThat(alreadyUsed).isEqualTo(19);
    }

    @Test
    @Order(6)
    void outboxPublishesAfterRabbitMqRecovers() throws Exception {
        Fixture fixture = fixture(1, 6);
        String buyer = account("outbox_buyer", "outbox-buyer@example.com", null);
        assertThat(RABBIT.execInContainer("rabbitmqctl", "stop_app").getExitCode()).isZero();
        String orderNo;
        try {
            orderNo = json.readTree(perform(orderRequest(buyer, "outbox-order", orderBody(fixture, fixture.seats()))).body())
                    .get("orderNo").asText();
        } finally {
            assertThat(RABBIT.execInContainer("rabbitmqctl", "start_app").getExitCode()).isZero();
            waitForRabbitMq();
        }
        String status = null;
        for (int i = 0; i < 60; i++) {
            List<String> statuses = jdbc.query("SELECT status FROM st_outbox_event WHERE aggregate_id = ?",
                    (rs, row) -> rs.getString(1), orderNo);
            status = statuses.isEmpty() ? null : statuses.getFirst();
            if ("PUBLISHED".equals(status)) break;
            Thread.sleep(1000);
        }
        assertThat(status).isEqualTo("PUBLISHED");
    }

    @Test
    @Order(7)
    void twoPublishersClaimOneOutboxEventAndRecoverStaleClaim() throws Exception {
        String aggregateId = "claim-test-" + SEQUENCE.incrementAndGet();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO st_outbox_event
                    (event_type, aggregate_id, payload, status, retry_count, next_retry_at, created_at)
                VALUES ('CLAIM_TEST', ?, ?, 'PENDING', 0, ?, ?)
                """, aggregateId, aggregateId, now.plus(1, ChronoUnit.HOURS), now);
        long id = jdbc.queryForObject("SELECT id FROM st_outbox_event WHERE aggregate_id = ?", Long.class, aggregateId);
        Instant claimTime = now.plus(2, ChronoUnit.HOURS);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<List<OutboxClaimService.OutboxMessage>> first = executor.submit(() -> {
                start.await();
                return outboxClaims.claim("publisher-a", claimTime, 20);
            });
            Future<List<OutboxClaimService.OutboxMessage>> second = executor.submit(() -> {
                start.await();
                return outboxClaims.claim("publisher-b", claimTime, 20);
            });
            start.countDown();
            long claimed = Stream.concat(first.get().stream(), second.get().stream())
                    .filter(row -> row.id() == id).count();
            assertThat(claimed).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM st_outbox_event WHERE id = ?", String.class, id))
                .isEqualTo("PROCESSING");

        jdbc.update("UPDATE st_outbox_event SET locked_at = ? WHERE id = ?", now.minus(5, ChronoUnit.MINUTES), id);
        List<OutboxClaimService.OutboxMessage> recovered = outboxClaims.claim(
                "publisher-recovery", claimTime.plus(2, ChronoUnit.MINUTES), 20);
        assertThat(recovered).anyMatch(row -> row.id() == id);
    }

    @Test
    @Order(8)
    void rabbitConsumerDeadLetterCanBeInspectedAndReplayed() throws Exception {
        String aggregateId = "dead-message-" + SEQUENCE.incrementAndGet();
        rabbit.send("starticket.order.failed.exchange", "failed",
                MessageBuilder.withBody(aggregateId.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .setContentType("text/plain").setMessageId(aggregateId)
                        .setHeader("eventType", "ORDER_EXPIRY").setHeader("aggregateId", aggregateId).build());
        Long deadId = null;
        for (int i = 0; i < 30; i++) {
            List<Long> ids = jdbc.query("SELECT id FROM st_failed_message WHERE aggregate_id = ?",
                    (rs, row) -> rs.getLong(1), aggregateId);
            if (!ids.isEmpty()) {
                deadId = ids.getFirst();
                break;
            }
            Thread.sleep(200);
        }
        assertThat(deadId).isNotNull();
        perform(get("/api/admin/messages/dead").header("Authorization", bearer(admin)));
        assertThat(perform(post("/api/admin/messages/dead/{id}/retry", deadId)
                .header("Authorization", bearer(admin))).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM st_failed_message WHERE id = ?", String.class, deadId))
                .isEqualTo("REPLAYED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM st_audit_log WHERE action = 'RABBIT_DEAD_REPLAY' AND target_id = ?
                """, Integer.class, String.valueOf(deadId))).isEqualTo(1);
    }

    private void waitForRabbitMq() throws Exception {
        for (int i = 0; i < 45; i++) {
            try {
                if (RABBIT.execInContainer("rabbitmq-diagnostics", "-q", "ping").getExitCode() == 0) return;
            } catch (Exception ignored) {
                // 容器已启动但 RabbitMQ 尚未就绪。
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("RabbitMQ 重启后未在 45 秒内就绪");
    }

    private Fixture fixture(int seatCount, int purchaseLimit) throws Exception {
        int suffix = SEQUENCE.incrementAndGet();
        long venueId = id(postJson("/api/admin/venues", admin,
                "{\"name\":\"真实并发馆" + suffix + "\",\"city\":\"上海\",\"address\":\"测试路" + suffix + "号\"}"));
        long areaId = id(postJson("/api/admin/venues/" + venueId + "/areas", admin,
                "{\"name\":\"并发区\",\"code\":\"R" + suffix + "\",\"sortOrder\":1}"));
        postJson("/api/admin/areas/" + areaId + "/seats/generate", admin,
                "{\"rowCount\":1,\"seatsPerRow\":" + seatCount + "}");
        long eventId = id(postJson("/api/organizer/events", organizer, """
                {"title":"真实并发演出%s","category":"CONCERT","description":"真实容器并发测试",
                 "purchaseNotice":"测试限购与幂等","posterUrl":""}
                """.formatted(suffix)));
        Instant starts = Instant.now().plus(30, ChronoUnit.DAYS);
        long performanceId = id(postJson("/api/organizer/events/" + eventId + "/performances", organizer, """
                {"venueId":%d,"name":"并发场次%s","startsAt":"%s","salesStartAt":"%s","salesEndAt":"%s"}
                """.formatted(venueId, suffix, starts, Instant.now().minus(1, ChronoUnit.DAYS),
                starts.minus(1, ChronoUnit.DAYS))));
        postJson("/api/organizer/performances/" + performanceId + "/tiers", organizer, """
                {"areaId":%d,"name":"并发票档","price":199.00,"color":"#6B3BFF","purchaseLimit":%d}
                """.formatted(areaId, purchaseLimit));
        postJson("/api/organizer/events/" + eventId + "/submit", organizer, null);
        postJson("/api/admin/events/" + eventId + "/approve", admin, null);
        JsonNode seats = json.readTree(perform(get("/api/performances/{id}/seats", performanceId)).body()).get("seats");
        List<Long> seatIds = new java.util.ArrayList<>();
        seats.forEach(seat -> seatIds.add(seat.get("seatId").asLong()));
        return new Fixture(performanceId, seatIds);
    }

    private String account(String username, String email, String role) throws Exception {
        perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"username":"%s","email":"%s","password":"Password123"}
                """.formatted(username, email)));
        if (role != null) jdbc.update("""
                INSERT INTO st_user_role (user_id, role) SELECT id, ? FROM st_user WHERE username = ?
                """, role, username);
        return json.readTree(perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"login":"%s","password":"Password123"}
                """.formatted(username))).body()).get("accessToken").asText();
    }

    private String postJson(String path, String token, String body) throws Exception {
        var request = post(path).header("Authorization", bearer(token));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        HttpResult result = perform(request);
        assertThat(result.status()).isBetween(200, 299);
        return result.body();
    }

    private MockHttpServletRequestBuilder orderRequest(String token, String key, String body) {
        return post("/api/orders").header("Authorization", bearer(token)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String orderBody(Fixture fixture, List<Long> seats) {
        return "{\"performanceId\":" + fixture.performanceId() + ",\"seatIds\":" + seats + "}";
    }

    private List<HttpResult> concurrent(int count, IntFunction<MockHttpServletRequestBuilder> requests) throws Exception {
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<HttpResult>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return perform(requests.apply(index));
                }));
            }
            ready.await();
            start.countDown();
            List<HttpResult> results = new java.util.ArrayList<>();
            for (Future<HttpResult> future : futures) results.add(future.get());
            return results;
        }
    }

    private HttpResult perform(MockHttpServletRequestBuilder request) throws Exception {
        var response = mvc.perform(request).andReturn().getResponse();
        return new HttpResult(response.getStatus(), response.getContentAsString());
    }

    private long id(String body) throws Exception { return json.readTree(body).get("id").asLong(); }
    private static String bearer(String token) { return "Bearer " + token; }
    private record Fixture(long performanceId, List<Long> seats) {}
    private record HttpResult(int status, String body) {}
}
