package com.starticket.demo;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
@ConditionalOnProperty(name = "app.demo.enabled", havingValue = "true")
class DemoDataInitializer implements ApplicationRunner {

    private static final ZoneId SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final String demoPassword;

    DemoDataInitializer(JdbcTemplate jdbc, PasswordEncoder passwords,
                        @Value("${app.demo.password:Password123}") String demoPassword) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.demoPassword = demoPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedAccount("admin", "admin@starticket.local", "ADMIN");
        seedAccount("organizer", "organizer@starticket.local", "ORGANIZER");
        seedAccount("checker", "checker@starticket.local", "CHECKER");
        seedAccount("user", "user@starticket.local", "USER");

        Instant now = Instant.now();
        long organizerId = jdbc.queryForObject("SELECT id FROM st_user WHERE username = 'organizer'", Long.class);
        seedSummerFestival(organizerId, now);
        seedNeonConcert(organizerId, now);
        seedClassicTheatre(organizerId, now);
        seedComedyWeekend(organizerId, now);
        seedDigitalExhibition(organizerId, now);
        seedCampusConcert(organizerId, now);
    }

    private void seedSummerFestival(long organizerId, Instant now) {
        if (eventExists("StarTicket 夏日音乐节")) return;
        long venueId = venue("星光中心", "上海", "浦东新区城市舞台路88号", now);
        long areaId = area(venueId, "一层A区", "A1", 1);
        seats(areaId, 3, 6);
        long eventId = event(organizerId, "StarTicket 夏日音乐节", "CONCERT",
                "融合流行、摇滚与电子音乐的城市音乐节，适合体验选座、支付、出票和核销完整链路。",
                "每个账号限购4张；请携带有效证件入场，开演前24小时可申请退款。", now);
        long performanceId = performance(eventId, venueId, "上海站·周六晚场 19:30", at(30, 19, 30), now);
        tier(performanceId, areaId, "A区看台票", "199.00", "#6B3BFF", 4);
        snapshot(performanceId);
    }

    private void seedNeonConcert(long organizerId, Instant now) {
        if (eventExists("城市霓虹巡演·上海站")) return;
        long venueId = venue("上海星河体育馆", "上海", "徐汇区星河路168号", now);
        long vip = area(venueId, "内场VIP区", "VIP", 1);
        long first = area(venueId, "一层看台", "L1", 2);
        long second = area(venueId, "二层看台", "L2", 3);
        seats(vip, 4, 8);
        seats(first, 5, 10);
        seats(second, 5, 12);
        long eventId = event(organizerId, "城市霓虹巡演·上海站", "CONCERT",
                "以城市夜景为主题的流行音乐现场，包含完整乐队、灯光舞美和互动返场环节。",
                "每场每人限购4张；实名入场，门票仅限对应场次使用，开演后停止检票。", now);
        concertPerformance(eventId, venueId, "周六首演 19:30", at(14, 19, 30), now, vip, first, second);
        concertPerformance(eventId, venueId, "周日加场 19:30", at(15, 19, 30), now, vip, first, second);
    }

    private void concertPerformance(long eventId, long venueId, String name, Instant startsAt, Instant now,
                                    long vip, long first, long second) {
        long performanceId = performance(eventId, venueId, name, startsAt, now);
        tier(performanceId, vip, "内场VIP票", "880.00", "#E64A7A", 4);
        tier(performanceId, first, "一层看台票", "580.00", "#F59E0B", 4);
        tier(performanceId, second, "二层看台票", "380.00", "#3B82F6", 4);
        snapshot(performanceId);
    }

    private void seedClassicTheatre(long organizerId, Instant now) {
        if (eventExists("《雷雨》经典话剧巡演")) return;
        long venueId = venue("海派艺术剧院", "上海", "静安区华灯路66号", now);
        long stalls = area(venueId, "一层池座", "STALLS", 1);
        long balcony = area(venueId, "二层楼座", "BALCONY", 2);
        seats(stalls, 5, 10);
        seats(balcony, 4, 8);
        long eventId = event(organizerId, "《雷雨》经典话剧巡演", "THEATRE",
                "经典现实主义话剧舞台版，演出约150分钟，含15分钟中场休息。",
                "每人限购4张；建议12岁以上观众观看，迟到观众须听从工作人员安排入场。", now);
        theatrePerformance(eventId, venueId, "周五晚场 19:30", at(21, 19, 30), now, stalls, balcony);
        theatrePerformance(eventId, venueId, "周六晚场 19:30", at(22, 19, 30), now, stalls, balcony);
    }

    private void theatrePerformance(long eventId, long venueId, String name, Instant startsAt, Instant now,
                                    long stalls, long balcony) {
        long performanceId = performance(eventId, venueId, name, startsAt, now);
        tier(performanceId, stalls, "一层池座", "480.00", "#8B5CF6", 4);
        tier(performanceId, balcony, "二层楼座", "280.00", "#06B6D4", 4);
        snapshot(performanceId);
    }

    private void seedComedyWeekend(long organizerId, Instant now) {
        if (eventExists("城市喜剧周末·脱口秀拼盘")) return;
        long venueId = venue("滨江喜剧空间", "杭州", "滨江区闻涛路299号", now);
        long front = area(venueId, "前排互动区", "FRONT", 1);
        long rear = area(venueId, "后排观演区", "REAR", 2);
        seats(front, 3, 8);
        seats(rear, 3, 8);
        long eventId = event(organizerId, "城市喜剧周末·脱口秀拼盘", "COMEDY",
                "由多位青年演员带来的90分钟脱口秀拼盘，内容涵盖职场、生活与城市观察。",
                "每人限购6张；演出含现场互动，16岁以下观众须由监护人陪同。", now);
        comedyPerformance(eventId, venueId, "周六晚场 20:00", at(7, 20, 0), now, front, rear);
        comedyPerformance(eventId, venueId, "周日下午场 15:00", at(8, 15, 0), now, front, rear);
    }

    private void comedyPerformance(long eventId, long venueId, String name, Instant startsAt, Instant now,
                                   long front, long rear) {
        long performanceId = performance(eventId, venueId, name, startsAt, now);
        tier(performanceId, front, "前排互动票", "220.00", "#F97316", 6);
        tier(performanceId, rear, "标准观演票", "150.00", "#22C55E", 6);
        snapshot(performanceId);
    }

    private void seedDigitalExhibition(long organizerId, Instant now) {
        if (eventExists("数字艺术沉浸展：光域")) return;
        long venueId = venue("湾区数字艺术中心", "深圳", "南山区海云路18号", now);
        long standard = area(venueId, "标准入场区", "STANDARD", 1);
        long student = area(venueId, "学生预约区", "STUDENT", 2);
        seats(standard, 5, 10);
        seats(student, 4, 10);
        long eventId = event(organizerId, "数字艺术沉浸展：光域", "EXHIBITION",
                "通过投影、声音和交互装置呈现光影空间，单次建议参观时间约90分钟。",
                "预约名额仅限所选时段使用；学生票入场时须出示有效学生证，每人限购6张。", now);
        exhibitionPerformance(eventId, venueId, "上午场 10:00", at(10, 10, 0), now, standard, student);
        exhibitionPerformance(eventId, venueId, "下午场 14:00", at(10, 14, 0), now, standard, student);
    }

    private void exhibitionPerformance(long eventId, long venueId, String name, Instant startsAt, Instant now,
                                       long standard, long student) {
        long performanceId = performance(eventId, venueId, name, startsAt, now);
        tier(performanceId, standard, "标准入场票", "128.00", "#0EA5E9", 6);
        tier(performanceId, student, "学生优惠票", "88.00", "#14B8A6", 6);
        snapshot(performanceId);
    }

    private void seedCampusConcert(long organizerId, Instant now) {
        if (eventExists("高校新生音乐会")) return;
        long venueId = venue("青年文化中心", "南京", "栖霞区学府路100号", now);
        long guest = area(venueId, "嘉宾区", "GUEST", 1);
        long standard = area(venueId, "普通观众区", "GENERAL", 2);
        seats(guest, 3, 8);
        seats(standard, 5, 10);
        long eventId = event(organizerId, "高校新生音乐会", "CAMPUS",
                "面向高校新生的校园音乐会，包含学生乐队、合唱团和器乐社团演出。",
                "每人限购2张；学生观众请携带校园卡，入场后按电子票座位就座。", now);
        long performanceId = performance(eventId, venueId, "迎新专场 19:00", at(12, 19, 0), now);
        tier(performanceId, guest, "嘉宾区", "98.00", "#A855F7", 2);
        tier(performanceId, standard, "学生普通票", "58.00", "#10B981", 2);
        snapshot(performanceId);
    }

    private long venue(String name, String city, String address, Instant now) {
        Long existing = firstId("SELECT id FROM st_venue WHERE name = ? AND city = ? ORDER BY id", name, city);
        return existing != null ? existing : insert("""
                INSERT INTO st_venue (name, city, address, enabled, created_at)
                VALUES (?, ?, ?, TRUE, ?)
                """, name, city, address, now);
    }

    private long area(long venueId, String name, String code, int order) {
        Long existing = firstId("SELECT id FROM st_venue_area WHERE venue_id = ? AND code = ?", venueId, code);
        return existing != null ? existing : insert("""
                INSERT INTO st_venue_area (venue_id, name, code, sort_order) VALUES (?, ?, ?, ?)
                """, venueId, name, code, order);
    }

    private void seats(long areaId, int rows, int seatsPerRow) {
        for (int row = 0; row < rows; row++) {
            String rowLabel = String.valueOf((char) ('A' + row));
            for (int number = 1; number <= seatsPerRow; number++) {
                jdbc.update("""
                        INSERT IGNORE INTO st_seat (area_id, row_label, seat_number, code, enabled)
                        VALUES (?, ?, ?, ?, TRUE)
                        """, areaId, rowLabel, number, rowLabel + "-" + String.format("%02d", number));
            }
        }
    }

    private long event(long organizerId, String title, String category, String description,
                       String purchaseNotice, Instant now) {
        return insert("""
                INSERT INTO st_event
                    (organizer_id, title, category, description, poster_url, purchase_notice,
                     status, created_at, updated_at)
                VALUES (?, ?, ?, ?, NULL, ?, 'ON_SALE', ?, ?)
                """, organizerId, title, category, description, purchaseNotice, now, now);
    }

    private long performance(long eventId, long venueId, String name, Instant startsAt, Instant now) {
        return insert("""
                INSERT INTO st_performance
                    (event_id, venue_id, name, starts_at, sales_start_at, sales_end_at, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'SCHEDULED', ?)
                """, eventId, venueId, name, startsAt, now.minus(1, ChronoUnit.DAYS),
                startsAt.minus(2, ChronoUnit.HOURS), now);
    }

    private void tier(long performanceId, long areaId, String name, String price, String color, int limit) {
        insert("""
                INSERT INTO st_ticket_tier
                    (performance_id, area_id, name, price, color, purchase_limit, enabled)
                VALUES (?, ?, ?, ?, ?, ?, TRUE)
                """, performanceId, areaId, name, new BigDecimal(price), color, limit);
    }

    private void snapshot(long performanceId) {
        jdbc.update("""
                INSERT INTO st_performance_seat
                    (performance_id, seat_id, ticket_tier_id, price, status, version)
                SELECT t.performance_id, s.id, t.id, t.price, 'AVAILABLE', 0
                FROM st_ticket_tier t
                JOIN st_seat s ON s.area_id = t.area_id AND s.enabled = TRUE
                WHERE t.performance_id = ? AND t.enabled = TRUE
                """, performanceId);
    }

    private Instant at(int daysFromToday, int hour, int minute) {
        LocalDate date = LocalDate.now(SHANGHAI_ZONE).plusDays(daysFromToday);
        return date.atTime(hour, minute).atZone(SHANGHAI_ZONE).toInstant();
    }

    private boolean eventExists(String title) {
        return exists("SELECT COUNT(*) FROM st_event WHERE title = ?", title);
    }

    private void seedAccount(String username, String email, String role) {
        if (!exists("SELECT COUNT(*) FROM st_user WHERE username = ?", username)) {
            jdbc.update("""
                    INSERT INTO st_user (username, email, password_hash, enabled, created_at)
                    VALUES (?, ?, ?, TRUE, ?)
                    """, username, email, passwords.encode(demoPassword), Instant.now());
        } else {
            jdbc.update("UPDATE st_user SET password_hash = ?, enabled = TRUE WHERE username = ?",
                    passwords.encode(demoPassword), username);
        }
        jdbc.update("""
                INSERT IGNORE INTO st_user_role (user_id, role)
                SELECT id, ? FROM st_user WHERE username = ?
                """, role, username);
    }

    private Long firstId(String sql, Object... args) {
        List<Long> ids = jdbc.query(sql, (result, row) -> result.getLong(1), args);
        return ids.isEmpty() ? null : ids.getFirst();
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
