package com.starticket.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "app.demo.enabled=true",
        "app.demo.password=DeploymentPassword123",
        "spring.datasource.url=jdbc:h2:mem:starticket_demo;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
})
@ActiveProfiles("test")
class DemoDataInitializerTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired DemoDataInitializer initializer;

    @Test
    void seedsRealisticEventsPerformancesTiersAndInventory() {
        assertThat(count("SELECT COUNT(*) FROM st_event")).isEqualTo(6);
        assertThat(count("SELECT COUNT(*) FROM st_performance")).isEqualTo(10);
        assertThat(count("SELECT COUNT(DISTINCT category) FROM st_event")).isEqualTo(5);
        assertThat(count("SELECT COUNT(*) FROM st_performance_seat")).isGreaterThanOrEqualTo(800);
        assertThat(passwords.matches("DeploymentPassword123",
                jdbc.queryForObject("SELECT password_hash FROM st_user WHERE username = 'admin'", String.class))).isTrue();
        assertThat(count("""
                SELECT COUNT(*) FROM st_performance p
                WHERE NOT EXISTS (SELECT 1 FROM st_performance_seat ps WHERE ps.performance_id = p.id)
                """)).isZero();
    }

    @Test
    void restoresConfiguredPasswordForExistingDemoAccount() throws Exception {
        jdbc.update("UPDATE st_user SET password_hash = ? WHERE username = 'admin'",
                passwords.encode("ObsoletePassword123"));

        initializer.run(new DefaultApplicationArguments());

        assertThat(passwords.matches("DeploymentPassword123",
                jdbc.queryForObject("SELECT password_hash FROM st_user WHERE username = 'admin'", String.class))).isTrue();
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
