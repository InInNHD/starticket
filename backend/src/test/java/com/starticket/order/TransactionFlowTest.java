package com.starticket.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TransactionFlowTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test
    void lockPayRedeemAndRefundStayConsistent() throws Exception {
        String admin = account("flow_admin", "flow-admin@example.com", "ADMIN");
        String organizer = account("flow_organizer", "flow-organizer@example.com", "ORGANIZER");
        String buyer = account("flow_buyer", "flow-buyer@example.com", null);
        String buyer2 = account("flow_buyer2", "flow-buyer2@example.com", null);
        String checker = account("flow_checker", "flow-checker@example.com", "CHECKER");

        long venueId = id(postJson("/api/admin/venues", admin,
                "{\"name\":\"交易测试馆\",\"city\":\"上海\",\"address\":\"测试路8号\"}"));
        long areaId = id(postJson("/api/admin/venues/" + venueId + "/areas", admin,
                "{\"name\":\"内场\",\"code\":\"VIP\",\"sortOrder\":1}"));
        postJson("/api/admin/areas/" + areaId + "/seats/generate", admin,
                "{\"rowCount\":1,\"seatsPerRow\":2}");

        long eventId = id(postJson("/api/organizer/events", organizer, """
                {"title":"交易闭环测试演出","category":"CONCERT","description":"测试完整交易链路",
                 "purchaseNotice":"每人限购2张","posterUrl":""}
                """));
        Instant starts = Instant.now().plus(30, ChronoUnit.DAYS);
        long performanceId = id(postJson("/api/organizer/events/" + eventId + "/performances", organizer, """
                {"venueId":%d,"name":"测试场次","startsAt":"%s","salesStartAt":"%s","salesEndAt":"%s"}
                """.formatted(venueId, starts, Instant.now().minus(1, ChronoUnit.DAYS),
                starts.minus(1, ChronoUnit.DAYS))));
        postJson("/api/organizer/performances/" + performanceId + "/tiers", organizer, """
                {"areaId":%d,"name":"VIP 票","price":199.00,"color":"#6B3BFF","purchaseLimit":2}
                """.formatted(areaId));
        postJson("/api/organizer/events/" + eventId + "/submit", organizer, null);
        postJson("/api/admin/events/" + eventId + "/approve", admin, null);

        JsonNode seats = json.readTree(mvc.perform(get("/api/performances/{id}/seats", performanceId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.seats.length()").value(2))
                .andReturn().getResponse().getContentAsString()).get("seats");
        long seat1 = seats.get(0).get("seatId").asLong();
        long seat2 = seats.get(1).get("seatId").asLong();

        String orderBody1 = "{\"performanceId\":" + performanceId + ",\"seatIds\":[" + seat1 + "]}";
        String order1 = createOrder(buyer, "idem-flow-1", orderBody1);
        String orderNo1 = json.readTree(order1).get("orderNo").asText();
        mvc.perform(post("/api/orders").header("Authorization", bearer(buyer))
                        .header("Idempotency-Key", "idem-flow-1").contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody1))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.orderNo").value(orderNo1));
        mvc.perform(post("/api/orders").header("Authorization", bearer(buyer2))
                        .header("Idempotency-Key", "idem-conflict").contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody1))
                .andExpect(status().isConflict());

        String payment1 = postJson("/api/payments", buyer, "{\"orderNo\":\"" + orderNo1 + "\"}");
        String paymentNo1 = json.readTree(payment1).get("paymentNo").asText();
        postJson("/api/payments/" + paymentNo1 + "/simulate-success", buyer, null);
        postJson("/api/payments/" + paymentNo1 + "/simulate-success", buyer, null);
        JsonNode ticket1 = tickets(buyer).get(0);
        String code1 = ticket1.get("code").asText();
        mvc.perform(post("/api/check-in/redeem").header("Authorization", bearer(checker))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code1 + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("SUCCESS"));
        mvc.perform(post("/api/check-in/redeem").header("Authorization", bearer(checker))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code1 + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("ALREADY_USED"));
        mvc.perform(post("/api/orders/{orderNo}/refunds", orderNo1).header("Authorization", bearer(buyer)))
                .andExpect(status().isConflict());

        String orderBody2 = "{\"performanceId\":" + performanceId + ",\"seatIds\":[" + seat2 + "]}";
        String orderNo2 = json.readTree(createOrder(buyer2, "idem-flow-2", orderBody2)).get("orderNo").asText();
        String paymentNo2 = json.readTree(postJson("/api/payments", buyer2,
                "{\"orderNo\":\"" + orderNo2 + "\"}")).get("paymentNo").asText();
        postJson("/api/payments/" + paymentNo2 + "/simulate-success", buyer2, null);
        String code2 = tickets(buyer2).get(0).get("code").asText();
        mvc.perform(post("/api/orders/{orderNo}/refunds", orderNo2).header("Authorization", bearer(buyer2)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCESS"));
        mvc.perform(post("/api/check-in/redeem").header("Authorization", bearer(checker))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code2 + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("REFUNDED"));

        mvc.perform(get("/api/organizer/events/{eventId}/sales-summary", eventId)
                        .header("Authorization", bearer(organizer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalOrders").value(2))
                .andExpect(jsonPath("$.paidOrders").value(1))
                .andExpect(jsonPath("$.refundedOrders").value(1))
                .andExpect(jsonPath("$.soldTickets").value(1))
                .andExpect(jsonPath("$.refundedTickets").value(1))
                .andExpect(jsonPath("$.grossRevenue").value(398.0))
                .andExpect(jsonPath("$.refundAmount").value(199.0))
                .andExpect(jsonPath("$.netRevenue").value(199.0));
        mvc.perform(get("/api/organizer/events/{eventId}/orders", eventId)
                        .header("Authorization", bearer(organizer)).param("status", "REFUNDED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].username").value("flow_buyer2"));
        mvc.perform(get("/api/admin/orders").header("Authorization", bearer(admin))
                        .param("keyword", "flow_buyer2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/admin/orders").header("Authorization", bearer(buyer)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/orders").header("Authorization", bearer(buyer2)).param("status", "REFUNDED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].orderNo").value(orderNo2));
    }

    private String createOrder(String token, String key, String body) throws Exception {
        return mvc.perform(post("/api/orders").header("Authorization", bearer(token))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    private JsonNode tickets(String token) throws Exception {
        return json.readTree(mvc.perform(get("/api/tickets").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private String postJson(String path, String token, String body) throws Exception {
        var request = post(path).header("Authorization", bearer(token));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request).andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
    }

    private long id(String body) throws Exception { return json.readTree(body).get("id").asLong(); }

    private String account(String username, String email, String role) throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"username":"%s","email":"%s","password":"Password123"}
                """.formatted(username, email))).andExpect(status().isCreated());
        if (role != null) jdbc.update("""
                INSERT INTO st_user_role (user_id, role) SELECT id, ? FROM st_user WHERE username = ?
                """, role, username);
        String response = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"login":"%s","password":"Password123"}
                """.formatted(username))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("accessToken").asText();
    }

    private static String bearer(String token) { return "Bearer " + token; }
}
