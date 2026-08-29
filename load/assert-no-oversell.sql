WITH formal_sales AS (
    SELECT p.id, p.name,
           CASE
               WHEN p.name LIKE '%-SINGLE-%' THEN 1
               WHEN p.name LIKE '%-LIMITED-%' THEN 100
               ELSE 1000
           END AS expected_sold,
           COUNT(i.id) AS sold_items,
           COUNT(DISTINCT i.seat_id) AS distinct_sold_seats
    FROM st_performance p
    LEFT JOIN st_order o
      ON o.performance_id = p.id
     AND o.status IN ('PENDING_PAYMENT', 'PAID', 'REFUNDING')
    LEFT JOIN st_order_item i ON i.order_id = o.id
    WHERE p.name REGEXP '^(MYSQL|REDIS)-(SINGLE|LIMITED|SPREAD)-R[1-3]-FORMAL$'
    GROUP BY p.id, p.name
)
SELECT IF(COUNT(*) = 18
          AND SUM(sold_items = expected_sold) = 18
          AND SUM(distinct_sold_seats = expected_sold) = 18,
          'PASS', 'FAIL') AS formal_inventory_assertion
FROM formal_sales;

SELECT IF(COUNT(*) = 0, 'PASS', 'FAIL') AS duplicate_seat_assertion
FROM (
    SELECT o.performance_id, i.seat_id
    FROM st_order_item i
    JOIN st_order o ON o.id = i.order_id
    JOIN st_performance p ON p.id = o.performance_id
    WHERE o.status IN ('PENDING_PAYMENT', 'PAID', 'REFUNDING')
      AND p.name REGEXP '^(MYSQL|REDIS)-(SINGLE|LIMITED|SPREAD)-R[1-3]-FORMAL$'
    GROUP BY o.performance_id, i.seat_id
    HAVING COUNT(*) > 1
) duplicated_seats;

SELECT p.name,
       CASE
           WHEN p.name LIKE '%-SINGLE-%' THEN 1
           WHEN p.name LIKE '%-LIMITED-%' THEN 100
           ELSE 1000
       END AS expected_sold,
       COUNT(i.id) AS sold_items,
       COUNT(DISTINCT i.seat_id) AS distinct_sold_seats,
       COUNT(i.id) - COUNT(DISTINCT i.seat_id) AS duplicate_seats
FROM st_performance p
LEFT JOIN st_order o
  ON o.performance_id = p.id
 AND o.status IN ('PENDING_PAYMENT', 'PAID', 'REFUNDING')
LEFT JOIN st_order_item i ON i.order_id = o.id
WHERE p.name REGEXP '^(MYSQL|REDIS)-(SINGLE|LIMITED|SPREAD)-R[1-3]-FORMAL$'
GROUP BY p.id, p.name
ORDER BY FIELD(SUBSTRING_INDEX(p.name, '-', 1), 'MYSQL', 'REDIS'),
         FIELD(SUBSTRING_INDEX(SUBSTRING_INDEX(p.name, '-', 2), '-', -1), 'SINGLE', 'LIMITED', 'SPREAD'),
         p.name;
