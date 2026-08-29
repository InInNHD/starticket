-- 仅用于隔离的 starticket_perf 数据库；应用迁移和 demo 初始化完成后执行。
SET @organizer_id = (SELECT id FROM st_user WHERE username = 'organizer');
SET @password_hash = (SELECT password_hash FROM st_user WHERE username = 'user');

INSERT INTO st_user (username, email, password_hash, enabled, created_at)
SELECT CONCAT('load', LPAD(n, 4, '0')), CONCAT('load', LPAD(n, 4, '0'), '@starticket.local'),
       @password_hash, TRUE, CURRENT_TIMESTAMP(6)
FROM (
    SELECT ones.n + tens.n * 10 + hundreds.n * 100 + 1 AS n
    FROM (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
          UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) ones
    CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
                UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) tens
    CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
                UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) hundreds
) numbers
WHERE n <= 600;

INSERT INTO st_user_role (user_id, role)
SELECT id, 'USER' FROM st_user WHERE username LIKE 'load%';

INSERT INTO st_venue (name, city, address, enabled, created_at)
VALUES ('StarTicket 持续压测场馆', '上海', '隔离压测数据库', TRUE, CURRENT_TIMESTAMP(6));
SET @venue_id = LAST_INSERT_ID();

INSERT INTO st_venue_area (venue_id, name, code, sort_order)
VALUES (@venue_id, '压力测试区', 'LOAD', 1);
SET @area_id = LAST_INSERT_ID();

INSERT INTO st_seat (area_id, row_label, seat_number, code, enabled)
SELECT @area_id, CONCAT('R', LPAD(row_no, 2, '0')), seat_no,
       CONCAT('R', LPAD(row_no, 2, '0'), '-', LPAD(seat_no, 3, '0')), TRUE
FROM (
    SELECT tens.n * 10 + ones.n + 1 AS row_no
    FROM (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4) tens
    CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
                UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) ones
) rows_50
CROSS JOIN (
    SELECT tens.n * 10 + ones.n + 1 AS seat_no
    FROM (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
          UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) tens
    CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
                UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) ones
) seats_100;

INSERT INTO st_event (organizer_id, title, category, description, poster_url, purchase_notice,
                      status, created_at, updated_at)
VALUES (@organizer_id, 'StarTicket 持续压测专场', 'CONCERT', '仅用于可复现性能测试。', NULL,
        '每个场次每位用户限购 6 张。', 'APPROVED', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6));
SET @event_id = LAST_INSERT_ID();

INSERT INTO st_performance (event_id, venue_id, name, starts_at, sales_start_at, sales_end_at, status, created_at)
SELECT @event_id, @venue_id,
       CONCAT(
           CASE WHEN n <= 18 THEN 'MYSQL' ELSE 'REDIS' END, '-',
           CASE WHEN MOD(n - 1, 18) < 9 THEN 'HOTSPOT' ELSE 'SPREAD' END, '-C',
           LPAD(CASE FLOOR(MOD(n - 1, 9) / 3) WHEN 0 THEN 20 WHEN 1 THEN 100 ELSE 300 END, 3, '0'),
           '-R', MOD(n - 1, 3) + 1),
       DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL (30 + n) DAY),
       DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 DAY),
       DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 29 DAY), 'SCHEDULED', CURRENT_TIMESTAMP(6)
FROM (
    SELECT tens.n * 10 + ones.n + 1 AS n
    FROM (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3) tens
    CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
                UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) ones
) numbers
WHERE n <= 36;

INSERT INTO st_ticket_tier (performance_id, area_id, name, price, color, purchase_limit, enabled)
SELECT id, @area_id, '压力测试票', 199.00, '#6B3BFF', 6, TRUE
FROM st_performance WHERE event_id = @event_id;

INSERT INTO st_performance_seat (performance_id, seat_id, ticket_tier_id, price, status, version)
SELECT performance.id, seat.id, tier.id, tier.price, 'AVAILABLE', 0
FROM st_performance performance
JOIN st_ticket_tier tier ON tier.performance_id = performance.id
JOIN st_seat seat ON seat.area_id = @area_id
WHERE performance.event_id = @event_id;

SELECT COUNT(*) AS load_users FROM st_user WHERE username LIKE 'load%';
SELECT COUNT(*) AS load_seats FROM st_seat WHERE area_id = @area_id;
SELECT COUNT(*) AS performance_seats
FROM st_performance_seat performance_seat
JOIN st_performance performance ON performance.id = performance_seat.performance_id
WHERE performance.event_id = @event_id;
