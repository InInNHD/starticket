package com.starticket.demo;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
@ConditionalOnProperty(name = "app.demo.enabled", havingValue = "true")
class DemoDataInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;

    DemoDataInitializer(JdbcTemplate jdbc, PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.passwords = passwords;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedAccount("admin", "admin@starticket.local", "ADMIN");
        seedAccount("organizer", "organizer@starticket.local", "ORGANIZER");
        seedAccount("checker", "checker@starticket.local", "CHECKER");
        seedAccount("user", "user@starticket.local", "USER");
        if (exists("SELECT COUNT(*) FROM st_event WHERE title = ?", "StarTicket 夏日音乐节")) return;

        Instant now = Instant.now();
        long organizerId = jdbc.queryForObject("SELECT id FROM st_user WHERE username = 'organizer'", Long.class);
        long venueId = insert("""
                INSERT INTO st_venue (name, city, address, enabled, created_at)
                VALUES ('星光中心', '上海', '浦东新区城市舞台路88号', TRUE, ?)
                """, now);
        long areaId = insert("""
                INSERT INTO st_venue_area (venue_id, name, code, sort_order)
                VALUES (?, '一层A区', 'A1', 1)
                """, venueId);
        for (int row = 1; row <= 3; row++) {
            for (int number = 1; number <= 6; number++) {
                jdbc.update("""
                        INSERT INTO st_seat (area_id, row_label, seat_number, code, enabled)
                        VALUES (?, ?, ?, ?, TRUE)
                        """, areaId, String.valueOf((char) ('A' + row - 1)), number,
                        (char) ('A' + row - 1) + "-" + String.format("%02d", number));
            }
        }
        long eventId = insert("""
                INSERT INTO st_event
                    (organizer_id, title, category, description, poster_url, purchase_notice,
                     status, created_at, updated_at)
                VALUES (?, 'StarTicket 夏日音乐节', 'CONCERT',
                        '用于本地演示完整购票、支付、出票和核销链路的示例活动。', NULL,
                        '每个账号限购4张，演出开始24小时前可模拟退款。', 'APPROVED', ?, ?)
                """, organizerId, now, now);
        Instant startsAt = now.plus(30, ChronoUnit.DAYS);
        long performanceId = insert("""
                INSERT INTO st_performance
                    (event_id, venue_id, name, starts_at, sales_start_at, sales_end_at, status, created_at)
                VALUES (?, ?, '上海站 19:30', ?, ?, ?, 'SCHEDULED', ?)
                """, eventId, venueId, startsAt, now.minus(1, ChronoUnit.DAYS),
                startsAt.minus(1, ChronoUnit.DAYS), now);
        long tierId = insert("""
                INSERT INTO st_ticket_tier
                    (performance_id, area_id, name, price, color, purchase_limit, enabled)
                VALUES (?, ?, 'A区演示票', 199.00, '#6B3BFF', 4, TRUE)
                """, performanceId, areaId);
        jdbc.update("""
                INSERT INTO st_performance_seat
                    (performance_id, seat_id, ticket_tier_id, price, status, version)
                SELECT ?, id, ?, 199.00, 'AVAILABLE', 0 FROM st_seat WHERE area_id = ?
                """, performanceId, tierId, areaId);
    }

    private void seedAccount(String username, String email, String role) {
        if (!exists("SELECT COUNT(*) FROM st_user WHERE username = ?", username)) {
            jdbc.update("""
                    INSERT INTO st_user (username, email, password_hash, enabled, created_at)
                    VALUES (?, ?, ?, TRUE, ?)
                    """, username, email, passwords.encode("Password123"), Instant.now());
        }
        jdbc.update("""
                INSERT IGNORE INTO st_user_role (user_id, role)
                SELECT id, ? FROM st_user WHERE username = ?
                """, role, username);
    }

    private boolean exists(String sql, Object value) {
        Integer count = jdbc.queryForObject(sql, Integer.class, value);
        return count != null && count > 0;
    }

    private long insert(String sql, Object... args) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }
}
