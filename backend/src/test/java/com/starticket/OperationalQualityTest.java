package com.starticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.demo.public=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class OperationalQualityTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test
    void authenticationAndAuthorizationErrorsShareTheProblemFormat() throws Exception {
        mvc.perform(get("/api/me").header("X-Request-Id", "trace-unauthorized"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Request-Id", "trace-unauthorized"))
                .andExpect(jsonPath("$.title").value("未认证"))
                .andExpect(jsonPath("$.requestId").value("trace-unauthorized"));

        String user = account("quality_user1", "quality-user1@example.com", null);
        mvc.perform(get("/api/admin/events").header("Authorization", bearer(user))
                        .header("X-Request-Id", "trace-forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(header().string("X-Request-Id", "trace-forbidden"))
                .andExpect(jsonPath("$.title").value("无权访问"))
                .andExpect(jsonPath("$.requestId").value("trace-forbidden"));
    }

    @Test
    void adminOrganizerCheckerAndUserHaveSeparatedPermissions() throws Exception {
        String admin = account("quality_admin", "quality-admin@example.com", "ADMIN");
        String organizer = account("quality_organizer", "quality-organizer@example.com", "ORGANIZER");
        String checker = account("quality_checker", "quality-checker@example.com", "CHECKER");
        String user = account("quality_user2", "quality-user2@example.com", null);

        mvc.perform(get("/api/admin/events").header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/organizer/events").header("Authorization", bearer(organizer)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/check-in/redeem").header("Authorization", bearer(checker))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"not-a-ticket\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/me").header("Authorization", bearer(user)))
                .andExpect(status().isOk());

        mvc.perform(get("/api/admin/events").header("Authorization", bearer(organizer)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/organizer/events").header("Authorization", bearer(checker)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/check-in/redeem").header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"not-a-ticket\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void organizerCannotReadOrModifyAnotherOrganizersEvent() throws Exception {
        String owner = account("quality_owner", "quality-owner@example.com", "ORGANIZER");
        String stranger = account("quality_stranger", "quality-stranger@example.com", "ORGANIZER");
        long eventId = json.readTree(mvc.perform(post("/api/organizer/events")
                        .header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content(eventDraft("归属隔离测试活动")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(get("/api/organizer/events/{id}", eventId).header("Authorization", bearer(stranger)))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/organizer/events/{id}", eventId).header("Authorization", bearer(stranger))
                        .contentType(MediaType.APPLICATION_JSON).content(eventDraft("越权修改")))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidPaymentCallbackSignatureIsRejectedBeforeStateChange() throws Exception {
        mvc.perform(post("/api/payments/callback").contentType(MediaType.APPLICATION_JSON).content("""
                        {"paymentNo":"PAY-NOT-EXISTS","channelTxnNo":"CHANNEL-1",
                         "success":true,"signature":"invalid-signature"}
                        """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("支付回调签名无效"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void approvedEventCannotBeEditedByOrganizer() throws Exception {
        String organizer = account("quality_published", "quality-published@example.com", "ORGANIZER");
        long organizerId = jdbc.queryForObject(
                "SELECT id FROM st_user WHERE username = 'quality_published'", Long.class);
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO st_event
                    (organizer_id, title, category, description, purchase_notice, status, created_at, updated_at)
                VALUES (?, '已发布不可编辑活动', 'CONCERT', '状态边界测试', '测试须知', 'APPROVED', ?, ?)
                """, organizerId, now, now);
        long eventId = jdbc.queryForObject(
                "SELECT id FROM st_event WHERE title = '已发布不可编辑活动'", Long.class);

        mvc.perform(put("/api/organizer/events/{id}", eventId).header("Authorization", bearer(organizer))
                        .contentType(MediaType.APPLICATION_JSON).content(eventDraft("试图修改已发布活动")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void validationFailureCanBeLocatedByRequestId(CapturedOutput output) throws Exception {
        mvc.perform(post("/api/auth/register").header("X-Request-Id", "trace-validation-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"x\",\"email\":\"bad\",\"password\":\"123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Request-Id", "trace-validation-001"))
                .andExpect(jsonPath("$.requestId").value("trace-validation-001"))
                .andExpect(jsonPath("$.errors.username").exists());
        assertThat(output.getOut()).contains("requestId:trace-validation-001")
                .contains("request completed method=POST path=/api/auth/register status=400");
    }

    @Test
    void openApiPublishesJwtBearerScheme() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.security[0].bearerAuth").isArray());
    }

    @Test
    void publicDemoBlocksDangerousMessageReplay() throws Exception {
        String admin = account("quality_demo_admin", "quality-demo-admin@example.com", "ADMIN");
        mvc.perform(post("/api/admin/outbox/1/retry").header("Authorization", bearer(admin)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/messages/dead/1/retry").header("Authorization", bearer(admin)))
                .andExpect(status().isForbidden());
    }

    private String account(String username, String email, String role) throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"username":"%s","email":"%s","password":"Password123"}
                """.formatted(username, email))).andExpect(status().isCreated());
        if (role != null) {
            jdbc.update("""
                    INSERT INTO st_user_role (user_id, role)
                    SELECT id, ? FROM st_user WHERE username = ?
                    """, role, username);
        }
        String response = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"login":"%s","password":"Password123"}
                """.formatted(username))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("accessToken").asText();
    }

    private static String eventDraft(String title) {
        return """
                {"title":"%s","category":"CONCERT","description":"用于验证权限与状态边界的活动描述", 
                 "posterUrl":"","purchaseNotice":"测试购票须知"}
                """.formatted(title);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
