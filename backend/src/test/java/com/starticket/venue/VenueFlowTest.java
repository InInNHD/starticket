package com.starticket.venue;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VenueFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void adminCreatesVenueAreaAndSeatsWhileUserIsForbidden() throws Exception {
        String adminToken = register("venue_admin", "venue-admin@example.com");
        jdbcTemplate.update("""
                INSERT INTO st_user_role (user_id, role)
                SELECT id, 'ADMIN' FROM st_user WHERE username = ?
                """, "venue_admin");
        adminToken = login("venue_admin");

        String userToken = register("venue_user", "venue-user@example.com");
        String venueBody = """
                {"name":"星光剧院","city":"上海","address":"浦东新区演出路1号"}
                """;
        mockMvc.perform(post("/api/admin/venues")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(venueBody))
                .andExpect(status().isForbidden());

        String createdVenue = mockMvc.perform(post("/api/admin/venues")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(venueBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("星光剧院"))
                .andReturn().getResponse().getContentAsString();
        long venueId = objectMapper.readTree(createdVenue).get("id").asLong();

        String createdArea = mockMvc.perform(post("/api/admin/venues/{venueId}/areas", venueId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"一层A区","code":"a1","sortOrder":10}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("A1"))
                .andReturn().getResponse().getContentAsString();
        long areaId = objectMapper.readTree(createdArea).get("id").asLong();

        String generation = """
                {"rowCount":12,"seatsPerRow":1}
                """;
        mockMvc.perform(post("/api/admin/areas/{areaId}/seats/generate", areaId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(generation))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.created").value(12));

        mockMvc.perform(post("/api/admin/areas/{areaId}/seats/generate", areaId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(generation))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/admin/venues/{venueId}/layout", venueId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.areas[0].seats.length()").value(12))
                .andExpect(jsonPath("$.areas[0].seats[0].code").value("A1-1-1"))
                .andExpect(jsonPath("$.areas[0].seats[9].code").value("A1-10-1"));
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

    private String login(String login) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"login":"%s","password":"Password123"}
                                """.formatted(login)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode body = objectMapper.readTree(response);
        return body.get("accessToken").asText();
    }
}
