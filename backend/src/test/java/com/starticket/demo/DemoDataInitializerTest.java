package com.starticket.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "app.demo.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:starticket_demo;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
})
@ActiveProfiles("test")
class DemoDataInitializerTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void seedsRealisticEventsPerformancesTiersAndInventory() {
        assertThat(count("SELECT COUNT(*) FROM st_event")).isEqualTo(6);
        assertThat(count("SELECT COUNT(*) FROM st_performance")).isEqualTo(10);
        assertThat(count("SELECT COUNT(DISTINCT category) FROM st_event")).isEqualTo(5);
        assertThat(count("SELECT COUNT(*) FROM st_performance_seat")).isGreaterThanOrEqualTo(800);
        assertThat(count("""
                SELECT COUNT(*) FROM st_performance p
                WHERE NOT EXISTS (SELECT 1 FROM st_performance_seat ps WHERE ps.performance_id = p.id)
                """)).isZero();
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
