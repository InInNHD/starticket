SELECT IF(COUNT(*) = 0, 'PASS', 'FAIL') AS no_oversell
FROM (
    SELECT o.performance_id, i.seat_id
    FROM st_order_item i
    JOIN st_order o ON o.id = i.order_id
    WHERE o.status IN ('PENDING_PAYMENT', 'PAID', 'REFUNDING')
    GROUP BY o.performance_id, i.seat_id
    HAVING COUNT(*) > 1
) duplicated_seats;

SELECT o.performance_id, COUNT(*) AS sold_items, COUNT(DISTINCT i.seat_id) AS distinct_sold_seats
FROM st_order_item i
JOIN st_order o ON o.id = i.order_id
WHERE o.status IN ('PENDING_PAYMENT', 'PAID', 'REFUNDING')
GROUP BY o.performance_id
ORDER BY o.performance_id;
