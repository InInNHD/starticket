CREATE TABLE st_performance_seat (
    id BIGINT NOT NULL AUTO_INCREMENT,
    performance_id BIGINT NOT NULL,
    seat_id BIGINT NOT NULL,
    ticket_tier_id BIGINT NOT NULL,
    price DECIMAL(10, 2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    locked_order_no VARCHAR(40),
    lock_expires_at TIMESTAMP(6),
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_st_performance_seat UNIQUE (performance_id, seat_id),
    CONSTRAINT fk_st_ps_performance FOREIGN KEY (performance_id) REFERENCES st_performance (id),
    CONSTRAINT fk_st_ps_seat FOREIGN KEY (seat_id) REFERENCES st_seat (id),
    CONSTRAINT fk_st_ps_tier FOREIGN KEY (ticket_tier_id) REFERENCES st_ticket_tier (id),
    INDEX idx_st_ps_status (performance_id, status),
    INDEX idx_st_ps_order (locked_order_no)
);

CREATE TABLE st_order (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_no VARCHAR(40) NOT NULL,
    user_id BIGINT NOT NULL,
    performance_id BIGINT NOT NULL,
    total_amount DECIMAL(10, 2) NOT NULL,
    status VARCHAR(30) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    paid_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_st_order_no UNIQUE (order_no),
    CONSTRAINT fk_st_order_user FOREIGN KEY (user_id) REFERENCES st_user (id),
    CONSTRAINT fk_st_order_performance FOREIGN KEY (performance_id) REFERENCES st_performance (id),
    INDEX idx_st_order_user (user_id, created_at),
    INDEX idx_st_order_expire (status, expires_at)
);

CREATE TABLE st_order_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    performance_seat_id BIGINT NOT NULL,
    seat_id BIGINT NOT NULL,
    seat_code VARCHAR(80) NOT NULL,
    ticket_tier_id BIGINT NOT NULL,
    tier_name VARCHAR(60) NOT NULL,
    price DECIMAL(10, 2) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_st_order_item_seat UNIQUE (order_id, seat_id),
    CONSTRAINT fk_st_item_order FOREIGN KEY (order_id) REFERENCES st_order (id),
    CONSTRAINT fk_st_item_ps FOREIGN KEY (performance_seat_id) REFERENCES st_performance_seat (id),
    CONSTRAINT fk_st_item_seat FOREIGN KEY (seat_id) REFERENCES st_seat (id),
    CONSTRAINT fk_st_item_tier FOREIGN KEY (ticket_tier_id) REFERENCES st_ticket_tier (id)
);

CREATE TABLE st_idempotency_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    order_no VARCHAR(40) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_st_idempotency UNIQUE (user_id, idempotency_key),
    CONSTRAINT fk_st_idempotency_user FOREIGN KEY (user_id) REFERENCES st_user (id)
);
