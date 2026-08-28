package com.starticket.event;

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
class EventFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void organizerBuildsEventAndAdminPublishesIt() throws Exception {
        String adminToken = registerAndGrant("event_admin", "event-admin@example.com", "ADMIN");
        String organizerToken = registerAndGrant("event_organizer", "event-organizer@example.com", "ORGANIZER");
        String userToken = register("event_user", "event-user@example.com");

        mockMvc.perform(post("/api/organizer/events")
                        .header("Authorization", bearer(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventDraft()))
                .andExpect(status().isForbidden());

        long venueId = idOf(postJson("/api/admin/venues", adminToken,
                "{\"name\":\"星光剧院\",\"city\":\"上海\",\"address\":\"演出路1号\"}"));
        long areaId = idOf(postJson("/api/admin/venues/" + venueId + "/areas", adminToken,
                "{\"name\":\"一层A区\",\"code\":\"A1\",\"sortOrder\":1}"));
        postJson("/api/admin/areas/" + areaId + "/seats/generate", adminToken,
                "{\"rowCount\":2,\"seatsPerRow\":3}");

        String createdEvent = postJson("/api/organizer/events", organizerToken, eventDraft());
        long eventId = idOf(createdEvent);

        mockMvc.perform(get("/api/events/{eventId}", eventId))
                .andExpect(status().isNotFound());

        Instant startsAt = Instant.now().plus(30, ChronoUnit.DAYS);
        String performanceBody = """
                {"venueId":%d,"name":"上海站 19:30","startsAt":"%s","salesStartAt":"%s","salesEndAt":"%s"}
                """.formatted(venueId, startsAt, startsAt.minus(20, ChronoUnit.DAYS),
                startsAt.minus(1, ChronoUnit.HOURS));
        String createdPerformance = postJson(
                "/api/organizer/events/" + eventId + "/performances", organizerToken, performanceBody);
        long performanceId = idOf(createdPerformance);

        postJson("/api/organizer/performances/" + performanceId + "/tiers", organizerToken,
                """
                {"areaId":%d,"name":"A区 680元","price":680.00,"color":"#6B3BFF","purchaseLimit":4}
                """.formatted(areaId));

        mockMvc.perform(post("/api/organizer/events/{eventId}/submit", eventId)
                        .header("Authorization", bearer(organizerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"));

        mockMvc.perform(get("/api/admin/events/pending")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(eventId));

        mockMvc.perform(post("/api/admin/events/{eventId}/approve", eventId)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        mockMvc.perform(get("/api/events/{eventId}", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("2026 星河巡演"))
                .andExpect(jsonPath("$.performances[0].ticketTiers[0].price").value(680.0))
                .andExpect(jsonPath("$.performances[0].ticketTiers[0].purchaseLimit").value(4));
    }

    private String postJson(String path, String token, String body) throws Exception {
        return mockMvc.perform(post(path)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
    }

    private long idOf(String json) throws Exception {
        return objectMapper.readTree(json).get("id").asLong();
    }

    private String registerAndGrant(String username, String email, String role) throws Exception {
        register(username, email);
        jdbcTemplate.update("""
                INSERT INTO st_user_role (user_id, role)
                SELECT id, ? FROM st_user WHERE username = ?
                """, role, username);
        return login(username);
    }

    private String register(String username, String email) throws Exception {
        String response = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","email":"%s","password":"Password123"}
                                """.formatted(username, email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("accessToken").asText();
    }

    private String login(String username) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"login":"%s","password":"Password123"}
                                """.formatted(username)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("accessToken").asText();
    }

    private static String eventDraft() {
        return """
                {
                  "title":"2026 星河巡演",
                  "category":"CONCERT",
                  "description":"一场覆盖城市年轻观众的现场音乐演出。",
                  "posterUrl":"https://example.com/poster.jpg",
                  "purchaseNotice":"每个账号每场限购4张，入场请出示电子票。"
                }
                """;
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
